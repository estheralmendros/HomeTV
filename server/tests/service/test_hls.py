"""HLS session manager with a stub ffmpeg (PRD §7 HLS steps 1-11, state machine, ACs)."""

import asyncio
import logging
import signal
from pathlib import Path

import pytest

from hometv.api.errors import ApiError
from hometv.playback.hls import HlsSessionManager, SessionSpec
from hometv.playback.segments import TS_OFFSET_SECONDS as T0
from hometv.playback.segments import Segment, plan_segments
from hometv.probe import AudioStream, ProbeResult, VideoStream
from tests import fmp4_builder as fb
from tests.stubs import FakeProc, Spawner, wait_until

FRAME = 1 / 25
KEYFRAMES = [float(t) for t in range(0, 60, 6)]
PLAN = plan_segments(KEYFRAMES, 60.0, 6)  # 10 x 6 s
SEG_BYTES = 100 + 8 + 8 * 26  # roughly; real sizes are read from the files


class StubFfmpeg:
    """Writes ``raw_k.mp4`` (synthetic fMP4 with the planned tfdt) and ``run.csv`` rows."""

    def __init__(self, plan: list[Segment], interval: float = 0.02) -> None:
        self.plan = plan
        self.interval = interval
        self.spawner = Spawner(self.scenario)
        self.rows: dict[tuple[int, int], tuple[float, float]] = {}  # (run, k) -> (start, end)
        self.rows_any: dict[int, tuple[float, float]] = {}
        self.extra_row = False
        self.stop_at: int | None = None
        self.mdat = 1000
        # lockstep: write raw_j only after the monitor finalized raw_{j-1} (it unlinks it), i.e.
        # a producer slower than the monitor, so at most one segment is in flight at SIGSTOP.
        self.lockstep = False
        # burst: segments k..burst-1 of the first run are written back to back without
        # yielding, i.e. ffmpeg outruns the monitor by that many segments whatever the load.
        self.burst = 0

    @property
    def count(self) -> int:
        return self.spawner.count

    @property
    def procs(self) -> list[FakeProc]:
        return self.spawner.procs

    async def scenario(self, proc: FakeProc) -> None:
        argv = proc.argv
        run = len(self.spawner.procs) - 1
        k = int(argv[argv.index("-segment_start_number") + 1])
        csv = Path(argv[argv.index("-segment_list") + 1])
        pattern = argv[-1]
        for j in range(k, len(self.plan)):
            if self.stop_at is not None and j >= self.stop_at:
                await asyncio.sleep(3600)
            if not (run == 0 and j < self.burst):
                await asyncio.sleep(self.interval)
            if self.lockstep and j > k:
                while Path(pattern % (j - 1)).exists() and proc.running:
                    await asyncio.sleep(0.002)
            await proc.checkpoint()
            if not proc.running:
                return
            start, end = (
                self.rows.get((run, j))
                or self.rows_any.get(j)
                or (
                    self.plan[j].start,
                    self.plan[j].end,
                )
            )
            Path(pattern % j).write_bytes(fb.raw_segment(start + T0, mdat_size=self.mdat))
            with open(csv, "a") as fh:
                fh.write(f"raw_{j}.mp4,{start + T0:.6f},{end + T0:.6f}\n")
        if self.extra_row:
            n = len(self.plan)
            Path(pattern % n).write_bytes(fb.raw_segment(self.plan[-1].end + T0))
            with open(csv, "a") as fh:
                fh.write(
                    f"raw_{n}.mp4,{self.plan[-1].end + T0:.6f},{self.plan[-1].end + T0 + 1:.6f}\n"
                )
        proc.finish(0)


class FakeClock:
    def __init__(self) -> None:
        self.now = 1000.0

    def __call__(self) -> float:
        return self.now


@pytest.fixture
def clock():
    return FakeClock()


@pytest.fixture
def stub():
    return StubFfmpeg(PLAN)


def make_settings_for(make_settings, **playback):
    return make_settings(playback=playback)


@pytest.fixture
async def manager(make_settings, stub, clock, tmp_path):
    settings = make_settings(playback={"hls_ahead_seconds": 120, "hls_behind_seconds": 60})
    settings.hls_dir.mkdir(parents=True, exist_ok=True)
    mgr = new_manager(settings, stub, clock)
    yield mgr
    await mgr.shutdown()
    await stub.spawner.close()


