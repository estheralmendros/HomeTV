"""Enrichment, merge rules and manual re-match.

Every item is processed as **fetch** (TMDB calls + artwork, no transaction open) then **apply**
(one short ``write_session()`` transaction, run via ``asyncio.to_thread``). Apply re-reads the
item, discards stale results (item gone, or ``match_status``/``tmdb_id`` changed since the fetch
started) and re-checks the ``tmdb_id`` duplicate inside the transaction, merging instead of
writing (``merge_items``), so ``UNIQUE(library_id, tmdb_id)`` is never violated.

Service functions for the admin API: ``match_item`` and
``resolve_unmatched``; they raise ``ApiError`` with the documented error codes.
"""

import asyncio
import logging
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from datetime import date
from typing import Any, Literal, TypeVar, cast

from sqlalchemy import delete, func, insert, select, update
from sqlalchemy.orm import Session

from hometv.api.errors import ApiError
from hometv.api.schemas import ItemKind
from hometv.config import Settings
from hometv.db import models as m
from hometv.db.engine import Database
from hometv.metadata.artwork import ArtworkCache, tmdb_file_name
from hometv.metadata.matching import MatchDecision, choose_match, merge_results
from hometv.metadata.tmdb import (
    MAX_IN_FLIGHT,
    TmdbAuthError,
    TmdbClient,
    TmdbError,
    TmdbUnavailable,
)
from hometv.scan_report import ScanReport
from hometv.scanner import PARSE_REASONS, link_episodes, unlink_file
from hometv.text import normalize_title, search_text, sort_title, str_or_none

logger = logging.getLogger("hometv.enrich")

CIRCUIT_THRESHOLD = 5
T = TypeVar("T")

_MODELS: dict[ItemKind, type[m.Film] | type[m.Show]] = {"film": m.Film, "show": m.Show}


def _get_item(session: Session, kind: ItemKind, item_id: int) -> m.Film | m.Show | None:
    return session.get(m.Film, item_id) if kind == "film" else session.get(m.Show, item_id)


@dataclass(frozen=True)
class _Snapshot:
    kind: ItemKind
    id: int
    library_id: int
    parsed_title: str
    parsed_year: int | None
    match_status: str
    tmdb_id: int | None


@dataclass(frozen=True)
class _SeasonJob:
    season_id: int
    show_id: int
    show_tmdb_id: int
    season_number: int


class _Stop(Exception):
    """Enrichment must stop issuing TMDB requests (auth failure or open circuit)."""


def _parse_date(value: Any) -> date | None:
    if not isinstance(value, str) or not value:
        return None
    try:
        return date.fromisoformat(value[:10])
    except ValueError:
        return None


# --- Writing TMDB details ------------------------------------------------------------------------


def write_details(
    session: Session,
    kind: ItemKind,
    item: m.Film | m.Show,
    details: dict[str, Any],
    cast_limit: int,
) -> None:
    """Store TMDB details on ``item`` (step 3), replacing genres and cast; mark it matched."""
    if kind == "film":
        assert isinstance(item, m.Film)
        title = str_or_none(details.get("title")) or item.title
        original = str_or_none(details.get("original_title"))
        released = _parse_date(details.get("release_date"))
        item.imdb_id = str_or_none(details.get("imdb_id"))
        item.release_date = released
        item.tagline = str_or_none(details.get("tagline"))
        runtime = details.get("runtime")
        item.runtime_minutes = runtime if isinstance(runtime, int) and runtime > 0 else None
    else:
        assert isinstance(item, m.Show)
        title = str_or_none(details.get("name")) or item.title
        original = str_or_none(details.get("original_name"))
        released = _parse_date(details.get("first_air_date"))
        item.first_air_date = released
        item.status = str_or_none(details.get("status"))
    item.tmdb_id = details["id"]
    item.title = title
    item.original_title = original
    item.year = released.year if released else item.year
    item.overview = str_or_none(details.get("overview"))
    vote = details.get("vote_average")
    item.vote_average = float(vote) if isinstance(vote, int | float) else None
    item.poster_path = tmdb_file_name(details.get("poster_path"))
    item.backdrop_path = tmdb_file_name(details.get("backdrop_path"))
    item.sort_title = sort_title(title)
    item.search_text = search_text(title, original)
    item.match_status = "matched"
    item.metadata_updated_at = m.utcnow()
    _write_genres(session, kind, item.id, details.get("genres") or [])
    _write_cast(
        session, kind, item.id, (details.get("credits") or {}).get("cast") or [], cast_limit
    )
    owner = m.UnmatchedEntry.film_id if kind == "film" else m.UnmatchedEntry.show_id
    session.execute(delete(m.UnmatchedEntry).where(owner == item.id))
    session.flush()


