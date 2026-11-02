"""Scan scheduler: startup + periodic + manual scans, single-flight, scan status.

One ``scan_run`` row per run; each run = ``Scanner.run`` then ``Enricher.run`` (phases
walking -> probing -> enriching -> done). Single-flight via an ``asyncio.Lock``: a manual
trigger while running raises 409 ``scan_in_progress`` (``details.scan`` = ScanStatus); a
scheduled tick while running is skipped.

Wiring: ``app.state.scheduler.trigger("manual")`` -> 202 ScanStatus and
``app.state.scheduler.status()`` (or ``scan_status(session)``) -> 200 ScanStatus.
Keyframe hook: pass ``keyframe_hook`` (forwarded to the scanner).

Library folders: production passes ``folders=LibraryFolders.current`` (forwarded to
every ``Scanner``); ``trigger("libraries", before=save)`` runs ``save`` under the lock before the
run row exists, and only a ``libraries`` run relinks (``Scanner(relinks=True)``).

Change-triggered scans: ``request_scoped(scopes)`` merges settled scopes
into a pending queue and kicks one ``watch`` run (``Scanner(scope=...)``) when the lock is free;
scopes requested while any run holds the lock run right after it. A full run clears the queue
once its row exists (it covers those scopes) and calls ``on_full_run`` (the watcher re-arms).
``run_scheduled`` waits for a ``watch`` run instead of skipping (``_full_waiting`` blocks new
``watch`` runs meanwhile); ``trigger`` never waits (409 as before). ``on_run_finished`` receives
the run's unreachable roots; ``watcher_status`` feeds ``ScanStatus.watcher``.
"""

import asyncio
import contextlib
import logging
from collections.abc import Awaitable, Callable, Sequence
from typing import Any

from sqlalchemy import select, update
from sqlalchemy.orm import Session

from hometv.admin import unmatched_count
from hometv.api.errors import ApiError
from hometv.api.schemas import (
    DISABLED_WATCHER,
    ScanProgress,
    ScanScopeOut,
    ScanStatus,
    WatcherStatus,
)
from hometv.changes.scope import ScanScope, merge_scopes
from hometv.config import Settings
from hometv.db import models as m
from hometv.db.engine import Database
from hometv.metadata.artwork import ArtworkCache
from hometv.metadata.enrich import Enricher
from hometv.metadata.tmdb import TmdbClient
from hometv.scan_report import ScanReport
from hometv.scanner import FolderProvider, KeyframeHook, ProbeFn, Scanner
from hometv.text import undecodable

logger = logging.getLogger("hometv.scheduler")

STARTUP_DELAY_SECONDS = 10.0
Sleep = Callable[[float], Awaitable[None]]


