"""Admin service functions, called by the ``/api/admin/*`` routes.

- ``list_unmatched(session, ...)`` / ``unmatched_count(session)``: sync, for ``def`` handlers
  with a ``ReadSession``. Entries of absent files (``missing_since`` set) are excluded.
- ``TmdbProxy``: ``search`` / ``tv_seasons`` / ``tv_season`` (season lookups cached in memory
  10 min, LRU 100). Raise ``ApiError`` 404 ``tmdb_not_found`` / 422 ``validation_error`` /
  502 ``tmdb_unavailable`` / 503 ``tmdb_not_configured``.

Scan status lives in ``scheduler.py``; match/resolve in ``metadata/enrich.py``.
"""

import time
from collections import OrderedDict
from collections.abc import Callable
from typing import Any, Literal

from sqlalchemy import Select, func, or_, select
from sqlalchemy.orm import Session

from hometv.api.errors import ApiError
from hometv.api.schemas import (
    Page,
    TmdbEpisodeSummary,
    TmdbSearchResponse,
    TmdbSearchResult,
    TmdbSeasonResponse,
    TmdbSeasonsResponse,
    TmdbSeasonSummary,
    UnmatchedEntry,
)
from hometv.db import models as m
from hometv.metadata.artwork import image, tmdb_file_name
from hometv.metadata.matching import result_year
from hometv.metadata.tmdb import TmdbClient, TmdbError
from hometv.text import str_or_none

SEASON_CACHE_TTL_SECONDS = 600.0
SEASON_CACHE_MAX_ENTRIES = 100
SEARCH_MAX_RESULTS = 20


# --- Unmatched -----------------------------------------------------------------------------------


def _unmatched_base() -> Select[m.UnmatchedEntry, m.MediaFile, m.Film, m.Show, str]:
    library_id = func.coalesce(m.MediaFile.library_id, m.Film.library_id, m.Show.library_id)
    return (
        select(m.UnmatchedEntry, m.MediaFile, m.Film, m.Show, m.Library.type)
        .outerjoin(m.MediaFile, m.MediaFile.id == m.UnmatchedEntry.media_file_id)
        .outerjoin(m.Film, m.Film.id == m.UnmatchedEntry.film_id)
        .outerjoin(m.Show, m.Show.id == m.UnmatchedEntry.show_id)
        .join(m.Library, m.Library.id == library_id)
        .where(or_(m.UnmatchedEntry.media_file_id.is_(None), m.MediaFile.missing_since.is_(None)))
    )


def unmatched_count(session: Session) -> int:
    """Live total of unmatched entries (all libraries), as in ScanStatus.unmatched_count."""
    return session.scalar(select(func.count()).select_from(_unmatched_base().subquery())) or 0


def _shows_parsed(session: Session, show_ids: list[int]) -> dict[int, dict[str, Any]]:
    """Each show's earliest present episode parse (``added_at``, ``id``), in one query."""
    if not show_ids:
        return {}
    rank = (
        func.row_number()
        .over(
            partition_by=m.Episode.show_id,
            order_by=(m.MediaFile.added_at, m.MediaFile.id),
        )
        .label("rank")
    )
    ranked = (
        select(m.Episode.show_id.label("show_id"), m.MediaFile.parsed.label("parsed"), rank)
        .join(m.MediaFileEpisode, m.MediaFileEpisode.episode_id == m.Episode.id)
        .join(m.MediaFile, m.MediaFile.id == m.MediaFileEpisode.media_file_id)
        .where(m.Episode.show_id.in_(show_ids), m.MediaFile.missing_since.is_(None))
        .subquery()
    )
    rows = session.execute(
        select(ranked.c.show_id, ranked.c.parsed).where(ranked.c.rank == 1)
    ).all()
    return {
        show_id: parsed
        for show_id, parsed in rows
        if isinstance(parsed, dict) and parsed.get("kind") == "episode"
    }


SHOW_FILES_SHOWN = 3


def _films_files(session: Session, film_ids: list[int]) -> dict[int, tuple[list[str], int]]:
    """Each film's present files (all, by ``relative_path``) and their count, in one query."""
    if not film_ids:
        return {}
    total = func.count().over(partition_by=m.MediaFile.film_id)
    rows = session.execute(
        select(m.MediaFile.film_id, m.MediaFile.relative_path, total)
        .where(m.MediaFile.film_id.in_(film_ids), m.MediaFile.missing_since.is_(None))
        .order_by(m.MediaFile.film_id, m.MediaFile.relative_path, m.MediaFile.id)
    ).all()
    result: dict[int, tuple[list[str], int]] = {}
    for film_id, relative_path, count in rows:
        assert film_id is not None
        result.setdefault(film_id, ([], count))[0].append(relative_path)
    return result