def _write_genres(session: Session, kind: ItemKind, item_id: int, genres: list[Any]) -> None:
    link = m.FilmGenre if kind == "film" else m.ShowGenre
    owner = link.film_id if kind == "film" else link.show_id  # type: ignore[union-attr]
    session.execute(delete(link).where(owner == item_id))
    for genre in genres:
        if not isinstance(genre, dict) or not isinstance(genre.get("id"), int):
            continue
        row = session.get(m.Genre, genre["id"])
        if row is None:
            session.add(m.Genre(id=genre["id"], name=genre.get("name") or ""))
        elif genre.get("name"):
            row.name = genre["name"]
        session.flush()
        if kind == "film":
            session.add(m.FilmGenre(film_id=item_id, genre_id=genre["id"]))
        else:
            session.add(m.ShowGenre(show_id=item_id, genre_id=genre["id"]))


def _write_cast(
    session: Session, kind: ItemKind, item_id: int, cast: list[Any], cast_limit: int
) -> None:
    owner = m.CastCredit.film_id if kind == "film" else m.CastCredit.show_id
    session.execute(delete(m.CastCredit).where(owner == item_id))
    members = [c for c in cast if isinstance(c, dict) and isinstance(c.get("id"), int)]

    def billing_order(member: dict[str, Any]) -> int:
        value = member.get("order")
        return value if isinstance(value, int) else 1_000_000

    members.sort(key=billing_order)
    seen: set[int] = set()
    unique = []  # one credit per person (TMDB can list a person twice): first by billing order
    for member in members:
        if member["id"] not in seen:
            seen.add(member["id"])
            unique.append(member)
    billed = unique[:cast_limit]
    # One SELECT for the whole cast and one multi-row INSERT for the new persons (never one
    # statement per member inside the write transaction).
    known = {
        p.tmdb_id: p
        for p in session.scalars(
            select(m.Person).where(m.Person.tmdb_id.in_([c["id"] for c in billed]))
        )
    }
    person_ids = {tmdb_id: p.id for tmdb_id, p in known.items()}
    new_rows = []
    for member in billed:
        profile_path = tmdb_file_name(member.get("profile_path"))
        person = known.get(member["id"])
        if person is None:
            name = member.get("name") or ""
            new_rows.append({"tmdb_id": member["id"], "name": name, "profile_path": profile_path})
        else:
            person.name = member.get("name") or person.name
            person.profile_path = profile_path
    if new_rows:
        inserted = session.execute(
            insert(m.Person).values(new_rows).returning(m.Person.tmdb_id, m.Person.id)
        )
        person_ids.update({tmdb_id: pid for tmdb_id, pid in inserted})
    for position, member in enumerate(billed):
        order = member.get("order") if isinstance(member.get("order"), int) else position
        session.add(
            m.CastCredit(
                person_id=person_ids[member["id"]],
                film_id=item_id if kind == "film" else None,
                show_id=item_id if kind == "show" else None,
                character=str_or_none(member.get("character")),
                order=order,
            )
        )


def write_season(session: Session, season: m.Season, data: dict[str, Any] | None) -> None:
    """Season + library episodes from ``tv_season`` (step 4); absent episodes keep title=null."""
    if data:
        season.name = str_or_none(data.get("name"))
        season.overview = str_or_none(data.get("overview"))
        season.poster_path = tmdb_file_name(data.get("poster_path"))
        season.air_date = _parse_date(data.get("air_date"))
        by_number = {
            e.get("episode_number"): e for e in data.get("episodes") or [] if isinstance(e, dict)
        }
        for episode in session.scalars(select(m.Episode).where(m.Episode.season_id == season.id)):
            tmdb_episode = by_number.get(episode.episode_number)
            if tmdb_episode is None:
                continue
            episode.title = str_or_none(tmdb_episode.get("name"))
            episode.overview = str_or_none(tmdb_episode.get("overview"))
            episode.still_path = tmdb_file_name(tmdb_episode.get("still_path"))
            episode.air_date = _parse_date(tmdb_episode.get("air_date"))
            runtime = tmdb_episode.get("runtime")
            episode.runtime_minutes = runtime if isinstance(runtime, int) and runtime > 0 else None
    season.metadata_fetched_at = m.utcnow()