def new_manager(settings, stub, clock, free=10 * 1024**4):
    mgr = HlsSessionManager(
        settings,
        spawn=stub.spawner,
        clock=clock,
        disk_free=lambda path: free,
        segment_times_mode="absolute",
    )
    mgr.wait_seconds = 1.0
    mgr.poll_seconds = 0.01
    mgr.kill_grace = 0.2
    return mgr


def spec(
    plan=PLAN,
    keyframes=KEYFRAMES,
    start=0.0,
    profile_id=1,
    bitrate=None,
    size=10_000_000,
    action="aac_stereo",
    video_index=0,
    codec="h264",
) -> SessionSpec:
    probe = ProbeResult(
        container="mkv",
        duration_seconds=plan[-1].end,
        bitrate=bitrate,
        video=VideoStream(index=video_index, codec=codec, frame_rate=25.0),
        audio=[AudioStream(index=video_index + 1, codec="ac3", channels=6, default=True)],
    )
    return SessionSpec(
        profile_id=profile_id,
        media_file_id=7,
        path="/media/films/x.mkv",
        size_bytes=size,
        probe=probe,
        keyframes=keyframes,
        plan=plan,
        audio_stream_index=video_index + 1,
        audio_action=action,
        start_position_seconds=start,
    )


async def read_segment(mgr: HlsSessionManager, session_id: str, k: int) -> bytes:
    opened = await mgr.open_segment(session_id, k)
    with opened.file as fh:
        data = fh.read()
    assert len(data) == opened.size
    return data


async def expect_error(coro, status, code):
    with pytest.raises(ApiError) as exc:
        await coro
    assert (exc.value.status_code, exc.value.code) == (status, code)
    return exc.value


# --- basics ----------------------------------------------------------------------------------


async def test_session_serves_playlist_init_and_segments(manager, stub):
    session = await manager.create_session(spec())
    text = manager.playlist(session.session_id)
    assert text.count("#EXTINF:6.000000,") == 10
    init = await manager.init_segment(session.session_id)
    assert init == fb.init_segment()
    for k in range(len(PLAN)):
        data = await read_segment(manager, session.session_id, k)
        assert data[4:8] == b"moof"
    assert stub.count == 1
    await wait_until(lambda: session.state == "done")
    await expect_error(read_segment(manager, session.session_id, 10), 404, "segment_not_found")


async def test_ffmpeg_argv_reference_command(manager, stub):
    session = await manager.create_session(spec(video_index=2, codec="hevc"))
    argv = stub.procs[0].argv
    assert "-ss" not in argv
    assert argv[argv.index("-map") + 1] == "0:2"
    assert "0:3" in argv
    assert argv[argv.index("-tag:v") + 1] == "hvc1"
    i = argv.index("-output_ts_offset")
    assert argv[i : i + 4] == ["-output_ts_offset", "10", "-avoid_negative_ts", "disabled"]
    assert i < argv.index("-f")
    assert argv[argv.index("-f") + 1] == "segment"
    options = argv[argv.index("-segment_format_options") + 1]
    assert "use_editlist=0:avoid_negative_ts=disabled" in options
    assert "+frag_discont" in options
    times = argv[argv.index("-segment_times") + 1].split(",")
    assert times == [f"{s.start + 10:.6f}" for s in PLAN[1:]]
    assert argv[argv.index("-c:a") + 1 : argv.index("-c:a") + 6] == [
        "aac",
        "-ac",
        "2",
        "-b:a",
        "192k",
    ]
    assert argv[argv.index("-segment_start_number") + 1] == "0"
    assert argv[-1].endswith("raw_%d.mp4")
    # A run started at k uses -ss b_k + 0.001 and only the remaining boundaries.
    argv_k = manager.ffmpeg_argv(session, 4)
    assert argv_k[argv_k.index("-ss") + 1] == f"{24.001 + 3 / 23:.6f}"
    assert argv_k.index("-ss") < argv_k.index("-i")
    assert argv_k[argv_k.index("-segment_times") + 1].split(",")[0] == "40.000000"
    manager.segment_times_mode = "relative"
    assert manager.ffmpeg_argv(session, 4)[argv_k.index("-segment_times") + 1].split(",")[:2] == [
        "6.000000",
        "12.000000",
    ]
    assert manager.ffmpeg_argv(session, 9)[argv_k.index("-segment_times") + 1] == "999999"


