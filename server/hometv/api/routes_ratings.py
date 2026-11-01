""" "Puntuar" and "Actividad": PUT/DELETE /api/ratings/{films|shows}/{id},
POST /api/ratings/{films|shows}/{id}/dismiss-prompt, GET /api/activity (ratings PRD FR-12).

Profile Bearer only (no ``?token=``, no admin token, no PIN); every write acts on the token's
profile. Writes are idempotent and answer 204 with an empty body; unknown or hidden titles are
404; a profile deleted between auth and the write is 401. Reads are household-wide.
"""

from typing import Annotated, Any

from fastapi import APIRouter, Query, Response

from hometv import ratings
from hometv.api.deps import CurrentProfile, Db, ReadSession
from hometv.api.list_params import PageNumber
from hometv.api.schemas import ActivityEntry, ErrorResponse, Page, RatingRequest, stars_to_x2

router = APIRouter(
    prefix="/api",
    tags=["ratings"],
    responses={401: {"model": ErrorResponse, "description": "unauthorized"}},
)

_NOT_FOUND: dict[int | str, dict[str, Any]] = {
    404: {"model": ErrorResponse, "description": "not_found (unknown or hidden title)"}
}


@router.get("/activity", response_model=Page[ActivityEntry])
def get_activity(
    profile: CurrentProfile,
    session: ReadSession,
    page: PageNumber = 1,
    page_size: Annotated[int, Query(ge=1, le=200)] = 50,
) -> Page[ActivityEntry]:
    return ratings.list_activity(session, profile.id, page, page_size)


@router.put(
    "/ratings/films/{film_id}", status_code=204, response_class=Response, responses=_NOT_FOUND
)
def put_film(film_id: int, body: RatingRequest, profile: CurrentProfile, db: Db) -> Response:
    ratings.set_rating(db, profile.id, "film", film_id, stars_to_x2(body.stars))
    return Response(status_code=204)


@router.delete(
    "/ratings/films/{film_id}", status_code=204, response_class=Response, responses=_NOT_FOUND
)
def delete_film(film_id: int, profile: CurrentProfile, db: Db) -> Response:
    ratings.remove_rating(db, profile.id, "film", film_id)
    return Response(status_code=204)


@router.post(
    "/ratings/films/{film_id}/dismiss-prompt",
    status_code=204,
    response_class=Response,
    responses=_NOT_FOUND,
)
def dismiss_film(film_id: int, profile: CurrentProfile, db: Db) -> Response:
    ratings.dismiss_prompt(db, profile.id, "film", film_id)
    return Response(status_code=204)


@router.put(
    "/ratings/shows/{show_id}", status_code=204, response_class=Response, responses=_NOT_FOUND
)
def put_show(show_id: int, body: RatingRequest, profile: CurrentProfile, db: Db) -> Response:
    ratings.set_rating(db, profile.id, "show", show_id, stars_to_x2(body.stars))
    return Response(status_code=204)


@router.delete(
    "/ratings/shows/{show_id}", status_code=204, response_class=Response, responses=_NOT_FOUND
)
def delete_show(show_id: int, profile: CurrentProfile, db: Db) -> Response:
    ratings.remove_rating(db, profile.id, "show", show_id)
    return Response(status_code=204)


@router.post(
    "/ratings/shows/{show_id}/dismiss-prompt",
    status_code=204,
    response_class=Response,
    responses=_NOT_FOUND,
)
def dismiss_show(show_id: int, profile: CurrentProfile, db: Db) -> Response:
    ratings.dismiss_prompt(db, profile.id, "show", show_id)
    return Response(status_code=204)
