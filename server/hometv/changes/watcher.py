"""Change watcher service: watches the library roots, settles the changes and asks the
scheduler for scoped ``watch`` runs. Instance on ``app.state.watcher``.

States (``status()``, an immutable ``WatcherStatus`` snapshot, safe from any thread):
``disabled`` (setting off) and ``unavailable`` on a non-Linux platform are final (inert: every
method returns at once); ``error`` (backend init failed, watch limit hit, reader stopped);
``starting`` until the first ``sync()`` ends; then ``unavailable`` (roots configured, none
armed) or ``active`` (``unavailable_roots`` = unwatchable + degraded + missing roots).

``sync()`` (single-flight; extra requests coalesce into one more pass) re-arms the watches to
match ``folders()``. It runs at start (as a task: the arming walk is never awaited by the
lifespan), at every full scan (``Scheduler.on_full_run``) and after an overflow (``rewalk``).
No timer touches the disk.

**Health facts.** ``root_gone``, ``watch_limit``, ``backend_failed`` and ``note_unreachable`` can
fire while a sync is between its steps. Each records a fact numbered by ``_fact_seq``, applies
its settler effect, republishes the snapshot and queues its backend I/O as a ``HealthAction``
bound to the backend instance current at that moment. Actions are drained only under the sync
lock (by ``_health_task`` and at the start of every sync pass), so they never interleave with
arming. A sync pass clears only facts numbered ``<= seq0`` (read when it took the lock), except
``_backend_dead``, which step 0 clears once it has built a new backend. ``_publish()`` derives
the snapshot from the facts only, so a sync can never publish ``active`` over a fact raised
during it.
"""

import asyncio
import contextlib
import logging
import os
import sys
import time
from collections.abc import Callable, Iterable
from dataclasses import dataclass
from typing import Literal

from hometv.api.schemas import DISABLED_WATCHER, WatcherStatus
from hometv.changes.backend import HEALTH_KINDS, Deliver, WatchBackend, WatchEvent, WatchLimitError
from hometv.changes.mounts import is_watchable, mount_for, parse_mountinfo, read_mountinfo
from hometv.changes.scope import ScanScope
from hometv.changes.settle import ChangeSettler
from hometv.config import Settings
from hometv.scanner import FolderProvider

logger = logging.getLogger("hometv.changes.watcher")

LIMIT_DETAIL = "inotify watch limit reached (fs.inotify.max_user_watches)"
NOT_LINUX_DETAIL = "inotify requires Linux"
READER_STOPPED = "inotify reader stopped: "


def _stat_size(path: str) -> int | None:
    try:
        return os.stat(path).st_size
    except OSError:
        return None


def _stat_identity(root: str) -> tuple[int, int]:
    st = os.stat(root)  # follows a symlinked root
    return st.st_dev, st.st_ino


def _default_backend_factory() -> WatchBackend:
    from hometv.changes.inotify import InotifyBackend

    return InotifyBackend()


@dataclass(frozen=True)
class HealthAction:
    backend: WatchBackend
    op: Literal["remove", "close"]
    roots: tuple[str, ...] = ()


@dataclass(frozen=True)
class _BackendDead:
    seq: int
    backend: WatchBackend
    detail: str


