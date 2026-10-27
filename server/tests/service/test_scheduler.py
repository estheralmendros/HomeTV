"""Scheduler (PRD §11): single-flight scan -> enrich, status, periodic ticks, shutdown."""

import asyncio

import pytest
from sqlalchemy import delete, select

from hometv.api.errors import ApiError
from hometv.db import models as m
from hometv.scheduler import Scheduler, scan_status

from .conftest import MediaTree, StubProbe


class GatedProbe(StubProbe):
    """Stub probe that blocks until ``gate`` is set."""

    def __init__(self) -> None:
        super().__init__()
        self.gate = asyncio.Event()
        self.started = asyncio.Event()

    async def __call__(self, path: str):
        self.started.set()
        await self.gate.wait()
        return await super().__call__(path)


def _runs(mdb) -> list[m.ScanRun]:
    with mdb.read_session() as s:
        return list(s.scalars(select(m.ScanRun).order_by(m.ScanRun.id)))


async def _until(predicate, timeout: float = 5.0) -> None:
    loop = asyncio.get_running_loop()
    deadline = loop.time() + timeout
    while not predicate():
        if loop.time() > deadline:
            raise AssertionError("condition not reached")
        await asyncio.sleep(0.01)


def test_idle_status_has_nulls_and_unmatched_count(mdb):
    with mdb.read_session() as s:
        status = scan_status(s)
    assert status.model_dump(mode="json") == {
        "id": None,
        "state": "idle",
        "trigger": None,
        "phase": None,
        "started_at": None,
        "finished_at": None,
        "progress": {"processed": None, "total": None},
        "counts": None,
        "errors": [],
        "error": None,
        "unmatched_count": 0,
        "scope": None,
        "watcher": {"state": "disabled", "unavailable_roots": [], "detail": None},
    }


def test_scan_status_fills_scope_and_watcher(mdb):
    from hometv.api.schemas import WatcherStatus

    scope = [{"root": "/media/MKV", "path": "/media/MKV", "recursive": False}]
    with mdb.write_session() as s:
        s.add(m.ScanRun(trigger="watch", state="completed", phase="done", scope=scope))
    watcher = WatcherStatus(state="active", unavailable_roots=["/media/series"], detail=None)
    with mdb.read_session() as s:
        status = scan_status(s, watcher)
    dumped = status.model_dump(mode="json")
    assert dumped["trigger"] == "watch"
    assert dumped["scope"] == scope
    assert dumped["watcher"] == {
        "state": "active",
        "unavailable_roots": ["/media/series"],
        "detail": None,
    }
    with mdb.write_session() as s:
        s.add(m.ScanRun(trigger="manual", state="completed", phase="done"))
    with mdb.read_session() as s:
        assert scan_status(s).scope is None


async def test_manual_scan_is_single_flight_and_reports_status(
    mdb, media: MediaTree, media_settings
):
    media.add("Dune (2021).mkv")
    media.show("Lost/Lost 01.mkv")  # unmatched (no_season)
    probe = GatedProbe()
    scheduler = Scheduler(mdb, media_settings(), tmdb=None, artwork=None, probe_fn=probe)
    await scheduler.start()
    try:
        status = await scheduler.trigger("manual")
        assert status.state == "running"
        assert status.trigger == "manual"
        assert status.id is not None
        await probe.started.wait()

        with pytest.raises(ApiError) as exc:
            await scheduler.trigger("manual")
        err = exc.value
        assert (err.status_code, err.code) == (409, "scan_in_progress")
        scan = err.details["scan"]
        assert scan["state"] == "running"
        assert scan["phase"] == "probing"
        assert scan["progress"]["total"] == 2
        assert "unmatched_count" in scan

        probe.gate.set()
        await scheduler.wait_idle()
    finally:
        await scheduler.stop()

    status = scheduler.status()
    assert status.state == "completed"
    assert status.phase == "done"
    assert status.finished_at is not None
    assert status.counts["new"] == 2
    assert status.progress.processed == 2
    assert status.unmatched_count == 1
    assert [r.trigger for r in _runs(mdb)] == ["manual"]

    with mdb.write_session() as s:  # e.g. an admin resolve, no new scan
        s.execute(delete(m.UnmatchedEntry))
    assert scheduler.status().unmatched_count == 0


