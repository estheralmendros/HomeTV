"""Keyframe service: registry, ffprobe fallback watchdog, failure memory, background indexer.
ffprobe and /proc are stubbed (tests/stubs.py)."""

import asyncio
import logging
import time

import pytest

from hometv.db import models as m
from hometv.playback.keyframes import (
    MB,
    KeyframeIndexFailed,
    KeyframeService,
    KeyframesPreparing,
    decode_times,
)
from hometv.scanner import Scanner
from tests import factories as f
from tests.stubs import FakeProc, ScaledClock, Spawner, wait_until

GB = 1000 * MB
ROWS = "".join(f"{t}.000000,{t}.000000,K__\n{t}.040000,N/A,___\n" for t in range(0, 60, 2))


def add_file(db, name="film.ts", container="ts", size=1_000_000):
    with db.write_session() as s:
        film = f.add_film(s, name, files=0)
        row = f.add_file(
            s, film_id=film.id, name=name, size=size, probe=f.probe_json(container=container)
        )
        return row.id


def service(db, settings, spawner, **kwargs) -> KeyframeService:
    svc = KeyframeService(
        db,
        settings,
        spawn=spawner,
        read_rchar=kwargs.pop("read_rchar", lambda pid: None),
        container_reader=kwargs.pop("container_reader", lambda info: None),
        **kwargs,
    )
    svc.sample_interval = 0.02
    svc.stall_seconds = 0.3
    svc.session_poll_seconds = 0.05
    return svc


def emit_rows(delay: float = 0.0, rows: str = ROWS):
    async def scenario(proc: FakeProc) -> None:
        await asyncio.sleep(delay)
        proc.stdout.feed_data(rows.encode())
        proc.finish(0)

    return scenario


@pytest.fixture
def settings(make_settings):
    return make_settings()


def stored(db, media_file_id):
    with db.read_session() as s:
        row = s.get(m.KeyframeIndex, media_file_id)
        return (row.source, decode_times(row.times)) if row else None


# --- on-demand builds ------------------------------------------------------------------------


async def test_ffprobe_fallback_builds_and_caches(mdb, settings):
    spawner = Spawner(emit_rows())
    svc = service(mdb, settings, spawner)
    fid = add_file(mdb)
    info = svc._load_info(fid)
    times = await svc.times_for_playback(info)
    assert times[:3] == [0.0, 2.0, 4.0]
    assert stored(mdb, fid) == ("ffprobe", times)
    argv = spawner.procs[0].argv
    assert argv[:6] == ["nice", "-n", "10", "ionice", "-c", "3"]
    assert argv[argv.index("-select_streams") + 1] == "0"  # bare index, never "0:0"
    assert "packet=pts_time,dts_time,flags" in argv
    # Second request: from the cache, no new process.
    assert await svc.times_for_playback(info) == times
    assert spawner.count == 1


async def test_times_are_relative_to_start_seconds(mdb, settings):
    spawner = Spawner(emit_rows(rows="11.400000,11.300000,K__\n15.400000,15.3,K__\n"))
    svc = service(mdb, settings, spawner)
    with mdb.write_session() as s:
        film = f.add_film(s, "x", files=0)
        probe = f.probe_json(container="ts")
        probe["start_seconds"] = 11.4
        probe["video"]["index"] = 1
        fid = f.add_file(s, film_id=film.id, name="x.ts", probe=probe).id
    times = await svc.times_for_playback(svc._load_info(fid))
    assert times == [0.0, 4.0]
    argv = spawner.procs[0].argv
    assert argv[argv.index("-select_streams") + 1] == "1"


async def test_container_reader_used_first(mdb, settings):
    spawner = Spawner(emit_rows())
    svc = service(
        mdb, settings, spawner, container_reader=lambda info: ("mkv_cues", [0.0, 5.0, 10.0])
    )
    fid = add_file(mdb, "a.mkv", "mkv")
    assert await svc.times_for_playback(svc._load_info(fid)) == [0.0, 5.0, 10.0]
    assert stored(mdb, fid)[0] == "mkv_cues"
    assert spawner.count == 0


