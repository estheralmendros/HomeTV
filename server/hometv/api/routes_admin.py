"""/api/admin/*: unlock (admin-profile) and the ``admin`` endpoints (PRD Admin, §8).

Every route of ``router`` requires ``AdminUser`` (router-level dependency): valid profile token
(401 ``unauthorized``), ``is_admin`` (403 ``forbidden``), valid ``X-Admin-Token`` (401
``admin_locked``). ``/api/admin/unlock`` lives on ``unlock_router`` (no admin token).
"""

import asyncio
from typing import Annotated, Literal

from fastapi import APIRouter, Depends, Path, Query, Response

from hometv import admin as admin_service
from hometv import catalog, profiles
from hometv.api.deps import (
    AdminProfile,
    AdminUser,
    ArtworkDep,
    Db,
    LimiterDep,
    ReadSession,
    SchedulerDep,
    SettingsDep,
    TmdbDep,
    TmdbProxyDep,
    TokensDep,
    require_admin,
)
from hometv.api.errors import ApiError
from hometv.api.routes_profiles import profile_out
from hometv.api.schemas import (
    ErrorResponse,
    FilmDetail,
    MatchRequest,
    Page,
    Profile,
    ProfileCreateRequest,
    ProfileUpdateRequest,
    ResolveRequest,
    ResolveResponse,
    ResolveTarget,
    Responses,
    ScanStatus,
    ShowDetail,
    TmdbSearchResponse,
    TmdbSeasonResponse,
    TmdbSeasonsResponse,
    UnlockRequest,
    UnlockResponse,
    UnmatchedEntry,
)
from hometv.db.models import UNMATCHED_REASONS
from hometv.metadata import enrich
from hometv.profiles import verify_pin

_AUTH_ERRORS: Responses = {
    401: {"model": ErrorResponse, "description": "unauthorized | admin_locked"},
    403: {"model": ErrorResponse, "description": "forbidden (profile is not admin)"},
}

unlock_router = APIRouter(prefix="/api/admin", tags=["admin"])
router = APIRouter(
    prefix="/api/admin",
    tags=["admin"],
    dependencies=[Depends(require_admin)],
    responses=_AUTH_ERRORS,
)


# --- Unlock --------------------------------------------------------------------------------------


@unlock_router.post(
    "/unlock",
    response_model=UnlockResponse,
    responses={
        401: {"model": ErrorResponse, "description": "unauthorized | invalid_pin"},
        403: {"model": ErrorResponse, "description": "forbidden (profile is not admin)"},
        429: {"model": ErrorResponse, "description": "too_many_attempts (Retry-After)"},
    },
)
async def unlock(
    body: UnlockRequest, profile: AdminProfile, tokens: TokensDep, limiter: LimiterDep
) -> UnlockResponse:
    # Check and reserve without an await in between (the attempt counts as a failure until the
    # PIN verifies), so parallel requests cannot all slip past the limiter.
    retry_after = limiter.acquire(profile.id)
    if retry_after is not None:
        raise ApiError(
            429,
            "too_many_attempts",
            "Too many failed PIN attempts",
            {"retry_after_seconds": retry_after},
            headers={"Retry-After": str(retry_after)},
        )
    # scrypt runs in a worker thread so the event loop stays free.
    if not await asyncio.to_thread(verify_pin, body.pin, profile.pin_hash):
        raise ApiError(401, "invalid_pin", "Invalid PIN")
    limiter.reset(profile.id)
    issued = tokens.issue_admin_token(profile.id, profile.admin_token_version)
    return UnlockResponse(admin_token=issued.token, expires_at=issued.expires_at)


# --- Scan ----------------------------------------------------------------------------------------


@router.post(
    "/scan",
    status_code=202,
    response_model=ScanStatus,
    responses={409: {"model": ErrorResponse, "description": "scan_in_progress (details.scan)"}},
)
async def start_scan(scheduler: SchedulerDep) -> ScanStatus:
    return await scheduler.trigger("manual")


@router.get("/scan", response_model=ScanStatus)
def scan_status(scheduler: SchedulerDep) -> ScanStatus:
    return scheduler.status()


# --- Unmatched -----------------------------------------------------------------------------------

Reason = Literal[UNMATCHED_REASONS]  # type: ignore[valid-type]


@router.get("/unmatched", response_model=Page[UnmatchedEntry])
def list_unmatched(
    session: ReadSession,
    library: Literal["films", "shows"] | None = None,
    reason: Reason | None = None,
    page: Annotated[int, Query(ge=1)] = 1,
    page_size: Annotated[int, Query(ge=1, le=200)] = 50,
) -> Page[UnmatchedEntry]:
    return admin_service.list_unmatched(
        session, library=library, reason=reason, page=page, page_size=page_size
    )


@router.post(
    "/unmatched/{entry_id}/resolve",
    response_model=ResolveResponse,
    responses={
        404: {"model": ErrorResponse, "description": "not_found | tmdb_not_found"},
        409: {"model": ErrorResponse, "description": "not_a_file_entry | not_resolvable"},
        502: {"model": ErrorResponse, "description": "tmdb_unavailable"},
        503: {"model": ErrorResponse, "description": "tmdb_not_configured"},
    },
)
async def resolve_unmatched(
    entry_id: int,
    body: ResolveRequest,
    db: Db,
    settings: SettingsDep,
    tmdb: TmdbDep,
    artwork: ArtworkDep,
) -> ResolveResponse:
    kind, item_id = await enrich.resolve_unmatched(
        db,
        settings,
        tmdb,
        artwork,
        entry_id,
        body.tmdb_id,
        season_number=body.season_number,
        episode_numbers=body.episode_numbers,
    )
    return ResolveResponse(target=ResolveTarget(type=kind, id=item_id))


