"""``ChangeWatcher``: availability, arming, settling, health facts, recovery.

Every test uses ``FakeWatchBackend`` (``tests/stubs.py``), a fake clock, injected mountinfo
text and platform, and ``auto_poll=False`` (``poll_once`` is called explicitly).
"""

import asyncio
import errno
import logging
import os
import threading
from collections.abc import Callable
from pathlib import Path
from typing import Any

import pytest

from hometv.changes.backend import WatchEvent, WatchLimitError
from hometv.changes.scope import ScanScope
from hometv.changes.watcher import LIMIT_DETAIL, ChangeWatcher
from hometv.libraries import LibraryFolderSet
from tests.stubs import BackendFactory, FakeWatchBackend

LIMIT = "inotify watch limit reached (fs.inotify.max_user_watches)"


class Clock:
    def __init__(self) -> None:
        self.now = 1000.0

    def __call__(self) -> float:
        return self.now


class Env:
    """Three real roots (MKV, películas, series) + a fake backend factory."""

    def __init__(self, tmp_path: Path, make_settings: Callable[..., Any]) -> None:
        self.base = tmp_path / "media"
        self.mkv = str(self.base / "MKV")
        self.pel = str(self.base / "películas")
        self.series = str(self.base / "series")
        for root in (self.mkv, self.pel, self.series):
            os.makedirs(root)
        self.folders = LibraryFolderSet(films=(self.mkv, self.pel), shows=(self.series,))
        self.make_settings = make_settings
        self.factory = BackendFactory()
        self.clock = Clock()
        self.requests: list[list[ScanScope]] = []
        self.mountinfo = "22 1 8:1 / / rw - ext4 /dev/sda1 rw\n"
        self.identity_errors: dict[str, OSError] = {}
        self.identities: dict[str, tuple[int, int]] = {}
        self.sizes: dict[str, int | None] = {}

    def stat_identity(self, root: str) -> tuple[int, int]:
        if root in self.identity_errors:
            raise self.identity_errors[root]
        return self.identities.get(root, (1, hash(root) & 0xFFFF))

    def watcher(self, *, enabled: bool = True, platform: str = "linux", **scan) -> ChangeWatcher:
        settings = self.make_settings(scan={"watch_enabled": enabled, **scan})
        return ChangeWatcher(
            settings,
            lambda: self.folders,
            self.requests.append,
            backend_factory=self.factory,
            mountinfo=lambda: self.mountinfo,
            platform=platform,
            clock=self.clock,
            stat_size=lambda path: self.sizes.get(path),
            stat_identity=self.stat_identity,
            auto_poll=False,
        )

    @property
    def roots(self) -> list[str]:
        return [self.mkv, self.pel, self.series]


@pytest.fixture
def env(tmp_path: Path, make_settings) -> Env:
    return Env(tmp_path, make_settings)


async def started(env: Env, **kw) -> ChangeWatcher:
    watcher = env.watcher(**kw)
    await watcher.start()
    await watcher.wait_idle()
    return watcher


async def until(predicate: Callable[[], bool], timeout: float = 5.0) -> None:
    loop = asyncio.get_running_loop()
    deadline = loop.time() + timeout
    while not predicate():
        assert loop.time() < deadline, "condition not reached"
        await asyncio.sleep(0.005)


async def emit(watcher: ChangeWatcher, backend: FakeWatchBackend, events: list[WatchEvent]):
    backend.emit(events)
    await asyncio.sleep(0)  # call_soon_threadsafe -> _on_events
    await asyncio.sleep(0)


def ev(kind, path, *, is_dir=False, size=None, root=None, detail=None) -> WatchEvent:
    return WatchEvent(kind, path, is_dir=is_dir, size=size, root=root, detail=detail)


async def advance_and_poll(env: Env, watcher: ChangeWatcher, seconds: float) -> None:
    env.clock.now += seconds
    await watcher.poll_once()


# --- Availability and inert states ---------------------------------------------------------