def _shows_files(session: Session, show_ids: list[int]) -> dict[int, tuple[list[str], int]]:
    """Each show's first ``SHOW_FILES_SHOWN`` present episode files (distinct, by path) + the
    count of all of them, in one query."""
    if not show_ids:
        return {}
    distinct = (
        select(m.Episode.show_id.label("show_id"), m.MediaFile.id, m.MediaFile.relative_path)
        .join(m.MediaFileEpisode, m.MediaFileEpisode.episode_id == m.Episode.id)
        .join(m.MediaFile, m.MediaFile.id == m.MediaFileEpisode.media_file_id)
        .where(m.Episode.show_id.in_(show_ids), m.MediaFile.missing_since.is_(None))
        .distinct()
        .subquery()
    )
    rank = (
        func.row_number()
        .over(partition_by=distinct.c.show_id, order_by=(distinct.c.relative_path, distinct.c.id))
        .label("rank")
    )
    total = func.count().over(partition_by=distinct.c.show_id).label("total")
    ranked = select(distinct.c.show_id, distinct.c.relative_path, rank, total).subquery()
    rows = session.execute(
        select(ranked.c.show_id, ranked.c.relative_path, ranked.c.total)
        .where(ranked.c.rank <= SHOW_FILES_SHOWN)
        .order_by(ranked.c.show_id, ranked.c.rank)
    ).all()
    result: dict[int, tuple[list[str], int]] = {}
    for show_id, relative_path, count in rows:
        result.setdefault(show_id, ([], count))[0].append(relative_path)
    return result


def list_unmatched(
    session: Session,
    *,
    library: Literal["films", "shows"] | None = None,
    reason: str | None = None,
    page: int = 1,
    page_size: int = 50,
) -> Page[UnmatchedEntry]:
    query = _unmatched_base()
    if library is not None:
        query = query.where(m.Library.type == library)
    if reason is not None:
        query = query.where(m.UnmatchedEntry.reason == reason)
    total = session.scalar(select(func.count()).select_from(query.subquery())) or 0
    rows = session.execute(
        query.order_by(m.UnmatchedEntry.created_at.desc(), m.UnmatchedEntry.id.desc())
        .offset((page - 1) * page_size)
        .limit(page_size)
    ).all()
    show_parses = _shows_parsed(
        session,
        [show.id for _e, media_file, film, show, _l in rows if media_file is None and film is None],
    )
    film_files = _films_files(
        session, [film.id for _e, media_file, film, _s, _l in rows if media_file is None and film]
    )
    show_files = _shows_files(
        session,
        [show.id for _e, media_file, film, show, _l in rows if media_file is None and film is None],
    )
    items: list[UnmatchedEntry] = []
    for entry, media_file, film, show, library_type in rows:
        target: dict[str, Any]
        if media_file is not None:
            target = {
                "type": "media_file",
                "id": media_file.id,
                "relative_path": media_file.relative_path,
            }
            parsed = media_file.parsed
            files, files_total = [media_file.relative_path], 1
        elif film is not None:
            target = {"type": "film", "id": film.id, "title": film.title, "year": film.year}
            parsed = {
                "kind": "film",
                "title": film.parsed_title,
                "year": film.parsed_year,
                "part": None,
            }
            files, files_total = film_files.get(film.id, ([], 0))
        else:
            target = {"type": "show", "id": show.id, "title": show.title, "year": show.year}
            parsed = show_parses.get(show.id)
            files, files_total = show_files.get(show.id, ([], 0))
        items.append(
            UnmatchedEntry.model_validate(
                {
                    "id": entry.id,
                    "library": library_type,
                    "reason": entry.reason,
                    "detail": entry.detail,
                    "target": target,
                    "parsed": parsed,
                    "files": files,
                    "files_total": files_total,
                    "created_at": entry.created_at,
                }
            )
        )
    return Page[UnmatchedEntry](items=items, page=page, page_size=page_size, total=total)


# --- TMDB proxies --------------------------------------------------------------------------------


class _TtlLru:
    def __init__(self, ttl: float, max_entries: int, clock: Callable[[], float]) -> None:
        self.ttl = ttl
        self.max_entries = max_entries
        self.clock = clock
        self._data: OrderedDict[Any, tuple[float, Any]] = OrderedDict()

    def get(self, key: Any) -> Any | None:
        hit = self._data.get(key)
        if hit is None:
            return None
        stored_at, value = hit
        if self.clock() - stored_at > self.ttl:
            del self._data[key]
            return None
        self._data.move_to_end(key)
        return value

    def put(self, key: Any, value: Any) -> None:
        self._data[key] = (self.clock(), value)
        self._data.move_to_end(key)
        while len(self._data) > self.max_entries:
            self._data.popitem(last=False)


