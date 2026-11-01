""" "Mi lista": PUT/DELETE /api/watchlist/{films|shows}/{id}, GET /api/watchlist (FR-8;
``type``, ``sort``, ``watched=true|false`` (the library rule), ``page``, ``page_size``).

Profile Bearer only (no ``?token=``, no admin token, no PIN); the profile comes from the token.
Writes are idempotent and answer 204 with an empty body; unknown or hidden titles are 404.
"""

from typing import Annotated, Any

from fastapi import APIRouter, Query, Response

from hometv import watchlist
from hometv.api.deps import CurrentProfile, Db, ReadSession
from hometv.api.list_params import PageNumber, WatchedParam, watched_flag
from hometv.api.schemas import ErrorResponse, Page, WatchlistEntry
from hometv.watchlist import WatchlistParams, WatchlistSort, WatchlistType

router = APIRouter(
    prefix="/api",
    tags=["watchlist"],
    responses={401: {"model": ErrorResponse, "description": "unauthorized"}},
)

_NOT_FOUND: dict[int | str, dict[str, Any]] = {
    404: {"model": ErrorResponse, "description": "not_found (unknown or hidden title)"}
}


@router.get("/watchlist", response_model=Page[WatchlistEntry])
def get_watchlist(
    profile: CurrentProfile,
    session: ReadSession,
    kind: Annotated[WatchlistType, Query(alias="type")] = "all",
    sort: WatchlistSort = "added",
    watched: WatchedParam = None,
    page: PageNumber = 1,
    page_size: Annotated[int, Query(ge=1, le=200)] = 50,
) -> Page[WatchlistEntry]:
    params = WatchlistParams(
        type=kind, sort=sort, watched=watched_flag(watched), page=page, page_size=page_size
    )
    return watchlist.list_entries(session, profile.id, params)


@router.put(
    "/watchlist/films/{film_id}", status_code=204, response_class=Response, responses=_NOT_FOUND
)
def put_film(film_id: int, profile: CurrentProfile, db: Db) -> Response:
    watchlist.add(db, profile.id, "film", film_id)
    return Response(status_code=204)


@router.delete(
    "/watchlist/films/{film_id}", status_code=204, response_class=Response, responses=_NOT_FOUND
)
def delete_film(film_id: int, profile: CurrentProfile, db: Db) -> Response:
    watchlist.remove(db, profile.id, "film", film_id)
    return Response(status_code=204)


@router.put(
    "/watchlist/shows/{show_id}", status_code=204, response_class=Response, responses=_NOT_FOUND
)
def put_show(show_id: int, profile: CurrentProfile, db: Db) -> Response:
    watchlist.add(db, profile.id, "show", show_id)
    return Response(status_code=204)


@router.delete(
    "/watchlist/shows/{show_id}", status_code=204, response_class=Response, responses=_NOT_FOUND
)
def delete_show(show_id: int, profile: CurrentProfile, db: Db) -> Response:
    watchlist.remove(db, profile.id, "show", show_id)
    return Response(status_code=204)