async def test_startup_and_periodic_scans(mdb, media: MediaTree, media_settings):
    media.add("Dune (2021).mkv")
    sleeps: list[float] = []

    async def fake_sleep(seconds: float) -> None:
        sleeps.append(seconds)
        if len(sleeps) > 3:
            await asyncio.Event().wait()  # park the loop forever
        await asyncio.sleep(0)

    settings = media_settings(scan={"on_startup": True, "interval_hours": 6})
    scheduler = Scheduler(
        mdb, settings, tmdb=None, artwork=None, probe_fn=StubProbe(), sleep=fake_sleep
    )
    await scheduler.start()
    try:
        await _until(lambda: len(sleeps) == 4)
    finally:
        await scheduler.stop()
    assert sleeps == [10.0, 21600.0, 21600.0, 21600.0]
    assert [r.trigger for r in _runs(mdb)] == ["startup", "scheduled", "scheduled"]
    assert {r.state for r in _runs(mdb)} == {"completed"}


async def test_no_background_task_when_disabled(mdb, media_settings):
    scheduler = Scheduler(mdb, media_settings(), tmdb=None, artwork=None, probe_fn=StubProbe())
    await scheduler.start()
    assert scheduler.background_task is None
    await scheduler.stop()


async def test_scheduled_tick_is_skipped_while_running(mdb, media: MediaTree, media_settings):
    media.add("Dune (2021).mkv")
    probe = GatedProbe()
    scheduler = Scheduler(mdb, media_settings(), tmdb=None, artwork=None, probe_fn=probe)
    await scheduler.start()
    try:
        await scheduler.trigger("manual")
        await probe.started.wait()
        assert await scheduler.run_scheduled("scheduled") is False
        probe.gate.set()
        await scheduler.wait_idle()
    finally:
        await scheduler.stop()
    assert [r.trigger for r in _runs(mdb)] == ["manual"]


async def test_stop_during_scan_marks_run_interrupted(mdb, media: MediaTree, media_settings):
    media.add("Dune (2021).mkv")
    probe = GatedProbe()
    scheduler = Scheduler(mdb, media_settings(), tmdb=None, artwork=None, probe_fn=probe)
    await scheduler.start()
    await scheduler.trigger("manual")
    await probe.started.wait()
    await scheduler.stop()
    [run] = _runs(mdb)
    assert (run.state, run.error) == ("failed", "interrupted")
    assert run.finished_at is not None


async def test_stale_running_rows_are_failed_on_start(mdb, media_settings):
    with mdb.write_session() as s:
        s.add(m.ScanRun(trigger="manual", state="running", phase="probing"))
    scheduler = Scheduler(mdb, media_settings(), tmdb=None, artwork=None, probe_fn=StubProbe())
    await scheduler.start()
    await scheduler.stop()
    [run] = _runs(mdb)
    assert (run.state, run.error) == ("failed", "interrupted")


async def test_unexpected_error_fails_the_run(mdb, media: MediaTree, media_settings):
    media.add("Dune (2021).mkv")

    async def broken_probe(_path: str):
        raise RuntimeError("kaboom")

    scheduler = Scheduler(mdb, media_settings(), tmdb=None, artwork=None, probe_fn=broken_probe)
    await scheduler.start()
    try:
        await scheduler.trigger("manual")
        await scheduler.wait_idle()
    finally:
        await scheduler.stop()
    status = scheduler.status()
    assert status.state == "failed"
    assert status.error == "kaboom"
    # The lock is released: a new scan can start.
    assert scheduler.running is False