# --- Merge rules -------------------------------------------------------------------------------


def _merge_progress(session: Session, column: Any, loser_id: int, survivor_id: int) -> None:
    """Move progress rows from loser to survivor; on conflict keep the later ``updated_at``."""
    pp = m.PlaybackProgress
    for row in list(session.scalars(select(pp).where(column == loser_id))):
        other = session.scalar(
            select(pp).where(pp.profile_id == row.profile_id, column == survivor_id)
        )
        if other is not None:
            if row.updated_at > other.updated_at:
                session.delete(other)
                session.flush()
            else:
                session.delete(row)
                session.flush()
                continue
        session.execute(
            update(pp)
            .where(pp.id == row.id)
            .values({column.key: survivor_id, "updated_at": row.updated_at})
        )
    session.flush()


def _merge_per_profile[Row: (m.WatchlistEntry, m.Rating, m.RatingDismissal)](
    session: Session,
    model: type[Row],
    column: Any,
    loser_id: int,
    survivor_id: int,
    resolve: Callable[[Row, Row], None],
) -> None:
    """Move ``model`` rows (one per profile and title) from loser to survivor. When a profile
    has both, ``resolve(loser_row, survivor_row)`` folds the loser's values into the survivor
    row, which stays, and the loser row is deleted, so the unique index is never hit."""
    for row in list(session.scalars(select(model).where(column == loser_id))):
        other = session.scalar(
            select(model).where(model.profile_id == row.profile_id, column == survivor_id)
        )
        if other is not None:
            resolve(row, other)
            session.delete(row)
            session.flush()
            continue
        session.execute(update(model).where(model.id == row.id).values({column.key: survivor_id}))
    session.flush()


def _keep_earlier_added(row: m.WatchlistEntry, survivor: m.WatchlistEntry) -> None:
    """Watchlist ("Mi lista"): one entry, with the earlier ``added_at``."""
    survivor.added_at = min(survivor.added_at, row.added_at)


def _keep_later_rating(row: m.Rating, survivor: m.Rating) -> None:
    """Ratings: the later rating (``stars_x2`` + ``rated_at``; the survivor's on a tie)."""
    if row.rated_at > survivor.rated_at:
        survivor.stars_x2, survivor.rated_at = row.stars_x2, row.rated_at


def _keep_earlier_dismissal(row: m.RatingDismissal, survivor: m.RatingDismissal) -> None:
    """Rating-prompt dismissals: the earlier ``dismissed_at``."""
    survivor.dismissed_at = min(survivor.dismissed_at, row.dismissed_at)


def _merge_profile_rows(session: Session, kind: ItemKind, loser_id: int, survivor_id: int) -> None:
    """Move the loser's "Mi lista" entries, ratings and rating-prompt dismissals."""
    key = f"{kind}_id"
    we, r, d = m.WatchlistEntry, m.Rating, m.RatingDismissal
    _merge_per_profile(session, we, getattr(we, key), loser_id, survivor_id, _keep_earlier_added)
    _merge_per_profile(session, r, getattr(r, key), loser_id, survivor_id, _keep_later_rating)
    _merge_per_profile(session, d, getattr(d, key), loser_id, survivor_id, _keep_earlier_dismissal)


