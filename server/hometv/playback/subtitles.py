"""WebVTT subtitles.

- Cache: ``{data_dir}/subtitles/{media_file_id}/{track_id}.vtt`` (atomic renames).
- External sidecars: converted on request (``.vtt`` copied; srt/ass/ssa through ffmpeg with
  ``-sub_charenc CP1252`` when the bytes are not valid UTF-8), 30 s timeout.
- Embedded text tracks: one extraction job per media file (registry), all uncached text tracks
  in a single idle-priority ffmpeg pass, with an input-progress stall watchdog fed by
  ``-progress pipe:1`` (``out_time_us`` of a stream-copied video carrier to the null muxer).

Injectable for tests: ``spawn`` (job), ``run`` (external conversion), ``stall_seconds``,
``pending_wait_seconds``, ``external_timeout``.
"""

import asyncio
import contextlib
import logging
import os
import time
from collections.abc import Awaitable, Callable, Sequence
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from hometv.api.errors import ApiError
from hometv.config import Settings
from hometv.playback import procs
from hometv.probe import ProbeResult, run_subprocess

logger = logging.getLogger("hometv.subtitles")

STALL_SECONDS = 120.0
PENDING_WAIT_SECONDS = 30.0
EXTERNAL_TIMEOUT_SECONDS = 30.0
RETRY_AFTER_SECONDS = 10

Runner = Callable[[Sequence[str], float], Awaitable[tuple[int, bytes, bytes]]]
Spawn = Callable[..., Awaitable[procs.Proc]]


@dataclass(frozen=True)
class Sidecar:
    id: int
    path: str
    format: str
    kind: str


@dataclass(frozen=True)
class SubtitleSource:
    media_file_id: int
    path: str
    probe: ProbeResult | None
    sidecars: tuple[Sidecar, ...] = ()

    def text_tracks(self) -> list[int]:
        if self.probe is None:
            return []
        return [s.index for s in self.probe.subtitles if s.kind == "text"]


@dataclass
class Job:
    tracks: list[int]
    task: "asyncio.Task[None] | None" = None
    failed: set[int] = field(default_factory=set)
    proc: procs.Proc | None = None


def _not_found() -> ApiError:
    return ApiError(404, "subtitle_not_found", "Subtitle track not found")


def _not_text() -> ApiError:
    return ApiError(422, "subtitle_not_text", "Image subtitles cannot be converted to WebVTT")


def _failed(detail: str) -> ApiError:
    return ApiError(
        500, "subtitle_extraction_failed", "Subtitle extraction failed", {"detail": detail}
    )


def is_utf8(data: bytes) -> bool:
    if data.startswith(b"\xef\xbb\xbf"):
        data = data[3:]
    try:
        data.decode("utf-8")
    except UnicodeDecodeError:
        return False
    return True


def parse_progress(line: str) -> tuple[str, str] | None:
    key, sep, value = line.strip().partition("=")
    return (key, value) if sep else None