async def test_keyframe_hook_is_passed_to_the_scanner(mdb, media: MediaTree, media_settings):
    media.add("Dune (2021).mkv")
    queued: list[list[int]] = []
    scheduler = Scheduler(
        mdb,
        media_settings(),
        tmdb=None,
        artwork=None,
        probe_fn=StubProbe(),
        keyframe_hook=queued.append,
    )
    await scheduler.start()
    try:
        await scheduler.trigger("manual")
        await scheduler.wait_idle()
    finally:
        await scheduler.stop()
    assert len(queued) == 1


# --- HOMETV-1: before=, ensure_idle, libraries trigger -----------------------------------------


async def test_trigger_runs_before_under_the_lock_before_creating_the_run(mdb, media_settings):
    scheduler = Scheduler(mdb, media_settings(), tmdb=None, artwork=None, probe_fn=StubProbe())
    seen: dict[str, object] = {}

    def before() -> None:
        seen["locked"] = scheduler.running
        seen["runs"] = len(_runs(mdb))

    try:
        status = await scheduler.trigger("libraries", before=before)
        assert status.trigger == "libraries"
        await scheduler.wait_idle()
    finally:
        await scheduler.stop()
    assert seen == {"locked": True, "runs": 0}
    assert [r.trigger for r in _runs(mdb)] == ["libraries"]


async def test_trigger_before_error_propagates_without_a_run_and_releases_the_lock(
    mdb, media_settings
):
    scheduler = Scheduler(mdb, media_settings(), tmdb=None, artwork=None, probe_fn=StubProbe())

    def before() -> None:
        raise ApiError(422, "invalid_library_folders", "Invalid library folders")

    with pytest.raises(ApiError) as exc:
        await scheduler.trigger("libraries", before=before)
    assert exc.value.code == "invalid_library_folders"
    assert _runs(mdb) == []
    assert not scheduler.running
    await scheduler.ensure_idle()  # no error: idle


async def test_ensure_idle_raises_scan_in_progress_while_running(
    mdb, media: MediaTree, media_settings
):
    media.add("Dune (2021).mkv")
    probe = GatedProbe()
    scheduler = Scheduler(mdb, media_settings(), tmdb=None, artwork=None, probe_fn=probe)
    try:
        started = await scheduler.trigger("manual")
        await probe.started.wait()
        with pytest.raises(ApiError) as exc:
            await scheduler.ensure_idle()
        assert (exc.value.status_code, exc.value.code) == (409, "scan_in_progress")
        assert exc.value.details["scan"]["id"] == started.id
        probe.gate.set()
        await scheduler.wait_idle()
    finally:
        await scheduler.stop()


@pytest.mark.parametrize(("trigger", "relinks"), [("libraries", True), ("manual", False)])
async def test_execute_passes_the_folders_provider_and_relinks_only_for_libraries(
    mdb, media_settings, monkeypatch, trigger, relinks
):
    from hometv import scheduler as scheduler_module
    from hometv.libraries import LibraryFolderSet

    captured: dict[str, object] = {}

    class SpyScanner:
        def __init__(self, *args, **kwargs) -> None:
            captured.update(kwargs)

        async def run(self, report, run_id):
            return report

    monkeypatch.setattr(scheduler_module, "Scanner", SpyScanner)

    def provider() -> LibraryFolderSet:
        return LibraryFolderSet(films=("/m",))

    scheduler = Scheduler(
        mdb, media_settings(), tmdb=None, artwork=None, probe_fn=StubProbe(), folders=provider
    )
    await scheduler.trigger(trigger)
    await scheduler.wait_idle()
    assert captured["folders"] is provider
    assert captured["relinks"] is relinks


async def test_scheduled_runs_never_relink(mdb, media_settings, monkeypatch):
    from hometv import scheduler as scheduler_module

    captured: list[bool] = []

    class SpyScanner:
        def __init__(self, *args, **kwargs) -> None:
            captured.append(kwargs["relinks"])

        async def run(self, report, run_id):
            return report

    monkeypatch.setattr(scheduler_module, "Scanner", SpyScanner)
    scheduler = Scheduler(mdb, media_settings(), tmdb=None, artwork=None, probe_fn=StubProbe())
    assert await scheduler.run_scheduled("startup")
    assert await scheduler.run_scheduled("scheduled")
    assert captured == [False, False]