def _write_alias(
    session: Session, kind: ItemKind, loser: m.Film | m.Show, survivor_id: int
) -> None:
    target = m.ItemAlias.film_id if kind == "film" else m.ItemAlias.show_id
    session.execute(update(m.ItemAlias).where(target == loser.id).values({target.key: survivor_id}))
    year = loser.parsed_year
    alias = session.scalar(
        select(m.ItemAlias).where(
            m.ItemAlias.library_id == loser.library_id,
            m.ItemAlias.parsed_title_norm == loser.parsed_title_norm,
            m.ItemAlias.parsed_year.is_(None) if year is None else m.ItemAlias.parsed_year == year,
        )
    )
    if alias is None:
        alias = m.ItemAlias(
            library_id=loser.library_id, parsed_title_norm=loser.parsed_title_norm, parsed_year=year
        )
        session.add(alias)
    alias.film_id = survivor_id if kind == "film" else None
    alias.show_id = survivor_id if kind == "show" else None
    session.flush()


def merge_items(
    session: Session, kind: ItemKind, loser: m.Film | m.Show, survivor: m.Film | m.Show
) -> int:
    """Merge ``loser`` into ``survivor`` (merge rules) in the caller's transaction."""
    assert loser.id != survivor.id
    _write_alias(session, kind, loser, survivor.id)
    if kind == "film":
        session.execute(
            update(m.MediaFile).where(m.MediaFile.film_id == loser.id).values(film_id=survivor.id)
        )
        _merge_progress(session, m.PlaybackProgress.film_id, loser.id, survivor.id)
        _merge_profile_rows(session, "film", loser.id, survivor.id)
        session.execute(delete(m.UnmatchedEntry).where(m.UnmatchedEntry.film_id == loser.id))
        session.delete(loser)
        session.flush()
        earliest = session.scalar(
            select(func.min(m.MediaFile.added_at)).where(m.MediaFile.film_id == survivor.id)
        )
        if earliest is not None:
            survivor.added_at = earliest
        return survivor.id

    assert isinstance(loser, m.Show) and isinstance(survivor, m.Show)
    for season in list(session.scalars(select(m.Season).where(m.Season.show_id == loser.id))):
        target = session.scalar(
            select(m.Season).where(
                m.Season.show_id == survivor.id, m.Season.season_number == season.season_number
            )
        )
        if target is None:
            season.show_id = survivor.id
            season.metadata_fetched_at = None
            session.execute(
                update(m.Episode)
                .where(m.Episode.season_id == season.id)
                .values(show_id=survivor.id)
            )
            continue
        for episode in list(
            session.scalars(select(m.Episode).where(m.Episode.season_id == season.id))
        ):
            twin = session.scalar(
                select(m.Episode).where(
                    m.Episode.season_id == target.id,
                    m.Episode.episode_number == episode.episode_number,
                )
            )
            if twin is None:
                episode.season_id = target.id
                episode.show_id = survivor.id
                continue
            for link in list(
                session.scalars(
                    select(m.MediaFileEpisode).where(m.MediaFileEpisode.episode_id == episode.id)
                )
            ):
                if session.get(m.MediaFileEpisode, (link.media_file_id, twin.id)) is None:
                    session.add(
                        m.MediaFileEpisode(media_file_id=link.media_file_id, episode_id=twin.id)
                    )
                session.delete(link)
            session.flush()
            _merge_progress(session, m.PlaybackProgress.episode_id, episode.id, twin.id)
            session.delete(episode)
        session.flush()
        session.delete(season)
    session.flush()
    survivor.content_updated_at = max(survivor.content_updated_at, loser.content_updated_at)
    _merge_profile_rows(session, "show", loser.id, survivor.id)
    session.execute(delete(m.UnmatchedEntry).where(m.UnmatchedEntry.show_id == loser.id))
    session.delete(loser)
    session.flush()
    return survivor.id


def _duplicate(session: Session, kind: ItemKind, item: m.Film | m.Show, tmdb_id: int) -> Any:
    model = _MODELS[kind]
    return session.scalar(
        select(model).where(
            model.library_id == item.library_id, model.tmdb_id == tmdb_id, model.id != item.id
        )
    )


# --- Enricher ---------------------------------------------------------------------------------


