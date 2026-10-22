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
    }


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