# --- HOMETV-1: trigger ``watch`` and the pending-scope queue (prd.md FR-10) --------------------


def _scope(root, rel_dir: str = "", recursive: bool = True):
    from hometv.changes.scope import ScanScope

    return ScanScope(str(root), rel_dir, recursive)


def _stored_scopes(run: m.ScanRun):
    from hometv.changes.scope import ScanScope

    return None if run.scope is None else [ScanScope.from_json(item) for item in run.scope]


def _scheduler(mdb, settings, probe=None, **kw) -> Scheduler:
    return Scheduler(mdb, settings, tmdb=None, artwork=None, probe_fn=probe or StubProbe(), **kw)


def _assert_idle_invariants(scheduler: Scheduler) -> None:
    assert not scheduler.running
    assert scheduler._running_trigger is None


async def test_request_scoped_while_idle_runs_one_watch_run(
    mdb, media: MediaTree, media_settings, monkeypatch
):
    from hometv import scheduler as scheduler_module

    media.add("Dune (2021)/Dune (2021).mkv")
    scope = _scope(media.films, "Dune (2021)")
    scheduler = _scheduler(mdb, media_settings())
    try:
        scheduler.request_scoped([scope])
        await scheduler.wait_idle()
    finally:
        await scheduler.stop()
    [run] = _runs(mdb)
    assert (run.trigger, run.state) == ("watch", "completed")
    assert _stored_scopes(run) == [scope]
    assert run.counts["new"] == 1
    _assert_idle_invariants(scheduler)

    captured: dict[str, object] = {}

    class SpyScanner:
        def __init__(self, *args, **kwargs) -> None:
            captured.update(kwargs)

        async def run(self, report, run_id):
            return report

    monkeypatch.setattr(scheduler_module, "Scanner", SpyScanner)
    spy = _scheduler(mdb, media_settings())
    spy.request_scoped([scope])
    await spy.wait_idle()
    await spy.stop()
    assert captured["scope"] == [scope]
    assert captured["relinks"] is False


async def test_requests_during_a_manual_run_merge_into_one_watch_run(
    mdb, media: MediaTree, media_settings
):
    media.add("Dune (2021).mkv")
    probe = GatedProbe()
    scheduler = _scheduler(mdb, media_settings(), probe)
    try:
        await scheduler.trigger("manual")
        await probe.started.wait()
        scheduler.request_scoped([_scope(media.films, "A")])
        scheduler.request_scoped([_scope(media.shows, "B")])
        assert scheduler.pending_scopes == [_scope(media.films, "A"), _scope(media.shows, "B")]
        probe.gate.set()
        await scheduler.wait_idle()
    finally:
        await scheduler.stop()
    runs = _runs(mdb)
    assert [r.trigger for r in runs] == ["manual", "watch"]
    assert _stored_scopes(runs[1]) == [_scope(media.films, "A"), _scope(media.shows, "B")]
    assert scheduler.pending_scopes == []


async def test_scopes_pending_when_a_full_run_starts_are_dropped(mdb, media_settings):
    scheduler = _scheduler(mdb, media_settings())
    try:
        scheduler.request_scoped([_scope("/media/x", "A")])  # kick not run yet
        await scheduler.trigger("manual")  # takes the lock first
        assert scheduler.pending_scopes == []
        await scheduler.wait_idle()
    finally:
        await scheduler.stop()
    assert [r.trigger for r in _runs(mdb)] == ["manual"]