async def test_audio_copy_and_none(manager, stub):
    session = await manager.create_session(spec(action="copy"))
    argv = manager.ffmpeg_argv(session, 0)
    assert argv[argv.index("-c:a") + 1] == "copy"
    s2 = spec(action="none")
    s2 = SessionSpec(**{**s2.__dict__, "audio_stream_index": None})
    session2 = await manager.create_session(s2)
    argv2 = manager.ffmpeg_argv(session2, 0)
    assert "-c:a" not in argv2
    assert argv2.count("-map") == 1


async def test_start_position_starts_the_run_at_its_segment(manager, stub):
    session = await manager.create_session(spec(start=27.0))
    assert session.run_start_segment == 4
    assert session.produced_max == 3
    assert stub.procs[0].argv[stub.procs[0].argv.index("-ss") + 1] == f"{24.001 + 3 / 23:.6f}"
    assert (await read_segment(manager, session.session_id, 4))[4:8] == b"moof"


# --- per-segment verification ------------------------------------------------------------------


async def test_missed_cut_restarts_then_fails(manager, stub, caplog):
    stub.rows_any[2] = (PLAN[2].start, PLAN[3].end)  # segments 2 and 3 merged
    session = await manager.create_session(spec())
    with caplog.at_level(logging.WARNING, logger="hometv"):
        assert await read_segment(manager, session.session_id, 0)
        assert await read_segment(manager, session.session_id, 1)
        err = await expect_error(
            read_segment(manager, session.session_id, 2), 500, "transcoder_failed"
        )
    assert err.details["detail"] == "segment_plan_mismatch: 2"
    assert "plan_mismatch" in caplog.text
    assert stub.count == 2  # restarted once at 2
    assert stub.procs[1].argv[stub.procs[1].argv.index("-segment_start_number") + 1] == "2"
    assert 2 not in session.ready
    assert not (session.dir / "seg_2.m4s").exists()
    await expect_error(read_segment(manager, session.session_id, 0), 500, "transcoder_failed")
    with pytest.raises(ApiError):
        manager.keepalive(session.session_id)


async def test_mismatch_once_then_correct_run_succeeds(manager, stub, caplog):
    stub.rows[(0, 2)] = (PLAN[2].start, PLAN[3].end)
    session = await manager.create_session(spec())
    with caplog.at_level(logging.WARNING, logger="hometv"):
        for k in range(5):
            assert await read_segment(manager, session.session_id, k)
    assert "plan_mismatch" in caplog.text
    assert stub.count == 2


@pytest.mark.parametrize(
    ("row", "ok"),
    [
        ((12.0, 18.0 - 3 * FRAME), True),  # open GOP: 3 frames short
        ((12.0, 18.0), True),
        ((12.0, 17.0), False),  # extra cut: 1 s short
        ((12.0 + 2 * FRAME, 18.0), False),  # non-first row starting 2 frames late
    ],
)
async def test_row_rules(manager, stub, row, ok, caplog):
    stub.rows_any[2] = row
    session = await manager.create_session(spec())
    with caplog.at_level(logging.WARNING, logger="hometv"):
        for k in range(4):
            await read_segment(manager, session.session_id, k) if ok else None
    if ok:
        assert "plan_mismatch" not in caplog.text
        assert stub.count == 1
    else:
        await wait_until(lambda: session.state == "failed")
        assert session.detail == "segment_plan_mismatch: 2"


async def test_first_of_run_starting_early_is_accepted(manager, stub, caplog):
    stub.rows[(1, 5)] = (PLAN[5].start - 1.0, PLAN[5].end)
    session = await manager.create_session(spec())
    assert await read_segment(manager, session.session_id, 0)
    with caplog.at_level(logging.WARNING, logger="hometv"):
        assert await read_segment(manager, session.session_id, 5)  # far seek -> run starts at 5
    assert "seek_mismatch" in caplog.text
    assert "plan_mismatch" not in caplog.text