class Enricher:
    """One enrichment run: pending films/shows, then seasons of matched shows to fetch."""

    def __init__(
        self,
        db: Database,
        settings: Settings,
        client: TmdbClient | None,
        artwork: ArtworkCache | None,
        *,
        report: ScanReport | None = None,
        run_id: int | None = None,
        concurrency: int = MAX_IN_FLIGHT,
    ) -> None:
        self.db = db
        self.settings = settings
        self.client = client
        self.artwork = artwork
        self.report = report or ScanReport()
        self.run_id = run_id
        self.concurrency = max(1, concurrency)
        self._failures = 0
        self._circuit_open = False
        self._stopped = False

    async def run(self) -> ScanReport:
        self.report.phase = "enriching"
        await asyncio.to_thread(self._save)
        if self.client is None:
            return self.report
        items = await asyncio.to_thread(self._pending_items)
        await self._pool(items, self._enrich_item)
        seasons = await asyncio.to_thread(self._pending_seasons, None)
        await self._pool(seasons, self._enrich_season)
        await asyncio.to_thread(self._save)
        return self.report

    async def refresh_show_seasons(self, show_id: int) -> None:
        jobs = await asyncio.to_thread(self._pending_seasons, show_id)
        await self._pool(jobs, self._enrich_season)

    def _save(self) -> None:
        if self.run_id is not None:
            with self.db.write_session() as session:
                self.report.save(session, self.run_id)

    async def _pool(self, jobs: list[Any], handler: Callable[[Any], Awaitable[None]]) -> None:
        queue = list(reversed(jobs))

        async def worker() -> None:
            while queue and not (self._stopped or self._circuit_open):
                await handler(queue.pop())

        await asyncio.gather(*(worker() for _ in range(min(self.concurrency, len(jobs)))))

    async def _call(self, fn: Callable[..., Awaitable[T]], *args: Any, **kwargs: Any) -> T:
        if self._stopped or self._circuit_open:
            raise _Stop
        try:
            result = await fn(*args, **kwargs)
        except TmdbAuthError as exc:
            self._stopped = True
            self.report.add_error("tmdb_auth_failed")
            raise _Stop from exc
        except TmdbUnavailable:
            self._failures += 1
            if self._failures >= CIRCUIT_THRESHOLD and not self._circuit_open:
                self._circuit_open = True
                self.report.add_error("tmdb_circuit_open")
            raise
        self._failures = 0
        return result

    # -- reads --

    def _pending_items(self) -> list[_Snapshot]:
        snapshots: list[_Snapshot] = []
        with self.db.read_session() as session:
            for kind, model in _MODELS.items():
                items: list[m.Film | m.Show] = list(
                    session.scalars(
                        select(model).where(model.match_status == "pending").order_by(model.id)
                    )
                )
                for item in items:
                    snapshots.append(
                        _Snapshot(
                            kind,
                            item.id,
                            item.library_id,
                            item.parsed_title,
                            item.parsed_year,
                            item.match_status,
                            item.tmdb_id,
                        )
                    )
        return snapshots

    def _pending_seasons(self, show_id: int | None) -> list[_SeasonJob]:
        with self.db.read_session() as session:
            query = (
                select(m.Season.id, m.Season.show_id, m.Show.tmdb_id, m.Season.season_number)
                .join(m.Show, m.Show.id == m.Season.show_id)
                .where(
                    m.Season.metadata_fetched_at.is_(None),
                    m.Show.match_status == "matched",
                    m.Show.tmdb_id.is_not(None),
                )
                .order_by(m.Season.show_id, m.Season.season_number)
            )
            if show_id is not None:
                query = query.where(m.Season.show_id == show_id)
            return [
                _SeasonJob(season_id, show, show_tmdb_id, number)
                for season_id, show, show_tmdb_id, number in session.execute(query)
                if show_tmdb_id is not None  # excluded by the query; narrows the type
            ]

    # -- items --

    async def _enrich_item(self, snap: _Snapshot) -> None:
        assert self.client is not None
        try:
            decision = await self._search(snap)
            details = None
            if decision.tmdb_id is not None:
                fetch = self.client.movie_details if snap.kind == "film" else self.client.tv_details
                details = await self._call(fetch, decision.tmdb_id)
                if details is None:
                    decision = MatchDecision(
                        None, "no_tmdb_results", f"TMDB id {decision.tmdb_id} not found"
                    )
        except TmdbUnavailable:
            self.report.add_error(f"tmdb_unavailable: {snap.parsed_title}")
            return
        except _Stop:
            return
        except TmdbError as exc:
            logger.warning("tmdb_error: %s: %s", snap.parsed_title, exc)
            self.report.add_error(f"tmdb_error: {snap.parsed_title}")
            return
        if details is None:
            await asyncio.to_thread(self._apply_unmatched, snap, decision)
            return
        await self.prefetch(details)
        await asyncio.to_thread(self._apply_match, snap, details)

    async def _search(self, snap: _Snapshot) -> MatchDecision:
        assert self.client is not None
        search = self.client.search_movie if snap.kind == "film" else self.client.search_tv
        kind: Literal["movie", "tv"] = "movie" if snap.kind == "film" else "tv"
        primary = await self._call(search, snap.parsed_title, snap.parsed_year)
        decision = choose_match(snap.parsed_title, snap.parsed_year, primary, kind)
        fallback_language = self.client.fallback_language
        if (
            decision.tmdb_id is None
            and fallback_language
            and fallback_language != self.client.language
        ):
            secondary = await self._call(
                search, snap.parsed_title, snap.parsed_year, language=fallback_language
            )
            merged = merge_results(primary, secondary)
            decision = choose_match(snap.parsed_title, snap.parsed_year, merged, kind)
        return decision

    async def prefetch(self, details: dict[str, Any]) -> None:
        if self.artwork is None:
            return
        await self.artwork.prefetch(
            poster=tmdb_file_name(details.get("poster_path")),
            backdrop=tmdb_file_name(details.get("backdrop_path")),
        )

    def _is_stale(self, item: m.Film | m.Show | None, snap: _Snapshot) -> bool:
        return (
            item is None or item.match_status != snap.match_status or item.tmdb_id != snap.tmdb_id
        )

    def _apply_unmatched(self, snap: _Snapshot, decision: MatchDecision) -> None:
        with self.db.write_session() as session:
            item = _get_item(session, snap.kind, snap.id)
            if self._is_stale(item, snap):
                return
            assert item is not None
            item.match_status = "unmatched"
            owner = m.UnmatchedEntry.film_id if snap.kind == "film" else m.UnmatchedEntry.show_id
            entry = session.scalar(select(m.UnmatchedEntry).where(owner == item.id))
            if entry is None:
                entry = m.UnmatchedEntry(
                    film_id=item.id if snap.kind == "film" else None,
                    show_id=item.id if snap.kind == "show" else None,
                    reason=decision.reason,
                )
                session.add(entry)
                self.report.count("unmatched")
            entry.reason = decision.reason
            entry.detail = decision.detail
            self.report.save(session, self.run_id)

    def _apply_match(self, snap: _Snapshot, details: dict[str, Any]) -> int | None:
        with self.db.write_session() as session:
            item = _get_item(session, snap.kind, snap.id)
            if self._is_stale(item, snap):
                logger.info("enrich_discarded_stale: %s %d", snap.kind, snap.id)
                return None
            assert item is not None
            survivor = _duplicate(session, snap.kind, item, details["id"])
            if survivor is not None:
                return merge_items(session, snap.kind, item, survivor)
            write_details(session, snap.kind, item, details, self.settings.tmdb.cast_limit)
            return item.id

    # -- seasons --

    async def _enrich_season(self, job: _SeasonJob) -> None:
        assert self.client is not None
        try:
            data = await self._call(self.client.tv_season, job.show_tmdb_id, job.season_number)
        except TmdbUnavailable:
            self.report.add_error(
                f"tmdb_unavailable: season {job.season_number} of {job.show_tmdb_id}"
            )
            return
        except _Stop:
            return
        except TmdbError as exc:
            logger.warning("tmdb_error: season %s: %s", job, exc)
            return
        await asyncio.to_thread(self._apply_season, job, data)

    def _apply_season(self, job: _SeasonJob, data: dict[str, Any] | None) -> None:
        with self.db.write_session() as session:
            season = session.get(m.Season, job.season_id)
            show = session.get(m.Show, job.show_id)
            if season is None or show is None or season.show_id != show.id:
                return
            if show.tmdb_id != job.show_tmdb_id:
                return
            write_season(session, season, data)


