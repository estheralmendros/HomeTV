"""HLS remux end-to-end with real ffmpeg/ffprobe (PRD §7 HLS; Playback integration ACs)."""

import asyncio
import json
import logging
import subprocess
import time
from pathlib import Path

import pytest

from hometv.playback.hls import RESUME_WINDOW, SessionSpec
from hometv.playback.segments import TS_OFFSET_SECONDS as T0
from hometv.playback.segments import has_edit_list, normalize_init, plan_segments, split_fmp4
from hometv.probe import ProbeResult
from tests.integration.conftest import FORCE_HLS_CAPS, WEB_CAPS, link_or_copy

pytestmark = pytest.mark.integration


def analyze(tmp_path: Path, init: bytes, seg: bytes) -> dict:
    """ffprobe ``init + seg``: per-stream codec/start and video packet bounds."""
    path = tmp_path / "probe.mp4"
    path.write_bytes(init + seg)
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries",
         "stream=index,codec_type,codec_name,start_time:format=start_time:"
         "packet=stream_index,pts_time,duration_time,flags", "-of", "json", str(path)],
        check=True, capture_output=True, text=True,
    ).stdout  # fmt: skip
    data = json.loads(out)
    streams = {s["index"]: s for s in data["streams"]}
    video = next(s for s in data["streams"] if s["codec_type"] == "video")
    audio = next((s for s in data["streams"] if s["codec_type"] == "audio"), None)
    vpk = [p for p in data["packets"] if p["stream_index"] == video["index"]]
    first = vpk[0]  # decode order: a segment starts with its keyframe
    end = max(float(p["pts_time"]) + float(p.get("duration_time") or 0) for p in vpk)
    return {
        "video_codec": video["codec_name"],
        "audio_codec": audio["codec_name"] if audio else None,
        "video_start": float(video["start_time"]),
        "key_pts": float(first["pts_time"]),
        "first_is_key": "K" in first["flags"],
        "video_end": end,
        "format_start": float(data["format"]["start_time"]),
        "streams": streams,
    }


async def keyframes_of(media, name: str) -> list[float]:
    from hometv.playback.keyframes import FileInfo

    with media.app.state.db.read_session() as s:
        from hometv.db import models as m

        row = s.get(m.MediaFile, media.ids[name])
        info = FileInfo.from_row(row, ProbeResult.from_json(row.probe))
    return await media.app.state.keyframes.times_for_playback(info, wait=30)


def frame_of(media, name: str) -> float:
    from hometv.db import models as m

    with media.app.state.db.read_session() as s:
        probe = ProbeResult.from_json(s.get(m.MediaFile, media.ids[name]).probe)
    return 1 / (probe.video.frame_rate or 25)


@pytest.mark.parametrize(
    ("name", "caps"),
    [
        ("h264_ac3_51.mkv", WEB_CAPS),
        ("h264_ac3_gop10.mkv", WEB_CAPS),
        ("h264_aac_opengop.mkv", WEB_CAPS),
        ("h264_aac_start10.ts", WEB_CAPS),
        ("h264_aac_audiotail.mp4", FORCE_HLS_CAPS),
        ("h264_aac_coverart.mp4", FORCE_HLS_CAPS),
    ],
)
async def test_hls_session_end_to_end(make_media_app, tmp_path, caplog, name, caps):
    async with make_media_app() as media:
        with caplog.at_level(logging.INFO, logger="hometv"):
            started = time.perf_counter()
            response = await media.play(name, caps)
            assert response.status_code == 200, response.text
            body = response.json()
            assert body["mode"] == "hls"
            sid = body["session_id"]
            playlist = (await media.client.get(body["url"])).text
            first_segment = await media.client.get(f"/api/hls/{sid}/seg_0.m4s")
            first_latency = time.perf_counter() - started
            assert first_segment.status_code == 200
            init = (await media.client.get(f"/api/hls/{sid}/init.mp4")).content
            keyframes = await keyframes_of(media, name)
            duration = body["duration_seconds"]
            plan = plan_segments(keyframes, duration, 6)
            extinf = [float(ln[8:-1]) for ln in playlist.splitlines() if ln.startswith("#EXTINF:")]
            target = int(
                next(ln for ln in playlist.splitlines() if "TARGETDURATION" in ln).split(":")[1]
            )
            assert len(extinf) == len(plan)
            assert all(target >= d for d in extinf)
            frame = frame_of(media, name)
            for k, segment in enumerate(plan):
                response = await media.client.get(f"/api/hls/{sid}/seg_{k}.m4s")
                assert response.status_code == 200, (k, response.text)
                info = analyze(tmp_path, init, response.content)
                assert info["video_codec"] == "h264"  # never the cover-art picture
                assert info["audio_codec"] == "aac"
                assert info["first_is_key"]
                assert abs(info["video_start"] - (segment.start + T0)) <= frame + 1e-3, k
                end = segment.start + segment.duration + T0
                if k < len(plan) - 1:
                    assert end - 0.5 <= info["video_end"] <= end + frame + 1e-3, k
                else:
                    assert info["video_end"] <= duration + T0 + 1
            session = media.hls.sessions[sid]
            await asyncio.sleep(0.3)
        assert session.state in ("done", "running", "throttled")
        assert session.state != "failed"
        assert "plan_mismatch" not in caplog.text
        assert "tfdt_mismatch" not in caplog.text
        print(f"G4 first HLS segment {name}: {first_latency * 1000:.0f}ms")
        assert first_latency < 6.0
        if name == "h264_aac_audiotail.mp4":
            await asyncio.sleep(0.5)
            assert session.state == "done"