async def test_concurrent_requests_share_one_build(mdb, settings):
    spawner = Spawner(emit_rows(delay=0.2))
    svc = service(mdb, settings, spawner)
    info = svc._load_info(add_file(mdb))
    results = await asyncio.gather(*(svc.times_for_playback(info) for _ in range(3)))
    assert results[0] == results[1] == results[2]
    assert spawner.count == 1


async def test_nonzero_exit_is_ffprobe_error(mdb, settings):
    async def scenario(proc):
        proc.stderr.feed_data(b"Invalid data found\n")
        proc.finish(1)

    svc = service(mdb, settings, Spawner(scenario))
    with pytest.raises(KeyframeIndexFailed) as exc:
        await svc.times_for_playback(svc._load_info(add_file(mdb)))
    assert exc.value.detail == "keyframe_index_failed: ffprobe_error"


async def test_empty_output_is_ffprobe_error(mdb, settings):
    svc = service(mdb, settings, Spawner(emit_rows(rows="0.04,N/A,___\n")))
    with pytest.raises(KeyframeIndexFailed, match="ffprobe_error"):
        await svc.times_for_playback(svc._load_info(add_file(mdb)))


# --- preparing / estimate ---------------------------------------------------------------------


async def test_preparing_with_estimates(mdb, settings):
    """10 GB file: before any sample -> 200 s; 1 GB read at 100 MB/s -> 90 s; later smaller;
    after completion the retry succeeds without recomputing."""
    clock = {"t": 0.0}
    rchar = {"v": None}
    gate = asyncio.Event()

    async def scenario(proc):
        await gate.wait()
        proc.stdout.feed_data(ROWS.encode())
        proc.finish(0)

    spawner = Spawner(scenario)
    svc = service(
        mdb, settings, spawner, read_rchar=lambda pid: rchar["v"], clock=lambda: clock["t"]
    )
    info = svc._load_info(add_file(mdb, size=10 * GB))
    with pytest.raises(KeyframesPreparing) as first:
        await svc.times_for_playback(info, wait=0.05)
    assert first.value.estimated_seconds == 200
    # Samples: 0 at t=0, 1 GB at t=10 (100 MB/s).
    build = svc.builds[info.id]
    rchar["v"] = 0
    await wait_until(lambda: len(build.samples) >= 1)
    clock["t"] = 10.0
    rchar["v"] = 1 * GB
    await wait_until(lambda: build.samples[-1].rchar == 1 * GB)
    with pytest.raises(KeyframesPreparing) as second:
        await svc.times_for_playback(info, wait=0.05)
    assert abs(second.value.estimated_seconds - 90) <= 1
    clock["t"] = 20.0
    rchar["v"] = 3 * GB
    await wait_until(lambda: build.samples[-1].rchar == 3 * GB)
    with pytest.raises(KeyframesPreparing) as third:
        await svc.times_for_playback(info, wait=0.05)
    assert third.value.estimated_seconds < second.value.estimated_seconds
    gate.set()
    await svc.times_for_playback(info, wait=1)
    assert await svc.times_for_playback(info, wait=0.05)
    assert spawner.count == 1


