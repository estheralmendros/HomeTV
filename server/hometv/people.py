"""Actor screen: TMDB person details, cached 30 days (actor screen PRD §3).

Pure part (no I/O, table-tested): ``normalize_credits``, ``normalize_person``, ``is_fresh``.

``PersonService`` (``app.state.people``): ``detail()`` fetches a person from TMDB on demand
(single-flight per person; tasks outlive the request and are cancelled by ``stop()``, after
which requests answer from the DB and start no fetch). A never-fetched person waits up to
``wait_seconds`` for the fetch; a stale one (older than ``PERSON_TTL``) answers at once from the
cache and refreshes in the background. A failed fetch leaves the DB untouched
(stale-on-failure). The write is one short transaction after the TMDB call; DB work runs in
threads (``asyncio.to_thread``).
"""

import asyncio
import logging
import re
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from typing import Any, Literal

from sqlalchemy import delete, insert, select

from hometv import catalog
from hometv.api.schemas import PersonDetail
from hometv.db import models as m
from hometv.db.engine import Database
from hometv.db.models import utcnow
from hometv.metadata.tmdb import BIOGRAPHY_LANGUAGE_KEY, TmdbClient, TmdbError

logger = logging.getLogger("hometv.people")

PERSON_TTL = timedelta(days=30)
PERSON_FETCH_WAIT_SECONDS = 5.0

_ISO_DATE = re.compile(r"^\d{4}-\d{2}-\d{2}$")

MediaType = Literal["movie", "tv"]


@dataclass(frozen=True)
class PersonCreditRow:
    media_type: MediaType
    tmdb_id: int
    character: str | None


@dataclass(frozen=True)
class PersonFetch:
    biography: str | None
    biography_language: str | None
    birthday: date | None
    deathday: date | None
    place_of_birth: str | None
    credits: tuple[PersonCreditRow, ...]  # sorted by (media_type, tmdb_id)


def _stripped(value: object) -> str | None:
    if not isinstance(value, str):
        return None
    return value.strip() or None


def _date(value: object) -> date | None:
    """Strict ``YYYY-MM-DD`` (``date.fromisoformat`` alone also takes ``19921008``)."""
    if not isinstance(value, str) or not _ISO_DATE.match(value):
        return None
    try:
        return date.fromisoformat(value)
    except ValueError:
        return None


def normalize_credits(cast: object) -> tuple[PersonCreditRow, ...]:
    """TMDB ``combined_credits.cast`` -> one row per (media_type, id), sorted.

    Drops non-dicts, entries without an ``int`` id or with another media type, adult credits;
    merges duplicates (one entry per character) joining distinct characters with ``" / "``.
    """
    if not isinstance(cast, list):
        return ()
    merged: dict[tuple[MediaType, int], list[str]] = {}
    for entry in cast:
        if not isinstance(entry, dict):
            continue
        tmdb_id = entry.get("id")
        media_type = entry.get("media_type")
        if type(tmdb_id) is not int or media_type not in ("movie", "tv"):
            continue
        if entry.get("adult") is True:
            continue
        key: tuple[MediaType, int] = ("movie" if media_type == "movie" else "tv", tmdb_id)
        characters = merged.setdefault(key, [])
        character = _stripped(entry.get("character"))
        if character is not None and character not in characters:
            characters.append(character)
    return tuple(
        PersonCreditRow(media_type, tmdb_id, " / ".join(chars) or None)
        for (media_type, tmdb_id), chars in sorted(merged.items())
    )


def normalize_person(data: Mapping[str, Any]) -> PersonFetch:
    """A ``TmdbClient.person_details`` payload -> the stored person details and credits."""
    biography = _stripped(data.get("biography"))
    language = data.get(BIOGRAPHY_LANGUAGE_KEY) if biography is not None else None
    combined = data.get("combined_credits")
    cast = combined.get("cast") if isinstance(combined, dict) else None
    return PersonFetch(
        biography=biography,
        biography_language=language if isinstance(language, str) else None,
        birthday=_date(data.get("birthday")),
        deathday=_date(data.get("deathday")),
        place_of_birth=_stripped(data.get("place_of_birth")),
        credits=normalize_credits(cast),
    )


def is_fresh(fetched_at: datetime | None, now: datetime, ttl: timedelta = PERSON_TTL) -> bool:
    return fetched_at is not None and now - fetched_at < ttl


