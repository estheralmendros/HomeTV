"""Catalog: GET /api/home, /api/search, /api/genres."""

from typing import Annotated, Literal

from fastapi import APIRouter, Query

from hometv import catalog
from hometv.api.deps import CurrentProfile, ReadSession
from hometv.api.schemas import ErrorResponse, GenresResponse, HomeResponse, SearchResponse

router = APIRouter(
    prefix="/api",
    tags=["catalog"],
    responses={401: {"model": ErrorResponse, "description": "unauthorized"}},
)
Limit = Annotated[int, Query(ge=1, le=50)]


@router.get("/home", response_model=HomeResponse)
def get_home(profile: CurrentProfile, session: ReadSession, limit: Limit = 20) -> HomeResponse:
    return catalog.home(session, profile.id, limit)


@router.get("/search", response_model=SearchResponse)
def search(
    profile: CurrentProfile,
    session: ReadSession,
    q: Annotated[str, Query(min_length=1, max_length=100)],
    limit: Limit = 20,
) -> SearchResponse:
    films, shows = catalog.search(session, profile.id, q, limit)
    return SearchResponse(films=films, shows=shows)


@router.get("/genres", response_model=GenresResponse)
def get_genres(
    profile: CurrentProfile, session: ReadSession, type: Literal["films", "shows"]
) -> GenresResponse:
    return GenresResponse(items=catalog.genres(session, type))