async def test_flat_rchar_returns_preparing_with_cumulative_estimate(mdb, settings):
    """2 GB then flat for 15 s (below the stall window) -> 503 preparing with a finite
    estimate from cumulative_rate; huge elapsed time -> 1 MB/s floor; clamped to [1, 86 400]."""
    clock = {"t": 0.0}
    rchar = {"v": 0}
    gate = asyncio.Event()

    async def scenario(proc):
        await gate.wait()
        proc.finish(1)

    svc = service(
        mdb,
        settings,
        Spawner(scenario),
        read_rchar=lambda pid: rchar["v"],
        clock=lambda: clock["t"],
    )
    svc.stall_seconds = 120.0
    info = svc._load_info(add_file(mdb, size=10 * GB))
    with pytest.raises(KeyframesPreparing):
        await svc.times_for_playback(info, wait=0.02)
    build = svc.builds[info.id]
    for t, value in [(5.0, 2 * GB), (10.0, 2 * GB), (15.0, 2 * GB), (20.0, 2 * GB)]:
        clock["t"], rchar["v"] = t, value
        await wait_until(lambda t=t: build.samples and build.samples[-1].t == t)
    with pytest.raises(KeyframesPreparing) as exc:
        await svc.times_for_playback(info, wait=0.02)
    assert exc.value.estimated_seconds == 80  # 8 GB at 2 GB / 20 s
    clock["t"], rchar["v"] = 20_000.0, 2 * GB + 1  # still progressing: no stall kill
    await wait_until(lambda: build.samples[-1].t == 20_000.0)
    with pytest.raises(KeyframesPreparing) as floor:
        await svc.times_for_playback(info, wait=0.02)
    assert floor.value.estimated_seconds == 8000  # 8 GB at the 1 MB/s floor
    clock["t"], rchar["v"] = 20_002.0, 11 * GB
    await wait_until(lambda: build.samples[-1].t == 20_002.0)
    with pytest.raises(KeyframesPreparing) as done:
        await svc.times_for_playback(info, wait=0.02)
    assert done.value.estimated_seconds == 1
    assert info.id in svc.builds
    gate.set()


async def test_estimate_uses_rchar_not_read_bytes(mdb, settings):
    """A stub with read_bytes = 0 but growing rchar yields a finite, shrinking estimate."""
    clock = ScaledClock(10.0)
    spawned = {"t": None}

    def rchar(pid):
        return int((clock() - spawned["t"]) * 2 * GB) if spawned["t"] is not None else 0

    gate = asyncio.Event()

    async def scenario(proc):
        spawned["t"] = clock()
        await gate.wait()
        proc.finish(1)

    svc = service(mdb, settings, Spawner(scenario), read_rchar=rchar, clock=clock)
    info = svc._load_info(add_file(mdb, size=100 * GB))
    estimates = []
    for _ in range(3):
        with pytest.raises(KeyframesPreparing) as exc:
            await svc.times_for_playback(info, wait=0.3)
        estimates.append(exc.value.estimated_seconds)
    assert 1 <= estimates[-1] < estimates[0] <= 86_400
    gate.set()


# --- watchdog / failure memory -------------------------------------------------------------


async def test_slow_but_progressing_read_is_never_killed(mdb, settings):
    """1 MB/s on a 10 GB file runs past size / 50 MB/s (200 s, virtual) without a kill."""
    clock = ScaledClock(1000.0)  # 1 real ms = 1 virtual s

    async def scenario(proc):
        await asyncio.sleep(0.5)  # 500 virtual seconds
        proc.stdout.feed_data(ROWS.encode())
        proc.finish(0)

    spawner = Spawner(scenario)
    svc = service(mdb, settings, spawner, clock=clock, read_rchar=lambda pid: int(clock() * MB))
    svc.sample_interval = 0.01
    svc.stall_seconds = 120.0
    info = svc._load_info(add_file(mdb, size=10 * GB))
    times = await svc.times_for_playback(info, wait=5)
    assert times
    assert spawner.procs[0].signals == []


async def test_stalled_read_is_killed_and_remembered(mdb, settings, caplog):
    async def scenario(proc):
        await asyncio.sleep(30)

    spawner = Spawner(scenario)
    svc = service(mdb, settings, spawner, read_rchar=lambda pid: 5 * MB)
    fid = add_file(mdb, size=10 * GB)
    info = svc._load_info(fid)
    with (
        caplog.at_level(logging.WARNING, logger="hometv"),
        pytest.raises(KeyframeIndexFailed) as exc,
    ):
        await svc.times_for_playback(info, wait=5)
    assert exc.value.reason == "stalled"
    assert spawner.procs[0].signals  # killed
    assert "keyframe_index_failed" in caplog.text
    # Remembered: answered at once, no new process.
    started = time.monotonic()
    with pytest.raises(KeyframeIndexFailed, match="keyframe_index_failed: stalled"):
        await svc.times_for_playback(info)
    assert time.monotonic() - started < 0.1
    assert spawner.count == 1
    # Not re-enqueued while unchanged.
    assert await svc.add([fid]) == []
    # File changed (mtime) -> stale entry -> indexed again.
    with mdb.write_session() as s:
        s.get(m.MediaFile, fid).mtime_ns += 1
    assert await svc.add([fid]) == [fid]