async def test_manual_during_a_watch_run_is_409_with_the_watch_scope(
    mdb, media: MediaTree, media_settings
):
    media.add("Dune (2021)/Dune (2021).mkv")
    probe = GatedProbe()
    scheduler = _scheduler(mdb, media_settings(), probe)
    scope = _scope(media.films, "Dune (2021)")
    try:
        scheduler.request_scoped([scope])
        await probe.started.wait()
        with pytest.raises(ApiError) as exc:
            await scheduler.trigger("manual")
        assert (exc.value.status_code, exc.value.code) == (409, "scan_in_progress")
        scan = exc.value.details["scan"]
        assert scan["trigger"] == "watch"
        assert scan["scope"] == [scope.to_json()]
        assert scan["watcher"]["state"] == "disabled"
        probe.gate.set()
        await scheduler.wait_idle()
    finally:
        await scheduler.stop()


async def test_on_full_run_once_per_full_run_after_before_never_for_watch(
    mdb, media: MediaTree, media_settings
):
    events: list[str] = []
    scheduler = _scheduler(mdb, media_settings())
    scheduler.on_full_run = lambda: events.append("full")
    try:
        await scheduler.trigger("libraries", before=lambda: events.append("before"))
        await scheduler.wait_idle()
        assert events == ["before", "full"]
        assert await scheduler.run_scheduled("scheduled")
        assert events == ["before", "full", "full"]
        scheduler.request_scoped([_scope(media.films, "A")])
        await scheduler.wait_idle()
    finally:
        await scheduler.stop()
    assert events == ["before", "full", "full"]
    assert [r.trigger for r in _runs(mdb)] == ["libraries", "scheduled", "watch"]


async def test_rejected_library_save_keeps_pending_scopes_for_a_watch_run(
    mdb, media: MediaTree, media_settings
):
    events: list[str] = []
    scheduler = _scheduler(mdb, media_settings())
    scheduler.on_full_run = lambda: events.append("full")
    scope = _scope(media.films, "A")

    def before() -> None:
        raise ApiError(409, "relinks_unconfirmed", "Relinks need confirmation")

    try:
        scheduler.request_scoped([scope])
        with pytest.raises(ApiError) as exc:
            await scheduler.trigger("libraries", before=before)
        assert exc.value.code == "relinks_unconfirmed"
        await scheduler.wait_idle()
    finally:
        await scheduler.stop()
    assert events == []
    runs = _runs(mdb)
    assert [r.trigger for r in runs] == ["watch"]
    assert _stored_scopes(runs[0]) == [scope]
    _assert_idle_invariants(scheduler)


async def test_run_scheduled_waits_for_a_watch_run_but_skips_a_manual_one(
    mdb, media: MediaTree, media_settings
):
    media.add("Dune (2021)/Dune (2021).mkv")
    probe = GatedProbe()
    scheduler = _scheduler(mdb, media_settings(), probe)
    try:
        scheduler.request_scoped([_scope(media.films, "Dune (2021)")])
        await probe.started.wait()
        waiting = asyncio.create_task(scheduler.run_scheduled("scheduled"))
        await _until(lambda: scheduler._full_waiting)
        probe.gate.set()
        assert await asyncio.wait_for(waiting, 5) is True
        await scheduler.wait_idle()
    finally:
        await scheduler.stop()
    assert [r.trigger for r in _runs(mdb)] == ["watch", "scheduled"]


async def test_scopes_requested_while_run_scheduled_waits_are_covered_by_it(
    mdb, media: MediaTree, media_settings
):
    media.add("Dune (2021)/Dune (2021).mkv")
    probe = GatedProbe()
    scheduler = _scheduler(mdb, media_settings(), probe)
    try:
        scheduler.request_scoped([_scope(media.films, "Dune (2021)")])
        await probe.started.wait()
        waiting = asyncio.create_task(scheduler.run_scheduled("scheduled"))
        await _until(lambda: scheduler._full_waiting)
        scheduler.request_scoped([_scope(media.films, "Other")])
        with pytest.raises(ApiError) as exc:  # a manual trigger never waits
            await asyncio.wait_for(scheduler.trigger("manual"), 5)
        assert exc.value.code == "scan_in_progress"
        probe.gate.set()
        assert await asyncio.wait_for(waiting, 5) is True
        await scheduler.wait_idle()
    finally:
        await scheduler.stop()
    assert [r.trigger for r in _runs(mdb)] == ["watch", "scheduled"]
    assert scheduler.pending_scopes == []


