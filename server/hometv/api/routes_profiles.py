"""Profiles: /api/profiles (public list), bootstrap, session (no PIN), /api/me (PRD §8)."""

import asyncio

from fastapi import APIRouter

from hometv import profiles
from hometv.api.deps import CurrentProfile, Db, ReadSession, TokensDep
from hometv.api.errors import ApiError
from hometv.api.schemas import (
    BootstrapRequest,
    ErrorResponse,
    Profile,
    ProfilesResponse,
    SessionResponse,
)
from hometv.db import models as m

router = APIRouter(prefix="/api", tags=["profiles"])


def profile_out(profile: m.Profile) -> Profile:
    return Profile.model_validate(profile, from_attributes=True)


def session_out(tokens: profiles.TokenService, profile: m.Profile) -> SessionResponse:
    issued = tokens.issue_profile_token(profile.id)
    return SessionResponse(
        profile=profile_out(profile), token=issued.token, expires_at=issued.expires_at
    )


@router.get("/profiles", response_model=ProfilesResponse)
def list_profiles(session: ReadSession) -> ProfilesResponse:
    return ProfilesResponse(items=[profile_out(x) for x in profiles.list_profiles(session)])


@router.post(
    "/profiles/bootstrap",
    status_code=201,
    response_model=SessionResponse,
    responses={409: {"model": ErrorResponse, "description": "already_bootstrapped"}},
)
async def bootstrap(body: BootstrapRequest, db: Db, tokens: TokensDep) -> SessionResponse:
    # async + to_thread: scrypt hashing must not block the event loop.
    profile = await asyncio.to_thread(
        profiles.bootstrap, db, name=body.name, avatar_id=body.avatar_id, pin=body.pin
    )
    return session_out(tokens, profile)


@router.post(
    "/profiles/{profile_id}/session",
    response_model=SessionResponse,
    responses={404: {"model": ErrorResponse, "description": "profile_not_found"}},
)
def create_session(profile_id: int, session: ReadSession, tokens: TokensDep) -> SessionResponse:
    """No request body (any body is ignored): selecting a profile never asks for a PIN."""
    profile = session.get(m.Profile, profile_id)
    if profile is None:
        raise ApiError(404, "profile_not_found", f"Profile {profile_id} not found")
    return session_out(tokens, profile)


@router.get(
    "/me",
    response_model=Profile,
    responses={401: {"model": ErrorResponse, "description": "unauthorized"}},
)
def me(profile: CurrentProfile) -> Profile:
    return profile_out(profile)