async def test_last_segment_rules(manager, stub, caplog):
    stub.rows_any[9] = (54.0, 58.0)  # video ends 2 s before the duration
    session = await manager.create_session(spec())
    with caplog.at_level(logging.WARNING, logger="hometv"):
        for k in range(10):
            assert await read_segment(manager, session.session_id, k)
    await wait_until(lambda: session.state == "done")
    assert "plan_mismatch" not in caplog.text


async def test_last_segment_too_long_is_a_mismatch(manager, stub):
    stub.rows_any[9] = (54.0, 61.5)
    session = await manager.create_session(spec())
    await wait_until(lambda: session.state == "failed", timeout=3)
    assert session.detail == "segment_plan_mismatch: 9"


async def test_row_beyond_the_plan_is_a_mismatch(manager, stub, caplog):
    stub.extra_row = True
    session = await manager.create_session(spec())
    with caplog.at_level(logging.WARNING, logger="hometv"):
        await wait_until(lambda: stub.count >= 2, timeout=3)
    assert "plan_mismatch" in caplog.text
    assert "segment 10 is beyond the plan" in caplog.text
    assert session.state != "done" or stub.count >= 2


async def test_tfdt_mismatch_is_treated_like_a_plan_mismatch(manager, stub, caplog, monkeypatch):
    original = stub.scenario

    async def relative_tfdt(proc):
        await original(proc)
        # nothing: the override below writes a relative tfdt for segment 1

    stub.rows_any[1] = (PLAN[1].start, PLAN[1].end)
    real_raw = fb.raw_segment

    def raw_segment(start, **kw):
        return real_raw(0.05 if abs(start - (6.0 + T0)) < 1e-6 else start, **kw)

    monkeypatch.setattr(fb, "raw_segment", raw_segment)
    session = await manager.create_session(spec())
    with caplog.at_level(logging.WARNING, logger="hometv"):
        await wait_until(lambda: session.state == "failed", timeout=3)
    assert "tfdt_mismatch" in caplog.text
    assert session.detail == "segment_plan_mismatch: 1"


# --- state machine -----------------------------------------------------------------------


async def test_concurrent_far_requests_cause_one_restart(manager, stub):
    stub.interval = 0.05
    session = await manager.create_session(spec())
    sid = session.session_id
    assert await read_segment(manager, sid, 0)
    results = await asyncio.gather(
        read_segment(manager, sid, 7), read_segment(manager, sid, 7), read_segment(manager, sid, 8)
    )
    assert all(r[4:8] == b"moof" for r in results)
    assert stub.count == 2
    assert session.run_start_segment == 7
    assert session.state in ("running", "throttled", "done")  # killed run's exit ignored
    assert stub.procs[0].returncode is not None


async def test_after_restart_a_nearby_request_waits(manager, stub):
    stub.interval = 0.1
    session = await manager.create_session(spec())
    sid = session.session_id
    await read_segment(manager, sid, 0)
    request = asyncio.create_task(read_segment(manager, sid, 6))
    await wait_until(lambda: stub.count == 2)
    assert session.produced_max == 5
    assert await read_segment(manager, sid, 8)  # within produced_max + 3: waits, no restart
    await request
    assert stub.count == 2


async def test_three_timeouts_fail_the_session(manager, stub):
    stub.stop_at = 2
    manager.wait_seconds = 0.2
    session = await manager.create_session(spec())
    sid = session.session_id
    await read_segment(manager, sid, 0)
    await read_segment(manager, sid, 1)
    for _ in range(3):
        await expect_error(read_segment(manager, sid, 2), 504, "segment_timeout")
    err = await expect_error(read_segment(manager, sid, 2), 500, "transcoder_failed")
    assert err.details["detail"] == "segment_timeout_repeated"
    assert stub.procs[0].returncode is not None  # killed
    assert signal.SIGTERM in stub.procs[0].signals


async def test_ready_segment_resets_timeouts(manager, stub):
    stub.stop_at = 3
    manager.wait_seconds = 0.2
    session = await manager.create_session(spec())
    sid = session.session_id
    await read_segment(manager, sid, 0)
    await expect_error(read_segment(manager, sid, 3), 504, "segment_timeout")
    assert session.consecutive_timeouts == 1
    await read_segment(manager, sid, 1)
    assert session.consecutive_timeouts == 0


