"""HLS remux sessions + state machine.

One ffmpeg ``segment``-muxer run at a time per session writes ``raw_k.mp4`` + ``run.csv``; a
per-run monitor task verifies each row against the plan, splits it into ``init.mp4`` +
``seg_k.m4s`` (off the loop) and applies eviction, the disk budget and the read-ahead throttle.
Segment requests decide ready / wait / restart under the session lock (never held while
waiting). Readiness is tracked in memory (``Session.ready``), so no request stats the disk.

``segment_times`` semantics differ between ffmpeg versions: 5.x compares the list with packet
timestamps (``absolute``, the reference behaviour: boundaries + T0), newer versions add the
reference stream's first PTS to every entry (``relative``: boundaries minus the run's first
keyframe). ``detect_segment_times_mode`` probes the installed ffmpeg once.

Injectable for tests: ``spawn``, ``clock``, ``disk_free``, ``segment_times_mode`` and the
``wait_seconds`` / ``poll_seconds`` / ``sweep_interval`` / ``kill_grace`` timings.
"""

import asyncio
import contextlib
import logging
import mmap
import os
import shutil
import signal
import tempfile
import time
import uuid
from collections.abc import Awaitable, Callable, Sequence
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, BinaryIO, Literal

from hometv.api.errors import ApiError
from hometv.config import Settings
from hometv.playback import procs
from hometv.playback.decide import AudioAction
from hometv.playback.segments import (
    TS_OFFSET_SECONDS,
    Fmp4Error,
    Segment,
    init_is_complete,
    normalize_init,
    parse_run_row,
    render_playlist,
    segment_index,
    split_fmp4,
    verify_row,
    verify_tfdt,
)
from hometv.probe import ProbeResult, run_subprocess

logger = logging.getLogger("hometv.hls")

WAIT_SECONDS = 20.0
POLL_SECONDS = 0.1
SWEEP_INTERVAL_SECONDS = 30.0
RESUME_WINDOW = 3
MAX_CONSECUTIVE_TIMEOUTS = 3
MIN_FREE_BYTES = 1024**3
# ``movie_timescale=1000`` pins 5.x's default: newer movenc derives the mvhd timescale per
# file from the fragment start, which would make init.mp4 differ between segments/runs.
MOVFLAGS = (
    "movflags=+frag_keyframe+empty_moov+default_base_moof+frag_discont"
    ":use_editlist=0:avoid_negative_ts=disabled:movie_timescale=1000"
)
AUDIO_ARGS: dict[str, list[str]] = {
    "copy": ["-c:a", "copy"],
    "aac_stereo": ["-c:a", "aac", "-ac", "2", "-b:a", "192k"],
    "aac_5_1": ["-c:a", "aac", "-ac", "6", "-b:a", "384k"],
    "none": [],
}
NO_MORE_CUTS = "999999"  # a cut time no run ever reaches (no remaining boundaries)
SEEK_MARGIN_SECONDS = 0.001 + 3 / 23
BACKWARD_SEEK_SECONDS = 0.5
FORWARD_SEEK_CONTAINERS = frozenset({"ts", "mpeg"})  # land on the first keyframe after -ss

SegmentTimesMode = Literal["absolute", "relative"]
State = Literal["running", "throttled", "done", "failed", "stopped"]
Spawn = Callable[..., Awaitable[procs.Proc]]


@dataclass(frozen=True)
class SessionSpec:
    profile_id: int
    media_file_id: int
    path: str
    size_bytes: int
    probe: ProbeResult
    keyframes: list[float]
    plan: list[Segment]
    audio_stream_index: int | None
    audio_action: AudioAction
    start_position_seconds: float = 0.0


@dataclass
class Run:
    run_id: int
    start_k: int
    proc: procs.Proc
    csv_offset: int = 0
    rows_seen: int = 0
    partial: str = ""
    monitor: "asyncio.Task[None] | None" = None
    # In-flight ``_finalize`` (worker thread). Cancelling the monitor does not stop the thread,
    # so stopping a run waits for it before anyone clears or removes the session dir.
    finalizing: "asyncio.Future[Any] | None" = None


