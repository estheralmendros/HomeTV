"""``PersonService`` (actor screen FR-6..8): on-demand TMDB fetch, 30-day cache, single-flight,
wait budget for never-fetched persons, stale-while-revalidate, stale-on-failure, ``stop()``."""

import asyncio
import json
import logging
from collections.abc import AsyncIterator, Iterator
from contextlib import contextmanager
from datetime import UTC, date, datetime, timedelta
from pathlib import Path
from typing import Any

import httpx
import pytest
import respx
from sqlalchemy import select

from hometv.api.errors import ApiError
from hometv.db import models as m
from hometv.db.engine import Database, write_lock_held
from hometv.metadata.tmdb import TMDB_BASE_URL, TmdbClient
from hometv.people import PERSON_TTL, PersonService
from tests.factories import add_cast_credit, add_film, add_person, add_person_credit, add_show
from tests.stubs import wait_until

FIXTURES = Path(__file__).resolve().parent.parent / "fixtures" / "tmdb"
TMDB_PERSON = 1190668
PERSON_URL = f"{TMDB_BASE_URL}/person/{TMDB_PERSON}"
T_NOW = datetime(2026, 10, 28, 12, 0, tzinfo=UTC)


def payload(**changes: Any) -> dict[str, Any]:
    data = json.loads((FIXTURES / "person_1190668.json").read_text(encoding="utf-8"))
    data["biography"] = "Timothée Hal Chalamet es un actor estadounidense."
    data.update(changes)
    return data


class Clock:
    def __init__(self, now: datetime = T_NOW) -> None:
        self.now = now

    def __call__(self) -> datetime:
        return self.now


class Gate:
    """An async respx side effect that blocks until ``open()`` (and records the lock state)."""

    def __init__(self, response: httpx.Response) -> None:
        self.response = response
        self.event = asyncio.Event()
        self.entered = asyncio.Event()
        self.calls = 0
        self.cancelled = False
        self.lock_held: list[bool] = []

    def open(self) -> None:
        self.event.set()

    async def __call__(self, request: httpx.Request) -> httpx.Response:
        self.calls += 1
        self.lock_held.append(write_lock_held())
        self.entered.set()
        try:
            await self.event.wait()
        except asyncio.CancelledError:
            self.cancelled = True
            raise
        return self.response


async def _no_sleep(_seconds: float) -> None:
    return None


@pytest.fixture
async def tmdb() -> AsyncIterator[TmdbClient]:
    client = TmdbClient("v3key", sleep=_no_sleep, max_rps=1000)
    yield client
    await client.aclose()


@pytest.fixture
def clock() -> Clock:
    return Clock()


@pytest.fixture
async def make_service(mdb: Database, tmdb: TmdbClient, clock: Clock):
    services: list[PersonService] = []

    def make(*, wait_seconds: float = 5.0, no_tmdb: bool = False) -> PersonService:
        service = PersonService(
            mdb, None if no_tmdb else tmdb, now=clock, wait_seconds=wait_seconds
        )
        services.append(service)
        return service

    yield make
    for service in services:
        await service.stop()


@pytest.fixture
def seeded(mdb: Database) -> dict[str, int]:
    """Person (TMDB 1190668) with a local credit on Dune; Wonka and Homeland only via TMDB."""
    with mdb.write_session() as s:
        profile = m.Profile(name="A", avatar_id="avatar-01", is_admin=False)
        s.add(profile)
        person = add_person(s, "Timothée Chalamet", tmdb_id=TMDB_PERSON, profile_path="tc.jpg")
        dune = add_film(s, "Dune", year=2021, tmdb_id=438631)
        wonka = add_film(s, "Wonka", year=2023, tmdb_id=787699)
        homeland, _ = add_show(s, "Homeland", year=2011, tmdb_id=1407)
        add_cast_credit(s, person, film=dune, character="Paul Atreides")
        s.flush()
        return {
            "person": person.id,
            "profile": profile.id,
            "dune": dune.id,
            "wonka": wonka.id,
            "homeland": homeland.id,
        }