async def test_nonzero_exit_fails_with_last_stderr_line(manager, stub):
    async def crash(proc):
        argv = proc.argv
        Path(argv[-1]).parent.joinpath("ffmpeg.log").write_text("x\nError while decoding stream\n")
        proc.finish(1)

    stub.spawner.scenario = crash
    session = await manager.create_session(spec())
    await wait_until(lambda: session.state == "failed")
    err = await expect_error(read_segment(manager, session.session_id, 0), 500, "transcoder_failed")
    assert err.details["detail"] == "Error while decoding stream"


# --- init.mp4 ------------------------------------------------------------------------------


async def test_init_waits_for_the_first_segment(manager, stub):
    stub.interval = 0.2
    session = await manager.create_session(spec(start=30.0))
    init = await manager.init_segment(session.session_id)
    assert init == fb.init_segment()
    assert stub.count == 1


async def test_init_on_failed_stopped_and_timeout(manager, stub):
    stub.stop_at = 0
    manager.wait_seconds = 0.3
    session = await manager.create_session(spec())
    sid = session.session_id
    await expect_error(manager.init_segment(sid), 504, "segment_timeout")
    assert session.consecutive_timeouts == 0
    waiter = asyncio.create_task(manager.init_segment(sid))
    await asyncio.sleep(0.05)
    async with session.lock:
        await manager._fail(session, "boom")
    await expect_error(waiter, 500, "transcoder_failed")

    session2 = await manager.create_session(spec())
    waiter = asyncio.create_task(manager.init_segment(session2.session_id))
    await asyncio.sleep(0.05)
    await manager.delete(session2.session_id)
    await expect_error(waiter, 404, "session_not_found")
    assert stub.count == 2  # init requests never start ffmpeg


# --- throttle, eviction, disk cap ------------------------------------------------------------


async def test_read_ahead_throttle_and_resume(make_settings, stub, clock):
    settings = make_settings(playback={"hls_ahead_seconds": 20, "hls_behind_seconds": 60})
    settings.hls_dir.mkdir(parents=True, exist_ok=True)
    long_keyframes = [float(t) for t in range(0, 180, 6)]
    long_plan = plan_segments(long_keyframes, 180.0, 6)
    stub.plan = long_plan
    # The window bound below assumes ffmpeg is slower than the monitor (real remuxes are);
    # without lockstep a loaded machine lets the 20 ms stub overshoot by several segments.
    stub.lockstep = True
    mgr = new_manager(settings, stub, clock)
    try:
        session = await mgr.create_session(spec(plan=long_plan, keyframes=long_keyframes))
        sid = session.session_id
        await read_segment(mgr, sid, 0)
        await wait_until(lambda: session.state == "throttled")
        proc = stub.procs[0]
        assert proc.stopped
        await asyncio.sleep(0.1)
        ahead = sum(PLAN[k].duration for k in session.ready if k > 0)
        # 20 s target reached at 24 s, plus at most the segment in flight when SIGSTOP lands.
        assert ahead <= 20 + 2 * 6
        assert max(session.ready) <= 5
        # Requesting further resumes it (lead drops below ahead / 2).
        await read_segment(mgr, sid, 4)
        await wait_until(lambda: max(session.ready) >= 7)
        assert stub.count == 1
        # Teardown of a paused process: SIGCONT before SIGTERM.
        await wait_until(lambda: session.state == "throttled")
        await mgr.delete(sid)
        sigs = proc.signals
        assert sigs.index(signal.SIGCONT, len(sigs) - 3) < sigs.index(signal.SIGTERM)
        assert proc.returncode is not None
    finally:
        await mgr.shutdown()