async def test_disabled_builds_no_backend(env: Env):
    watcher = await started(env, enabled=False)
    assert env.factory.calls == 0
    assert watcher.status().model_dump() == {
        "state": "disabled",
        "unavailable_roots": (),
        "detail": None,
    }
    await watcher.stop()


async def test_not_linux_is_unavailable(env: Env):
    watcher = await started(env, platform="darwin")
    assert env.factory.calls == 0
    status = watcher.status()
    assert (status.state, status.detail) == ("unavailable", "inotify requires Linux")
    await watcher.stop()


@pytest.mark.parametrize("kind", ["disabled", "darwin", "stopped"])
async def test_inert_states_do_nothing(env: Env, kind):
    if kind == "disabled":
        watcher = await started(env, enabled=False)
    elif kind == "darwin":
        watcher = await started(env, platform="darwin")
    else:
        watcher = await started(env)
        await watcher.stop()
    before = watcher.status()
    calls = env.factory.calls
    tasks = len(asyncio.all_tasks())
    watcher.request_sync()
    await watcher.sync()
    watcher.note_unreachable({env.mkv})
    await watcher.poll_once()
    assert len(asyncio.all_tasks()) == tasks
    assert env.factory.calls == calls
    assert watcher.status() == before
    if kind != "stopped":
        await watcher.stop()


async def test_backend_init_failure_is_error_and_a_later_sync_recovers(env: Env):
    env.factory.error = OSError(errno.EMFILE, "Too many open files")
    watcher = await started(env)
    status = watcher.status()
    assert status.state == "error"
    assert status.detail == "[Errno 24] Too many open files"
    env.factory.error = None
    await watcher.sync()
    backend = env.factory.last
    assert sorted(backend.ops("add_tree")) == sorted(env.roots)
    assert watcher.status().state == "active"
    await watcher.stop()


async def test_every_root_on_9p_is_unavailable(env: Env):
    real = os.path.realpath(env.base)
    env.mountinfo = f"22 1 8:1 / / rw - ext4 /dev/sda1 rw\n40 22 0:50 / {real} rw - 9p drvfs rw\n"
    watcher = await started(env)
    assert env.factory.last.ops("add_tree") == []
    status = watcher.status()
    assert (status.state, status.unavailable_roots) == ("unavailable", tuple(sorted(env.roots)))
    await watcher.stop()


async def test_mixed_mounts_list_the_unwatchable_root(env: Env):
    series = os.path.realpath(env.series)
    env.mountinfo = (
        f"22 1 8:1 / / rw - ext4 /dev/sda1 rw\n41 22 0:51 / {series} rw - cifs //nas/series rw\n"
    )
    watcher = await started(env)
    assert sorted(env.factory.last.ops("add_tree")) == sorted([env.mkv, env.pel])
    status = watcher.status()
    assert (status.state, status.unavailable_roots) == ("active", (env.series,))
    await watcher.stop()


# --- Settling ---------------------------------------------------------------------------------


async def test_events_settle_into_one_scan_request(env: Env):
    watcher = await started(env)
    backend = env.factory.last
    path = f"{env.pel}/A/Alien (1979).mkv"
    env.sizes[path] = 10
    await emit(watcher, backend, [ev("closed_write", path, size=10, root=env.pel)])
    await advance_and_poll(env, watcher, 29)
    assert env.requests == []
    await advance_and_poll(env, watcher, 1)
    assert env.requests == [[ScanScope(env.pel, "A", True)]]
    await advance_and_poll(env, watcher, 30)
    assert len(env.requests) == 1
    await watcher.stop()


async def test_folders_moved_in_together_are_one_scan_request(env: Env):
    watcher = await started(env)
    backend = env.factory.last
    # One `mv` of two folders: the reader delivers them in two batches, a moment apart.
    await emit(watcher, backend, [ev("moved_to", f"{env.pel}/E", is_dir=True, root=env.pel)])
    env.clock.now += 0.05
    await emit(watcher, backend, [ev("moved_to", f"{env.pel}/F", is_dir=True, root=env.pel)])
    await advance_and_poll(env, watcher, 29.95)
    assert env.requests == []
    await advance_and_poll(env, watcher, 0.05)
    assert env.requests == [[ScanScope(env.pel, "E", True), ScanScope(env.pel, "F", True)]]
    await watcher.stop()