class ChangeWatcher:
    def __init__(
        self,
        settings: Settings,
        folders: FolderProvider,
        request_scan: Callable[[list[ScanScope]], None],
        *,
        backend_factory: Callable[[], WatchBackend] | None = None,
        mountinfo: Callable[[], str | None] = read_mountinfo,
        platform: str = sys.platform,
        clock: Callable[[], float] = time.monotonic,
        stat_size: Callable[[str], int | None] = _stat_size,
        stat_identity: Callable[[str], tuple[int, int]] = _stat_identity,
        auto_poll: bool = True,
    ) -> None:
        self.settings = settings
        self.folders = folders
        self.request_scan = request_scan
        self.backend_factory = backend_factory or _default_backend_factory
        self.mountinfo = mountinfo
        self.platform = platform
        self.clock = clock
        self.stat_size = stat_size
        self.stat_identity = stat_identity
        self.auto_poll = auto_poll
        self.poll_retry_seconds = 1.0  # pause after a failed poll_once (tests: 0)
        self._settler = ChangeSettler([], settings.scan.watch_quiet_seconds)
        self._status: WatcherStatus = DISABLED_WATCHER
        self._inert = True  # until start(): disabled / not Linux are final
        self._stopped = False
        self._loop: asyncio.AbstractEventLoop | None = None
        self._backend: WatchBackend | None = None
        # Arming state (sync only).
        self._roots: list[str] = []  # configured roots at the last sync
        self._armed: dict[str, tuple[int, int]] = {}  # root -> (st_dev, st_ino) at arming
        self._unwatchable: set[str] = set()
        self._missing: set[str] = set()  # failed stat/add_tree in the last sync
        self._first_sync_done = False
        # Health facts.
        self._fact_seq = 0
        self._degraded: dict[str, int] = {}
        self._limit_hit: int | None = None
        self._backend_dead: _BackendDead | None = None
        self._init_error: tuple[int, str] | None = None
        self._health_actions: list[HealthAction] = []
        # Tasks.
        self._sync_lock = asyncio.Lock()
        self._sync_task: asyncio.Task[None] | None = None
        self._resync = False
        self._resync_rewalk = False
        self._health_task: asyncio.Task[None] | None = None
        self._poll_task: asyncio.Task[None] | None = None
        self._wakeup = asyncio.Event()

    # -- lifecycle --

    async def start(self) -> None:
        """Never awaits the arming walk: the first ``sync()`` runs as a task."""
        self._loop = asyncio.get_running_loop()
        if not self.settings.scan.watch_enabled:
            self._status = DISABLED_WATCHER
            return
        if not self.platform.startswith("linux"):
            self._status = WatcherStatus(
                state="unavailable", unavailable_roots=(), detail=NOT_LINUX_DETAIL
            )
            return
        self._inert = False
        self._settler.set_roots(self._configured_roots())
        try:
            backend = self._build_backend()
        except Exception as exc:
            self._backend_init_failed(exc)
            return
        self._backend = backend
        self._publish()
        self.request_sync()
        if self.auto_poll:
            self._poll_task = asyncio.create_task(self._poll_loop(), name="hometv-watch-poll")

    async def stop(self) -> None:
        if self._stopped:
            return
        self._stopped = True
        if self._poll_task is not None:
            self._poll_task.cancel()
            await self._join(self._poll_task)
        backend = self._backend
        if backend is not None:
            backend.abort()  # an in-flight add_tree stops at the next directory
        for task in (self._sync_task, self._health_task):
            if task is not None:
                # Awaited, not cancelled: a backend call already in a worker thread cannot be
                # cancelled, and close() must not race it.
                await self._join(task)
        if backend is not None:
            await self._call(backend.close)

    @staticmethod
    async def _join(task: asyncio.Task[None]) -> None:
        """Await a task at shutdown; its failure is logged, never re-raised (the rest of the
        lifespan shutdown must run)."""
        try:
            await task
        except asyncio.CancelledError:
            pass
        except Exception:
            logger.exception("watch_task_failed: %s", task.get_name())

    async def wait_idle(self) -> None:
        """Until no sync pass and no health action is pending (tests)."""
        await asyncio.sleep(0)  # let call_soon_threadsafe batches run first
        while True:
            task = next(
                (t for t in (self._sync_task, self._health_task) if t is not None and not t.done()),
                None,
            )
            if task is None:
                return
            await asyncio.wait({task})

    def status(self) -> WatcherStatus:
        return self._status

    # -- events (reader thread -> loop) --

    def _deliver_for(self, backend: WatchBackend) -> Deliver:
        loop = self._loop
        assert loop is not None

        def deliver(batch: list[WatchEvent]) -> None:
            with contextlib.suppress(RuntimeError):  # loop closed at shutdown: batch dropped
                loop.call_soon_threadsafe(self._on_events, backend, batch)

        return deliver

    def _on_events(self, source: WatchBackend, batch: list[WatchEvent]) -> None:
        if self._inert or self._stopped:
            return
        now = self.clock()
        health: list[WatchEvent] = []
        for event in batch:
            if event.kind in HEALTH_KINDS:
                if source is not self._backend:
                    logger.info("watch_stale_event: %s", event.kind)
                    continue
                health.append(event)
                if event.kind == "overflow":
                    self._settler.add(event, now)  # whole-root scopes for every root
                continue
            self._settler.add(event, now)  # real changes, whichever backend saw them
        for event in health:
            if event.kind == "root_gone":
                logger.warning("watch_root_gone: %s", event.root)
                if event.root is not None:
                    self._degrade([event.root])
            elif event.kind == "overflow":
                logger.warning("watch_overflow")
                self.request_sync(rewalk=True)
            elif event.kind == "watch_limit":
                logger.error("watch_limit_reached: %s", event.path)
                self._limit_hit = self._next_seq()
                self._settler.suspend()
                self._publish()
                self._queue_remove(tuple(self._armed))
            elif event.kind == "backend_failed":
                logger.error("watch_backend_failed: %s", event.detail)
                self._backend_dead = _BackendDead(
                    self._next_seq(), source, READER_STOPPED + (event.detail or "")
                )
                self._settler.suspend()
                self._publish()
                self._queue(HealthAction(source, "close"))
        self._wakeup.set()

    def note_unreachable(self, roots: set[str]) -> None:
        """A scan reported ``root_unavailable`` (or a root ``walk_error``): degrade those
        armed roots until the next full run's sync re-arms them."""
        if self._inert or self._stopped:
            return
        armed = [root for root in sorted(roots) if root in self._armed]
        if armed:
            self._degrade(armed)

    def _degrade(self, roots: Iterable[str]) -> None:
        roots = [r for r in roots if r in self._roots or r in self._armed]
        if not roots:
            return
        for root in roots:
            self._degraded[root] = self._next_seq()
            self._settler.drop_root(root)
        self._publish()
        self._queue_remove(tuple(roots))

    # -- health actions --

    def _next_seq(self) -> int:
        self._fact_seq += 1
        return self._fact_seq

    def _live_backend(self) -> WatchBackend | None:
        backend = self._backend
        if backend is None:
            return None
        if self._backend_dead is not None and self._backend_dead.backend is backend:
            return None
        return backend

    def _queue_remove(self, roots: tuple[str, ...]) -> None:
        backend = self._live_backend()
        if backend is not None and roots:
            self._queue(HealthAction(backend, "remove", roots))

    def _queue(self, action: HealthAction) -> None:
        self._health_actions.append(action)
        if self._health_task is None or self._health_task.done():
            self._health_task = asyncio.create_task(self._health_loop(), name="hometv-watch-health")

    async def _health_loop(self) -> None:
        async with self._sync_lock:
            await self._drain_health_actions()

    async def _drain_health_actions(self) -> None:
        """Lock held. Each action touches only its bound backend instance."""
        while self._health_actions:
            action = self._health_actions.pop(0)
            if action.op == "close":
                await self._call(action.backend.close)
                continue
            if action.backend is not self._backend:
                logger.info("watch_stale_action: remove %s", ", ".join(action.roots))
                continue
            for root in action.roots:
                await self._call(action.backend.remove, root)
                self._armed.pop(root, None)

    @staticmethod
    async def _call(fn: Callable[..., object], *args: object) -> None:
        try:
            await asyncio.to_thread(fn, *args)
        except Exception:
            logger.exception("watch_backend_call_failed: %s", getattr(fn, "__name__", fn))

    # -- sync --

    def request_sync(self, *, rewalk: bool = False) -> None:
        """Schedule a ``sync()`` (event loop only); coalesces while one runs."""
        if self._inert or self._stopped:
            return
        if self._sync_task is not None and not self._sync_task.done():
            self._resync = True
            self._resync_rewalk = self._resync_rewalk or rewalk
            return
        self._sync_task = asyncio.create_task(self.sync(rewalk=rewalk), name="hometv-watch-sync")

    async def sync(self, *, rewalk: bool = False) -> None:
        """(Re)arm the watches to match ``folders()`` (single-flight)."""
        if self._inert or self._stopped:
            return
        async with self._sync_lock:
            await self._sync_pass(rewalk)
            while self._resync and not self._stopped:
                rewalk, self._resync, self._resync_rewalk = self._resync_rewalk, False, False
                await self._sync_pass(rewalk)

    async def _sync_pass(self, rewalk: bool) -> None:
        seq0 = self._fact_seq
        await self._drain_health_actions()
        if self._stopped:
            return
        # Step 0: backend liveness.
        backend = self._backend
        replaced = False
        if backend is None or self._backend_dead is not None or not backend.is_alive():
            backend = await self._replace_backend(backend)
            if backend is None:
                return
            replaced = True
        previous_error = replaced or self._limit_hit is not None
        # Step 1: roots and mounts.
        roots = self._configured_roots()
        text = await asyncio.to_thread(self.mountinfo)
        # Step 2: unwatchable file systems.
        unwatchable = await asyncio.to_thread(self._unwatchable_roots, roots, text)
        watchable = [r for r in roots if r not in unwatchable]
        # Step 3: removed (or now unwatchable) roots.
        for root in [r for r in self._armed if r not in watchable]:
            await self._call(backend.remove, root)
            self._armed.pop(root, None)
        if self._stopped:
            return
        # Step 4: dead-watch check (one stat per armed root, full runs only).
        failed: set[str] = set()
        changed: set[str] = set()
        for root in list(self._armed):
            try:
                identity = await asyncio.to_thread(self.stat_identity, root)
            except OSError as exc:
                logger.warning("watch_root_unreachable: %s: %s", root, exc)
                await self._call(backend.remove, root)
                self._armed.pop(root, None)
                failed.add(root)
                continue
            if identity != self._armed[root]:
                logger.info("watch_root_remounted: %s", root)
                await self._call(backend.remove, root)
                self._armed.pop(root, None)
                changed.add(root)
        if self._stopped:
            return
        # Step 5: arm new / degraded / missing / changed roots (every root after an error or
        # on rewalk); healthy armed roots are not re-walked otherwise.
        everything = previous_error or rewalk
        to_arm = [
            r
            for r in watchable
            if r not in failed
            and (everything or r not in self._armed or r in self._degraded or r in changed)
        ]
        missing = set(failed)
        for root in to_arm:
            try:
                await asyncio.to_thread(backend.add_tree, root)
                if self._stopped:
                    return
                identity = await asyncio.to_thread(self.stat_identity, root)
            except WatchLimitError:
                await self._limit_reached(backend)
                self._finish_roots(roots, unwatchable, missing)
                self._publish()
                return
            except OSError as exc:
                logger.warning("watch_arm_failed: %s: %s", root, exc)
                self._armed.pop(root, None)
                missing.add(root)
                continue
            self._armed[root] = identity
            if self._degraded.get(root, seq0 + 1) <= seq0:
                del self._degraded[root]
        if self._stopped:
            return
        if self._limit_hit is not None and self._limit_hit <= seq0:
            self._limit_hit = None
        # Step 7: settle on the facts, not on this pass's local results.
        self._finish_roots(roots, unwatchable, missing)
        for root in [r for r in self._degraded if r not in roots]:
            del self._degraded[root]
        if not self._error_fact():
            self._settler.set_roots(r for r in self._armed if r not in self._degraded)
        self._first_sync_done = True
        self._publish()

    async def _replace_backend(self, old: WatchBackend | None) -> WatchBackend | None:
        """Step 0: close the old backend, build and start a new one. None on failure."""
        if old is not None:
            await self._call(old.close)
        self._armed.clear()
        if self._stopped:
            return None
        try:
            new = self._build_backend()
        except Exception as exc:
            self._backend_init_failed(exc)
            return None
        self._backend = new
        self._init_error = None
        # Any dead-backend fact names a backend that is gone now (``old``, or one closed by an
        # earlier step 0 whose rebuild failed): no fact outlives its backend.
        if self._backend_dead is not None and self._backend_dead.backend is not new:
            self._backend_dead = None
        return new

    def _build_backend(self) -> WatchBackend:
        """Build and start a backend; one whose ``start()`` raises is closed (guarded)."""
        backend = self.backend_factory()
        try:
            backend.start(self._deliver_for(backend))
        except BaseException:
            try:
                backend.close()
            except Exception:
                logger.exception("watch_backend_call_failed: close")
            raise
        return backend

    def _backend_init_failed(self, exc: BaseException) -> None:
        logger.error("watch_backend_start_failed: %s", exc)
        self._backend = None
        self._init_error = (self._next_seq(), str(exc) or type(exc).__name__)
        self._settler.suspend()
        self._publish()

    async def _limit_reached(self, backend: WatchBackend) -> None:
        """Step 6: all or nothing. Remove every watch, ``error``, intake suspended."""
        logger.error("watch_limit_reached")
        for root in list(self._armed):
            await self._call(backend.remove, root)
        self._armed.clear()
        self._limit_hit = self._next_seq()
        self._settler.suspend()

    def _finish_roots(self, roots: list[str], unwatchable: set[str], missing: set[str]) -> None:
        self._roots = roots
        self._unwatchable = unwatchable
        self._missing = missing

    def _configured_roots(self) -> list[str]:
        return list(dict.fromkeys(path for path, _ in self.folders().roots()))

    @staticmethod
    def _unwatchable_roots(roots: list[str], mountinfo: str | None) -> set[str]:
        if not mountinfo:
            return set()
        mounts = parse_mountinfo(mountinfo)
        unwatchable: set[str] = set()
        for root in roots:
            mount = mount_for(os.path.realpath(root), mounts)
            if mount is not None and not is_watchable(mount.fs_type):
                unwatchable.add(root)
        return unwatchable

    # -- snapshot --

    def _error_fact(self) -> tuple[int, str] | None:
        """The latest error fact (seq, detail), if any."""
        candidates: list[tuple[int, str]] = []
        if self._backend is None and self._init_error is not None:
            candidates.append(self._init_error)
        if self._backend_dead is not None:
            candidates.append((self._backend_dead.seq, self._backend_dead.detail))
        if self._limit_hit is not None:
            candidates.append((self._limit_hit, LIMIT_DETAIL))
        return max(candidates) if candidates else None

    def _publish(self) -> None:
        """The only place that builds the snapshot; derived from the facts only."""
        error = self._error_fact()
        if error is not None:
            self._status = WatcherStatus(state="error", unavailable_roots=(), detail=error[1])
            return
        if not self._first_sync_done:
            self._status = WatcherStatus(state="starting", unavailable_roots=(), detail=None)
            return
        effective = {r for r in self._armed if r not in self._degraded}
        unavailable = tuple(sorted(r for r in self._roots if r not in effective))
        if self._roots and not any(r in effective for r in self._roots):
            self._status = WatcherStatus(
                state="unavailable", unavailable_roots=unavailable, detail=None
            )
            return
        self._status = WatcherStatus(state="active", unavailable_roots=unavailable, detail=None)

    # -- settling --

    async def poll_once(self) -> None:
        """Stat the due paths (worker thread), settle, request the quiet scopes."""
        if self._inert or self._stopped:
            return
        now = self.clock()  # one reading: ``settle`` judges exactly the scopes ``due`` listed
        paths = self._settler.due(now)
        sizes: dict[str, int | None] = {}
        if paths:
            sizes = await asyncio.to_thread(lambda: {p: self.stat_size(p) for p in paths})
        scopes = self._settler.settle(now, sizes)
        if scopes:
            logger.info("watch_scan_requested scopes=%s", [s.path for s in scopes])
            try:
                self.request_scan(scopes)
            except Exception:
                logger.exception("watch_scan_request_failed")

    async def _poll_loop(self) -> None:
        while True:
            deadline = self._settler.next_deadline()
            if deadline is not None and self.clock() >= deadline:
                try:
                    await self.poll_once()
                except Exception:
                    logger.exception("watch_poll_failed")
                    await asyncio.sleep(self.poll_retry_seconds)  # no hot loop on a lasting fault
                continue
            self._wakeup.clear()
            timeout = None if deadline is None else deadline - self.clock()
            with contextlib.suppress(TimeoutError):
                await asyncio.wait_for(self._wakeup.wait(), timeout)