class TmdbProxy:
    """Thin admin proxies over ``TmdbClient`` (``client=None`` -> 503 tmdb_not_configured)."""

    def __init__(
        self, client: TmdbClient | None, *, clock: Callable[[], float] = time.monotonic
    ) -> None:
        self.client = client
        self._cache = _TtlLru(SEASON_CACHE_TTL_SECONDS, SEASON_CACHE_MAX_ENTRIES, clock)

    def _require(self) -> TmdbClient:
        if self.client is None:
            raise ApiError(503, "tmdb_not_configured", "TMDB is not configured")
        return self.client

    async def search(
        self, type_: Literal["movie", "tv"], q: str, year: int | None
    ) -> TmdbSearchResponse:
        client = self._require()
        try:
            if type_ == "movie":
                raw = await client.search_movie(q, year)
            else:
                raw = await client.search_tv(q, year)
        except TmdbError as exc:
            raise ApiError(502, "tmdb_unavailable", "TMDB is unavailable") from exc
        title_key, original_key = (
            ("title", "original_title") if type_ == "movie" else ("name", "original_name")
        )
        results = [
            TmdbSearchResult(
                tmdb_id=r["id"],
                title=str_or_none(r.get(title_key)) or str_or_none(r.get(original_key)) or "",
                original_title=str_or_none(r.get(original_key)),
                year=result_year(r, type_),
                overview=str_or_none(r.get("overview")),
                poster=image("poster", tmdb_file_name(r.get("poster_path"))),
            )
            for r in raw[:SEARCH_MAX_RESULTS]
            if isinstance(r.get("id"), int)
        ]
        return TmdbSearchResponse(results=results)

    async def tv_seasons(self, tmdb_id: int) -> TmdbSeasonsResponse:
        key = ("seasons", tmdb_id)
        cached = self._cache.get(key)
        if cached is not None:
            return cached
        client = self._require()
        try:
            data = await client.tv_details(tmdb_id)
        except TmdbError as exc:
            raise ApiError(502, "tmdb_unavailable", "TMDB is unavailable") from exc
        if data is None:
            raise ApiError(404, "tmdb_not_found", f"TMDB show {tmdb_id} not found")
        seasons = [
            TmdbSeasonSummary(
                season_number=s["season_number"],
                name=str_or_none(s.get("name")),
                episode_count=int(s.get("episode_count") or 0),
                air_date=str_or_none(s.get("air_date")),
            )
            for s in data.get("seasons") or []
            if isinstance(s.get("season_number"), int)
        ]
        seasons.sort(key=lambda s: (s.season_number == 0, s.season_number))
        response = TmdbSeasonsResponse(
            tmdb_id=tmdb_id,
            name=str_or_none(data.get("name")) or str_or_none(data.get("original_name")) or "",
            year=result_year(data, "tv"),
            seasons=seasons,
        )
        self._cache.put(key, response)
        return response

    async def tv_season(self, tmdb_id: int, season_number: int) -> TmdbSeasonResponse:
        if season_number < 0:
            raise ApiError(
                422,
                "validation_error",
                "Request validation failed",
                {"errors": [{"loc": ["path", "season_number"], "msg": "must be >= 0"}]},
            )
        key = ("season", tmdb_id, season_number)
        cached = self._cache.get(key)
        if cached is not None:
            return cached
        client = self._require()
        try:
            data = await client.tv_season(tmdb_id, season_number)
        except TmdbError as exc:
            raise ApiError(502, "tmdb_unavailable", "TMDB is unavailable") from exc
        if data is None:
            raise ApiError(
                404, "tmdb_not_found", f"TMDB show {tmdb_id} season {season_number} not found"
            )
        episodes = [
            TmdbEpisodeSummary(
                episode_number=e["episode_number"],
                title=str_or_none(e.get("name")),
                air_date=str_or_none(e.get("air_date")),
            )
            for e in data.get("episodes") or []
            if isinstance(e.get("episode_number"), int)
        ]
        episodes.sort(key=lambda e: e.episode_number)
        response = TmdbSeasonResponse(
            tmdb_id=tmdb_id,
            season_number=season_number,
            name=str_or_none(data.get("name")),
            episodes=episodes,
        )
        self._cache.put(key, response)
        return response
