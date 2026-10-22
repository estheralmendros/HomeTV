"""System endpoints: /api/health, /api/info (public)."""

from fastapi import APIRouter
from fastapi.responses import JSONResponse
from sqlalchemy import exists, select

from hometv import __version__
from hometv.api.deps import Db, ReadSession, SettingsDep
from hometv.api.schemas import Avatar, HealthResponse, InfoResponse
from hometv.db.models import Profile
from hometv.profiles import AVATARS

# Spanish (es-ES): server-produced text shown to viewers (PRD §UI Language, §5).
TMDB_ATTRIBUTION = (
    "Este producto utiliza la API de TMDB, pero no está respaldado ni certificado por TMDB."
)

router = APIRouter(prefix="/api", tags=["system"])


@router.get(
    "/health",
    response_model=HealthResponse,
    responses={503: {"model": HealthResponse, "description": "Database unreachable"}},
)
def health(db: Db) -> HealthResponse | JSONResponse:
    if db.ping():
        return HealthResponse(status="ok")
    return JSONResponse(status_code=503, content={"status": "error"})


@router.get("/info", response_model=InfoResponse)
def info(settings: SettingsDep, session: ReadSession) -> InfoResponse:
    has_profiles = session.scalar(select(exists().where(Profile.id.is_not(None))))
    return InfoResponse(
        name="HomeTV",
        version=__version__,
        needs_bootstrap=not has_profiles,
        tmdb_configured=settings.tmdb_configured,
        tmdb_attribution=TMDB_ATTRIBUTION,
        avatars=[Avatar(id=avatar_id, color=color) for avatar_id, color in AVATARS],
    )