# --- TMDB proxies --------------------------------------------------------------------------------

_TMDB_ERRORS: Responses = {
    404: {"model": ErrorResponse, "description": "tmdb_not_found"},
    502: {"model": ErrorResponse, "description": "tmdb_unavailable"},
    503: {"model": ErrorResponse, "description": "tmdb_not_configured"},
}


@router.get("/tmdb/search", response_model=TmdbSearchResponse, responses=_TMDB_ERRORS)
async def tmdb_search(
    proxy: TmdbProxyDep,
    type: Literal["movie", "tv"],
    q: Annotated[str, Query(min_length=1, max_length=100)],
    year: Annotated[int | None, Query(ge=1800, le=2200)] = None,
) -> TmdbSearchResponse:
    return await proxy.search(type, q, year)


@router.get(
    "/tmdb/tv/{tmdb_id}/seasons", response_model=TmdbSeasonsResponse, responses=_TMDB_ERRORS
)
async def tmdb_tv_seasons(
    proxy: TmdbProxyDep, tmdb_id: Annotated[int, Path(gt=0)]
) -> TmdbSeasonsResponse:
    return await proxy.tv_seasons(tmdb_id)


@router.get(
    "/tmdb/tv/{tmdb_id}/seasons/{season_number}",
    response_model=TmdbSeasonResponse,
    responses=_TMDB_ERRORS,
)
async def tmdb_tv_season(
    proxy: TmdbProxyDep,
    tmdb_id: Annotated[int, Path(gt=0)],
    season_number: Annotated[int, Path(ge=0)],
) -> TmdbSeasonResponse:
    return await proxy.tv_season(tmdb_id, season_number)


# --- Manual match --------------------------------------------------------------------------------

_MATCH_ERRORS: Responses = {
    404: {"model": ErrorResponse, "description": "not_found | tmdb_not_found"},
    502: {"model": ErrorResponse, "description": "tmdb_unavailable"},
    503: {"model": ErrorResponse, "description": "tmdb_not_configured"},
}


@router.post("/films/{film_id}/match", response_model=FilmDetail, responses=_MATCH_ERRORS)
async def match_film(
    film_id: int,
    body: MatchRequest,
    profile: AdminUser,
    db: Db,
    settings: SettingsDep,
    tmdb: TmdbDep,
    artwork: ArtworkDep,
) -> FilmDetail:
    survivor = await enrich.match_item(db, settings, tmdb, artwork, "film", film_id, body.tmdb_id)

    def detail() -> FilmDetail:
        with db.read_session() as session:
            return catalog.film_detail(session, profile.id, survivor, settings.tmdb.cast_limit)

    return await asyncio.to_thread(detail)


@router.post("/shows/{show_id}/match", response_model=ShowDetail, responses=_MATCH_ERRORS)
async def match_show(
    show_id: int,
    body: MatchRequest,
    profile: AdminUser,
    db: Db,
    settings: SettingsDep,
    tmdb: TmdbDep,
    artwork: ArtworkDep,
) -> ShowDetail:
    survivor = await enrich.match_item(db, settings, tmdb, artwork, "show", show_id, body.tmdb_id)

    def detail() -> ShowDetail:
        with db.read_session() as session:
            return catalog.show_detail(session, profile.id, survivor, settings.tmdb.cast_limit)

    return await asyncio.to_thread(detail)


# --- Profiles CRUD -------------------------------------------------------------------------------


@router.post(
    "/profiles",
    status_code=201,
    response_model=Profile,
    responses={409: {"model": ErrorResponse, "description": "name_taken | profile_limit_reached"}},
)
async def create_profile(body: ProfileCreateRequest, db: Db) -> Profile:
    profile = await asyncio.to_thread(
        profiles.create_profile,
        db,
        name=body.name,
        avatar_id=body.avatar_id,
        is_admin=body.is_admin,
        pin=body.pin,
    )
    return profile_out(profile)


@router.patch(
    "/profiles/{profile_id}",
    response_model=Profile,
    responses={
        404: {"model": ErrorResponse, "description": "not_found"},
        409: {"model": ErrorResponse, "description": "name_taken | last_admin | conflict"},
    },
)
async def update_profile(profile_id: int, body: ProfileUpdateRequest, db: Db) -> Profile:
    profile = await asyncio.to_thread(profiles.update_profile, db, profile_id, body.changes())
    return profile_out(profile)


@router.delete(
    "/profiles/{profile_id}",
    status_code=204,
    response_class=Response,
    responses={
        404: {"model": ErrorResponse, "description": "not_found"},
        409: {"model": ErrorResponse, "description": "last_admin"},
    },
)
def delete_profile(profile_id: int, db: Db) -> Response:
    profiles.delete_profile(db, profile_id)
    return Response(status_code=204)