async def test_init_before_first_segment_mid_file(make_media_app):
    async with make_media_app() as media:
        mid = await media.play("h264_ac3_gop10.mkv", WEB_CAPS, start_position_seconds=35)
        sid = mid.json()["session_id"]
        init = await media.client.get(f"/api/hls/{sid}/init.mp4")
        assert init.status_code == 200
        assert init.headers["cache-control"] == "no-cache"
        assert media.hls.spawn_count == 1
        zero = await media.play("h264_ac3_gop10.mkv", WEB_CAPS, previous_session_id=sid)
        sid0 = zero.json()["session_id"]
        init0 = await media.client.get(f"/api/hls/{sid0}/init.mp4")
        assert init0.content == init.content


async def test_far_seek_restart_and_backward_seek(make_media_app, caplog):
    async with make_media_app(
        playback={"hls_segment_seconds": 2, "hls_ahead_seconds": 4, "hls_behind_seconds": 0}
    ) as media:
        body = (await media.play("h264_aac_start10.ts", WEB_CAPS)).json()
        sid = body["session_id"]
        hls = media.hls
        for k in range(3):
            assert (await media.client.get(f"/api/hls/{sid}/seg_{k}.m4s")).status_code == 200
        session = hls.sessions[sid]
        # A tiny clip is remuxed faster than the monitor finalizes rows (much faster on a slow
        # or emulated machine), so run 0 may have written most of the file before SIGSTOP
        # landed, and contiguous overshoot is kept. Make "far" deterministic, as a long real
        # file would be: wait until run 0 has settled (paused with every written row
        # finalized, or done), then evict what it produced past the requested window.
        await wait_for_settled_run(session)
        async with session.lock:
            for j in [j for j in session.ready if j > 2]:
                hls._drop(session, j).unlink(missing_ok=True)
        n = len(session.plan)
        produced = session.produced_max
        k = n - 4
        if produced < k <= produced + RESUME_WINDOW:  # would resume run 0, not restart
            k = produced
        assert k > 2 and k + 3 < n, (k, n, produced)
        spawns = hls.spawn_count
        assert (await media.client.get(f"/api/hls/{sid}/seg_{k}.m4s")).status_code == 200
        assert hls.spawn_count == spawns + 1
        for j in range(k + 1, k + 4):
            assert (await media.client.get(f"/api/hls/{sid}/seg_{j}.m4s")).status_code == 200
        assert hls.spawn_count == spawns + 1
        assert (await media.client.get(f"/api/hls/{sid}/seg_1.m4s")).status_code == 200
        assert hls.spawn_count == spawns + 2
        assert "plan_mismatch" not in caplog.text


def process_state(pid: int) -> str:
    """Single-letter process state: /proc/<pid>/stat on Linux (slim images have no ``ps``),
    else ``ps -o stat=``."""
    proc_stat = Path(f"/proc/{pid}/stat")
    if proc_stat.exists():
        return proc_stat.read_text().rsplit(")", 1)[1].split()[0]
    out = subprocess.run(["ps", "-o", "stat=", "-p", str(pid)], capture_output=True, text=True)
    return out.stdout.strip()[:1]