# --- Manual re-match (admin service functions) ---------------------------------------------------


def _require_client(client: TmdbClient | None) -> TmdbClient:
    if client is None:
        raise ApiError(503, "tmdb_not_configured", "TMDB is not configured")
    return client


async def _fetch_details(client: TmdbClient, kind: ItemKind, tmdb_id: int) -> dict[str, Any]:
    try:
        if kind == "film":
            details = await client.movie_details(tmdb_id)
        else:
            details = await client.tv_details(tmdb_id)
    except TmdbError as exc:
        raise ApiError(502, "tmdb_unavailable", "TMDB is unavailable") from exc
    if details is None:
        raise ApiError(404, "tmdb_not_found", f"TMDB id {tmdb_id} not found")
    return details


def _not_found(kind: str, item_id: int) -> ApiError:
    return ApiError(404, "not_found", f"{kind.capitalize()} {item_id} not found")


async def match_item(
    db: Database,
    settings: Settings,
    client: TmdbClient | None,
    artwork: ArtworkCache | None,
    kind: ItemKind,
    item_id: int,
    tmdb_id: int,
) -> int:
    """``POST /api/admin/{films|shows}/{id}/match``: refetch everything; merge if the id is taken.

    Returns the surviving item id. Raises ``ApiError`` 404 ``not_found`` / 404 ``tmdb_not_found``
    / 502 ``tmdb_unavailable`` / 503 ``tmdb_not_configured``.
    """
    tmdb = _require_client(client)

    def exists() -> bool:
        with db.read_session() as session:
            return _get_item(session, kind, item_id) is not None

    if not await asyncio.to_thread(exists):
        raise _not_found(kind, item_id)
    details = await _fetch_details(tmdb, kind, tmdb_id)
    enricher = Enricher(db, settings, tmdb, artwork)
    await enricher.prefetch(details)

    def apply() -> int | None:
        with db.write_session() as session:
            item = _get_item(session, kind, item_id)
            if item is None:
                return None
            survivor = _duplicate(session, kind, item, tmdb_id)
            if survivor is not None:
                return merge_items(session, kind, item, survivor)
            write_details(session, kind, item, details, settings.tmdb.cast_limit)
            if kind == "show":
                session.execute(
                    update(m.Season)
                    .where(m.Season.show_id == item.id)
                    .values(metadata_fetched_at=None)
                )
            return item.id

    survivor_id = await asyncio.to_thread(apply)
    if survivor_id is None:
        raise _not_found(kind, item_id)
    if kind == "show":
        await enricher.refresh_show_seasons(survivor_id)
    return survivor_id