async def test_quiet_period_comes_from_the_settings(env: Env):
    watcher = await started(env, watch_quiet_seconds=60)
    await emit(watcher, env.factory.last, [ev("created", f"{env.pel}/B", is_dir=True)])
    await advance_and_poll(env, watcher, 30)
    assert env.requests == []
    await advance_and_poll(env, watcher, 30)
    assert env.requests == [[ScanScope(env.pel, "B", True)]]
    await watcher.stop()


# --- Arming and health --------------------------------------------------------------------------


async def test_watch_limit_in_sync_is_error_and_removes_armed_roots(env: Env):
    env.factory.configure = lambda b: b.fail_with.__setitem__(
        env.series, WatchLimitError(errno.ENOSPC, "No space left on device")
    )
    watcher = await started(env)
    backend = env.factory.last
    status = watcher.status()
    assert (status.state, status.detail, status.unavailable_roots) == ("error", LIMIT, ())
    assert LIMIT_DETAIL == LIMIT
    assert sorted(backend.ops("remove")) == sorted([env.mkv, env.pel])
    await emit(watcher, backend, [ev("created", f"{env.pel}/X", is_dir=True)])
    await advance_and_poll(env, watcher, 60)
    assert env.requests == []

    backend.fail_with.clear()  # limit raised: the next full run re-arms every root
    backend.calls.clear()
    await watcher.sync()
    assert sorted(backend.ops("add_tree")) == sorted(env.roots)
    assert watcher.status().state == "active"
    await watcher.stop()


async def test_root_gone_degrades_until_a_sync_rearms(env: Env):
    watcher = await started(env)
    backend = env.factory.last
    await emit(watcher, backend, [ev("closed_write", f"{env.series}/Show/e.mkv", size=1)])
    await emit(watcher, backend, [ev("root_gone", env.series, is_dir=True, root=env.series)])
    await watcher.wait_idle()
    assert backend.ops("remove") == [env.series]
    status = watcher.status()
    assert (status.state, status.unavailable_roots) == ("active", (env.series,))
    await emit(watcher, backend, [ev("closed_write", f"{env.series}/Other/f.mkv", size=1)])
    await advance_and_poll(env, watcher, 60)
    assert env.requests == []

    backend.calls.clear()
    await watcher.sync()
    assert backend.ops("add_tree") == [env.series]
    status = watcher.status()
    assert (status.state, status.unavailable_roots) == ("active", ())
    await watcher.stop()


async def test_sync_after_a_folder_change(env: Env, tmp_path: Path):
    watcher = await started(env)
    backend = env.factory.last
    backend.calls.clear()
    new = str(tmp_path / "media" / "nuevas")
    os.makedirs(new)
    env.folders = LibraryFolderSet(films=(env.mkv, new), shows=(env.series,))
    await watcher.sync()
    assert backend.ops("remove") == [env.pel]
    assert backend.ops("add_tree") == [new]
    assert watcher.status().state == "active"
    await watcher.stop()


async def test_start_returns_while_the_first_arming_walk_blocks(env: Env):
    env.factory.configure = lambda b: b.gate("add_tree")
    watcher = env.watcher()
    await asyncio.wait_for(watcher.start(), 1)
    assert watcher.status().state == "starting"
    backend = env.factory.last
    await until(backend.entered["add_tree"].is_set)
    backend.open_gate("add_tree")
    await watcher.wait_idle()
    assert watcher.status().state == "active"
    await watcher.stop()


