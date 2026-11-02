"""Subtitle service with stubbed ffmpeg."""

import asyncio
import logging
from pathlib import Path

import pytest

from hometv.api.errors import ApiError
from hometv.playback.subtitles import Sidecar, SubtitleService, SubtitleSource, is_utf8
from hometv.probe import AudioStream, ProbeResult, SubtitleStream, VideoStream
from tests.stubs import FakeProc, Spawner

VTT = b"WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHola\n"


def source(tmp_path: Path, sidecars=()) -> SubtitleSource:
    probe = ProbeResult(
        container="mkv",
        duration_seconds=100.0,
        video=VideoStream(index=0, codec="h264"),
        audio=[AudioStream(index=1, codec="ac3", channels=6)],
        subtitles=[
            SubtitleStream(index=2, codec="subrip", kind="text", language="spa"),
            SubtitleStream(index=3, codec="hdmv_pgs_subtitle", kind="image"),
            SubtitleStream(index=4, codec="ass", kind="text", forced=True),
        ],
    )
    return SubtitleSource(7, str(tmp_path / "film.mkv"), probe, tuple(sidecars))


def outputs(argv: list[str]) -> dict[int, Path]:
    result = {}
    for i, arg in enumerate(argv):
        if arg == "webvtt" and argv[i - 1] == "-f":
            result[int(argv[i - 4].split(":")[1])] = Path(argv[i + 1])
    return result


def extraction(delay: float = 0.0, progress_steps: int = 0, step: float = 0.1, stall=False):
    async def scenario(proc: FakeProc) -> None:
        out_time = 0
        for _ in range(progress_steps):
            await asyncio.sleep(step)
            if not proc.running:
                return
            if not stall:
                out_time += 1_000_000
            proc.stdout.feed_data(f"out_time_us={out_time}\nprogress=continue\n".encode())
        await asyncio.sleep(delay)
        if not proc.running:
            return
        for path in outputs(proc.argv).values():
            path.write_bytes(VTT)
        proc.stdout.feed_data(b"progress=end\n")
        proc.finish(0)

    return scenario


@pytest.fixture
def settings(make_settings):
    s = make_settings()
    s.subtitles_dir.mkdir(parents=True, exist_ok=True)
    return s


def service(settings, spawner, run=None) -> SubtitleService:
    svc = SubtitleService(settings, spawn=spawner, run=run)
    svc.stall_seconds = 1.0
    svc.pending_wait_seconds = 1.0
    return svc


async def test_embedded_extraction_single_job(settings, tmp_path):
    spawner = Spawner(extraction(delay=0.2))
    svc = service(settings, spawner)
    src = source(tmp_path)
    svc.prefetch(src)
    paths = await asyncio.gather(svc.get_vtt(src, "emb-2"), svc.get_vtt(src, "emb-4"))
    assert spawner.count == 1
    assert paths[0].read_bytes() == VTT
    assert paths[0] == settings.subtitles_dir / "7" / "emb-2.vtt"
    argv = spawner.procs[0].argv
    assert argv[:6] == ["nice", "-n", "10", "ionice", "-c", "3"]
    assert argv[argv.index("-progress") + 1] == "pipe:1"
    assert set(outputs(argv)) == {2, 4}  # all text tracks in one pass, never the image track
    i = len(argv) - 7
    assert argv[i:] == ["-map", "0:0", "-c", "copy", "-f", "null", "-"]
    # Cached: no new job.
    assert (await svc.get_vtt(src, "emb-2")).read_bytes() == VTT
    svc.prefetch(src)
    await asyncio.sleep(0.05)
    assert spawner.count == 1


async def test_non_canonical_track_ids_use_the_canonical_cache_file(settings, tmp_path):
    """``emb-02`` is track 2: the job writes ``emb-2.vtt``, the request must read that file."""
    spawner = Spawner(extraction())
    svc = service(settings, spawner)
    src = source(tmp_path)
    path = await svc.get_vtt(src, "emb-02")
    assert path == settings.subtitles_dir / "7" / "emb-2.vtt"
    assert path.read_bytes() == VTT
    vtt = tmp_path / "film.vtt"
    vtt.write_bytes(VTT)
    ext = source(tmp_path, [Sidecar(3, str(vtt), "vtt", "text")])
    assert await svc.get_vtt(ext, "ext-003") == settings.subtitles_dir / "7" / "ext-3.vtt"


async def test_pending_after_wait_then_served(settings, tmp_path):
    spawner = Spawner(extraction(progress_steps=15, step=0.1))  # 1.5 s, progressing
    svc = service(settings, spawner)
    src = source(tmp_path)
    with pytest.raises(ApiError) as exc:
        await svc.get_vtt(src, "emb-2")
    assert (exc.value.status_code, exc.value.code) == (503, "subtitle_pending")
    assert exc.value.headers["Retry-After"] == "10"
    path = await svc.get_vtt(src, "emb-2")  # awaits the same job
    assert path.is_file()
    assert spawner.count == 1


async def test_sparse_track_with_progress_is_not_killed(settings, tmp_path, caplog):
    spawner = Spawner(extraction(progress_steps=15, step=0.2))  # 3 s without subtitle output
    svc = service(settings, spawner)
    svc.pending_wait_seconds = 10
    src = source(tmp_path)
    with caplog.at_level(logging.WARNING, logger="hometv"):
        path = await svc.get_vtt(src, "emb-4")
    assert path.read_bytes() == VTT
    assert "subtitle_stalled" not in caplog.text
    assert spawner.procs[0].signals == []


