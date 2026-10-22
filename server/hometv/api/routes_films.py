"""Films: GET /api/films (paginated FilmSummary), GET /api/films/{id} (FilmDetail)."""

from typing import Annotated

from fastapi import APIRouter, Depends

from hometv import catalog
from hometv.api.deps import CurrentProfile, ReadSession, SettingsDep
from hometv.api.list_params import list_params
from hometv.api.schemas import ErrorResponse, FilmDetail, FilmSummary, Page

router = APIRouter(
    prefix="/api",
    tags=["catalog"],
    responses={401: {"model": ErrorResponse, "description": "unauthorized"}},
)


@router.get("/films", response_model=Page[FilmSummary])
def list_films(
    profile: CurrentProfile,
    session: ReadSession,
    params: Annotated[catalog.ListParams, Depends(list_params)],
) -> Page[FilmSummary]:
    return catalog.list_films(session, profile.id, params)


@router.get(
    "/films/{film_id}",
    response_model=FilmDetail,
    responses={404: {"model": ErrorResponse, "description": "not_found"}},
)
def get_film(
    film_id: int, profile: CurrentProfile, session: ReadSession, settings: SettingsDep
) -> FilmDetail:
    return catalog.film_detail(session, profile.id, film_id, settings.tmdb.cast_limit)