async def test_dead_watch_found_by_stat_identity(env: Env):
    watcher = await started(env)
    backend = env.factory.last
    backend.calls.clear()
    env.identity_errors[env.series] = OSError(errno.EIO, "Input/output error")
    await watcher.sync()
    assert backend.ops("remove") == [env.series]
    status = watcher.status()
    assert (status.state, status.unavailable_roots) == ("active", (env.series,))

    del env.identity_errors[env.series]
    env.identities[env.mkv] = (99, 1)  # remounted at the same path
    backend.calls.clear()
    await watcher.sync()
    assert backend.ops("remove") == [env.mkv]
    assert sorted(backend.ops("add_tree")) == sorted([env.mkv, env.series])
    assert backend.calls.index(("remove", env.mkv)) < backend.calls.index(("add_tree", env.mkv))
    status = watcher.status()
    assert (status.state, status.unavailable_roots) == ("active", ())
    await watcher.stop()


async def test_note_unreachable_degrades_and_drops_pending(env: Env):
    watcher = await started(env)
    backend = env.factory.last
    await emit(watcher, backend, [ev("closed_write", f"{env.series}/Show/e.mkv", size=1)])
    watcher.note_unreachable({env.series, "/not/a/root"})
    await watcher.wait_idle()
    assert backend.ops("remove") == [env.series]
    assert watcher.status().unavailable_roots == (env.series,)
    await advance_and_poll(env, watcher, 60)
    assert env.requests == []
    backend.calls.clear()
    await watcher.sync()
    assert backend.ops("add_tree") == [env.series]
    assert watcher.status().unavailable_roots == ()
    await watcher.stop()


async def test_overflow_requests_a_rewalk_of_every_armed_root(env: Env):
    watcher = await started(env)
    backend = env.factory.last
    backend.calls.clear()
    await emit(watcher, backend, [ev("overflow", "")])
    await watcher.wait_idle()
    assert sorted(backend.ops("add_tree")) == sorted(env.roots)
    await advance_and_poll(env, watcher, 30)
    assert env.requests == [[ScanScope(r, "", True) for r in sorted(env.roots)]]
    await watcher.stop()


async def test_sync_requests_coalesce_into_one_more_pass(env: Env):
    watcher = await started(env)
    backend = env.factory.last
    backend.calls.clear()
    entered = backend.gate("add_tree")
    watcher.request_sync(rewalk=True)
    await until(entered.is_set)
    watcher.request_sync()
    watcher.request_sync(rewalk=True)
    backend.open_gate("add_tree")
    await watcher.wait_idle()
    assert backend.max_active == 1
    assert len(backend.ops("add_tree")) == 2 * len(env.roots)  # exactly one more rewalk pass
    await watcher.stop()


async def test_stop_during_a_gated_add_tree(env: Env):
    env.factory.configure = lambda b: b.gate("add_tree")
    watcher = env.watcher()
    await watcher.start()
    backend = env.factory.last
    await until(backend.entered["add_tree"].is_set)
    await asyncio.wait_for(watcher.stop(), 5)
    assert backend._closing
    assert backend.closed
    assert backend.close_thread is not None
    assert backend.close_thread != threading.current_thread().name


async def test_watch_limit_from_the_reader_keeps_the_new_folder(env: Env):
    watcher = await started(env)
    backend = env.factory.last
    new = f"{env.pel}/A/New"
    await emit(
        watcher,
        backend,
        [
            ev("created", new, is_dir=True, root=env.pel),
            ev("watch_limit", new, is_dir=True, root=env.pel),
        ],
    )
    await watcher.wait_idle()
    status = watcher.status()
    assert (status.state, status.detail) == ("error", LIMIT)
    assert sorted(backend.ops("remove")) == sorted(env.roots)
    await emit(watcher, backend, [ev("created", f"{env.pel}/B/x.mkv", size=1)])
    await advance_and_poll(env, watcher, 30)
    assert env.requests == [[ScanScope(env.pel, "A", True)]]
    await advance_and_poll(env, watcher, 60)
    assert len(env.requests) == 1

    backend.calls.clear()
    await watcher.sync()
    assert env.factory.calls == 1  # same backend
    assert sorted(backend.ops("add_tree")) == sorted(env.roots)
    assert watcher.status().state == "active"
    await watcher.stop()