async def test_stall_with_process_ignoring_sigkill(mdb, settings):
    """The waiter gets `stalled` within one sampling interval of the kill even when the
    process never exits."""

    async def scenario(proc):
        proc.ignore_kill = True
        await asyncio.sleep(30)

    spawner = Spawner(scenario)
    svc = service(mdb, settings, spawner, read_rchar=lambda pid: 7)
    info = svc._load_info(add_file(mdb, size=10 * GB))
    started = time.monotonic()
    with pytest.raises(KeyframeIndexFailed, match="stalled"):
        await svc.times_for_playback(info, wait=5)
    elapsed = time.monotonic() - started
    assert elapsed < svc.stall_seconds + 3 * svc.sample_interval + 0.2
    assert spawner.procs[0].running  # never exited
    await svc.stop()
    await spawner.close()


async def test_large_output_is_drained_while_rchar_grows(mdb, settings):
    """10 MB of CSV on stdout with steadily growing rchar completes (no stall)."""
    big = "".join(f"{t / 25:.6f},{t / 25:.6f},{'K__' if t % 50 == 0 else '___'}\n"
                  for t in range(400_000))  # fmt: skip
    assert len(big) > 10 * MB

    async def scenario(proc):
        data = big.encode()
        for i in range(0, len(data), 256 * 1024):
            proc.stdout.feed_data(data[i : i + 256 * 1024])
            await asyncio.sleep(0)
        proc.finish(0)

    started = time.monotonic()
    svc = service(
        mdb,
        settings,
        Spawner(scenario),
        read_rchar=lambda pid: int((time.monotonic() - started) * 1000 * MB),
    )
    fid = add_file(mdb, size=10 * GB)
    times = await svc.times_for_playback(svc._load_info(fid), wait=10)
    assert len(times) == 8000
    assert stored(mdb, fid)[0] == "ffprobe"


async def test_wall_clock_limit_without_proc_io(mdb, settings):
    clock = ScaledClock(10_000.0)

    async def scenario(proc):
        await asyncio.sleep(30)

    svc = service(mdb, settings, Spawner(scenario), clock=clock, read_rchar=lambda pid: None)
    svc.sample_interval = 0.01
    info = svc._load_info(add_file(mdb, size=1 * GB))  # limit max(600 s, 100 s) = 600 s
    with pytest.raises(KeyframeIndexFailed, match="timeout"):
        await svc.times_for_playback(info, wait=5)


# --- background indexer ----------------------------------------------------------------------