@dataclass
class Session:
    session_id: str
    spec: SessionSpec
    dir: Path
    last_access: float
    lock: asyncio.Lock = field(default_factory=asyncio.Lock)
    state: State = "running"
    detail: str | None = None
    run_id: int = 0
    run: Run | None = None
    run_start_segment: int = 0
    produced_max: int = -1
    last_requested: int | None = None
    consecutive_timeouts: int = 0
    last_mismatch_segment: int | None = None
    ready: dict[int, int] = field(default_factory=dict)  # k -> bytes on disk
    init_size: int | None = None
    init_complete: bool = False
    incomplete_inits: int = 0

    def init_servable(self) -> bool:
        """A complete init, or the best there will be (two incomplete ones, or the run ended)."""
        if self.init_size is None:
            return False
        return self.init_complete or self.incomplete_inits >= 2 or self.state == "done"

    @property
    def profile_id(self) -> int:
        return self.spec.profile_id

    @property
    def plan(self) -> list[Segment]:
        return self.spec.plan

    @property
    def frame(self) -> float:
        rate = self.spec.probe.video.frame_rate if self.spec.probe.video else None
        return 1.0 / rate if rate else 0.042

    @property
    def first_keyframe(self) -> float:
        return max(0.0, self.spec.keyframes[0]) if self.spec.keyframes else 0.0

    def expected_start(self, k: int) -> float:
        return self.first_keyframe if k == 0 else self.plan[k].start

    def anchor(self) -> int:
        return self.last_requested if self.last_requested is not None else self.run_start_segment

    def lead_seconds(self) -> float:
        """Planned duration of the contiguous ready run after the player's position."""
        lead = 0.0
        k = (self.last_requested + 1) if self.last_requested is not None else self.run_start_segment
        while k < len(self.plan) and k in self.ready:
            lead += self.plan[k].duration
            k += 1
        return lead

    def bytes_on_disk(self) -> int:
        return sum(self.ready.values()) + (self.init_size or 0)


@dataclass
class SegmentFile:
    """An open ``seg_k.m4s``: opened before the segment may be evicted, so the response keeps
    streaming it after an unlink. The caller closes ``file``."""

    file: BinaryIO
    size: int


def _open_segment(path: Path) -> SegmentFile:
    fh = open(path, "rb")  # noqa: SIM115 - owned by the caller (streamed, then closed)
    return SegmentFile(fh, os.fstat(fh.fileno()).st_size)


def _rmtree(path: Path) -> None:
    shutil.rmtree(path, ignore_errors=True)


def _unlink_all(paths: Sequence[Path]) -> None:
    for path in paths:
        with contextlib.suppress(OSError):
            path.unlink()


def _disk_free(path: Path) -> int:
    return shutil.disk_usage(path).free


async def detect_segment_times_mode(ffmpeg_path: str) -> SegmentTimesMode:
    """Cut a 4 s lavfi clip (keyframe every second, ``-output_ts_offset 10``) with
    ``-segment_times 2``: absolute semantics cut at 11 s, relative ones at 12 s."""
    with tempfile.TemporaryDirectory(prefix="hometv-segprobe-") as tmp:
        args = [
            ffmpeg_path, "-hide_banner", "-loglevel", "error", "-nostdin",
            "-f", "lavfi", "-i", "testsrc=size=32x32:rate=10:duration=4",
            "-c:v", "mpeg4", "-g", "10", "-output_ts_offset", "10",
            "-f", "segment", "-segment_format", "nut", "-segment_times", "2",
            "-segment_list", f"{tmp}/list.csv", "-segment_list_type", "csv",
            f"{tmp}/s_%d.nut",
        ]  # fmt: skip
        try:
            code, _out, err = await run_subprocess(args, 30.0)
            list_file = Path(tmp, "list.csv")
            text = list_file.read_text() if code == 0 and list_file.is_file() else ""
        except (OSError, TimeoutError) as exc:
            logger.warning("segment_times_probe_failed: %s", exc)
            return "absolute"
    try:
        first_end = float(text.splitlines()[0].split(",")[2])
    except (IndexError, ValueError):
        logger.warning("segment_times_probe_failed: %s", procs.last_line(err))
        return "absolute"
    mode: SegmentTimesMode = "relative" if first_end > 11.5 else "absolute"
    logger.info("segment_times_mode: %s", mode)
    return mode