def make_stale(db: Database, person_id: int, fetched_at: datetime) -> None:
    with db.write_session() as s:
        person = s.get(m.Person, person_id)
        assert person is not None
        person.biography = "Old bio"
        person.biography_language = "es-ES"
        person.birthday = date(1995, 12, 27)
        person.details_fetched_at = fetched_at
        add_person_credit(s, person, "movie", 787699, character="Old Wonka")


def person_row(db: Database, person_id: int) -> m.Person:
    with db.read_session() as s:
        person = s.get(m.Person, person_id)
        assert person is not None
        return person


def credit_rows(db: Database, person_id: int) -> list[tuple[str, int, str | None]]:
    with db.read_session() as s:
        rows = s.execute(
            select(m.PersonCredit.media_type, m.PersonCredit.tmdb_id, m.PersonCredit.character)
            .where(m.PersonCredit.person_id == person_id)
            .order_by(m.PersonCredit.media_type, m.PersonCredit.tmdb_id)
        ).all()
        return [tuple(r) for r in rows]  # type: ignore[misc]


def titles(detail) -> list[tuple[str, str, str | None]]:
    return [(a.kind, a.item.title, a.character) for a in detail.appearances]


@contextmanager
def count_writes(db: Database) -> Iterator[list[int]]:
    calls: list[int] = []
    original = db.write_session

    @contextmanager
    def counting():
        calls.append(1)
        with original() as session:
            yield session

    db.write_session = counting  # type: ignore[method-assign]
    try:
        yield calls
    finally:
        db.write_session = original  # type: ignore[method-assign]


# --- First open ------------------------------------------------------------------------------


async def test_first_open_fetches_and_answers_complete(mdb, seeded, make_service, respx_mock):
    route = respx_mock.get(PERSON_URL).respond(json=payload())
    service = make_service()
    with count_writes(mdb) as writes:
        detail = await service.detail(seeded["person"], seeded["profile"])
    assert route.call_count == 1
    assert len(writes) == 1  # columns + credits in one transaction
    assert detail.credits_complete is True
    assert detail.biography == "Timothée Hal Chalamet es un actor estadounidense."
    assert detail.biography_language == "es-ES"
    assert detail.birthday == date(1995, 12, 27)
    assert detail.place_of_birth == "Manhattan, New York City, New York, USA"
    assert titles(detail) == [
        ("film", "Wonka", "Willy Wonka"),
        ("film", "Dune", "Paul Atreides"),
        ("show", "Homeland", "Finn Walden"),
    ]
    row = person_row(mdb, seeded["person"])
    assert row.details_fetched_at == T_NOW
    assert row.name == "Timothée Chalamet" and row.profile_path == "tc.jpg"  # untouched
    assert credit_rows(mdb, seeded["person"]) == [
        ("movie", 438631, "Paul Atreides"),
        ("movie", 787699, "Willy Wonka"),
        ("tv", 1407, "Finn Walden"),
        ("tv", 4194, None),
    ]


async def test_first_open_uses_fallback_biography(mdb, seeded, make_service, respx_mock):
    respx_mock.get(PERSON_URL, params={"language": "es-ES"}).respond(json=payload(biography=""))
    en = json.loads((FIXTURES / "person_1190668_en.json").read_text(encoding="utf-8"))
    respx_mock.get(PERSON_URL, params={"language": "en-US"}).respond(json=en)
    detail = await make_service().detail(seeded["person"], seeded["profile"])
    assert detail.biography_language == "en-US"
    assert detail.biography is not None and detail.biography.startswith("Timothée Hal")


async def test_unknown_person_is_404_without_tmdb_call(seeded, make_service, respx_mock):
    route = respx_mock.get(url__startswith=f"{TMDB_BASE_URL}/person/").respond(json=payload())
    with pytest.raises(ApiError) as exc:
        await make_service().detail(999, seeded["profile"])
    assert (exc.value.status_code, exc.value.code) == (404, "not_found")
    assert route.call_count == 0


# --- Freshness -------------------------------------------------------------------------------


