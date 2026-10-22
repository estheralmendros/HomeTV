"""Shows: list, detail, season detail, episode detail."""

from typing import Annotated

from fastapi import APIRouter, Depends, Path

from hometv import catalog
from hometv.api.deps import CurrentProfile, ReadSession, SettingsDep
from hometv.api.list_params import list_params
from hometv.api.schemas import (
    EpisodeDetail,
    ErrorResponse,
    Page,
    SeasonDetail,
    ShowDetail,
    ShowSummary,
)

router = APIRouter(
    prefix="/api",
    tags=["catalog"],
    responses={401: {"model": ErrorResponse, "description": "unauthorized"}},
)
_NOT_FOUND = {404: {"model": ErrorResponse, "description": "not_found"}}


@router.get("/shows", response_model=Page[ShowSummary])
def list_shows(
    profile: CurrentProfile,
    session: ReadSession,
    params: Annotated[catalog.ListParams, Depends(list_params)],
) -> Page[ShowSummary]:
    return catalog.list_shows(session, profile.id, params)


@router.get("/shows/{show_id}", response_model=ShowDetail, responses=_NOT_FOUND)
def get_show(
    show_id: int, profile: CurrentProfile, session: ReadSession, settings: SettingsDep
) -> ShowDetail:
    return catalog.show_detail(session, profile.id, show_id, settings.tmdb.cast_limit)


@router.get(
    "/shows/{show_id}/seasons/{season_number}", response_model=SeasonDetail, responses=_NOT_FOUND
)
def get_season(
    show_id: int,
    season_number: Annotated[int, Path(ge=0)],
    profile: CurrentProfile,
    session: ReadSession,
) -> SeasonDetail:
    return catalog.season_detail(session, profile.id, show_id, season_number)


@router.get("/episodes/{episode_id}", response_model=EpisodeDetail, responses=_NOT_FOUND)
def get_episode(episode_id: int, profile: CurrentProfile, session: ReadSession) -> EpisodeDetail:
    return catalog.episode_detail(session, profile.id, episode_id)