async def test_backend_failed_closes_it_and_the_next_sync_replaces_it(env: Env):
    watcher = await started(env)
    first = env.factory.last
    await emit(watcher, first, [ev("created", f"{env.pel}/C", is_dir=True)])
    await emit(
        watcher, first, [ev("backend_failed", "", detail="OSError: [Errno 9] Bad file descriptor")]
    )
    await watcher.wait_idle()
    status = watcher.status()
    assert status.state == "error"
    assert status.detail == "inotify reader stopped: OSError: [Errno 9] Bad file descriptor"
    assert first.closed
    await advance_and_poll(env, watcher, 30)
    assert env.requests == [[ScanScope(env.pel, "C", True)]]

    await watcher.sync()
    assert env.factory.calls == 2
    second = env.factory.last
    assert second.deliver is not None
    assert sorted(second.ops("add_tree")) == sorted(env.roots)
    assert watcher.status().state == "active"
    await watcher.stop()


@pytest.mark.parametrize("handler", ["backend_failed", "watch_limit", "root_gone", "unreachable"])
async def test_health_fact_during_a_sync_survives_it(env: Env, tmp_path: Path, handler):
    watcher = await started(env)
    backend = env.factory.last
    new = str(tmp_path / "media" / "nuevas")
    os.makedirs(new)
    env.folders = LibraryFolderSet(films=(env.mkv, env.pel, new), shows=(env.series,))
    entered = backend.gate("add_tree", {new})
    backend.calls.clear()
    sync = asyncio.create_task(watcher.sync())
    await until(entered.is_set)
    if handler == "backend_failed":
        await emit(watcher, backend, [ev("backend_failed", "", detail="RuntimeError: bug")])
    elif handler == "watch_limit":
        await emit(watcher, backend, [ev("watch_limit", f"{env.pel}/Z", is_dir=True)])
    elif handler == "root_gone":
        await emit(watcher, backend, [ev("root_gone", env.series, is_dir=True, root=env.series)])
    else:
        watcher.note_unreachable({env.series})
    await asyncio.sleep(0.02)
    assert [c for c in backend.calls if c[0] in ("remove", "close")] == []  # not during the sync
    backend.open_gate("add_tree")
    await sync
    await watcher.wait_idle()
    status = watcher.status()
    if handler in ("backend_failed", "watch_limit"):
        assert status.state == "error"
        assert status.detail == (
            "inotify reader stopped: RuntimeError: bug" if handler == "backend_failed" else LIMIT
        )
        await emit(watcher, backend, [ev("created", f"{env.mkv}/x.mkv", size=1)])
        await advance_and_poll(env, watcher, 60)
        assert env.requests == []  # the settler stays suspended
    else:
        assert (status.state, status.unavailable_roots) == ("active", (env.series,))
        assert backend.ops("remove") == [env.series]
        await emit(watcher, backend, [ev("created", f"{env.series}/S/x.mkv", size=1)])
        await advance_and_poll(env, watcher, 60)
        assert env.requests == []
    await watcher.stop()


async def test_dead_reader_without_an_event_is_replaced(env: Env):
    watcher = await started(env)
    env.factory.last.alive = False
    await watcher.sync()
    assert env.factory.calls == 2
    assert sorted(env.factory.last.ops("add_tree")) == sorted(env.roots)
    assert watcher.status().state == "active"
    await watcher.stop()


async def test_stale_failure_during_step_0_never_touches_the_new_backend(env: Env, caplog):
    watcher = await started(env)
    b1 = env.factory.last
    b1.alive = False
    entered = b1.gate("close")
    sync = asyncio.create_task(watcher.sync())
    await until(entered.is_set)
    await emit(watcher, b1, [ev("backend_failed", "", detail="OSError: late")])
    b1.open_gate("close")
    await sync
    await watcher.wait_idle()
    b2 = env.factory.last
    assert b2 is not b1
    assert not b2.closed
    assert sorted(b2.ops("add_tree")) == sorted(env.roots)
    assert watcher.status().state == "active"
    assert watcher._backend_dead is None

    with caplog.at_level(logging.INFO, logger="hometv.changes.watcher"):
        await emit(watcher, b1, [ev("backend_failed", "", detail="OSError: stale")])
    assert "watch_stale_event: backend_failed" in caplog.text
    assert watcher.status().state == "active"
    await emit(watcher, b2, [ev("backend_failed", "", detail="OSError: real")])
    await watcher.wait_idle()
    assert watcher.status().state == "error"
    assert b2.closed
    assert b1.ops("close") == [""] * len(b1.ops("close"))
    await watcher.stop()