async def wait_for_settled_run(session, timeout: float = 20.0) -> None:
    """Wait until the session's run produces no more segments: ``done``, or ``throttled``
    with every row written to ``run.csv`` so far finalized (``produced_max`` caught up)."""

    def settled() -> bool:
        if session.state == "done":
            return True
        run = session.run
        if session.state != "throttled" or run is None:
            return False
        try:
            text = (session.dir / "run.csv").read_text()
        except FileNotFoundError:
            return False
        rows = sum(1 for line in text.splitlines() if line.strip())
        return rows > 0 and session.produced_max == run.start_k + rows - 1

    deadline = time.monotonic() + timeout
    while not settled():
        assert time.monotonic() < deadline, f"run never settled (state {session.state})"
        await asyncio.sleep(0.02)


def reorder_delay(path: str, index: int) -> float:
    """Max keyframe PTS - DTS of the source video (0 without B-frames)."""
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", str(index), "-show_entries",
         "packet=pts_time,dts_time,flags", "-of", "csv=p=0", path],
        check=True, capture_output=True, text=True,
    ).stdout  # fmt: skip
    delays = [0.0]
    for line in out.splitlines():
        pts, dts, flags = [*line.split(","), "", "", ""][:3]
        if "K" in flags and "N/A" not in (pts, dts):
            delays.append(float(pts) - float(dts))
    return max(delays)


def _tfdt_checks(split, start: float, frame: float, reorder: float, audio_low: float):
    video = split.video_start() - T0
    assert abs(video - start) <= frame + 1e-6, (video, start)
    audio = split.audio_start()
    assert audio is not None
    audio -= T0
    assert start - reorder - audio_low - 1e-6 <= audio <= start + 0.025, (audio, start)


@pytest.mark.parametrize(
    ("name", "action"),
    [
        ("h264_ac3_gop10.mkv", "aac_stereo"),
        ("h264_bframes_editlist.mp4", "copy"),
        ("h264_bframes_editlist.mp4", "aac_stereo"),
        ("h264_aac_start10.ts", "copy"),
    ],
)
async def test_cross_run_tfdt(make_media_app, caplog, name, action):
    """Fetch seg_0..2 (run 0), a far k (run 1), k+1, then seg_1 again after it was evicted
    (run 2): video tfdt == plan start + T0 in every run, audio within its window, identical
    init without elst, and seg_1's video tfdt identical across runs."""
    async with make_media_app(
        playback={"hls_segment_seconds": 2, "hls_ahead_seconds": 120, "hls_behind_seconds": 0}
    ) as media:
        hls = media.hls
        argvs: list[list[str]] = []
        real_spawn = hls.spawn

        async def recording_spawn(argv, **kwargs):
            argvs.append(list(argv))
            return await real_spawn(argv, **kwargs)

        hls.spawn = recording_spawn
        with media.app.state.db.read_session() as s:
            from hometv.db import models as m

            row = s.get(m.MediaFile, media.ids[name])
            probe = ProbeResult.from_json(row.probe)
            path, size = row.path, row.size_bytes
        keyframes = await keyframes_of(media, name)
        plan = plan_segments(keyframes, probe.duration_seconds, 2)
        session = await hls.create_session(
            SessionSpec(
                1, media.ids[name], path, size, probe, keyframes, plan, probe.audio[0].index, action
            )
        )
        sid = session.session_id
        frame = 1 / probe.video.frame_rate
        reorder = reorder_delay(path, probe.video.index)
        # Audio window lower bound below ``start - reorder`` (PRD §7 HLS step 3 (a)): one AAC
        # frame for copy, two for an AAC re-encode (Rev 10: ffmpeg 5.1 starts a restarted
        # run's re-encoded audio up to ~35 ms early), 1 s for MPEG-TS sources (Rev 6).
        audio_frame = 1024 / 44_100  # the fixtures' lavfi sine rate
        frames = 1 if action == "copy" else 2
        audio_low = 1.0 if name.endswith(".ts") else frames * audio_frame + 0.007
        inits, segs = [], {}
        with caplog.at_level(logging.WARNING, logger="hometv"):
            order = [0, 1, 2, len(plan) - 3, len(plan) - 2, 1]
            for i, k in enumerate(order):
                if i == 3:  # force a second run: run 0 done, what it produced ahead evicted
                    deadline = time.monotonic() + 20
                    while session.state != "done" and time.monotonic() < deadline:
                        await asyncio.sleep(0.05)
                    async with session.lock:
                        for j in [j for j in session.ready if j > 2]:
                            hls._drop(session, j).unlink(missing_ok=True)
                opened = await hls.open_segment(sid, k)
                with opened.file as fh:
                    data = fh.read()
                init = await hls.init_segment(sid)
                inits.append(init)
                split = split_fmp4(init + data)
                _tfdt_checks(split, plan[k].start, frame, reorder, audio_low)
                if k == 1:
                    segs.setdefault("seg1", []).append(split.video_start())
                assert i != 5 or hls.spawn_count == 3
        assert len(argvs) == 3
        # Byte-identical, except the esds/btrt bitrate estimates of stream-copied ADTS audio.
        if name.endswith(".ts"):
            assert len({normalize_init(i) for i in inits}) == 1
        else:
            assert len(set(inits)) == 1
        assert not has_edit_list(inits[0])
        assert segs["seg1"][0] == segs["seg1"][1]
        assert "plan_mismatch" not in caplog.text
        assert "init_mismatch" not in caplog.text
        argv = argvs[0]
        i = argv.index("-output_ts_offset")
        assert argv[i : i + 4] == ["-output_ts_offset", "10", "-avoid_negative_ts", "disabled"]
        assert i < argv.index("-f")
        options = argv[argv.index("-segment_format_options") + 1]
        assert "use_editlist=0:avoid_negative_ts=disabled" in options
        times = [float(t) for t in argv[argv.index("-segment_times") + 1].split(",")]
        if hls.segment_times_mode == "absolute":
            assert times == pytest.approx([s.start + 10 for s in plan[1:]], abs=1e-6)
        else:
            anchor = max(0.0, keyframes[0])
            assert times == pytest.approx([s.start - anchor for s in plan[1:]], abs=1e-6)