async def resolve_unmatched(
    db: Database,
    settings: Settings,
    client: TmdbClient | None,
    artwork: ArtworkCache | None,
    entry_id: int,
    tmdb_id: int,
    season_number: int | None = None,
    episode_numbers: list[int] | None = None,
) -> tuple[ItemKind, int]:
    """``POST /api/admin/unmatched/{id}/resolve``: link a media-file-level entry's file.

    Stores ``media_file.manual_match``, finds or creates the item by ``tmdb_id`` (created
    ``matched``), links the file (multi-part: all sibling parts) and deletes the parse-reason
    entries. Returns ``(kind, item_id)``. Raises ``ApiError`` 404 ``not_found``, 409
    ``not_a_file_entry`` / ``not_resolvable``, 422 ``validation_error``, 404 ``tmdb_not_found``,
    502 ``tmdb_unavailable``, 503 ``tmdb_not_configured``.
    """

    def read_entry() -> tuple[str, str]:
        with db.read_session() as session:
            entry = session.get(m.UnmatchedEntry, entry_id)
            if entry is None:
                raise ApiError(404, "not_found", f"Unmatched entry {entry_id} not found")
            if entry.media_file_id is None:
                raise ApiError(
                    409, "not_a_file_entry", "Entry targets a film or show; use the match endpoint"
                )
            if entry.reason == "probe_failed":
                raise ApiError(409, "not_resolvable", "The file could not be probed; fix the file")
            library_type = session.scalar(
                select(m.Library.type)
                .join(m.MediaFile, m.MediaFile.library_id == m.Library.id)
                .where(m.MediaFile.id == entry.media_file_id)
            )
            assert library_type is not None
            return entry.reason, library_type

    reason, library_type = await asyncio.to_thread(read_entry)
    kind: ItemKind = "film" if library_type == "films" else "show"
    episodes: list[int] = []
    if kind == "show":
        episodes = sorted(set(episode_numbers or []))
        if season_number is None or season_number < 0 or not episodes:
            raise ApiError(
                422,
                "validation_error",
                "season_number and episode_numbers are required for the shows library",
                {"errors": [{"loc": ["body", "season_number"], "msg": "required"}]},
            )
    tmdb = _require_client(client)
    details = await _fetch_details(tmdb, kind, tmdb_id)
    enricher = Enricher(db, settings, tmdb, artwork)
    await enricher.prefetch(details)

    def apply() -> int | None:
        with db.write_session() as session:
            entry = session.get(m.UnmatchedEntry, entry_id)
            if entry is None or entry.media_file_id is None:
                return None
            row = session.get(m.MediaFile, entry.media_file_id)
            if row is None:
                return None
            item = _find_or_create_matched(session, kind, row, details, settings.tmdb.cast_limit)
            files = [row]
            if kind == "film" and reason == "multi_part":
                files += _sibling_parts(session, row)
            for media_file in files:
                unlink_file(session, media_file)
                if kind == "film":
                    media_file.film_id = item.id
                    media_file.manual_match = {"tmdb_id": tmdb_id}
                else:
                    assert season_number is not None
                    link_episodes(session, media_file.id, item.id, season_number, episodes)
                    media_file.manual_match = {
                        "tmdb_id": tmdb_id,
                        "season": season_number,
                        "episodes": episodes,
                    }
                    assert isinstance(item, m.Show)
                    item.content_updated_at = m.utcnow()
                session.execute(
                    delete(m.UnmatchedEntry).where(
                        m.UnmatchedEntry.media_file_id == media_file.id,
                        m.UnmatchedEntry.reason.in_(PARSE_REASONS),
                    )
                )
            return item.id

    item_id = await asyncio.to_thread(apply)
    if item_id is None:
        raise ApiError(404, "not_found", f"Unmatched entry {entry_id} not found")
    if kind == "show":
        await enricher.refresh_show_seasons(item_id)
    return kind, item_id