class SubtitleService:
    def __init__(
        self,
        settings: Settings,
        *,
        spawn: Spawn | None = None,
        run: Runner | None = None,
    ) -> None:
        self.settings = settings
        self.spawn: Spawn = spawn or procs.spawn
        self.run: Runner = run or run_subprocess
        self.stall_seconds = STALL_SECONDS
        self.pending_wait_seconds = PENDING_WAIT_SECONDS
        self.external_timeout = EXTERNAL_TIMEOUT_SECONDS
        self.jobs: dict[int, Job] = {}
        self.spawn_count = 0

    @property
    def ffmpeg(self) -> str:
        return self.settings.ffmpeg.ffmpeg_path

    def cache_dir(self, media_file_id: int) -> Path:
        return self.settings.subtitles_dir / str(media_file_id)

    def cache_path(self, media_file_id: int, track_id: str) -> Path:
        return self.cache_dir(media_file_id) / f"{track_id}.vtt"

    async def shutdown(self) -> None:
        for job in list(self.jobs.values()):
            if job.proc is not None:
                with contextlib.suppress(ProcessLookupError, OSError):
                    job.proc.kill()
            if job.task is not None:
                job.task.cancel()
        tasks = [j.task for j in self.jobs.values() if j.task is not None]
        await asyncio.gather(*tasks, return_exceptions=True)

    # -- requests --

    async def get_vtt(self, src: SubtitleSource, track_id: str) -> Path:
        """Path of the cached WebVTT file for ``track_id`` (converting/extracting first)."""
        # Cache files are named from the parsed id (``emb-02`` -> ``emb-2.vtt``), as the
        # extraction job writes them.
        if track_id.startswith("emb-"):
            index = self._embedded_index(src, track_id)
            path = self.cache_path(src.media_file_id, f"emb-{index}")
            if await asyncio.to_thread(path.is_file):
                return path
            return await self._embedded(src, index, path)
        if track_id.startswith("ext-"):
            sidecar = self._sidecar(src, track_id)
            path = self.cache_path(src.media_file_id, f"ext-{sidecar.id}")
            if await asyncio.to_thread(path.is_file):
                return path
            await self._convert_external(sidecar, path)
            return path
        raise _not_found()

    def _embedded_index(self, src: SubtitleSource, track_id: str) -> int:
        try:
            index = int(track_id[4:])
        except ValueError:
            raise _not_found() from None
        stream = next(
            (s for s in (src.probe.subtitles if src.probe else []) if s.index == index), None
        )
        if stream is None:
            raise _not_found()
        if stream.kind != "text":
            raise _not_text()
        return index

    @staticmethod
    def _sidecar(src: SubtitleSource, track_id: str) -> Sidecar:
        try:
            sidecar_id = int(track_id[4:])
        except ValueError:
            raise _not_found() from None
        sidecar = next((s for s in src.sidecars if s.id == sidecar_id), None)
        if sidecar is None:
            raise _not_found()
        if sidecar.kind != "text":
            raise _not_text()
        return sidecar

    # -- external --

    async def _convert_external(self, sidecar: Sidecar, path: Path) -> None:
        try:
            data = await asyncio.to_thread(Path(sidecar.path).read_bytes)
        except OSError as exc:
            raise _not_found() from exc
        await asyncio.to_thread(path.parent.mkdir, parents=True, exist_ok=True)
        tmp = path.with_name(f".{path.name}.{os.getpid()}.{time.monotonic_ns()}.tmp")
        if sidecar.format.lower() == "vtt" or sidecar.path.lower().endswith(".vtt"):
            await asyncio.to_thread(tmp.write_bytes, data)
            await asyncio.to_thread(tmp.replace, path)
            return
        args = [self.ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin", "-y"]
        if not is_utf8(data):
            args += ["-sub_charenc", "CP1252"]
        args += ["-i", sidecar.path, "-f", "webvtt", str(tmp)]
        try:
            code, _out, err = await self.run(args, self.external_timeout)
        except TimeoutError as exc:
            await asyncio.to_thread(tmp.unlink, missing_ok=True)
            raise ApiError(504, "subtitle_timeout", "Subtitle conversion timed out") from exc
        except OSError as exc:
            raise _failed(str(exc)) from exc
        if code != 0 or not await asyncio.to_thread(tmp.is_file):
            await asyncio.to_thread(tmp.unlink, missing_ok=True)
            raise _failed(procs.last_line(err) or f"ffmpeg exited with code {code}")
        await asyncio.to_thread(tmp.replace, path)

    # -- embedded --

    def prefetch(self, src: SubtitleSource) -> None:
        """Start the extraction job in the background (no-op without text tracks or when a job
        is running; the job itself skips cached tracks)."""
        if src.text_tracks() and src.media_file_id not in self.jobs:
            self._start_job(src)

    def _start_job(self, src: SubtitleSource) -> Job:
        job = Job(src.text_tracks())
        self.jobs[src.media_file_id] = job
        job.task = asyncio.create_task(self._run_job(src, job), name=f"subs-{src.media_file_id}")
        job.task.add_done_callback(lambda t: t.cancelled() or t.exception())
        return job

    async def _embedded(self, src: SubtitleSource, index: int, path: Path) -> Path:
        job = self.jobs.get(src.media_file_id) or self._start_job(src)
        assert job.task is not None
        try:
            await asyncio.wait_for(asyncio.shield(job.task), self.pending_wait_seconds)
        except TimeoutError:
            raise ApiError(
                503,
                "subtitle_pending",
                "Subtitle track is still being extracted",
                {"retry_after_seconds": RETRY_AFTER_SECONDS},
                {"Retry-After": str(RETRY_AFTER_SECONDS)},
            ) from None
        except Exception as exc:
            raise _failed(str(exc)) from exc
        if await asyncio.to_thread(path.is_file):
            return path
        raise _failed(f"track {index} was not extracted")

    def job_argv(self, src: SubtitleSource, outputs: list[tuple[int, Path]]) -> list[str]:
        args = [
            *procs.IDLE_PREFIX,
            self.ffmpeg, "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
            "-progress", "pipe:1", "-nostats",
            "-i", src.path,
        ]  # fmt: skip
        for index, tmp in outputs:
            args += ["-map", f"0:{index}", "-c:s", "webvtt", "-f", "webvtt", str(tmp)]
        probe = src.probe
        carrier = None
        if probe is not None and probe.video is not None:
            carrier = probe.video.index
        elif probe is not None and probe.audio:
            carrier = probe.audio[0].index
        if carrier is not None:
            args += ["-map", f"0:{carrier}", "-c", "copy", "-f", "null", "-"]
        return args

    async def _run_job(self, src: SubtitleSource, job: Job) -> None:
        try:
            directory = self.cache_dir(src.media_file_id)

            def pending() -> list[int]:
                return [i for i in job.tracks if not (directory / f"emb-{i}.vtt").is_file()]

            todo = await asyncio.to_thread(pending)
            if not todo:
                return
            await asyncio.to_thread(directory.mkdir, parents=True, exist_ok=True)
            outputs = [(i, directory / f".emb-{i}.vtt.{os.getpid()}.tmp") for i in todo]
            await self._extract(src, job, outputs)
        finally:
            self.jobs.pop(src.media_file_id, None)

    async def _extract(
        self, src: SubtitleSource, job: Job, outputs: list[tuple[int, Path]]
    ) -> None:
        argv = self.job_argv(src, outputs)
        try:
            proc = await self.spawn(argv)
        except OSError as exc:
            job.failed.update(i for i, _ in outputs)
            logger.warning("subtitle_extraction_failed: %s %s", src.media_file_id, exc)
            return
        self.spawn_count += 1
        job.proc = proc
        state: dict[str, Any] = {"out_time": -1, "progress_at": time.monotonic()}

        async def read_progress() -> None:
            assert proc.stdout is not None
            while line := await proc.stdout.readline():
                parsed = parse_progress(line.decode("ascii", "replace"))
                if parsed is None:
                    continue
                key, value = parsed
                if (
                    key in ("out_time_us", "out_time_ms")
                    and value.lstrip("-").isdigit()
                    and int(value) > state["out_time"]
                ):
                    state["out_time"] = int(value)
                    state["progress_at"] = time.monotonic()

        reader = asyncio.ensure_future(read_progress())
        stderr_task = asyncio.ensure_future(procs.drain(proc.stderr))
        wait_task = asyncio.ensure_future(proc.wait())
        interval = min(1.0, self.stall_seconds / 4)
        stalled = False
        while True:
            await asyncio.wait({wait_task}, timeout=interval)
            if wait_task.done():
                break
            if time.monotonic() - state["progress_at"] >= self.stall_seconds:
                stalled = True
                with contextlib.suppress(ProcessLookupError, OSError):
                    proc.kill()
                break
        if stalled:
            logger.warning("subtitle_stalled: %s", src.media_file_id)
            reader.cancel()
            stderr_task.cancel()
            wait_task.cancel()
            await asyncio.to_thread(self._cleanup, outputs)
            job.failed.update(i for i, _ in outputs)
            raise RuntimeError("subtitle_stalled")
        with contextlib.suppress(TimeoutError):
            await asyncio.wait_for(asyncio.gather(reader, stderr_task), 10.0)
        code = wait_task.result()
        if code != 0:
            err = stderr_task.result() if stderr_task.done() else b""
            logger.warning(
                "subtitle_extraction_failed: %s %s", src.media_file_id, procs.last_line(err)
            )
            await asyncio.to_thread(self._cleanup, outputs)
            job.failed.update(i for i, _ in outputs)
            return

        def publish() -> list[int]:
            missing = []
            for index, tmp in outputs:
                if tmp.is_file():
                    tmp.replace(tmp.parent / f"emb-{index}.vtt")
                else:
                    missing.append(index)
            return missing

        job.failed.update(await asyncio.to_thread(publish))

    @staticmethod
    def _cleanup(outputs: list[tuple[int, Path]]) -> None:
        for _index, tmp in outputs:
            with contextlib.suppress(OSError):
                tmp.unlink()