async def test_overshoot_beyond_the_window_is_kept_when_contiguous(make_settings, stub, clock):
    """A stub that outruns the monitor (several rows written before SIGSTOP lands) must not
    leave a gap right after the player: dropping the contiguous overshoot would keep the lead
    below the ahead target forever (ffmpeg never paused again, every new segment evicted) and
    the next sequential request would restart ffmpeg."""
    settings = make_settings(playback={"hls_ahead_seconds": 20, "hls_behind_seconds": 60})
    settings.hls_dir.mkdir(parents=True, exist_ok=True)
    long_keyframes = [float(t) for t in range(0, 180, 6)]
    long_plan = plan_segments(long_keyframes, 180.0, 6)
    stub.plan = long_plan
    stub.burst = 9  # rows 0..8 exist before the monitor finalizes the first one
    mgr = new_manager(settings, stub, clock)
    try:
        session = await mgr.create_session(spec(plan=long_plan, keyframes=long_keyframes))
        sid = session.session_id
        await read_segment(mgr, sid, 0)
        await wait_until(lambda: session.state == "throttled")
        await asyncio.sleep(0.1)
        ready = sorted(session.ready)
        assert ready == list(range(ready[0], ready[-1] + 1))  # no gap
        # Sequential playback is served by the same run, which pauses again ahead of it.
        for k in range(1, 9):
            await read_segment(mgr, sid, k)
        assert stub.count == 1
        # ... and the read-ahead throttle still works: ffmpeg is paused again ahead of the
        # player instead of running to the end with every new segment evicted.
        await wait_until(lambda: session.state == "throttled")
        assert stub.procs[0].stopped
        assert session.produced_max < len(long_plan) - 1
        ready = sorted(k for k in session.ready if k > 8)
        assert ready == list(range(9, 9 + len(ready)))
    finally:
        await mgr.shutdown()
        await stub.spawner.close()


async def test_behind_and_far_ahead_eviction(make_settings, stub, clock):
    settings = make_settings(playback={"hls_ahead_seconds": 12, "hls_behind_seconds": 6})
    settings.hls_dir.mkdir(parents=True, exist_ok=True)
    mgr = new_manager(settings, stub, clock)
    try:
        session = await mgr.create_session(spec())
        sid = session.session_id
        for k in range(6):
            await read_segment(mgr, sid, k)
        assert min(session.ready) >= 4  # start(5) - 6 s = 24 s -> segments from 4 on
        assert not (session.dir / "seg_2.m4s").exists()
        # Backward seek to an evicted segment restarts; far-ahead segments are evicted.
        assert await read_segment(mgr, sid, 1)
        assert stub.count == 2
        await asyncio.sleep(0.1)
        assert all(PLAN[k].start <= PLAN[1].start + 12 + 6 for k in session.ready)
    finally:
        await mgr.shutdown()


async def test_disk_full_at_creation(make_settings, stub, clock):
    settings = make_settings(
        playback={"hls_max_disk_mb": 100, "hls_ahead_seconds": 120, "hls_behind_seconds": 60}
    )
    settings.hls_dir.mkdir(parents=True, exist_ok=True)
    mgr = new_manager(settings, stub, clock)
    # bitrate null: size * 8 / duration = 1 GB over 60 s -> 16.7 MB/s x 180 s >> 100 MB
    await expect_error(mgr.create_session(spec(size=1_000_000_000)), 503, "hls_disk_full")
    assert (await mgr.create_session(spec(size=1_000_000))).state == "running"
    low = new_manager(settings, stub, clock, free=512 * 1024**2)
    await expect_error(low.create_session(spec(size=1000)), 503, "hls_disk_full")
    await mgr.shutdown()


async def test_cap_livelock(make_settings, stub, clock):
    """Over the cap: the session pauses with k+1..k+2 ready, stays paused, serves them without
    a restart; below 90 % of the cap it resumes up to hls_ahead_seconds."""
    stub.interval = 0.05
    stub.mdat = 400_000
    settings = make_settings(
        playback={"hls_max_disk_mb": 1, "hls_ahead_seconds": 120, "hls_behind_seconds": 600}
    )
    settings.hls_dir.mkdir(parents=True, exist_ok=True)
    mgr = new_manager(settings, stub, clock)
    try:
        session = await mgr.create_session(spec(size=1000))
        sid = session.session_id
        await read_segment(mgr, sid, 0)
        await wait_until(lambda: session.state == "throttled", timeout=3)
        assert mgr.over_cap
        assert {1, 2} <= set(session.ready)
        proc = stub.procs[0]
        await asyncio.sleep(2.0)
        assert proc.stopped
        assert stub.count == 1
        assert 3 not in session.ready and not (session.dir / "seg_3.m4s").exists()
        assert await read_segment(mgr, sid, 1)
        assert await read_segment(mgr, sid, 2)
        assert stub.count == 1
        # Raise the cap: below 90 % -> normal target restored, production continues.
        mgr.settings.playback.hls_max_disk_mb = 100
        await read_segment(mgr, sid, 2)
        await wait_until(lambda: max(session.ready) == 9, timeout=3)
        assert not mgr.over_cap
    finally:
        await mgr.shutdown()