def _find_or_create_matched(
    session: Session, kind: ItemKind, row: m.MediaFile, details: dict[str, Any], cast_limit: int
) -> m.Film | m.Show:
    model = _MODELS[kind]
    # SQLAlchemy types a select over a union of models as ``Base``.
    existing = cast(
        "m.Film | m.Show | None",
        session.scalar(
            select(model).where(model.library_id == row.library_id, model.tmdb_id == details["id"])
        ),
    )
    if existing is not None:
        return existing
    parsed = row.parsed or {}
    tmdb_title = details.get("title") if kind == "film" else details.get("name")
    title = parsed.get("title") or parsed.get("show_title") or tmdb_title or "?"
    year = parsed.get("year") if kind == "film" else parsed.get("show_year")
    item = model(
        library_id=row.library_id,
        match_status="pending",
        parsed_title=title,
        parsed_title_norm=normalize_title(title),
        parsed_year=year,
        title=tmdb_title or title,
        year=year,
        sort_title=sort_title(title),
        search_text=search_text(title, None),
    )
    session.add(item)
    session.flush()
    write_details(session, kind, item, details, cast_limit)
    return item


def _sibling_parts(session: Session, row: m.MediaFile) -> list[m.MediaFile]:
    """Other ``multi_part`` files in the same directory with the same parsed title/year."""
    directory = row.relative_path.rsplit("/", 1)[0] if "/" in row.relative_path else ""
    parsed = row.parsed or {}
    siblings: list[m.MediaFile] = []
    for other in session.scalars(
        select(m.MediaFile)
        .join(m.UnmatchedEntry, m.UnmatchedEntry.media_file_id == m.MediaFile.id)
        .where(
            m.UnmatchedEntry.reason == "multi_part",
            m.MediaFile.id != row.id,
            m.MediaFile.library_id == row.library_id,
            m.MediaFile.root_path == row.root_path,
        )
    ):
        other_dir = other.relative_path.rsplit("/", 1)[0] if "/" in other.relative_path else ""
        other_parsed = other.parsed or {}
        if other_dir == directory and (other_parsed.get("title"), other_parsed.get("year")) == (
            parsed.get("title"),
            parsed.get("year"),
        ):
            siblings.append(other)
    return siblings
