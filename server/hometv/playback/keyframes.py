"""Keyframe index service (also the scanner's background indexer).

- Sources in order: MKV/WebM ``Cues``, MP4 ``stss`` (``keyframe_readers``, off the loop), then
  the ffprobe packet scan run at idle priority with an ``rchar`` stall watchdog (wall-clock
  limit when ``/proc/{pid}/io`` is unreadable). stdout/stderr are drained concurrently.
- One build per media file at a time (``builds`` registry; playback joins or promotes).
- Failure memory keyed by ``(media_file_id, size_bytes, mtime_ns)``, shared with the indexer.
- Background indexer: FIFO, one file at a time, waits while an HLS session is ``running``.
- Cache: ``keyframe_index`` row (zlib float64 LE times relative to ``start_seconds``).

Injectable for tests: ``spawn``, ``read_rchar``, ``container_reader``, ``clock`` and the
``sample_interval`` / ``stall_seconds`` / ``session_poll_seconds`` timings.
"""

import asyncio
import contextlib
import logging
import math
import struct
import time
import zlib
from collections import deque
from collections.abc import Awaitable, Callable, Sequence
from dataclasses import dataclass, field
from typing import Any

from sqlalchemy import delete

from hometv.config import Settings
from hometv.db import models as m
from hometv.db.engine import Database
from hometv.playback import procs
from hometv.playback.keyframe_readers import (
    IndexUnavailable,
    mkv_cue_times,
    mp4_sync_times,
    parse_packet_row,
)
from hometv.probe import ProbeResult

logger = logging.getLogger("hometv.keyframes")

MB = 1_000_000
INITIAL_RATE = 50 * MB
FLOOR_RATE = 1 * MB
WINDOW_SECONDS = 10.0
MAX_ESTIMATE_SECONDS = 86_400
NO_PROC_IO_MIN_SECONDS = 600.0
NO_PROC_IO_RATE = 10 * MB
PLAYBACK_WAIT_SECONDS = 10.0
READER_SOURCES = {"mkv": "mkv_cues", "webm": "mkv_cues", "mp4": "mp4_stss"}


# --- estimate (pure) ---------------------------------------------------------------------------


@dataclass(frozen=True)
class RateSample:
    t: float
    rchar: int


def estimate_seconds(
    size_bytes: int,
    samples: Sequence[RateSample],
    *,
    started: float,
    now: float,
    proc_io: bool,
) -> int:
    """``estimated_seconds`` read-rate estimate: an int in ``[1, 86 400]``."""
    if not proc_io:
        bytes_read = max(0.0, now - started) * INITIAL_RATE
        rate = float(INITIAL_RATE)
    elif len(samples) < 2:
        bytes_read = float(samples[-1].rchar) if samples else 0.0
        rate = float(INITIAL_RATE)
    else:
        latest = samples[-1]
        bytes_read = float(latest.rchar)
        elapsed = max(latest.t - started, 1e-9)
        cumulative = latest.rchar / elapsed
        reference = None
        for sample in samples:
            if sample.t <= latest.t - WINDOW_SECONDS:
                reference = sample
        if reference is None or latest.t - reference.t <= 0:
            windowed = cumulative
        else:
            windowed = (latest.rchar - reference.rchar) / (latest.t - reference.t)
        rate = max(windowed, cumulative, float(FLOOR_RATE))
    remaining = max(0.0, size_bytes - bytes_read)
    return int(min(MAX_ESTIMATE_SECONDS, max(1, math.ceil(remaining / rate))))


# --- times blob ----------------------------------------------------------------------------


def encode_times(times: Sequence[float]) -> bytes:
    return zlib.compress(struct.pack(f"<{len(times)}d", *times))


def decode_times(blob: bytes) -> list[float]:
    raw = zlib.decompress(blob)
    return list(struct.unpack(f"<{len(raw) // 8}d", raw))


# --- data ----------------------------------------------------------------------------------


@dataclass(frozen=True)
class FileInfo:
    """What a build needs, snapshotted from the ``media_file`` row."""

    id: int
    path: str
    size_bytes: int
    mtime_ns: int
    container: str
    video_index: int
    start_seconds: float

    @classmethod
    def from_row(cls, row: m.MediaFile, probe: ProbeResult) -> "FileInfo":
        assert probe.video is not None
        return cls(
            row.id,
            row.path,
            row.size_bytes,
            row.mtime_ns,
            probe.container,
            probe.video.index,
            probe.start_seconds,
        )