async def test_stalled_job_is_killed_and_restarted_next_time(settings, tmp_path, caplog):
    spawner = Spawner(extraction(progress_steps=100, step=0.1, stall=True))
    svc = service(settings, spawner)
    svc.pending_wait_seconds = 10
    src = source(tmp_path)
    with caplog.at_level(logging.WARNING, logger="hometv"), pytest.raises(ApiError) as exc:
        await svc.get_vtt(src, "emb-2")
    assert exc.value.code == "subtitle_extraction_failed"
    assert "subtitle_stalled" in caplog.text
    assert spawner.procs[0].signals  # killed
    assert not (settings.subtitles_dir / "7" / "emb-2.vtt").exists()
    assert not list((settings.subtitles_dir / "7").glob("*.tmp"))
    spawner.scenario = extraction()
    assert (await svc.get_vtt(src, "emb-2")).is_file()
    assert spawner.count == 2


async def test_job_failure_is_500(settings, tmp_path):
    async def crash(proc):
        proc.stderr.feed_data(b"boom\n")
        proc.finish(1)

    svc = service(settings, Spawner(crash))
    with pytest.raises(ApiError) as exc:
        await svc.get_vtt(source(tmp_path), "emb-2")
    assert (exc.value.status_code, exc.value.code) == (500, "subtitle_extraction_failed")


async def test_unknown_and_image_tracks(settings, tmp_path):
    svc = service(settings, Spawner(extraction()))
    src = source(tmp_path, [Sidecar(9, str(tmp_path / "a.sup"), "sup", "image")])
    for track, status, code in [
        ("emb-3", 422, "subtitle_not_text"),
        ("ext-9", 422, "subtitle_not_text"),
        ("emb-99", 404, "subtitle_not_found"),
        ("ext-1", 404, "subtitle_not_found"),
        ("emb-x", 404, "subtitle_not_found"),
        ("bogus", 404, "subtitle_not_found"),
    ]:
        with pytest.raises(ApiError) as exc:
            await svc.get_vtt(src, track)
        assert (exc.value.status_code, exc.value.code) == (status, code)


class StubRun:
    def __init__(self, delay: float = 0.0, code: int = 0) -> None:
        self.calls: list[list[str]] = []
        self.delay = delay
        self.code = code

    async def __call__(self, args, timeout):
        self.calls.append(list(args))
        if self.delay > timeout:
            raise TimeoutError
        if self.code == 0:
            Path(args[-1]).write_bytes(VTT)
        return self.code, b"", b"Invalid data\n"


async def test_external_srt_utf8_and_cp1252(settings, tmp_path):
    utf8 = tmp_path / "film.es.srt"
    utf8.write_bytes("﻿1\n00:00:01,000 --> 00:00:02,000\nAñoranza\n".encode())
    cp1252 = tmp_path / "film.en.srt"
    cp1252.write_bytes("1\n00:00:01,000 --> 00:00:02,000\nCaf\xe9\n".encode("cp1252"))
    run = StubRun()
    svc = service(settings, Spawner(extraction()), run=run)
    src = source(
        tmp_path,
        [Sidecar(1, str(utf8), "srt", "text"), Sidecar(2, str(cp1252), "srt", "text")],
    )
    assert (await svc.get_vtt(src, "ext-1")).read_bytes() == VTT
    assert "-sub_charenc" not in run.calls[0]
    await svc.get_vtt(src, "ext-2")
    i = run.calls[1].index("-sub_charenc")
    assert run.calls[1][i + 1] == "CP1252"
    assert i < run.calls[1].index("-i")
    await svc.get_vtt(src, "ext-2")  # cached
    assert len(run.calls) == 2


async def test_external_vtt_is_copied(settings, tmp_path):
    vtt = tmp_path / "film.vtt"
    vtt.write_bytes(VTT)
    run = StubRun()
    svc = service(settings, Spawner(extraction()), run=run)
    src = source(tmp_path, [Sidecar(3, str(vtt), "vtt", "text")])
    assert (await svc.get_vtt(src, "ext-3")).read_bytes() == VTT
    assert run.calls == []


async def test_external_timeout_and_failure(settings, tmp_path):
    srt = tmp_path / "film.srt"
    srt.write_bytes(b"1\n")
    svc = service(settings, Spawner(extraction()), run=StubRun(delay=60))
    src = source(tmp_path, [Sidecar(1, str(srt), "srt", "text")])
    with pytest.raises(ApiError) as exc:
        await svc.get_vtt(src, "ext-1")
    assert (exc.value.status_code, exc.value.code) == (504, "subtitle_timeout")
    svc.run = StubRun(code=1)
    with pytest.raises(ApiError) as exc:
        await svc.get_vtt(src, "ext-1")
    assert exc.value.code == "subtitle_extraction_failed"
    assert not list((settings.subtitles_dir / "7").glob("*"))


def test_is_utf8():
    assert is_utf8("ñ".encode())
    assert is_utf8(b"\xef\xbb\xbfabc")
    assert not is_utf8("é".encode("cp1252"))
