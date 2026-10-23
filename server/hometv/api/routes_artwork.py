"""GET /api/artwork/{size}/{file_name}: cached TMDB image, downloaded on a miss (PRD §5)."""

from fastapi import APIRouter
from fastapi.responses import FileResponse

from hometv.api.deps import ArtworkDep, MediaUser
from hometv.api.errors import ApiError
from hometv.api.schemas import ErrorResponse
from hometv.metadata.artwork import (
    ArtworkNotFound,
    InvalidArtworkName,
    InvalidArtworkSize,
    content_type,
)
from hometv.metadata.tmdb import TmdbError

ARTWORK_CACHE_CONTROL = "private, max-age=31536000, immutable"
# TMDB serves some logos as SVG; opened directly from the app origin, a script inside one
# would run there. The policy blocks scripts (and sandboxes the document) for every image.
ARTWORK_HEADERS = {
    "Cache-Control": ARTWORK_CACHE_CONTROL,
    "Content-Security-Policy": "default-src 'none'; style-src 'unsafe-inline'; sandbox",
    "X-Content-Type-Options": "nosniff",
}

router = APIRouter(prefix="/api", tags=["media"])


@router.get(
    "/artwork/{size}/{file_name}",
    response_class=FileResponse,
    responses={
        200: {"content": {"image/jpeg": {}, "image/png": {}, "image/svg+xml": {}}},
        404: {"model": ErrorResponse, "description": "artwork_not_found"},
        422: {"model": ErrorResponse, "description": "invalid_size | invalid_file_name"},
        502: {"model": ErrorResponse, "description": "tmdb_unavailable"},
    },
)
async def get_artwork(
    size: str, file_name: str, artwork: ArtworkDep, _user: MediaUser
) -> FileResponse:
    try:
        path = await artwork.ensure(size, file_name)
    except InvalidArtworkSize as exc:
        raise ApiError(422, "invalid_size", f"Invalid artwork size {size!r}") from exc
    except InvalidArtworkName as exc:
        raise ApiError(422, "invalid_file_name", "Invalid artwork file name") from exc
    except ArtworkNotFound as exc:
        raise ApiError(404, "artwork_not_found", "Artwork not found") from exc
    except TmdbError as exc:
        raise ApiError(502, "tmdb_unavailable", "TMDB is unavailable") from exc
    return FileResponse(
        path,
        media_type=content_type(file_name),
        headers=ARTWORK_HEADERS,
    )