async def test_read_ahead_pauses_ffmpeg(make_media_app, clips_dir, tmp_path):
    root = tmp_path / "long"
    root.mkdir()
    link_or_copy(clips_dir / "h264_ac3_long.mkv", root / "h264_ac3_long.mkv")
    async with make_media_app(root=root, playback={"hls_ahead_seconds": 20}) as media:
        body = (await media.play("h264_ac3_long.mkv", WEB_CAPS)).json()
        sid = body["session_id"]
        assert (await media.client.get(f"/api/hls/{sid}/seg_0.m4s")).status_code == 200
        session = media.hls.sessions[sid]
        deadline = time.monotonic() + 10
        while session.state != "throttled" and time.monotonic() < deadline:
            await asyncio.sleep(0.05)
        assert session.state == "throttled"
        pid = session.run.proc.pid
        assert process_state(pid) == "T"
        await asyncio.sleep(0.3)
        ahead = sum(session.plan[k].duration for k in session.ready if k > 0)
        assert ahead <= 20 + 2 * 6
        segs_on_disk = sorted(Path(session.dir).glob("seg_*.m4s"))
        assert len(segs_on_disk) == len(session.ready)
        # Requesting further resumes it. The next unproduced segment always does: a ready one
        # (e.g. seg_3) resumes only when the lead behind it drops below ahead / 2, which
        # depends on how far ffmpeg overshot before SIGSTOP landed.
        produced = session.produced_max
        response = await media.client.get(f"/api/hls/{sid}/seg_{produced + 1}.m4s")
        assert response.status_code == 200
        deadline = time.monotonic() + 10
        while session.produced_max <= produced and time.monotonic() < deadline:
            await asyncio.sleep(0.05)
        assert session.produced_max > produced
        # Teardown of a (possibly paused) process completes.
        proc = session.run.proc if session.run else None
        assert (await media.client.delete(f"/api/hls/{sid}")).status_code == 204
        if proc is not None:
            assert proc.returncode is not None
        assert not session.dir.exists()


async def test_idle_cleanup_and_shutdown(make_media_app):
    async with make_media_app(playback={"session_idle_timeout_seconds": 1}) as media:
        body = (await media.play("h264_ac3_gop10.mkv", WEB_CAPS)).json()
        sid = body["session_id"]
        session = media.hls.sessions[sid]
        # Idle without sleeping: the last access moves past the timeout. The sweep may land
        # while a segment is being finalized; stopping waits for that thread before rmtree.
        session.last_access = media.hls.clock() - 2
        assert await media.hls.sweep_idle() == [sid]
        assert (await media.client.get(body["url"])).status_code == 404
        assert not session.dir.exists()
        other = (await media.play("h264_ac3_gop10.mkv", WEB_CAPS)).json()["session_id"]
        other_dir = media.hls.sessions[other].dir
        hls_dir = media.app.state.settings.hls_dir
    assert not other_dir.exists()
    assert not [p for p in hls_dir.iterdir() if p.is_dir()]