async def test_fresh_person_causes_no_call_and_30_days_later_one_refetch(
    mdb, seeded, make_service, respx_mock, clock
):
    route = respx_mock.get(PERSON_URL).respond(json=payload())
    service = make_service()
    await service.detail(seeded["person"], seeded["profile"])
    assert route.call_count == 1
    clock.now = T_NOW + PERSON_TTL - timedelta(seconds=1)
    await service.detail(seeded["person"], seeded["profile"])
    await service.wait_idle()
    assert route.call_count == 1
    clock.now = T_NOW + PERSON_TTL
    detail = await service.detail(seeded["person"], seeded["profile"])
    assert detail.credits_complete is True
    await service.wait_idle()
    assert route.call_count == 2
    assert person_row(mdb, seeded["person"]).details_fetched_at == T_NOW + PERSON_TTL


async def test_stale_person_answers_without_waiting_then_refreshes(
    mdb, seeded, make_service, respx_mock
):
    make_stale(mdb, seeded["person"], T_NOW - timedelta(days=31))
    gate = Gate(httpx.Response(200, json=payload()))
    respx_mock.get(PERSON_URL).mock(side_effect=gate)
    service = make_service(wait_seconds=5)
    detail = await asyncio.wait_for(service.detail(seeded["person"], seeded["profile"]), 1)
    assert not gate.event.is_set()
    assert detail.credits_complete is True
    assert detail.biography == "Old bio"
    assert ("film", "Wonka", "Old Wonka") in titles(detail)
    await asyncio.wait_for(gate.entered.wait(), 1)
    gate.open()
    await service.wait_idle()
    row = person_row(mdb, seeded["person"])
    assert row.details_fetched_at == T_NOW
    assert row.biography == "Timothée Hal Chalamet es un actor estadounidense."
    assert ("movie", 787699, "Willy Wonka") in credit_rows(mdb, seeded["person"])
    assert gate.lock_held == [False]  # no write transaction while TMDB is awaited


async def test_concurrent_opens_share_one_fetch(seeded, make_service, respx_mock):
    gate = Gate(httpx.Response(200, json=payload()))
    route = respx_mock.get(PERSON_URL).mock(side_effect=gate)
    service = make_service(wait_seconds=5)
    first = asyncio.create_task(service.detail(seeded["person"], seeded["profile"]))
    # The fetch reached TMDB, so ``first`` is already waiting on it (with the 5 s budget).
    await asyncio.wait_for(gate.entered.wait(), 1)
    # A second open with no budget returns while the gate is still closed: it went through
    # start-or-join during the in-flight fetch, deterministically.
    service.wait_seconds = 0
    second = await asyncio.wait_for(service.detail(seeded["person"], seeded["profile"]), 1)
    assert second.credits_complete is False  # answered from the DB without waiting
    gate.open()
    assert (await asyncio.wait_for(first, 1)).credits_complete is True
    await service.wait_idle()
    assert route.call_count == 1  # the second open joined the in-flight fetch


async def test_gated_beyond_budget_answers_local_then_completes(
    mdb, seeded, make_service, respx_mock
):
    gate = Gate(httpx.Response(200, json=payload()))
    respx_mock.get(PERSON_URL).mock(side_effect=gate)
    service = make_service(wait_seconds=0.05)
    detail = await service.detail(seeded["person"], seeded["profile"])
    assert detail.credits_complete is False
    assert detail.biography is None
    assert titles(detail) == [("film", "Dune", "Paul Atreides")]  # local credits only
    assert person_row(mdb, seeded["person"]).details_fetched_at is None
    gate.open()
    await service.wait_idle()
    assert person_row(mdb, seeded["person"]).details_fetched_at == T_NOW
    after = await service.detail(seeded["person"], seeded["profile"])
    assert after.credits_complete is True
    assert len(after.appearances) == 3
    assert gate.calls == 1