class KeyframeIndexFailed(Exception):
    def __init__(self, reason: str) -> None:
        super().__init__(f"keyframe_index_failed: {reason}")
        self.reason = reason

    @property
    def detail(self) -> str:
        return f"keyframe_index_failed: {self.reason}"


class KeyframesPreparing(Exception):
    def __init__(self, estimated_seconds: int) -> None:
        super().__init__(f"keyframe index still building ({estimated_seconds}s)")
        self.estimated_seconds = estimated_seconds


@dataclass
class Build:
    info: FileInfo
    clock: Callable[[], float]
    task: "asyncio.Task[list[float]] | None" = None
    container_phase: bool = True
    started: float = 0.0
    proc_io: bool = True
    samples: list[RateSample] = field(default_factory=list)
    proc: procs.Proc | None = None

    def estimated_seconds(self) -> int:
        if self.container_phase:
            return 1
        return estimate_seconds(
            self.info.size_bytes,
            self.samples,
            started=self.started,
            now=self.clock(),
            proc_io=self.proc_io,
        )


ContainerReader = Callable[[FileInfo], tuple[str, list[float]] | None]
Spawn = Callable[[Sequence[str]], Awaitable[procs.Proc]]


def read_container_index(info: FileInfo) -> tuple[str, list[float]] | None:
    """Sources 1-2 (blocking; run in a thread). ``None`` -> use the ffprobe fallback."""
    source = READER_SOURCES.get(info.container)
    if source is None:
        return None
    reader = mkv_cue_times if source == "mkv_cues" else mp4_sync_times
    try:
        with open(info.path, "rb") as fh:

            def read(offset: int, size: int) -> bytes:
                fh.seek(offset)
                return fh.read(size)

            return source, reader(read, info.size_bytes, info.video_index)
    except (IndexUnavailable, OSError, ValueError, IndexError, StopIteration, struct.error):
        return None