async def test_background_queue_scan_promotion_join_and_session_gate(
    mdb, media, media_settings, stub_probe
):
    settings = media_settings()
    for name in ("a.ts", "b.ts", "nocues.mkv", "cues.mkv", "film.mp4"):
        media.add(f"{name.split('.')[0]} (2001)/{name}")

    async def scenario(proc):
        await asyncio.sleep(0.3)
        proc.stdout.feed_data(ROWS.encode())
        proc.finish(0)

    def reader(info):
        return (
            ("mkv_cues", [0.0, 6.0])
            if info.path.endswith("cues.mkv") and "no" not in info.path
            else None
        )

    # Lockstep: a "session" holds the worker (it pops nothing) until the queue and the promotion
    # have been checked, so no background build can race the assertions (CLAUDE.md "Timing").
    session = {"running": True}
    spawner = Spawner(scenario)
    svc = service(
        mdb,
        settings,
        spawner,
        container_reader=reader,
        session_running=lambda: session["running"],
    )
    svc.start()
    try:
        scanner = Scanner(mdb, settings, probe_fn=stub_probe, keyframe_hook=svc.enqueue)
        report = await scanner.run()
        assert spawner.count == 0  # nothing built during the scan
        assert report.counts["new"] == 5
        await svc.settle()
        with mdb.read_session() as s:
            ids = {row.path.rsplit("/", 1)[1]: row.id for row in s.query(m.MediaFile)}
        queued = {ids[n] for n in ("a.ts", "b.ts", "nocues.mkv", "cues.mkv")}
        assert set(svc.queue) == queued and not svc.builds
        assert not any(stored(mdb, i) for i in queued)
        assert ids["film.mp4"] not in svc.queue

        # Promotion: the file at the back of the queue is built at once, despite the session.
        last = svc.queue[-1]
        info = svc._load_info(last)
        times = await svc.times_for_playback(info, wait=5)
        assert times
        assert {i for i in queued if stored(mdb, i)} == {last}
        assert set(svc.queue) == queued - {last}

        session["running"] = False  # the session ends: the worker drains the queue
        # Everything finishes; one stub ffprobe at a time from the worker (+1 promoted).
        await wait_until(lambda: all(stored(mdb, i) for i in queued), timeout=10)
        assert spawner.max_active <= 2
        assert stored(mdb, ids["cues.mkv"])[0] == "mkv_cues"
        assert stored(mdb, ids["a.ts"])[0] == "ffprobe"
        ffprobe_runs = spawner.count
        assert ffprobe_runs == 3  # two TS + the cue-less MKV

        # A rescan with no changes queues nothing and runs nothing.
        await Scanner(mdb, settings, probe_fn=stub_probe, keyframe_hook=svc.enqueue).run()
        await svc.settle()
        assert not svc.queue
        await asyncio.sleep(0.1)
        assert spawner.count == ffprobe_runs
    finally:
        await svc.stop()
        await spawner.close()


async def test_worker_is_one_at_a_time_and_joins(mdb, settings):
    async def scenario(proc):
        await asyncio.sleep(0.2)
        proc.stdout.feed_data(ROWS.encode())
        proc.finish(0)

    spawner = Spawner(scenario)
    svc = service(mdb, settings, spawner)
    ids = [add_file(mdb, f"f{i}.ts") for i in range(3)]
    svc.start()
    try:
        await svc.add(ids)
        await wait_until(lambda: ids[0] in svc.builds)
        # Join the file being built: no extra process.
        await svc.times_for_playback(svc._load_info(ids[0]), wait=5)
        await wait_until(lambda: all(stored(mdb, i) for i in ids), timeout=5)
        assert spawner.max_active == 1
        assert spawner.count == 3
        assert [p.argv[-1] for p in spawner.procs] == [svc._load_info(i).path for i in ids]  # FIFO
    finally:
        await svc.stop()


async def test_worker_waits_while_a_session_is_running(mdb, settings):
    running = {"v": True}
    spawner = Spawner(emit_rows())
    svc = service(mdb, settings, spawner, session_running=lambda: running["v"])
    fid = add_file(mdb)
    svc.start()
    try:
        await svc.add([fid])
        await asyncio.sleep(0.3)
        assert spawner.count == 0
        running["v"] = False  # session throttled/stopped
        await wait_until(lambda: stored(mdb, fid) is not None, timeout=svc.session_poll_seconds + 2)
    finally:
        await svc.stop()


async def test_failed_file_is_not_requeued_and_worker_continues(mdb, settings):
    calls = []

    async def scenario(proc):
        calls.append(proc.argv[-1])
        if len(calls) == 1:
            proc.finish(1)
        else:
            proc.stdout.feed_data(ROWS.encode())
            proc.finish(0)

    svc = service(mdb, settings, Spawner(scenario))
    bad, good = add_file(mdb, "bad.ts"), add_file(mdb, "good.ts")
    svc.start()
    try:
        await svc.add([bad, good])
        await wait_until(lambda: stored(mdb, good) is not None)
        assert svc.failures[bad][2] == "ffprobe_error"
        assert await svc.add([bad]) == []
    finally:
        await svc.stop()