async def test_lead_ignores_segments_beyond_a_gap(manager, stub):
    session = await manager.create_session(spec())
    await read_segment(manager, session.session_id, 0)
    session.ready = {0: 1, 5: 1}
    session.last_requested = 0
    assert session.lead_seconds() == 0.0
    session.ready = {0: 1, 1: 1, 2: 1, 5: 1}
    assert session.lead_seconds() == 12.0


async def test_waited_for_segment_resumes_a_throttled_session_over_cap(make_settings, stub, clock):
    stub.interval = 0.05
    stub.mdat = 400_000
    settings = make_settings(
        playback={"hls_max_disk_mb": 1, "hls_ahead_seconds": 120, "hls_behind_seconds": 600}
    )
    settings.hls_dir.mkdir(parents=True, exist_ok=True)
    mgr = new_manager(settings, stub, clock)
    try:
        session = await mgr.create_session(spec(size=1000))
        sid = session.session_id
        await read_segment(mgr, sid, 0)
        await wait_until(lambda: session.state == "throttled", timeout=3)
        assert await read_segment(mgr, sid, 3)  # waits for the next segment: resumes and is served
        assert stub.count == 1
        assert 0 not in session.ready  # behind evicted while over the cap
    finally:
        await mgr.shutdown()


# --- sessions, idle, keep-alive, shutdown ----------------------------------------------------


async def test_too_many_sessions_and_previous_session(make_settings, stub, clock):
    settings = make_settings(playback={"max_sessions": 3})
    settings.hls_dir.mkdir(parents=True, exist_ok=True)
    mgr = new_manager(settings, stub, clock)
    try:
        sessions = [await mgr.create_session(spec(profile_id=1)) for _ in range(3)]
        await expect_error(mgr.create_session(spec()), 503, "too_many_sessions")
        assert not await mgr.stop_owned(sessions[0].session_id, profile_id=2)  # foreign
        assert not await mgr.stop_owned("f" * 32, profile_id=1)  # unknown
        assert await mgr.stop_owned(sessions[0].session_id, profile_id=1)
        assert not sessions[0].dir.exists()
        assert (await mgr.create_session(spec())).state == "running"
        with pytest.raises(ApiError):
            mgr.playlist(sessions[0].session_id)
    finally:
        await mgr.shutdown()


async def test_idle_sweep_and_keepalive(make_settings, stub, clock):
    settings = make_settings(playback={"session_idle_timeout_seconds": 1})
    settings.hls_dir.mkdir(parents=True, exist_ok=True)
    mgr = new_manager(settings, stub, clock)
    try:
        kept = await mgr.create_session(spec())
        idle = await mgr.create_session(spec())
        for _ in range(4):  # keep-alive every 0.5 s for 2 s, no segment requests
            clock.now += 0.5
            mgr.keepalive(kept.session_id)
            await mgr.sweep_idle()
        assert kept.session_id in mgr.sessions
        assert idle.session_id not in mgr.sessions
        assert not idle.dir.exists()
        assert stub.procs[1].returncode is not None
        with pytest.raises(ApiError) as exc:
            mgr.keepalive(idle.session_id)
        assert exc.value.code == "session_not_found"
        with pytest.raises(ApiError):
            mgr.keepalive("0" * 32)
        assert stub.count == 2  # keep-alive never starts ffmpeg
        async with kept.lock:
            await mgr._fail(kept, "boom")
        with pytest.raises(ApiError) as exc:
            mgr.keepalive(kept.session_id)
        assert exc.value.status_code == 500
    finally:
        await mgr.shutdown()


async def test_any_request_updates_last_access(manager, stub, clock):
    session = await manager.create_session(spec())
    for action in (
        lambda: manager.playlist(session.session_id),
        lambda: manager.keepalive(session.session_id),
    ):
        clock.now += 5
        action()
        assert session.last_access == clock.now
    clock.now += 5
    await manager.init_segment(session.session_id)
    assert session.last_access == clock.now
    clock.now += 5
    await read_segment(manager, session.session_id, 0)
    assert session.last_access == clock.now