@pytest.mark.parametrize("first", ["health", "sync"])
async def test_action_queued_before_a_sync_runs_before_its_rearm(env: Env, first):
    watcher = await started(env)
    backend = env.factory.last
    backend.calls.clear()
    if first == "health":
        entered = backend.gate("remove")
        await emit(watcher, backend, [ev("root_gone", env.series, is_dir=True, root=env.series)])
        await until(entered.is_set)  # the health task holds the lock
        watcher.request_sync()
        backend.open_gate("remove")
    else:
        backend.emit([ev("root_gone", env.series, is_dir=True, root=env.series)])
        watcher.request_sync()  # same loop turn: the sync task takes the lock first
    await watcher.wait_idle()
    assert backend.calls.index(("remove", env.series)) < backend.calls.index(
        ("add_tree", env.series)
    )
    assert backend.ops("remove") == [env.series]
    status = watcher.status()
    assert (status.state, status.unavailable_roots) == ("active", ())
    await watcher.stop()


async def test_actions_bound_to_a_replaced_backend(env: Env, caplog):
    watcher = await started(env)
    b1 = env.factory.last
    b1.alive = False
    entered = b1.gate("close")
    sync = asyncio.create_task(watcher.sync())
    await until(entered.is_set)  # step 0 is replacing b1
    with caplog.at_level(logging.INFO, logger="hometv.changes.watcher"):
        watcher.note_unreachable({env.series})  # remove(series) bound to b1
        b1.open_gate("close")
        await sync
        await watcher.wait_idle()
    b2 = env.factory.last
    assert b2.ops("remove") == []
    assert b1.ops("remove") == []
    assert "watch_stale_action" in caplog.text
    await watcher.stop()


async def test_factory_failure_during_rebuild_is_retried(env: Env):
    watcher = await started(env)
    env.factory.last.alive = False
    env.factory.error = OSError(errno.EMFILE, "Too many open files")
    await watcher.sync()
    status = watcher.status()
    assert (status.state, status.detail) == ("error", "[Errno 24] Too many open files")
    env.factory.error = None
    await watcher.sync()
    assert env.factory.calls == 3
    assert watcher.status().state == "active"
    await watcher.stop()


async def test_deliver_from_another_thread_reaches_the_settler(env: Env):
    watcher = await started(env)
    backend = env.factory.last
    thread = threading.Thread(
        target=backend.emit, args=([ev("created", f"{env.pel}/T", is_dir=True)],)
    )
    thread.start()
    thread.join()
    await until(lambda: watcher._settler.pending == [ScanScope(env.pel, "T", True)])
    await watcher.stop()


def test_deliver_after_the_loop_closed_does_not_raise(env: Env):
    loop = asyncio.new_event_loop()
    watcher = env.watcher()

    async def run() -> FakeWatchBackend:
        await watcher.start()
        await watcher.wait_idle()
        return env.factory.last

    backend = loop.run_until_complete(run())
    loop.close()
    backend.emit([ev("created", f"{env.pel}/T", is_dir=True)])  # no RuntimeError


async def test_no_roots_configured_is_active(env: Env):
    env.folders = LibraryFolderSet()
    watcher = await started(env)
    status = watcher.status()
    assert (status.state, status.unavailable_roots) == ("active", ())
    await watcher.stop()


# --- Review fixes (review.md) -------------------------------------------------------------------