async def test_wait_idle_covers_fetches_started_while_waiting(
    seeded, mdb, make_service, respx_mock
):
    with mdb.write_session() as s:
        other = add_person(s, "Other", tmdb_id=42).id
    first = Gate(httpx.Response(200, json=payload()))
    second = Gate(httpx.Response(200, json=payload()))
    respx_mock.get(PERSON_URL).mock(side_effect=first)
    respx_mock.get(f"{TMDB_BASE_URL}/person/42").mock(side_effect=second)
    service = make_service(wait_seconds=0.01)
    await service.detail(seeded["person"], seeded["profile"])
    idle = asyncio.create_task(service.wait_idle())
    await asyncio.sleep(0)
    await service.detail(other, seeded["profile"])  # starts a second fetch while idle waits
    first.open()
    await wait_until(lambda: person_row(mdb, seeded["person"]).details_fetched_at is not None)
    assert not idle.done()  # still waiting for the second fetch
    second.open()
    await asyncio.wait_for(idle, 1)
    assert person_row(mdb, other).details_fetched_at == T_NOW
    assert person_row(mdb, seeded["person"]).details_fetched_at == T_NOW


async def _finished_task() -> "asyncio.Task[None]":
    async def nothing() -> None:
        return None

    task = asyncio.create_task(nothing())
    await task
    return task


async def test_wait_idle_ignores_a_finished_task_whose_callback_is_pending(make_service):
    """``gather`` over finished tasks never yields (3.12): looping on ``_inflight`` would spin."""
    service = make_service()
    service._inflight[1] = await _finished_task()  # as if its done-callback were still queued
    await asyncio.wait_for(service.wait_idle(), 1)


async def test_finished_task_is_not_joined(mdb, seeded, make_service, respx_mock):
    route = respx_mock.get(PERSON_URL).respond(json=payload())
    service = make_service()
    service._inflight[seeded["person"]] = await _finished_task()
    detail = await service.detail(seeded["person"], seeded["profile"])
    assert route.call_count == 1
    assert detail.credits_complete is True


# --- Failures --------------------------------------------------------------------------------


async def test_failed_refresh_keeps_stale_data(mdb, seeded, make_service, respx_mock, caplog):
    stale_at = T_NOW - timedelta(days=40)
    make_stale(mdb, seeded["person"], stale_at)
    route = respx_mock.get(PERSON_URL).respond(503)
    service = make_service()
    with caplog.at_level(logging.WARNING, logger="hometv.people"):
        detail = await service.detail(seeded["person"], seeded["profile"])
        await service.wait_idle()
    assert route.call_count == 4  # first try + 3 retries (sleep injected)
    assert detail.credits_complete is True and detail.biography == "Old bio"
    row = person_row(mdb, seeded["person"])
    assert row.details_fetched_at == stale_at
    assert row.biography == "Old bio"
    assert credit_rows(mdb, seeded["person"]) == [("movie", 787699, "Old Wonka")]
    assert any("person_fetch_failed" in r.getMessage() for r in caplog.records)
    # the next open tries again
    await service.detail(seeded["person"], seeded["profile"])
    await service.wait_idle()
    assert route.call_count == 8


async def test_failure_on_never_fetched_answers_local(mdb, seeded, make_service, respx_mock):
    respx_mock.get(PERSON_URL).respond(401)
    detail = await make_service().detail(seeded["person"], seeded["profile"])
    assert detail.credits_complete is False
    assert titles(detail) == [("film", "Dune", "Paul Atreides")]
    assert person_row(mdb, seeded["person"]).details_fetched_at is None


async def test_failed_fallback_call_fails_the_fetch(mdb, seeded, make_service, respx_mock):
    respx_mock.get(PERSON_URL, params={"language": "es-ES"}).respond(json=payload(biography=""))
    respx_mock.get(PERSON_URL, params={"language": "en-US"}).respond(503)
    detail = await make_service().detail(seeded["person"], seeded["profile"])
    assert detail.credits_complete is False
    assert person_row(mdb, seeded["person"]).details_fetched_at is None
    assert credit_rows(mdb, seeded["person"]) == []