class HlsSessionManager:
    def __init__(
        self,
        settings: Settings,
        *,
        spawn: Spawn | None = None,
        clock: Callable[[], float] = time.monotonic,
        disk_free: Callable[[Path], int] = _disk_free,
        segment_times_mode: SegmentTimesMode | None = None,
    ) -> None:
        self.settings = settings
        self.spawn: Spawn = spawn or procs.spawn
        self.clock = clock
        self.disk_free = disk_free
        self.segment_times_mode = segment_times_mode
        self.wait_seconds = WAIT_SECONDS
        self.poll_seconds = POLL_SECONDS
        self.sweep_interval = SWEEP_INTERVAL_SECONDS
        self.kill_grace = 3.0
        self.sessions: dict[str, Session] = {}
        self.over_cap = False
        self.spawn_count = 0
        self._mode_lock = asyncio.Lock()
        self._create_lock = asyncio.Lock()
        self._sweeper: asyncio.Task[None] | None = None

    # -- config shortcuts --

    @property
    def hls_dir(self) -> Path:
        return self.settings.hls_dir

    @property
    def cap_bytes(self) -> int:
        return self.settings.playback.hls_max_disk_mb * 1024 * 1024

    @property
    def ahead(self) -> float:
        return float(self.settings.playback.hls_ahead_seconds)

    @property
    def behind(self) -> float:
        return float(self.settings.playback.hls_behind_seconds)

    # -- lifecycle --

    def start(self) -> None:
        if self._sweeper is None:
            self._sweeper = asyncio.create_task(self._sweep_loop(), name="hls-sweeper")

    async def shutdown(self) -> None:
        if self._sweeper is not None:
            self._sweeper.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await self._sweeper
            self._sweeper = None
        await asyncio.gather(
            *(self._stop_session(s) for s in list(self.sessions.values())),
            return_exceptions=True,
        )

    async def _sweep_loop(self) -> None:
        while True:
            await asyncio.sleep(self.sweep_interval)
            try:
                await self.sweep_idle()
            except Exception:
                logger.exception("hls_sweep_error")

    async def sweep_idle(self) -> list[str]:
        timeout = self.settings.playback.session_idle_timeout_seconds
        now = self.clock()
        idle = [s for s in self.sessions.values() if now - s.last_access > timeout]
        for session in idle:
            logger.info("hls_session_idle: %s", session.session_id)
            await self._stop_session(session)
        return [s.session_id for s in idle]

    def any_running(self) -> bool:
        return any(s.state == "running" for s in self.sessions.values())

    def total_bytes(self) -> int:
        return sum(s.bytes_on_disk() for s in self.sessions.values())

    # -- session lookup --

    def _get(self, session_id: str) -> Session:
        session = self.sessions.get(session_id)
        if session is None or session.state == "stopped":
            raise ApiError(404, "session_not_found", "HLS session not found")
        return session

    @staticmethod
    def _failed_error(session: Session) -> ApiError:
        return ApiError(
            500,
            "transcoder_failed",
            "The streaming conversion failed",
            {"detail": session.detail or "ffmpeg failed"},
        )

    def _check_alive(self, session: Session) -> None:
        if session.state == "failed":
            raise self._failed_error(session)
        if session.state == "stopped":
            raise ApiError(404, "session_not_found", "HLS session not found")

    # -- create / stop --

    async def ensure_mode(self) -> SegmentTimesMode:
        async with self._mode_lock:
            if self.segment_times_mode is None:
                self.segment_times_mode = await detect_segment_times_mode(
                    self.settings.ffmpeg.ffmpeg_path
                )
        return self.segment_times_mode

    def estimate_bytes(self, spec: SessionSpec) -> int:
        bitrate = spec.probe.bitrate
        if not bitrate and spec.probe.duration_seconds:
            bitrate = int(spec.size_bytes * 8 / spec.probe.duration_seconds)
        return int((bitrate or 0) / 8 * (self.ahead + self.behind))

    async def create_session(self, spec: SessionSpec) -> Session:
        # Check and register under one lock: the checks await (disk_free, the mode probe,
        # mkdir), so concurrent creates would otherwise all pass ``max_sessions`` / the budget.
        async with self._create_lock:
            active = [s for s in self.sessions.values() if s.state not in ("failed", "stopped")]
            if len(active) >= self.settings.playback.max_sessions:
                raise ApiError(503, "too_many_sessions", "Too many streaming sessions")
            free = await asyncio.to_thread(self.disk_free, self.hls_dir)
            if (
                self.total_bytes() + self.estimate_bytes(spec) > self.cap_bytes
                or free < MIN_FREE_BYTES
            ):
                raise ApiError(503, "hls_disk_full", "Not enough space for a streaming session")
            await self.ensure_mode()
            session_id = uuid.uuid4().hex
            directory = self.hls_dir / session_id
            await asyncio.to_thread(directory.mkdir, parents=True, exist_ok=True)
            session = Session(session_id, spec, directory, last_access=self.clock())
            self.sessions[session_id] = session
        k0 = segment_index(spec.plan, spec.start_position_seconds)
        async with session.lock:
            await self._start_run(session, k0)
        logger.info(
            "hls_session_created: %s media_file=%s k0=%s segments=%s",
            session_id,
            spec.media_file_id,
            k0,
            len(spec.plan),
        )
        return session

    async def stop_owned(self, session_id: str | None, profile_id: int) -> bool:
        """``previous_session_id``: stop it if it belongs to ``profile_id``; else ignore."""
        if not session_id:
            return False
        session = self.sessions.get(session_id)
        if session is None or session.profile_id != profile_id or session.state == "stopped":
            return False
        await self._stop_session(session)
        return True

    async def delete(self, session_id: str) -> None:
        session = self.sessions.get(session_id)
        if session is not None:
            await self._stop_session(session)

    async def _stop_session(self, session: Session) -> None:
        async with session.lock:
            if session.state == "stopped":
                return
            session.state = "stopped"
            run, session.run = session.run, None
            if run is not None:
                await self._stop_run(run)
            self.sessions.pop(session.session_id, None)
            session.ready.clear()
            session.init_size = None
            session.init_complete = False
        await asyncio.to_thread(_rmtree, session.dir)
        logger.info("hls_session_stopped: %s", session.session_id)

    # -- ffmpeg runs --

    def ffmpeg_argv(self, session: Session, k: int) -> list[str]:
        spec = session.spec
        assert spec.probe.video is not None
        args = [self.settings.ffmpeg.ffmpeg_path, "-hide_banner", "-loglevel", "error", "-nostdin"]
        if k > 0:
            args += ["-noaccurate_seek", "-ss", f"{self.seek_position(session, k):.6f}"]
        args += [
            "-i",
            spec.path,
            "-copyts",
            "-start_at_zero",
            "-map",
            f"0:{spec.probe.video.index}",
        ]
        if spec.audio_action != "none" and spec.audio_stream_index is not None:
            args += ["-map", f"0:{spec.audio_stream_index}"]
        args += ["-c:v", "copy"]
        if spec.probe.video.codec.lower() == "hevc":
            args += ["-tag:v", "hvc1"]
        args += AUDIO_ARGS[spec.audio_action]
        args += ["-sn", "-dn", "-map_chapters", "-1", "-map_metadata", "-1"]
        args += ["-output_ts_offset", str(TS_OFFSET_SECONDS), "-avoid_negative_ts", "disabled"]
        args += ["-f", "segment", "-segment_format", "mp4", "-segment_format_options", MOVFLAGS]
        args += ["-segment_times", self.segment_times(session, k)]
        args += [
            "-segment_time_delta", "0.01",
            "-segment_start_number", str(k),
            "-reset_timestamps", "0",
            "-segment_list", str(session.dir / "run.csv"),
            "-segment_list_type", "csv",
            str(session.dir / "raw_%d.mp4"),
        ]  # fmt: skip
        return args

    @staticmethod
    def seek_position(session: Session, k: int) -> float:
        """Input ``-ss`` that makes a run start exactly on the keyframe ``b_k``.

        - Index-seeking containers (MKV/WebM/MP4/AVI) land on the keyframe at or before the
          target, but fftools moves the target ``3/23 s`` earlier when a stream has a video
          delay (B-frames), which would land on the previous keyframe: use ``b_k + 0.001 +
          3/23``, capped at half the distance to the next keyframe.
        - Timestamp-searched containers (MPEG-TS/PS) land on the first keyframe after the
          searched position: use a target before ``b_k``'s DTS, ``b_k - min(0.5 s, half the
          distance to the previous keyframe)``.
        ``-noaccurate_seek`` goes with it so re-encoded audio is not trimmed at the target."""
        start = session.plan[k].start
        keyframes = session.spec.keyframes
        if session.spec.probe.container in FORWARD_SEEK_CONTAINERS:
            earlier = [t for t in keyframes if t < start - 1e-6]
            back = BACKWARD_SEEK_SECONDS
            if earlier:
                back = min(back, (start - earlier[-1]) / 2)
            return max(0.0, start - back)
        later = [t for t in keyframes if t > start + 1e-6]
        margin = SEEK_MARGIN_SECONDS
        if later:
            margin = min(margin, (later[0] - start) / 2)
        return start + max(0.001, margin)

    def segment_times(self, session: Session, k: int) -> str:
        plan = session.plan
        bounds = [s.start for s in plan[k + 1 :]]
        if not bounds:
            return NO_MORE_CUTS
        if self.segment_times_mode == "relative":
            anchor = session.expected_start(k)
            return ",".join(f"{b - anchor:.6f}" for b in bounds)
        return ",".join(f"{b + TS_OFFSET_SECONDS:.6f}" for b in bounds)

    async def _start_run(self, session: Session, k: int) -> None:
        """(Re)start ffmpeg at segment ``k``. Caller holds ``session.lock``."""
        if session.run is not None:
            old, session.run = session.run, None
            await self._stop_run(old)
        await asyncio.to_thread(self._clear_run_files, session.dir)
        session.run_id += 1
        session.run_start_segment = k
        session.produced_max = k - 1
        session.state = "running"
        argv = self.ffmpeg_argv(session, k)
        log = await asyncio.to_thread(open, session.dir / "ffmpeg.log", "ab")
        try:
            proc = await self.spawn(argv, stdout=asyncio.subprocess.DEVNULL, stderr=log)
        except OSError as exc:
            await self._fail(session, f"ffmpeg could not be started: {exc}")
            return
        finally:
            await asyncio.to_thread(log.close)
        self.spawn_count += 1
        run = Run(session.run_id, k, proc)
        session.run = run
        run.monitor = asyncio.create_task(
            self._monitor(session, run), name=f"hls-{session.session_id}-{run.run_id}"
        )

    @staticmethod
    def _clear_run_files(directory: Path) -> None:
        with contextlib.suppress(FileNotFoundError):
            for entry in os.scandir(directory):
                if entry.name.startswith("raw_") or entry.name == "run.csv":
                    with contextlib.suppress(OSError):
                        os.unlink(entry.path)

    async def _stop_run(self, run: Run) -> None:
        if run.monitor is not None and run.monitor is not asyncio.current_task():
            run.monitor.cancel()
        await procs.kill_sequence(run.proc, grace=self.kill_grace)
        job = run.finalizing
        if job is not None and not job.done():
            await asyncio.wait({job})
            if not job.cancelled():
                job.exception()  # retrieved: its result belongs to the cancelled monitor

    async def _fail(self, session: Session, detail: str) -> None:
        """Caller holds ``session.lock``."""
        logger.error("hls_session_failed: %s %s", session.session_id, detail)
        session.state = "failed"
        session.detail = detail
        run, session.run = session.run, None
        if run is not None:
            await self._stop_run(run)

    # -- monitor --

    async def _monitor(self, session: Session, run: Run) -> None:
        try:
            await self._watch_run(session, run)
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # a bug must not leave waiters hanging on a dead monitor
            logger.exception("hls_monitor_error: %s", session.session_id)
            async with session.lock:
                if run is session.run:
                    await self._fail(session, f"internal error: {exc}")

    async def _watch_run(self, session: Session, run: Run) -> None:
        wait_task = asyncio.ensure_future(run.proc.wait())
        try:
            while True:
                exited = wait_task.done()
                lines = await asyncio.to_thread(self._read_rows, session.dir, run)
                for line in lines:
                    if run is not session.run:
                        return
                    if not await self._on_row(session, run, line):
                        return
                if run is not session.run:
                    return
                if exited:
                    await self._on_exit(session, run, wait_task.result())
                    return
                await asyncio.wait({wait_task}, timeout=self.poll_seconds)
        finally:
            if not wait_task.done():
                wait_task.cancel()

    @staticmethod
    def _read_rows(directory: Path, run: Run) -> list[str]:
        try:
            with open(directory / "run.csv", "rb") as fh:
                fh.seek(run.csv_offset)
                data = fh.read()
        except FileNotFoundError:
            return []
        run.csv_offset += len(data)
        text = run.partial + data.decode("utf-8", "replace")
        *lines, run.partial = text.split("\n")
        return [ln for ln in lines if ln.strip()]

    def _finalize(
        self, session: Session, k: int
    ) -> tuple[str | None, int, tuple[int, bool] | None]:
        """Split ``raw_k.mp4`` (blocking; worker thread). Returns ``(error, seg_size, init)``
        with ``init = (size, complete)`` when ``init.mp4`` was (re)written; ``error`` is a
        mismatch reason (``tfdt_mismatch``) or ``missing``.

        ``init.mp4`` comes from the first finalized segment; an init whose ``esds`` lacks the
        AudioSpecificConfig (first segment of a run of an ADTS source) is replaced by the first
        complete one. Any other difference (ignoring the informational bitrate fields, see
        ``normalize_init``) is logged as ``init_mismatch`` (first one kept)."""
        raw = session.dir / f"raw_{k}.mp4"
        try:
            fh = open(raw, "rb")  # noqa: SIM115 - closed by the ``with fh`` below
        except FileNotFoundError:
            return "missing", 0, None
        with fh:
            if os.fstat(fh.fileno()).st_size == 0:
                return self._finalize_mapped(session, k, raw, b"")
            # Mapped, not read: only the init and box headers are touched in memory; the
            # media boxes are written from the map (no copy of a 200 MB 4K segment).
            with mmap.mmap(fh.fileno(), 0, access=mmap.ACCESS_READ) as data:
                return self._finalize_mapped(session, k, raw, data)

    def _finalize_mapped(
        self, session: Session, k: int, raw: Path, data: bytes | mmap.mmap
    ) -> tuple[str | None, int, tuple[int, bool] | None]:
        try:
            split = split_fmp4(data)
        except Fmp4Error as exc:
            return f"tfdt_mismatch: {exc}", 0, None
        if not verify_tfdt(split, expected_start=session.expected_start(k), frame=session.frame):
            video = split.video_start()
            return (
                f"tfdt_mismatch: segment {k} video starts at "
                f"{(video or 0) - TS_OFFSET_SECONDS:.6f}, planned {session.expected_start(k):.6f}",
                0,
                None,
            )
        init_path = session.dir / "init.mp4"
        init: tuple[int, bool] | None = None
        complete = init_is_complete(split.init)
        write = not init_path.exists()
        if not write:
            existing = init_path.read_bytes()
            if normalize_init(existing) != normalize_init(split.init):
                if complete and not init_is_complete(existing):
                    logger.info("init_replaced: %s segment %s", session.session_id, k)
                    write = True
                elif complete == init_is_complete(existing):
                    logger.warning("init_mismatch: %s segment %s", session.session_id, k)
        if write:
            tmp = init_path.with_suffix(".tmp")
            tmp.write_bytes(split.init)
            tmp.replace(init_path)
        if write or not complete:
            init = (len(split.init), complete)
        tmp = session.dir / f"seg_{k}.m4s.tmp"
        with open(tmp, "wb") as out, memoryview(data) as view:
            for start, end in split.media_ranges:
                with view[start:end] as chunk:
                    out.write(chunk)
        tmp.replace(session.dir / f"seg_{k}.m4s")
        raw.unlink(missing_ok=True)
        return None, split.media_size, init

    async def _on_row(self, session: Session, run: Run, line: str) -> bool:
        """Verify + finalize one row. Returns False when this run is over (replaced)."""
        row = parse_run_row(line)
        first = run.rows_seen == 0
        expected_k = run.start_k + run.rows_seen
        run.rows_seen += 1
        error: str | None = None
        seg_size: int = 0
        init_info: tuple[int, bool] | None = None
        if row is None:
            error = f"unparseable run.csv row {line!r}"
        elif row.k != expected_k:
            error = f"unexpected segment {row.k}, expected {expected_k}"
        else:
            check = verify_row(
                row,
                session.plan,
                first_of_run=first,
                duration=session.spec.probe.duration_seconds or 0.0,
                frame=session.frame,
                expected_start=(
                    session.expected_start(row.k) if row.k < len(session.plan) else None
                ),
            )
            if not check.ok:
                error = check.reason
            else:
                if check.seek_mismatch:
                    logger.warning(
                        "seek_mismatch: %s segment %s starts at %.6f",
                        session.session_id,
                        row.k,
                        row.start - TS_OFFSET_SECONDS,
                    )
                job = asyncio.ensure_future(asyncio.to_thread(self._finalize, session, row.k))
                run.finalizing = job
                error, seg_size, init_info = await asyncio.shield(job)
        k = row.k if row is not None else expected_k
        to_delete: list[Path] = []
        async with session.lock:
            if run is not session.run or session.state in ("failed", "stopped"):
                return False
            if error == "missing":
                return True
            if error is not None:
                kind = "tfdt_mismatch" if error.startswith("tfdt_mismatch") else "plan_mismatch"
                logger.warning("%s: %s segment %s: %s", kind, session.session_id, k, error)
                if session.last_mismatch_segment == k:
                    await self._fail(session, f"segment_plan_mismatch: {k}")
                    return False
                session.last_mismatch_segment = k
                await self._start_run(session, min(k, len(session.plan) - 1))
                return False
            session.ready[k] = seg_size
            if init_info is not None:
                size, complete = init_info
                if complete:
                    session.init_size, session.init_complete = size, True
                else:
                    session.incomplete_inits += 1
                    if session.init_size is None:
                        session.init_size = size
            session.produced_max = max(session.produced_max, k)
            to_delete += self._evict(session)
            to_delete += self._enforce_budget()
            self._throttle(session)
        if to_delete:
            await asyncio.to_thread(_unlink_all, to_delete)
        return True

    async def _on_exit(self, session: Session, run: Run, code: int) -> None:
        async with session.lock:
            if run is not session.run or session.state in ("failed", "stopped"):
                return
            if code == 0:
                session.state = "done"
                session.run = None
                logger.info("hls_run_done: %s run %s", session.session_id, run.run_id)
                return
            tail = await asyncio.to_thread(self._log_tail, session.dir)
            await self._fail(session, tail or f"ffmpeg exited with code {code}")

    @staticmethod
    def _log_tail(directory: Path) -> str:
        try:
            with open(directory / "ffmpeg.log", "rb") as fh:
                fh.seek(0, os.SEEK_END)
                fh.seek(max(0, fh.tell() - 4096))
                return procs.last_line(fh.read())
        except OSError:
            return ""

    # -- throttle / eviction / budget (callers hold the session lock) --

    def _process_alive(self, session: Session) -> bool:
        return session.run is not None and session.run.proc.returncode is None

    def _ahead_target(self, session: Session) -> float:
        if not self.over_cap:
            return self.ahead
        base = (
            session.anchor()
            if session.last_requested is not None
            else session.run_start_segment - 1
        )
        return sum(s.duration for s in session.plan[base + 1 : base + 3])

    def _throttle(self, session: Session) -> None:
        if not self._process_alive(session):
            return
        assert session.run is not None
        lead = session.lead_seconds()
        if session.state == "running":
            if session.produced_max >= len(session.plan) - 1:
                return
            if lead >= self._ahead_target(session) - 1e-6 and procs.signal_proc(
                session.run.proc, signal.SIGSTOP
            ):
                session.state = "throttled"
        elif session.state == "throttled" and lead < self.ahead / 2 and not self.over_cap:
            self._resume(session)

    def _resume(self, session: Session) -> None:
        if session.state == "throttled" and session.run is not None:
            procs.signal_proc(session.run.proc, signal.SIGCONT)
            session.state = "running"

    def _drop(self, session: Session, k: int) -> Path:
        session.ready.pop(k, None)
        return session.dir / f"seg_{k}.m4s"

    def _evict(self, session: Session) -> list[Path]:
        """Keep ``[start(anchor) - behind, start(anchor) + ahead + one segment]``.

        Segments past that window are kept while they continue the contiguous ready run after
        the player's position: they are what ffmpeg wrote before SIGSTOP landed (normally one
        segment in flight; more when the monitor lags behind a fast remux). Dropping them would
        leave a gap that stops the lead from ever reaching the ahead target (ffmpeg never pauses
        again) and makes the next sequential request restart ffmpeg. Far-ahead segments beyond
        a gap (e.g. left over from before a backward seek) are evicted."""
        plan = session.plan
        anchor = session.anchor()
        start = plan[anchor].start
        limit = start + self.ahead + max(s.duration for s in plan) + 1e-6
        first = anchor + 1 if session.last_requested is not None else anchor
        end = first
        while end in session.ready:
            end += 1
        doomed = [
            j
            for j in session.ready
            if plan[j].start < start - self.behind - 1e-6
            or (plan[j].start > limit and not first <= j < end)
        ]
        return [self._drop(session, j) for j in doomed]

    def _enforce_budget(self) -> list[Path]:
        cap = self.cap_bytes
        total = self.total_bytes()
        doomed: list[Path] = []
        if total > cap:
            self.over_cap = True
            for s in self.sessions.values():
                if s.last_requested is not None:
                    doomed += [self._drop(s, k) for k in list(s.ready) if k < s.last_requested]
            for s in self.sessions.values():
                if s.state == "running":
                    self._throttle(s)
            total = self.total_bytes()
            if total > cap:
                far = sorted(
                    (
                        (k - s.anchor(), s, k)
                        for s in self.sessions.values()
                        for k in s.ready
                        if k > s.anchor() + 2
                    ),
                    key=lambda item: item[0],
                    reverse=True,
                )
                for _dist, s, k in far:
                    if total <= cap:
                        break
                    total -= s.ready.get(k, 0)
                    doomed.append(self._drop(s, k))
        elif self.over_cap and total < 0.9 * cap:
            self.over_cap = False
            for s in self.sessions.values():
                if s.state == "throttled":
                    self._throttle(s)
        return doomed

    # -- requests --

    def playlist(self, session_id: str) -> str:
        session = self._get(session_id)
        self._check_alive(session)
        session.last_access = self.clock()
        return render_playlist(session.plan)

    def keepalive(self, session_id: str) -> None:
        session = self._get(session_id)
        self._check_alive(session)
        session.last_access = self.clock()

    async def init_segment(self, session_id: str) -> bytes:
        session = self._get(session_id)
        session.last_access = self.clock()
        deadline = time.monotonic() + self.wait_seconds
        while True:
            self._check_alive(session)
            if session.init_servable():
                with contextlib.suppress(FileNotFoundError):
                    return await asyncio.to_thread((session.dir / "init.mp4").read_bytes)
            if time.monotonic() >= deadline:
                raise ApiError(504, "segment_timeout", "Segment not ready in time")
            await asyncio.sleep(self.poll_seconds)

    async def open_segment(self, session_id: str, k: int) -> SegmentFile:
        session = self._get(session_id)
        if k < 0 or k >= len(session.plan):
            raise ApiError(404, "segment_not_found", "Segment not found")
        session.last_requested = k
        session.last_access = self.clock()
        deadline = time.monotonic() + self.wait_seconds
        while True:
            run_id = await self._decide(session, k)
            outcome = await self._wait_for(session, k, run_id, deadline)
            if isinstance(outcome, SegmentFile):
                return outcome
            if outcome == "timeout":
                async with session.lock:
                    session.consecutive_timeouts += 1
                    if session.consecutive_timeouts >= MAX_CONSECUTIVE_TIMEOUTS:
                        await self._fail(session, "segment_timeout_repeated")
                raise ApiError(504, "segment_timeout", "Segment not ready in time")
            # "reenter": the run changed under us; decide again.

    async def _decide(self, session: Session, k: int) -> int:
        to_delete: list[Path] = []
        async with session.lock:
            self._check_alive(session)
            if k in session.ready:
                session.consecutive_timeouts = 0
            elif (
                session.state in ("running", "throttled")
                and session.run is not None
                and session.produced_max < k <= session.produced_max + RESUME_WINDOW
            ):
                self._resume(session)
            else:
                logger.info(
                    "hls_restart: %s segment %s (state %s, produced_max %s)",
                    session.session_id,
                    k,
                    session.state,
                    session.produced_max,
                )
                await self._start_run(session, k)
            to_delete += self._evict(session)
            to_delete += self._enforce_budget()
            self._throttle(session)
            run_id = session.run_id
        if to_delete:
            await asyncio.to_thread(_unlink_all, to_delete)
        return run_id

    async def _wait_for(self, session: Session, k: int, run_id: int, deadline: float) -> Any:
        while True:
            self._check_alive(session)
            if k in session.ready:
                try:
                    opened = await asyncio.to_thread(_open_segment, session.dir / f"seg_{k}.m4s")
                except FileNotFoundError:
                    return "reenter"  # evicted between the check and the open
                session.consecutive_timeouts = 0
                return opened
            if session.run_id != run_id and not (
                session.produced_max < k <= session.produced_max + RESUME_WINDOW
            ):
                return "reenter"
            if session.state == "done":
                return "reenter"
            if session.state == "throttled":
                async with session.lock:
                    self._resume(session)  # a waited-for segment always resumes its session
            if time.monotonic() >= deadline:
                return "timeout"
            await asyncio.sleep(self.poll_seconds)
