"""Remembered audio/subtitle choice: PUT /api/track-choices/{films|shows}/{id}.

Profile Bearer only (no ``?token=``, no admin token, no PIN); the write acts on the token's
profile and replaces its whole choice for the title. Answers 204 with an empty body; unknown or
hidden titles are 404; a profile deleted between auth and the write is 401. The choice is read
back on film detail, episode detail and the "Seguir viendo" episodes of /api/home.
"""

from typing import Any

from fastapi import APIRouter, Response

from hometv import track_choices
from hometv.api.deps import CurrentProfile, Db
from hometv.api.schemas import ErrorResponse, TrackChoice

router = APIRouter(
    prefix="/api",
    tags=["track-choices"],
    responses={401: {"model": ErrorResponse, "description": "unauthorized"}},
)

_NOT_FOUND: dict[int | str, dict[str, Any]] = {
    404: {"model": ErrorResponse, "description": "not_found (unknown or hidden title)"}
}


@router.put(
    "/track-choices/films/{film_id}",
    status_code=204,
    response_class=Response,
    responses=_NOT_FOUND,
)
def put_film(film_id: int, body: TrackChoice, profile: CurrentProfile, db: Db) -> Response:
    track_choices.set_track_choice(db, profile.id, "film", film_id, body)
    return Response(status_code=204)


@router.put(
    "/track-choices/shows/{show_id}",
    status_code=204,
    response_class=Response,
    responses=_NOT_FOUND,
)
def put_show(show_id: int, body: TrackChoice, profile: CurrentProfile, db: Db) -> Response:
    track_choices.set_track_choice(db, profile.id, "show", show_id, body)
    return Response(status_code=204)