async def test_running_trigger_is_none_whenever_the_lock_is_free(
    mdb, media: MediaTree, media_settings
):
    import sqlite3

    media.add("Dune (2021).mkv")
    scheduler = _scheduler(mdb, media_settings())
    try:
        await scheduler.trigger("manual")  # completed
        await scheduler.wait_idle()
        _assert_idle_invariants(scheduler)

        async def broken(_path):
            raise RuntimeError("kaboom")

        scheduler.probe_fn = broken
        media.add("Other (2020).mkv")
        await scheduler.trigger("manual")  # failed
        await scheduler.wait_idle()
        assert scheduler.status().state == "failed"
        _assert_idle_invariants(scheduler)

        def before() -> None:
            raise ApiError(422, "invalid_library_folders", "Invalid")

        with pytest.raises(ApiError):
            await scheduler.trigger("libraries", before=before)
        _assert_idle_invariants(scheduler)

        original = scheduler._create_run

        def failing(trigger, scope=None):
            raise sqlite3.OperationalError("database is locked")

        scheduler._create_run = failing
        scheduler.request_scoped([_scope(media.films, "A")])
        await scheduler.wait_idle()
        _assert_idle_invariants(scheduler)
        scheduler._create_run = original
    finally:
        await scheduler.stop()

    probe = GatedProbe()
    media.add("Third (2019).mkv")
    cancelled = _scheduler(mdb, media_settings(), probe)
    await cancelled.trigger("manual")
    await probe.started.wait()
    await cancelled.stop()  # cancels _execute
    _assert_idle_invariants(cancelled)


@pytest.mark.parametrize("then", ["manual", "request"])
async def test_watch_create_run_failure_keeps_scopes_pending(
    mdb, media: MediaTree, media_settings, then
):
    import sqlite3

    scheduler = _scheduler(mdb, media_settings())
    original = scheduler._create_run
    calls = {"n": 0}

    def flaky(trigger, scope=None):
        calls["n"] += 1
        if calls["n"] == 1:
            raise sqlite3.OperationalError("disk I/O error")
        return original(trigger, scope)

    scheduler._create_run = flaky
    first = _scope(media.films, "A")
    try:
        scheduler.request_scoped([first])
        await scheduler.wait_idle()
        assert _runs(mdb) == []
        _assert_idle_invariants(scheduler)
        assert scheduler.pending_scopes == [first]
        if then == "manual":
            status = await scheduler.trigger("manual")  # 202, not 409
            assert status.trigger == "manual"
            assert scheduler.pending_scopes == []
            await scheduler.wait_idle()
            assert [r.trigger for r in _runs(mdb)] == ["manual"]
        else:
            second = _scope(media.shows, "B")
            scheduler.request_scoped([second])
            await scheduler.wait_idle()
            runs = _runs(mdb)
            assert [r.trigger for r in runs] == ["watch"]
            assert _stored_scopes(runs[0]) == [first, second]
    finally:
        await scheduler.stop()


async def test_cancelling_run_scheduled_while_it_waits(mdb, media: MediaTree, media_settings):
    media.add("Dune (2021)/Dune (2021).mkv")
    probe = GatedProbe()
    scheduler = _scheduler(mdb, media_settings(), probe)
    try:
        scheduler.request_scoped([_scope(media.films, "Dune (2021)")])
        await probe.started.wait()
        watch_task = scheduler._scan_task
        waiting = asyncio.create_task(scheduler.run_scheduled("scheduled"))
        await _until(lambda: scheduler._full_waiting)
        waiting.cancel()
        with pytest.raises(asyncio.CancelledError):
            await waiting
        assert scheduler._full_waiting is False
        assert watch_task is not None and not watch_task.cancelled()
        probe.gate.set()
        await scheduler.wait_idle()
        assert [(r.trigger, r.state) for r in _runs(mdb)] == [("watch", "completed")]
        scheduler.request_scoped([_scope(media.films, "Other")])
        await scheduler.wait_idle()
    finally:
        await scheduler.stop()
    assert [r.trigger for r in _runs(mdb)] == ["watch", "watch"]