class PersonService:
    def __init__(
        self,
        db: Database,
        tmdb: TmdbClient | None,
        *,
        now: Callable[[], datetime] = utcnow,
        wait_seconds: float = PERSON_FETCH_WAIT_SECONDS,
        ttl: timedelta = PERSON_TTL,
    ) -> None:
        self.db = db
        self.tmdb = tmdb
        self.now = now
        self.wait_seconds = wait_seconds
        self.ttl = ttl
        # person.id -> in-flight fetch; touched only on the event loop.
        self._inflight: dict[int, asyncio.Task[None]] = {}
        # Set by ``stop()``: later requests answer from the DB and start no fetch.
        self._stopped = False

    async def detail(self, person_id: int, profile_id: int) -> PersonDetail:
        state = await asyncio.to_thread(self._state, person_id)
        if state is None:
            raise catalog.not_found("Person", person_id)
        tmdb_id, fetched_at = state
        task = None
        if not is_fresh(fetched_at, self.now(), self.ttl):
            task = self._start(person_id, tmdb_id)
        if task is not None and fetched_at is None:
            # Never cancels the shared task (on timeout or when this request is cancelled).
            await asyncio.wait({task}, timeout=self.wait_seconds)
        # Stale: answer from the cache at once; the task refreshes it in the background.
        return await asyncio.to_thread(self._read, person_id, profile_id)

    async def wait_idle(self) -> None:
        """Tests: await every in-flight fetch (also those started while waiting).

        Only unfinished tasks are awaited: a finished task stays in ``_inflight`` until its
        done-callback runs, and ``gather`` over finished tasks completes without yielding to
        the loop (Python 3.12), so looping on ``_inflight`` alone could spin forever.
        """
        while pending := [t for t in self._inflight.values() if not t.done()]:
            await asyncio.gather(*pending, return_exceptions=True)

    async def stop(self) -> None:
        """Lifespan: cancel and await every in-flight fetch; later requests start none."""
        self._stopped = True
        tasks = list(self._inflight.values())
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        self._inflight.clear()

    # -- internals --

    def _start(self, person_id: int, tmdb_id: int) -> "asyncio.Task[None] | None":
        """The person's in-flight fetch (started when needed); ``None`` without TMDB or after
        ``stop()`` (the request then answers from the DB, credits incomplete if never fetched)."""
        tmdb = self.tmdb
        if tmdb is None or self._stopped:
            return None
        task = self._inflight.get(person_id)
        if task is None or task.done():  # done: its done-callback has not run yet
            task = asyncio.create_task(self._refresh(person_id, tmdb_id, tmdb))
            self._inflight[person_id] = task

            def _forget(done: "asyncio.Task[None]") -> None:
                if self._inflight.get(person_id) is done:
                    del self._inflight[person_id]

            task.add_done_callback(_forget)
        return task

    def _state(self, person_id: int) -> tuple[int, datetime | None] | None:
        with self.db.read_session() as session:
            row = session.execute(
                select(m.Person.tmdb_id, m.Person.details_fetched_at).where(
                    m.Person.id == person_id
                )
            ).first()
        return None if row is None else (row[0], row[1])

    def _read(self, person_id: int, profile_id: int) -> PersonDetail:
        with self.db.read_session() as session:
            return catalog.person_detail(session, profile_id, person_id)

    async def _refresh(self, person_id: int, tmdb_id: int, tmdb: TmdbClient) -> None:
        """Never raises (a failure is logged and leaves the DB untouched)."""
        try:
            data = await tmdb.person_details(tmdb_id)  # no DB session open here
            fetch = None if data is None else normalize_person(data)
            credits = await asyncio.to_thread(self._apply, person_id, fetch, self.now())
        except TmdbError as exc:
            logger.warning(
                "person_fetch_failed person_id=%s tmdb_id=%s error=%s", person_id, tmdb_id, exc
            )
            return
        except Exception:
            logger.exception("person_fetch_crashed person_id=%s tmdb_id=%s", person_id, tmdb_id)
            return
        logger.debug("person_fetched person_id=%s credits=%s", person_id, credits)

    def _apply(self, person_id: int, fetch: PersonFetch | None, now: datetime) -> int:
        """One write transaction: detail columns (unless TMDB 404), the whole credit set and
        ``details_fetched_at``. Returns the number of credits stored."""
        credits = fetch.credits if fetch is not None else ()
        with self.db.write_session() as session:
            person = session.get(m.Person, person_id)
            if person is None:
                return 0
            if fetch is not None:
                person.biography = fetch.biography
                person.biography_language = fetch.biography_language
                person.birthday = fetch.birthday
                person.deathday = fetch.deathday
                person.place_of_birth = fetch.place_of_birth
            session.execute(delete(m.PersonCredit).where(m.PersonCredit.person_id == person_id))
            if credits:
                session.execute(
                    insert(m.PersonCredit).values(
                        [
                            {
                                "person_id": person_id,
                                "media_type": c.media_type,
                                "tmdb_id": c.tmdb_id,
                                "character": c.character,
                            }
                            for c in credits
                        ]
                    )
                )
            person.details_fetched_at = now
        return len(credits)
