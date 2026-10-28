"""FastAPI dependencies.

DB route handlers are plain ``def`` (FastAPI thread pool); reads use ``ReadSession``; writes
must go through ``Db.write_session()`` (one short transaction under the process-wide write
lock). Service objects created in the lifespan: ``SchedulerDep``, ``TmdbDep`` (``None`` without
a TMDB key), ``TmdbProxyDep``, ``ArtworkDep``, ``TokensDep``, ``LimiterDep``, ``LibrariesDep``.

Auth (PRD §8; each loads the profile in its own short read session, so no read transaction
stays open across an ``await`` in async handlers):
- ``CurrentProfile``: ``user`` auth, ``Authorization: Bearer <profile token>`` only -> 401
  ``unauthorized``.
- ``MediaUser``: ``user`` auth for media endpoints only (stream, subtitles, artwork): header
  **or** ``?token=``; the header wins when both are present.
- ``AdminProfile``: ``admin-profile`` (``/api/admin/unlock``): valid token, then ``is_admin``
  (403 ``forbidden``).
- ``AdminUser``: ``admin``: ``AdminProfile`` + a valid ``X-Admin-Token`` header for that
  profile (401 ``admin_locked``). The admin token is never read from the query string.
"""

from collections.abc import Iterator
from typing import Annotated

from fastapi import Depends, Header, Query, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy.orm import Session

from hometv.admin import TmdbProxy
from hometv.api.errors import ApiError
from hometv.config import Settings
from hometv.db import models as m
from hometv.db.engine import Database
from hometv.libraries import LibraryFolders
from hometv.metadata.artwork import ArtworkCache
from hometv.metadata.tmdb import TmdbClient
from hometv.people import PersonService
from hometv.profiles import TokenService, UnlockLimiter
from hometv.scheduler import Scheduler


def get_settings(request: Request) -> Settings:
    return request.app.state.settings


def get_db(request: Request) -> Database:
    return request.app.state.db


def get_read_session(db: Annotated[Database, Depends(get_db)]) -> Iterator[Session]:
    with db.read_session() as session:
        yield session


SettingsDep = Annotated[Settings, Depends(get_settings)]
Db = Annotated[Database, Depends(get_db)]
ReadSession = Annotated[Session, Depends(get_read_session)]


def get_scheduler(request: Request) -> Scheduler:
    return request.app.state.scheduler


def get_tmdb(request: Request) -> TmdbClient | None:
    return request.app.state.tmdb


def get_tmdb_proxy(request: Request) -> TmdbProxy:
    return request.app.state.tmdb_proxy


def get_artwork(request: Request) -> ArtworkCache:
    return request.app.state.artwork


def get_libraries(request: Request) -> LibraryFolders:
    return request.app.state.libraries


def get_people(request: Request) -> PersonService:
    return request.app.state.people


SchedulerDep = Annotated[Scheduler, Depends(get_scheduler)]
TmdbDep = Annotated[TmdbClient | None, Depends(get_tmdb)]
TmdbProxyDep = Annotated[TmdbProxy, Depends(get_tmdb_proxy)]
ArtworkDep = Annotated[ArtworkCache, Depends(get_artwork)]
LibrariesDep = Annotated[LibraryFolders, Depends(get_libraries)]
PeopleDep = Annotated[PersonService, Depends(get_people)]


# --- Auth ----------------------------------------------------------------------------------------


def get_tokens(request: Request) -> TokenService:
    return request.app.state.tokens


def get_unlock_limiter(request: Request) -> UnlockLimiter:
    return request.app.state.unlock_limiter


TokensDep = Annotated[TokenService, Depends(get_tokens)]
LimiterDep = Annotated[UnlockLimiter, Depends(get_unlock_limiter)]

_bearer = HTTPBearer(auto_error=False, description="Profile token (Authorization: Bearer)")
BearerCredentials = Annotated[HTTPAuthorizationCredentials | None, Depends(_bearer)]


def unauthorized() -> ApiError:
    return ApiError(401, "unauthorized", "Authentication required")


def profile_from_token(db: Database, tokens: TokenService, token: str | None) -> m.Profile:
    """The profile a profile token identifies (signature OK, not expired, profile exists);
    else 401 ``unauthorized``."""
    profile_id = tokens.profile_id_from_token(token)
    if profile_id is None:
        raise unauthorized()
    with db.read_session() as session:
        profile = session.get(m.Profile, profile_id)
    if profile is None:
        raise unauthorized()
    return profile


def get_current_profile(db: Db, tokens: TokensDep, credentials: BearerCredentials) -> m.Profile:
    return profile_from_token(db, tokens, credentials.credentials if credentials else None)


def get_media_profile(
    db: Db,
    tokens: TokensDep,
    credentials: BearerCredentials,
    token: Annotated[
        str | None,
        Query(description="Profile token for <video>/<track>/<img> (header wins if both)"),
    ] = None,
) -> m.Profile:
    """Token from the header, else ``?token=``. Only for the media endpoints of §8 (stream,
    subtitles, artwork); every other endpoint uses ``CurrentProfile``."""
    return profile_from_token(db, tokens, credentials.credentials if credentials else token)


CurrentProfile = Annotated[m.Profile, Depends(get_current_profile)]
MediaUser = Annotated[m.Profile, Depends(get_media_profile)]


def get_admin_profile(profile: CurrentProfile) -> m.Profile:
    if not profile.is_admin:
        raise ApiError(403, "forbidden", "Admin profile required")
    return profile


AdminProfile = Annotated[m.Profile, Depends(get_admin_profile)]


def require_admin(
    profile: AdminProfile,
    tokens: TokensDep,
    x_admin_token: Annotated[str | None, Header(alias="X-Admin-Token")] = None,
) -> m.Profile:
    if not tokens.verify_admin_token(
        x_admin_token, profile_id=profile.id, admin_token_version=profile.admin_token_version
    ):
        raise ApiError(401, "admin_locked", "Admin PIN required")
    return profile


AdminUser = Annotated[m.Profile, Depends(require_admin)]