async def test_stop_while_the_periodic_tick_waits_on_a_watch_run_does_not_hang(
    mdb, media: MediaTree, media_settings
):
    media.add("Dune (2021)/Dune (2021).mkv")
    probe = GatedProbe()
    tick = asyncio.Event()
    sleeps = 0

    async def fake_sleep(_seconds: float) -> None:
        nonlocal sleeps
        sleeps += 1
        if sleeps == 1:
            await tick.wait()
        else:
            await asyncio.Event().wait()

    settings = media_settings(scan={"interval_hours": 1})
    scheduler = _scheduler(mdb, settings, probe, sleep=fake_sleep)
    await scheduler.start()
    scheduler.request_scoped([_scope(media.films, "Dune (2021)")])
    await probe.started.wait()
    tick.set()
    await _until(lambda: scheduler._full_waiting)
    background = scheduler.background_task
    assert background is not None
    await asyncio.wait_for(scheduler.stop(), 5)
    assert background.cancelled()
    assert [r.trigger for r in _runs(mdb)] == ["watch"]


async def test_on_run_finished_receives_unreachable_roots(
    mdb, media: MediaTree, media_settings, tmp_path
):
    missing = str(tmp_path / "unplugged")
    settings = media_settings(libraries={"films": [str(media.films), missing], "shows": []})
    seen: list[set[str]] = []
    scheduler = _scheduler(mdb, settings)
    scheduler.on_run_finished = seen.append
    try:
        await scheduler.trigger("manual")
        await scheduler.wait_idle()
        scheduler.request_scoped([_scope(missing, "A")])
        await scheduler.wait_idle()
        scheduler.request_scoped([_scope(media.films, "A")])
        await scheduler.wait_idle()
        assert await scheduler.run_scheduled("scheduled")
    finally:
        await scheduler.stop()
    assert seen == [{missing}, {missing}, set(), {missing}]
    assert [r.trigger for r in _runs(mdb)] == ["manual", "watch", "watch", "scheduled"]


async def test_on_run_finished_errors_are_logged_not_raised(mdb, media_settings, caplog):
    scheduler = _scheduler(mdb, media_settings())

    def broken(_roots):
        raise RuntimeError("watcher broke")

    scheduler.on_run_finished = broken
    try:
        await scheduler.trigger("manual")
        await scheduler.wait_idle()
    finally:
        await scheduler.stop()
    assert scheduler.status().state == "completed"
    _assert_idle_invariants(scheduler)


async def test_stop_with_pending_scopes_creates_no_run(mdb, media: MediaTree, media_settings):
    media.add("Dune (2021).mkv")
    scheduler = _scheduler(mdb, media_settings())
    scheduler.request_scoped([_scope(media.films, "A")])  # kick task not run yet
    await scheduler.stop()
    assert _runs(mdb) == []
    assert scheduler.pending_scopes == []

    probe = GatedProbe()
    gated = _scheduler(mdb, media_settings(), probe)
    await gated.trigger("manual")
    await probe.started.wait()
    gated.request_scoped([_scope(media.films, "B")])
    await gated.stop()
    assert [(r.trigger, r.error) for r in _runs(mdb)] == [("manual", "interrupted")]
    assert gated.pending_scopes == []


async def test_two_requests_in_one_step_share_the_kick_task(mdb, media: MediaTree, media_settings):
    scheduler = _scheduler(mdb, media_settings())
    try:
        scheduler.request_scoped([_scope(media.films, "A")])
        kick = scheduler._kick_task
        scheduler.request_scoped([_scope(media.films, "B")])
        assert kick is not None and scheduler._kick_task is kick
        await scheduler.wait_idle()
    finally:
        await scheduler.stop()
    [run] = _runs(mdb)
    assert _stored_scopes(run) == [_scope(media.films, "A"), _scope(media.films, "B")]


