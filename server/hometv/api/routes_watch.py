"""Watch state: PUT /api/progress, POST /api/watched (PRD §9)."""

from fastapi import APIRouter

from hometv import watch
from hometv.api.deps import CurrentProfile, Db
from hometv.api.schemas import (
    ErrorResponse,
    Progress,
    ProgressRequest,
    WatchedRequest,
    WatchedResponse,
)

router = APIRouter(
    prefix="/api",
    tags=["watch"],
    responses={401: {"model": ErrorResponse, "description": "unauthorized"}},
)


@router.put(
    "/progress",
    response_model=Progress,
    responses={
        404: {"model": ErrorResponse, "description": "not_found (item or media file)"},
        422: {"model": ErrorResponse, "description": "media_file_not_linked | validation_error"},
    },
)
def put_progress(body: ProgressRequest, profile: CurrentProfile, db: Db) -> Progress:
    return watch.report_progress(db, profile.id, body)


@router.post(
    "/watched",
    response_model=WatchedResponse,
    responses={404: {"model": ErrorResponse, "description": "not_found"}},
)
def post_watched(body: WatchedRequest, profile: CurrentProfile, db: Db) -> WatchedResponse:
    updated = watch.mark_watched(db, profile.id, body.target, body.id, body.watched)
    return WatchedResponse(updated=updated)