class KeyframeService:
    def __init__(
        self,
        db: Database,
        settings: Settings,
        *,
        spawn: Spawn | None = None,
        read_rchar: Callable[[int], int | None] = procs.read_rchar,
        container_reader: ContainerReader = read_container_index,
        clock: Callable[[], float] = time.monotonic,
        session_running: Callable[[], bool] = lambda: False,
    ) -> None:
        self.db = db
        self.settings = settings
        self.spawn: Spawn = spawn or procs.spawn
        self.read_rchar = read_rchar
        self.container_reader = container_reader
        self.clock = clock
        self.session_running = session_running
        self.sample_interval = 2.0
        self.stall_seconds = 120.0
        self.session_poll_seconds = 5.0
        self.playback_wait = PLAYBACK_WAIT_SECONDS
        self.failures: dict[int, tuple[int, int, str]] = {}
        self.builds: dict[int, Build] = {}
        self.queue: deque[int] = deque()
        self._queue_event = asyncio.Event()
        self._worker: asyncio.Task[None] | None = None
        self._background: set[asyncio.Task[Any]] = set()

    # -- lifecycle --

    def start(self) -> None:
        if self._worker is None:
            self._worker = asyncio.create_task(self._run_worker(), name="keyframe-indexer")

    async def stop(self) -> None:
        tasks: list[asyncio.Task[Any]] = []
        if self._worker is not None:
            self._worker.cancel()
            tasks.append(self._worker)
            self._worker = None
        for build in list(self.builds.values()):
            if build.proc is not None:
                with contextlib.suppress(ProcessLookupError, OSError):
                    build.proc.kill()
            if build.task is not None:
                build.task.cancel()
                tasks.append(build.task)
        for task in list(self._background):
            task.cancel()
            tasks.append(task)
        for task in tasks:
            with contextlib.suppress(BaseException):
                await asyncio.wait_for(task, 2.0)

    def _spawn_bg(self, coro: Awaitable[Any]) -> asyncio.Task[Any]:
        task = asyncio.ensure_future(coro)
        self._background.add(task)
        task.add_done_callback(self._background.discard)
        return task

    # -- failure memory / cache --

    def failure_for(self, info: FileInfo) -> str | None:
        entry = self.failures.get(info.id)
        if entry is None:
            return None
        size, mtime, reason = entry
        if (size, mtime) != (info.size_bytes, info.mtime_ns):
            del self.failures[info.id]  # stale: the file changed
            return None
        return reason

    def _load_cached(self, media_file_id: int) -> list[float] | None:
        with self.db.read_session() as session:
            row = session.get(m.KeyframeIndex, media_file_id)
            return decode_times(row.times) if row is not None else None

    def _store(self, info: FileInfo, source: str, times: list[float]) -> bool:
        with self.db.write_session() as session:
            row = session.get(m.MediaFile, info.id)
            if row is None or (row.size_bytes, row.mtime_ns) != (info.size_bytes, info.mtime_ns):
                return False  # the file changed or vanished meanwhile
            session.execute(delete(m.KeyframeIndex).where(m.KeyframeIndex.media_file_id == info.id))
            session.add(
                m.KeyframeIndex(media_file_id=info.id, source=source, times=encode_times(times))
            )
        return True

    def _load_info(self, media_file_id: int) -> FileInfo | None:
        with self.db.read_session() as session:
            row = session.get(m.MediaFile, media_file_id)
            if row is None or row.missing_since is not None or row.probe_error or not row.probe:
                return None
            probe = ProbeResult.from_json(row.probe)
            if probe.video is None or probe.duration_seconds is None:
                return None
            return FileInfo.from_row(row, probe)

    # -- background queue --

    def enqueue(self, media_file_ids: list[int]) -> None:
        """Scanner ``keyframe_hook`` (sync, on the loop): filtering happens in a task."""
        self._spawn_bg(self.add(media_file_ids))

    async def add(self, media_file_ids: list[int]) -> list[int]:
        """Append eligible ids to the queue (skips queued, building and failed files)."""
        infos = await asyncio.to_thread(lambda: [self._load_info(i) for i in media_file_ids])
        added = []
        for info in infos:
            if info is None or info.id in self.queue or info.id in self.builds:
                continue
            if self.failure_for(info) is not None:
                continue
            self.queue.append(info.id)
            added.append(info.id)
        if added:
            self._queue_event.set()
        return added

    async def settle(self) -> None:
        """Wait for pending ``enqueue`` filtering tasks (tests)."""
        while pending := [t for t in self._background if t.get_coro().__name__ == "add"]:  # type: ignore[union-attr]
            await asyncio.gather(*pending, return_exceptions=True)

    async def _run_worker(self) -> None:
        while True:
            await self._queue_event.wait()
            if not self.queue:
                self._queue_event.clear()
                continue
            while self.session_running():
                await asyncio.sleep(self.session_poll_seconds)
            if not self.queue:
                continue
            media_file_id = self.queue.popleft()
            try:
                build = self.builds.get(media_file_id)
                if build is None:
                    info = await asyncio.to_thread(self._load_info, media_file_id)
                    if info is None or self.failure_for(info) is not None:
                        continue
                    if media_file_id in self.builds:  # promoted meanwhile
                        build = self.builds[media_file_id]
                    else:
                        build = self._start(info)
                assert build.task is not None
                with contextlib.suppress(Exception):
                    await asyncio.shield(build.task)
            except asyncio.CancelledError:
                raise
            except Exception:  # never let one file kill the worker
                logger.exception("keyframe_indexer_error: %s", media_file_id)

    # -- builds --

    def _start(self, info: FileInfo) -> Build:
        build = Build(info, self.clock)
        self.builds[info.id] = build
        build.task = asyncio.create_task(self._build(build), name=f"keyframes-{info.id}")
        build.task.add_done_callback(lambda t: t.cancelled() or t.exception())
        return build

    def ensure(self, info: FileInfo) -> Build:
        """Join the running build, or start one now (promoting a queued file)."""
        build = self.builds.get(info.id)
        if build is not None:
            return build
        with contextlib.suppress(ValueError):
            self.queue.remove(info.id)
        return self._start(info)

    async def times_for_playback(self, info: FileInfo, wait: float | None = None) -> list[float]:
        """Cached times; else start/join the build and wait up to ``wait`` seconds.

        Raises ``KeyframeIndexFailed`` (remembered failure or build failure) or
        ``KeyframesPreparing`` (still running; carries ``estimated_seconds``)."""
        reason = self.failure_for(info)
        if reason is not None:
            raise KeyframeIndexFailed(reason)
        build = self.builds.get(info.id)
        if build is None:
            cached = await asyncio.to_thread(self._load_cached, info.id)
            if cached is not None:
                return cached
            build = self.ensure(info)
        assert build.task is not None
        try:
            timeout = self.playback_wait if wait is None else wait
            return await asyncio.wait_for(asyncio.shield(build.task), timeout)
        except TimeoutError:
            raise KeyframesPreparing(build.estimated_seconds()) from None

    async def _build(self, build: Build) -> list[float]:
        info = build.info
        start = self.clock()
        try:
            cached = await asyncio.to_thread(self._load_cached, info.id)
            if cached is not None:
                return cached
            found = await asyncio.to_thread(self.container_reader, info)
            if found is not None and found[1]:
                source, raw = found
            else:
                build.container_phase = False
                source, raw = "ffprobe", await self._ffprobe_times(build)
            times = sorted({round(t - info.start_seconds, 6) for t in raw})
            await asyncio.to_thread(self._store, info, source, times)
            logger.info("keyframe_index_built: %s %s %.1fs", info.id, source, self.clock() - start)
            return times
        except KeyframeIndexFailed as exc:
            self.failures[info.id] = (info.size_bytes, info.mtime_ns, exc.reason)
            logger.warning("keyframe_index_failed: %s %s", info.id, exc.reason)
            raise
        finally:
            self.builds.pop(info.id, None)

    def ffprobe_argv(self, info: FileInfo) -> list[str]:
        return [
            *procs.IDLE_PREFIX,
            self.settings.ffmpeg.ffprobe_path,
            "-v",
            "error",
            "-select_streams",
            str(info.video_index),
            "-show_entries",
            "packet=pts_time,dts_time,flags",
            "-of",
            "csv=p=0",
            info.path,
        ]

    async def _ffprobe_times(self, build: Build) -> list[float]:
        info = build.info
        try:
            proc = await self.spawn(self.ffprobe_argv(info))
        except OSError as exc:
            raise KeyframeIndexFailed("ffprobe_error") from exc
        build.proc = proc
        build.started = self.clock()
        times: set[float] = set()

        async def read_stdout() -> None:
            assert proc.stdout is not None
            while line := await proc.stdout.readline():
                t = parse_packet_row(line.decode("ascii", "replace"))
                if t is not None:
                    times.add(t)

        stdout_task = asyncio.ensure_future(read_stdout())
        stderr_task = asyncio.ensure_future(procs.drain(proc.stderr))
        wait_task = asyncio.ensure_future(proc.wait())
        wall_limit = max(NO_PROC_IO_MIN_SECONDS, info.size_bytes / NO_PROC_IO_RATE)
        last_rchar = -1
        last_progress = build.started
        failure: str | None = None
        while True:
            await asyncio.wait({wait_task}, timeout=self.sample_interval)
            if wait_task.done():
                break
            now = self.clock()
            rchar = self.read_rchar(proc.pid)
            if rchar is None:
                build.proc_io = False
                if now - build.started > wall_limit:
                    failure = "timeout"
                    break
                continue
            build.proc_io = True
            build.samples.append(RateSample(now, rchar))
            if rchar > last_rchar:
                last_rchar, last_progress = rchar, now
            elif now - last_progress >= self.stall_seconds:
                failure = "stalled"
                break
        if failure is not None:
            with contextlib.suppress(ProcessLookupError, OSError):
                proc.kill()
            # Never await the exit here: a process stuck in uninterruptible I/O may never exit.
            self._spawn_bg(self._reap(wait_task, stdout_task, stderr_task))
            raise KeyframeIndexFailed(failure)
        try:
            await asyncio.wait_for(asyncio.gather(stdout_task, stderr_task), 10.0)
        except TimeoutError:
            stdout_task.cancel()
            stderr_task.cancel()
        if wait_task.result() != 0:
            logger.warning(
                "keyframe_ffprobe_error: %s %s",
                info.id,
                procs.last_line(stderr_task.result())
                if stderr_task.done() and not stderr_task.cancelled()
                else "",
            )
            raise KeyframeIndexFailed("ffprobe_error")
        if not times:
            raise KeyframeIndexFailed("ffprobe_error")
        return sorted(times)

    async def _reap(self, *tasks: "asyncio.Future[Any]") -> None:
        try:
            await tasks[0]
        finally:
            for task in tasks[1:]:
                task.cancel()