async def test_shutdown_kills_and_removes_everything(make_settings, stub, clock):
    settings = make_settings()
    settings.hls_dir.mkdir(parents=True, exist_ok=True)
    stub.stop_at = 1
    mgr = new_manager(settings, stub, clock)
    sessions = [await mgr.create_session(spec()) for _ in range(2)]
    await mgr.shutdown()
    assert not mgr.sessions
    assert all(not s.dir.exists() for s in sessions)
    assert all(p.returncode is not None for p in stub.procs)


async def test_delete_is_idempotent(manager, stub):
    session = await manager.create_session(spec())
    await manager.delete(session.session_id)
    await manager.delete(session.session_id)
    await manager.delete("nope")
    assert not manager.any_running()


async def test_any_running(manager, stub):
    stub.stop_at = 2
    assert not manager.any_running()
    session = await manager.create_session(spec())
    assert manager.any_running()
    session.state = "throttled"
    assert not manager.any_running()


def test_seek_position_by_container(make_settings, stub, clock):
    mgr = new_manager(make_settings(), stub, clock)
    kf = [0.0, 6.0, 6.1, 12.0, 18.0]
    plan = plan_segments(kf, 24.0, 6)
    mkv = spec(plan=plan, keyframes=kf)
    session = type("S", (), {"plan": plan, "spec": mkv})()
    assert mgr.seek_position(session, 1) == pytest.approx(6.05)  # capped: next keyframe 6.1
    assert mgr.seek_position(session, 2) == pytest.approx(12.001 + 3 / 23)
    ts = SessionSpec(**{**mkv.__dict__, "probe": mkv.probe.model_copy(update={"container": "ts"})})
    session_ts = type("S", (), {"plan": plan, "spec": ts})()
    assert mgr.seek_position(session_ts, 2) == pytest.approx(11.5)
    assert mgr.seek_position(session_ts, 1) == pytest.approx(5.5)


async def test_stop_waits_for_an_in_flight_finalize(manager, stub, monkeypatch):
    """A monitor cancelled while ``_finalize`` runs in its worker thread must not let the
    session dir be removed under it (the thread would recreate files in it)."""
    import threading

    from hometv.playback import hls as hls_module

    entered, release = threading.Event(), threading.Event()
    real_split = hls_module.split_fmp4

    def blocking_split(data):
        entered.set()
        release.wait(5)
        return real_split(data)

    monkeypatch.setattr(hls_module, "split_fmp4", blocking_split)
    stub.stop_at = 1
    session = await manager.create_session(spec())
    await wait_until(entered.is_set)
    stopping = asyncio.create_task(manager.delete(session.session_id))
    try:
        await asyncio.sleep(0.2)
        assert not stopping.done()  # the stop waits for the finalize thread
    finally:
        release.set()
    await stopping
    assert not session.dir.exists()


async def test_concurrent_creates_respect_max_sessions(make_settings, stub, clock):
    import time

    settings = make_settings(playback={"max_sessions": 2})
    settings.hls_dir.mkdir(parents=True, exist_ok=True)
    mgr = new_manager(settings, stub, clock)

    def slow_free(path):
        time.sleep(0.05)  # every create is suspended here at once without the create lock
        return 10 * 1024**4

    mgr.disk_free = slow_free
    try:
        results = await asyncio.gather(
            *(mgr.create_session(spec()) for _ in range(5)), return_exceptions=True
        )
        errors = [r for r in results if isinstance(r, ApiError)]
        assert len(mgr.sessions) == 2
        assert [e.code for e in errors] == ["too_many_sessions"] * 3
        assert stub.count == 2
    finally:
        await mgr.shutdown()


async def test_an_opened_segment_survives_its_eviction(manager, stub):
    """The fd is opened before the segment is released to eviction: unlinking it afterwards
    (eviction, budget, stop) does not break the response that is streaming it."""
    session = await manager.create_session(spec())
    opened = await manager.open_segment(session.session_id, 0)
    async with session.lock:
        manager._drop(session, 0).unlink()
    with opened.file as fh:
        assert fh.read()[4:8] == b"moof"