def scan_status(session: Session, watcher: WatcherStatus = DISABLED_WATCHER) -> ScanStatus:
    """The running run, else the last finished one, else ``state="idle"`` with nulls;
    ``watcher`` is the change-detection snapshot (``Scheduler.watcher_status()``)."""
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
            scope=None,
            watcher=watcher,
        )
    return ScanStatus(
        id=run.id,
        state=run.state,
        trigger=run.trigger,
        phase=run.phase,
        started_at=run.started_at,
        finished_at=run.finished_at,
        progress=ScanProgress(processed=run.processed, total=run.total),
        counts=dict(run.counts or {}),
        errors=list(run.errors or []),
        error=run.error,
        unmatched_count=count,
        scope=None if run.scope is None else [ScanScopeOut(**item) for item in run.scope],
        watcher=watcher,
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
        folders: FolderProvider | None = None,
    ) -> None:
        self.db = db
        self.settings = settings
        self.tmdb = tmdb
        self.artwork = artwork
        self.probe_fn = probe_fn
        self.keyframe_hook = keyframe_hook
        self.folders = folders
        self._sleep = sleep
        self.startup_delay = startup_delay
        self.interval_seconds = settings.scan.interval_hours * 3600.0
        self._lock = asyncio.Lock()
        self.background_task: asyncio.Task[None] | None = None
        self._scan_task: asyncio.Task[None] | None = None
        # Change-triggered runs.
        self._pending: list[ScanScope] = []
        self._kick_task: asyncio.Task[None] | None = None
        self._stopping = False
        self._running_trigger: str | None = None  # trigger of the run holding the lock
        self._full_waiting = False  # run_scheduled waits for a watch run: no new watch run
        self.on_full_run: Callable[[], None] | None = None
        self.on_run_finished: Callable[[set[str]], None] | None = None
        self.watcher_status: Callable[[], WatcherStatus] = lambda: DISABLED_WATCHER

    @property
    def running(self) -> bool:
        return self._lock.locked()

    # -- lifecycle --

    async def start(self) -> None:
        self._stopping = False
        await asyncio.to_thread(self._fail_stale_runs)
        if self.settings.scan.on_startup or self.interval_seconds > 0:
            self.background_task = asyncio.create_task(self._loop(), name="hometv-scheduler")

    async def stop(self) -> None:
        self._stopping = True
        self._pending = []
        tasks = (self.background_task, self._scan_task, self._kick_task)
        for task in tasks:
            if task is not None and not task.done():
                task.cancel()
        for task in tasks:
            if task is not None:
                with contextlib.suppress(asyncio.CancelledError):
                    await task
        self.background_task = None

    async def wait_idle(self) -> None:
        """Until neither a kick nor a run is alive (a finished run may kick a ``watch`` run)."""
        while True:
            task = next(
                (t for t in (self._kick_task, self._scan_task) if t is not None and not t.done()),
                None,
            )
            if task is None:
                return
            with contextlib.suppress(asyncio.CancelledError):
                await asyncio.shield(task)

    def _fail_stale_runs(self) -> None:
        with self.db.write_session() as session:
            session.execute(
                update(m.ScanRun)
                .where(m.ScanRun.state == "running")
                .values(state="failed", error="interrupted", finished_at=m.utcnow())
            )

    # -- status / triggers --

    def status(self) -> ScanStatus:
        watcher = self.watcher_status()
        with self.db.read_session() as session:
            return scan_status(session, watcher)

    @property
    def pending_scopes(self) -> list[ScanScope]:
        return list(self._pending)

    async def ensure_idle(self) -> None:
        """Raise 409 ``scan_in_progress`` (``details.scan``) while a scan holds the lock."""
        if self._lock.locked():
            current = await asyncio.to_thread(self.status)
            raise ApiError(
                409,
                "scan_in_progress",
                "A scan is already running",
                {"scan": current.model_dump(mode="json")},
            )

    async def trigger(
        self, trigger: str = "manual", *, before: Callable[[], Any] | None = None
    ) -> ScanStatus:
        """Start a scan in the background (202). Raises 409 ``scan_in_progress`` if running.

        ``before`` runs in a worker thread under the lock, before the run row is created; if
        it raises, the lock is released and the error propagates (no run, no scan)."""
        await self.ensure_idle()  # returns without yielding when the lock is free
        await self._lock.acquire()  # never yields: the lock is free
        self._running_trigger = trigger
        try:
            if before is not None:
                await asyncio.to_thread(before)
            run_id = await asyncio.to_thread(self._create_run, trigger)
        except BaseException:
            self._release()
            raise
        self._full_run_started()
        self._scan_task = asyncio.create_task(
            self._execute(run_id, trigger), name=f"hometv-scan-{run_id}"
        )
        return await asyncio.to_thread(self.status)

    async def run_scheduled(self, trigger: str = "scheduled") -> bool:
        """Run a scan to completion. Waits for a ``watch`` run holding the lock; skipped
        (returns False) when any other run holds it."""
        self._full_waiting = True  # the kick starts no new watch run meanwhile
        try:
            while self._lock.locked() and self._running_trigger == "watch":
                await self._wait_watch_holder()
            if self._lock.locked():  # a full run holds it (or took it while we waited)
                logger.info("scan_skipped_running: %s", trigger)
                return False
            await self._lock.acquire()  # never yields: checked free in the same step
        finally:
            self._full_waiting = False
        self._running_trigger = trigger
        try:
            run_id = await asyncio.to_thread(self._create_run, trigger)
        except BaseException:
            self._release()
            raise
        self._full_run_started()
        self._scan_task = asyncio.create_task(
            self._execute(run_id, trigger), name=f"hometv-scan-{run_id}"
        )
        await self._scan_task
        return True

    async def _wait_watch_holder(self) -> None:
        """Await once the task holding the lock for a ``watch`` run (the kick inside
        ``_create_run``, else the run itself); always yields afterwards so a stale handle
        cannot spin the loop. Never suppresses a cancellation (``stop()`` must get through)."""
        holder = next(
            (t for t in (self._kick_task, self._scan_task) if t is not None and not t.done()),
            None,
        )
        if holder is not None:  # none alive: only the yield below (asyncio.wait rejects {})
            await asyncio.wait({holder})
        await asyncio.sleep(0)

    # -- change-triggered (watch) runs --

    def request_scoped(self, scopes: Sequence[ScanScope]) -> None:
        """Queue settled scopes (event loop only) and kick a ``watch`` run."""
        if self._stopping:
            return
        valid: list[ScanScope] = []
        for scope in scopes:
            if undecodable(scope.path):
                logger.warning("scope_dropped_undecodable: %r", scope.path)
                continue
            valid.append(scope)
        if not valid:
            return
        self._pending = merge_scopes([*self._pending, *valid])
        self._kick()

    def _kick(self) -> None:
        """The only place that creates the (single) kick task."""
        if self._stopping:
            return
        if self._kick_task is not None and not self._kick_task.done():
            return
        self._kick_task = asyncio.create_task(self._run_pending(), name="hometv-scan-kick")

    async def _run_pending(self) -> None:
        """Start one ``watch`` run with every pending scope when the lock is free. Never
        raises anything but a cancellation (from ``stop()``)."""
        if self._lock.locked() or self._full_waiting or not self._pending or self._stopping:
            return
        await self._lock.acquire()  # never yields: the lock is free
        self._running_trigger = "watch"
        scopes, self._pending = self._pending, []
        try:
            run_id = await asyncio.to_thread(self._create_run, "watch", scopes)
        except asyncio.CancelledError:
            self._running_trigger = None
            self._lock.release()
            raise
        except Exception as exc:
            self._running_trigger = None
            self._lock.release()
            self._pending = merge_scopes([*scopes, *self._pending])
            logger.warning("watch_run_create_failed: %s", exc)
            return
        self._scan_task = asyncio.create_task(
            self._execute(run_id, "watch", scopes), name=f"hometv-scan-{run_id}"
        )

    def _full_run_started(self) -> None:
        """A full run's row exists: it covers every pending scope; the watcher re-arms."""
        self._pending = []
        if self.on_full_run is not None:
            try:
                self.on_full_run()
            except Exception:
                logger.exception("on_full_run_failed")

    def _release(self) -> None:
        self._running_trigger = None
        self._lock.release()
        self._kick()

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

    def _create_run(self, trigger: str, scope: Sequence[ScanScope] | None = None) -> int:
        with self.db.write_session() as session:
            run = m.ScanRun(trigger=trigger, state="running", phase="walking")
            if scope:
                run.scope = [s.to_json() for s in scope]
            session.add(run)
            session.flush()
            return run.id

    def _finish(self, run_id: int, report: ScanReport, state: str, error: str | None) -> None:
        with self.db.write_session() as session:
            report.save(session, run_id, state=state, error=error, finished_at=m.utcnow())

    async def _execute(
        self, run_id: int, trigger: str, scopes: Sequence[ScanScope] | None = None
    ) -> None:
        report = ScanReport()
        try:
            scanner = Scanner(
                self.db,
                self.settings,
                probe_fn=self.probe_fn,
                keyframe_hook=self.keyframe_hook,
                folders=self.folders,
                relinks=trigger == "libraries",
                scope=scopes,
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
            if self.on_run_finished is not None:
                try:
                    self.on_run_finished(set(report.unreachable_roots))
                except Exception:
                    logger.exception("on_run_finished_failed")
            self._release()  # then kick: pending scopes run right after this run