async def test_unexpected_error_is_logged_and_db_untouched(
    mdb, seeded, make_service, tmdb, monkeypatch, caplog
):
    async def boom(_tmdb_id: int) -> dict[str, Any]:
        raise RuntimeError("boom")

    monkeypatch.setattr(tmdb, "person_details", boom)
    service = make_service()
    with caplog.at_level(logging.ERROR, logger="hometv.people"):
        detail = await service.detail(seeded["person"], seeded["profile"])
    assert detail.credits_complete is False
    assert person_row(mdb, seeded["person"]).details_fetched_at is None
    assert any("person_fetch_crashed" in r.getMessage() for r in caplog.records)


async def test_tmdb_404_marks_fetched_with_empty_credits(mdb, seeded, make_service, respx_mock):
    stale_at = T_NOW - timedelta(days=31)
    make_stale(mdb, seeded["person"], stale_at)
    respx_mock.get(PERSON_URL).respond(404)
    service = make_service()
    await service.detail(seeded["person"], seeded["profile"])
    await service.wait_idle()
    row = person_row(mdb, seeded["person"])
    assert row.details_fetched_at == T_NOW
    assert row.biography == "Old bio" and row.birthday == date(1995, 12, 27)  # untouched
    assert credit_rows(mdb, seeded["person"]) == []
    detail = await service.detail(seeded["person"], seeded["profile"])
    assert detail.credits_complete is True
    assert titles(detail) == [("film", "Dune", "Paul Atreides")]  # local credits still listed


async def test_without_tmdb_no_task_and_local_credits(mdb, seeded, make_service):
    service = make_service(no_tmdb=True)
    detail = await asyncio.wait_for(service.detail(seeded["person"], seeded["profile"]), 1)
    await asyncio.wait_for(service.wait_idle(), 1)
    assert person_row(mdb, seeded["person"]).details_fetched_at is None  # nothing fetched
    assert detail.credits_complete is False
    assert titles(detail) == [("film", "Dune", "Paul Atreides")]


# --- Shutdown --------------------------------------------------------------------------------


async def test_stop_cancels_in_flight_fetch_and_leaves_db_unchanged(
    mdb, seeded, make_service, respx_mock
):
    gate = Gate(httpx.Response(200, json=payload()))
    respx_mock.get(PERSON_URL).mock(side_effect=gate)
    service = make_service(wait_seconds=0.01)
    await service.detail(seeded["person"], seeded["profile"])
    await asyncio.wait_for(gate.entered.wait(), 1)
    await service.stop()
    assert gate.cancelled
    gate.open()  # a surviving task would now complete and write
    await asyncio.wait_for(service.wait_idle(), 1)
    assert gate.calls == 1
    assert person_row(mdb, seeded["person"]).details_fetched_at is None
    assert credit_rows(mdb, seeded["person"]) == []


async def test_request_after_stop_starts_no_fetch(mdb, seeded, make_service, respx_mock):
    route = respx_mock.get(PERSON_URL).respond(200, json=payload())
    service = make_service(wait_seconds=5)
    await service.stop()
    detail = await asyncio.wait_for(service.detail(seeded["person"], seeded["profile"]), 1)
    await asyncio.wait_for(service.wait_idle(), 1)
    assert route.call_count == 0
    assert detail.credits_complete is False  # answered from the DB, like without TMDB
    assert titles(detail) == [("film", "Dune", "Paul Atreides")]
    assert person_row(mdb, seeded["person"]).details_fetched_at is None


async def test_request_cancellation_does_not_cancel_the_shared_fetch(
    mdb, seeded, make_service, respx_mock
):
    gate = Gate(httpx.Response(200, json=payload()))
    respx_mock.get(PERSON_URL).mock(side_effect=gate)
    service = make_service(wait_seconds=5)
    request = asyncio.create_task(service.detail(seeded["person"], seeded["profile"]))
    await asyncio.wait_for(gate.entered.wait(), 1)
    request.cancel()
    with pytest.raises(asyncio.CancelledError):
        await request
    gate.open()
    await service.wait_idle()
    assert person_row(mdb, seeded["person"]).details_fetched_at == T_NOW


@pytest.fixture(autouse=True)
def _respx_strict(respx_mock: respx.MockRouter) -> None:
    respx_mock.assert_all_called = False