async def test_stale_kick_handle_never_blocks_the_loop(mdb, media: MediaTree, media_settings):
    import threading

    scheduler = _scheduler(mdb, media_settings())
    original = scheduler._create_run
    gate = threading.Event()
    entered = threading.Event()

    def gated(trigger, scope=None):
        if trigger == "watch" and not gate.is_set():
            entered.set()
            gate.wait(10)
        return original(trigger, scope)

    scheduler._create_run = gated
    ticks = 0

    async def canary() -> None:
        nonlocal ticks
        while True:
            ticks += 1
            await asyncio.sleep(0)

    canary_task = asyncio.create_task(canary())
    try:
        scheduler.request_scoped([_scope(media.films, "A")])
        await _until(entered.is_set)
        scheduler.request_scoped([_scope(media.films, "B")])
        waiting = asyncio.create_task(scheduler.run_scheduled("scheduled"))
        await _until(lambda: scheduler._full_waiting)
        before = ticks
        await asyncio.sleep(0.05)
        assert ticks > before  # the loop kept running while run_scheduled waited
        gate.set()
        assert await asyncio.wait_for(waiting, 5) is True
        await asyncio.wait_for(scheduler.wait_idle(), 5)
    finally:
        gate.set()
        canary_task.cancel()
        await scheduler.stop()
    runs = _runs(mdb)
    assert [r.trigger for r in runs] == ["watch", "scheduled"]
    assert _stored_scopes(runs[0]) == [_scope(media.films, "A")]


async def test_wait_watch_holder_yields_without_an_alive_holder(mdb, media_settings):
    scheduler = _scheduler(mdb, media_settings())
    await scheduler._lock.acquire()
    scheduler._running_trigger = "watch"
    ran = asyncio.Event()

    async def concurrent() -> None:
        ran.set()

    task = asyncio.create_task(concurrent())
    await scheduler._wait_watch_holder()  # no ValueError from asyncio.wait(set())
    assert ran.is_set()
    await task
    scheduler._running_trigger = None
    scheduler._lock.release()


async def test_stop_while_the_kick_is_inside_create_run(mdb, media: MediaTree, media_settings):
    import threading

    scheduler = _scheduler(mdb, media_settings())
    original = scheduler._create_run
    gate = threading.Event()
    entered = threading.Event()

    def gated(trigger, scope=None):
        entered.set()
        gate.wait(10)
        return original(trigger, scope)

    scheduler._create_run = gated
    scheduler.request_scoped([_scope(media.films, "A")])
    kick = scheduler._kick_task
    await _until(entered.is_set)
    scheduler.request_scoped([_scope(media.films, "B")])
    await asyncio.wait_for(scheduler.stop(), 5)
    try:
        assert kick is not None and kick.cancelled()
        _assert_idle_invariants(scheduler)
        assert scheduler.pending_scopes == []
    finally:
        gate.set()


async def test_request_scoped_drops_undecodable_scopes(mdb, media: MediaTree, media_settings):
    import os

    from hometv.changes.scope import ScanScope

    valid = _scope(media.films, "A")
    bad = ScanScope(str(media.films), os.fsdecode(b"\xff"), True)
    scheduler = _scheduler(mdb, media_settings())
    try:
        scheduler.request_scoped([valid, bad])
        await scheduler.wait_idle()
    finally:
        await scheduler.stop()
    [run] = _runs(mdb)
    assert _stored_scopes(run) == [valid]
    assert scheduler.status().scope is not None


def test_status_uses_the_bound_watcher_status(mdb, media_settings):
    from hometv.api.schemas import WatcherStatus

    scheduler = _scheduler(mdb, media_settings())
    assert scheduler.status().watcher.state == "disabled"
    error = WatcherStatus(state="error", unavailable_roots=[], detail="boom")
    scheduler.watcher_status = lambda: error
    assert scheduler.status().watcher == error
