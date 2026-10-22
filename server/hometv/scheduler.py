"""Scan scheduler (PRD §11): startup + periodic + manual scans, single-flight, scan status.

One ``scan_run`` row per run; each run = ``Scanner.run`` then ``Enricher.run`` (phases
walking -> probing -> enriching -> done). Single-flight via an ``asyncio.Lock``: a manual
trigger while running raises 409 ``scan_in_progress`` (``details.scan`` = ScanStatus); a
scheduled tick while running is skipped.

Phase 3 wiring: ``app.state.scheduler.trigger("manual")`` -> 202 ScanStatus and
``app.state.scheduler.status()`` (or ``scan_status(session)``) -> 200 ScanStatus.
Phase 4 hook: pass ``keyframe_hook`` (forwarded to the scanner, §4 step 5a).
"""

import asyncio
import contextlib
import logging
from collections.abc import Awaitable, Callable

from sqlalchemy import select, update
from sqlalchemy.orm import Session

from hometv.admin import unmatched_count
from hometv.api.errors import ApiError
from hometv.api.schemas import ScanProgress, ScanStatus
from hometv.config import Settings
from hometv.db import models as m
from hometv.db.engine import Database
from hometv.metadata.artwork import ArtworkCache
from hometv.metadata.enrich import Enricher
from hometv.metadata.tmdb import TmdbClient
from hometv.scan_report import ScanReport
from hometv.scanner import KeyframeHook, ProbeFn, Scanner

logger = logging.getLogger("hometv.scheduler")

STARTUP_DELAY_SECONDS = 10.0
Sleep = Callable[[float], Awaitable[None]]


def scan_status(session: Session) -> ScanStatus:
    """The running run, else the last finished one, else ``state="idle"`` with nulls."""
    run = session.scalar(
        select(m.ScanRun).where(m.ScanRun.state == "running").order_by(m.ScanRun.id.desc())
    ) or session.scalar(select(m.ScanRun).order_by(m.ScanRun.id.desc()))
    count = unmatched_count(session)
    if run is None:
        return ScanStatus(
            id=None,
            state="idle",
            trigger=None,
            phase=None,
            started_at=None,
            finished_at=None,
            progress=ScanProgress(processed=None, total=None),
            counts=None,
            errors=[],
            error=None,
            unmatched_count=count,
        )
    return ScanStatus(
        id=run.id,
        state=run.state,  # type: ignore[arg-type]
        trigger=run.trigger,  # type: ignore[arg-type]
        phase=run.phase,  # type: ignore[arg-type]
        started_at=run.started_at,
        finished_at=run.finished_at,
        progress=ScanProgress(processed=run.processed, total=run.total),
        counts=dict(run.counts or {}),
        errors=list(run.errors or []),
        error=run.error,
        unmatched_count=count,
    )