async def test_dead_backend_fact_cleared_when_a_rebuild_from_none_succeeds(env: Env):
    watcher = await started(env)
    first = env.factory.last
    await emit(watcher, first, [ev("backend_failed", "", detail="OSError: dead")])
    await watcher.wait_idle()
    env.factory.error = OSError(errno.EMFILE, "Too many open files")
    await watcher.sync()  # full run: step 0 closes B1, the factory raises -> no backend
    assert watcher.status().state == "error"
    env.factory.error = None
    await watcher.sync()  # step 0 rebuilds from no backend
    assert watcher.status().state == "active"
    calls = env.factory.calls
    await watcher.sync()
    assert env.factory.calls == calls  # the healthy backend is kept
    second = env.factory.last
    await emit(watcher, second, [ev("created", f"{env.pel}/D", is_dir=True)])
    await advance_and_poll(env, watcher, 30)
    assert env.requests == [[ScanScope(env.pel, "D", True)]]  # intake not suspended
    await watcher.stop()


async def test_poll_once_settles_at_the_time_it_read_once(env: Env):
    watcher = await started(env)
    backend = env.factory.last
    a, b = f"{env.pel}/A/a.mkv", f"{env.pel}/B/b.mkv"
    env.sizes.update({a: 1, b: 2})
    await emit(watcher, backend, [ev("closed_write", a, size=1, root=env.pel)])
    env.clock.now += 30.5
    await emit(watcher, backend, [ev("closed_write", b, size=2, root=env.pel)])
    env.clock.now += 29.5  # A waited two quiet periods; B is 0.5 s short of quiet

    def slow_stat(path: str) -> int | None:
        env.clock.now += 1  # e.g. a USB disk spinning up: B turns quiet meanwhile
        return env.sizes.get(path)

    watcher.stat_size = slow_stat
    await watcher.poll_once()
    assert env.requests == [[ScanScope(env.pel, "A", True)]]
    await watcher.poll_once()  # B quiet since 0.5 s ago, size unchanged: released now
    assert env.requests == [[ScanScope(env.pel, "A", True)], [ScanScope(env.pel, "B", True)]]
    await watcher.stop()


async def test_poll_loop_survives_a_failing_poll(env: Env, caplog):
    watcher = env.watcher()
    watcher.auto_poll = True
    watcher.poll_retry_seconds = 0
    await watcher.start()
    await watcher.wait_idle()
    path = f"{env.pel}/E/e.mkv"
    env.sizes[path] = 5
    failures = [OSError("boom")]

    def flaky_stat(p: str) -> int | None:
        if failures:
            raise failures.pop()
        return env.sizes.get(p)

    watcher.stat_size = flaky_stat
    await emit(watcher, env.factory.last, [ev("closed_write", path, size=5, root=env.pel)])
    env.clock.now += 30
    watcher._wakeup.set()
    await until(lambda: env.requests == [[ScanScope(env.pel, "E", True)]])
    assert "watch_poll_failed" in caplog.text
    await watcher.stop()


async def test_stop_survives_a_task_that_died_with_an_exception(env: Env, caplog):
    watcher = env.watcher()

    def broken_mountinfo() -> str | None:
        raise RuntimeError("mountinfo bug")

    watcher.mountinfo = broken_mountinfo
    await watcher.start()
    await until(lambda: watcher._sync_task is not None and watcher._sync_task.done())
    backend = env.factory.last
    await watcher.stop()  # does not re-raise the sync task's RuntimeError
    assert backend.closed
    assert "watch_task_failed" in caplog.text


def _start_raises(backend: FakeWatchBackend) -> None:
    def start(deliver) -> None:
        raise OSError(errno.EMFILE, "Too many open files")

    backend.start = start  # type: ignore[method-assign]


async def test_backend_whose_start_raises_is_closed(env: Env):
    env.factory.configure = _start_raises
    watcher = await started(env)
    assert watcher.status().state == "error"
    assert env.factory.last.ops("close") == [""]
    await watcher.stop()


async def test_rebuilt_backend_whose_start_raises_is_closed(env: Env):
    watcher = await started(env)
    env.factory.last.alive = False
    env.factory.configure = _start_raises
    await watcher.sync()
    assert watcher.status().state == "error"
    assert env.factory.last.ops("close") == [""]
    await watcher.stop()