class Scheduler:
    def __init__(
        self,
        db: Database,
        settings: Settings,
        *,
        tmdb: TmdbClient | None,
        artwork: ArtworkCache | None,
        probe_fn: ProbeFn | None = None,
        keyframe_hook: KeyframeHook | None = None,
        sleep: Sleep = asyncio.sleep,
        startup_delay: float = STARTUP_DELAY_SECONDS,
    ) -> None:
        self.db = db
        self.settings = settings
        self.tmdb = tmdb
        self.artwork = artwork
        self.probe_fn = probe_fn
        self.keyframe_hook = keyframe_hook
        self._sleep = sleep
        self.startup_delay = startup_delay
        self.interval_seconds = settings.scan.interval_hours * 3600.0
        self._lock = asyncio.Lock()
        self.background_task: asyncio.Task[None] | None = None
        self._scan_task: asyncio.Task[None] | None = None

    @property
    def running(self) -> bool:
        return self._lock.locked()

    # -- lifecycle --

    async def start(self) -> None:
        await asyncio.to_thread(self._fail_stale_runs)
        if self.settings.scan.on_startup or self.interval_seconds > 0:
            self.background_task = asyncio.create_task(self._loop(), name="hometv-scheduler")

    async def stop(self) -> None:
        for task in (self.background_task, self._scan_task):
            if task is not None and not task.done():
                task.cancel()
        for task in (self.background_task, self._scan_task):
            if task is not None:
                with contextlib.suppress(asyncio.CancelledError):
                    await task
        self.background_task = None

    async def wait_idle(self) -> None:
        if self._scan_task is not None:
            with contextlib.suppress(asyncio.CancelledError):
                await asyncio.shield(self._scan_task)

    def _fail_stale_runs(self) -> None:
        with self.db.write_session() as session:
            session.execute(
                update(m.ScanRun)
                .where(m.ScanRun.state == "running")
                .values(state="failed", error="interrupted", finished_at=m.utcnow())
            )

    # -- status / triggers --

    def status(self) -> ScanStatus:
        with self.db.read_session() as session:
            return scan_status(session)

    async def trigger(self, trigger: str = "manual") -> ScanStatus:
        """Start a scan in the background (202). Raises 409 ``scan_in_progress`` if running."""
        if self._lock.locked():
            current = await asyncio.to_thread(self.status)
            raise ApiError(
                409,
                "scan_in_progress",
                "A scan is already running",
                {"scan": current.model_dump(mode="json")},
            )
        await self._lock.acquire()  # never yields: the lock is free
        try:
            run_id = await asyncio.to_thread(self._create_run, trigger)
        except BaseException:
            self._lock.release()
            raise
        self._scan_task = asyncio.create_task(self._execute(run_id), name=f"hometv-scan-{run_id}")
        return await asyncio.to_thread(self.status)

    async def run_scheduled(self, trigger: str = "scheduled") -> bool:
        """Run a scan to completion unless one is running (then skipped: returns False)."""
        if self._lock.locked():
            logger.info("scan_skipped_running: %s", trigger)
            return False
        await self._lock.acquire()
        try:
            run_id = await asyncio.to_thread(self._create_run, trigger)
        except BaseException:
            self._lock.release()
            raise
        self._scan_task = asyncio.create_task(self._execute(run_id), name=f"hometv-scan-{run_id}")
        await self._scan_task
        return True

    async def _loop(self) -> None:
        if self.settings.scan.on_startup:
            await self._sleep(self.startup_delay)
            await self.run_scheduled("startup")
        if self.interval_seconds <= 0:
            return
        while True:
            await self._sleep(self.interval_seconds)  # measured from the previous finish
            await self.run_scheduled("scheduled")

    # -- one run --

    def _create_run(self, trigger: str) -> int:
        with self.db.write_session() as session:
            run = m.ScanRun(trigger=trigger, state="running", phase="walking")
            session.add(run)
            session.flush()
            return run.id

    def _finish(self, run_id: int, report: ScanReport, state: str, error: str | None) -> None:
        with self.db.write_session() as session:
            report.save(session, run_id, state=state, error=error, finished_at=m.utcnow())

    async def _execute(self, run_id: int) -> None:
        report = ScanReport()
        try:
            scanner = Scanner(
                self.db, self.settings, probe_fn=self.probe_fn, keyframe_hook=self.keyframe_hook
            )
            await scanner.run(report, run_id)
            enricher = Enricher(
                self.db, self.settings, self.tmdb, self.artwork, report=report, run_id=run_id
            )
            await enricher.run()
            report.phase = "done"
            await asyncio.to_thread(self._finish, run_id, report, "completed", None)
            logger.info(
                "scan_completed: %d counts=%s errors=%s", run_id, report.counts, report.errors
            )
        except asyncio.CancelledError:
            # Shutdown: short synchronous write (the loop is stopping anyway).
            self._finish(run_id, report, "failed", "interrupted")
            raise
        except Exception as exc:
            logger.exception("scan_failed: %d", run_id)
            await asyncio.to_thread(
                self._finish, run_id, report, "failed", str(exc) or type(exc).__name__
            )
        finally:
            self._lock.release()
