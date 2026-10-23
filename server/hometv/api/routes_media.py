"""Media & playback routes (PRD REST "Media & playback", §7).

- ``GET /api/media-files/{id}`` and ``POST .../playback``: ``CurrentProfile`` (Bearer only).
- ``GET|HEAD .../stream`` and ``GET .../subtitles/{track}.vtt``: ``MediaUser`` (``?token=``).
- ``/api/hls/{session_id}/*``: authorized by the unguessable session id (no token).
"""

import asyncio
import os
from dataclasses import dataclass
from typing import Annotated, Any

from fastapi import APIRouter, Body, Request, Response
from fastapi.responses import FileResponse, StreamingResponse
from sqlalchemy.orm import Session

from hometv import catalog
from hometv.api.deps import CurrentProfile, Db, MediaUser, ReadSession
from hometv.api.errors import ApiError
from hometv.api.schemas import (
    ErrorResponse,
    MediaFile,
    PlaybackDirect,
    PlaybackHls,
    PlaybackRequest,
    PlaybackResponse,
    PlaybackUnsupported,
    Responses,
    SubtitleTrack,
)
from hometv.db import models as m
from hometv.db.engine import Database
from hometv.playback.decide import DirectPlay, InvalidAudioStream, Unsupported, decide
from hometv.playback.hls import HlsSessionManager, SessionSpec
from hometv.playback.keyframes import (
    FileInfo,
    KeyframeIndexFailed,
    KeyframeService,
    KeyframesPreparing,
)
from hometv.playback.segments import check_plan, plan_segments
from hometv.playback.stream import (
    RangeNotSatisfiable,
    content_type,
    file_chunks,
    open_file_chunks,
    parse_range,
)
from hometv.playback.subtitles import Sidecar, SubtitleService, SubtitleSource
from hometv.probe import ProbeResult

router = APIRouter(prefix="/api", tags=["media"])

PREPARING_RETRY_AFTER = 5
NO_CACHE = {"Cache-Control": "no-cache"}


def _not_found(media_file_id: int) -> ApiError:
    return ApiError(404, "not_found", f"Media file {media_file_id} not found")


def _file_missing() -> ApiError:
    return ApiError(404, "file_missing", "The media file is missing")


@dataclass(frozen=True)
class LoadedFile:
    id: int
    path: str
    size_bytes: int
    mtime_ns: int
    container: str | None
    probe: ProbeResult | None
    sidecars: tuple[Sidecar, ...]
    subtitle_tracks: list[SubtitleTrack]

    def subtitle_source(self) -> SubtitleSource:
        return SubtitleSource(self.id, self.path, self.probe, self.sidecars)


def _present_file(session: Session, media_file_id: int) -> tuple[m.MediaFile, list[m.SubtitleFile]]:
    """The media file row and its sidecars; 404 ``not_found`` / ``file_missing``."""
    row = session.get(m.MediaFile, media_file_id)
    if row is None:
        raise _not_found(media_file_id)
    if row.missing_since is not None:
        raise _file_missing()
    return row, catalog.sidecars(session, [row.id]).get(row.id, [])


def _load(db: Database, media_file_id: int, *, with_tracks: bool = True) -> LoadedFile:
    with db.read_session() as session:
        row, sidecar_rows = _present_file(session, media_file_id)
        probe = ProbeResult.from_json(row.probe) if row.probe and row.probe_error is None else None
        tracks = catalog.media_file_out(row, sidecar_rows).subtitle_tracks if with_tracks else []
        return LoadedFile(
            row.id,
            row.path,
            row.size_bytes,
            row.mtime_ns,
            row.container,
            probe,
            tuple(Sidecar(s.id, s.path, s.format, s.kind) for s in sidecar_rows),
            tracks,
        )


def _keyframes(request: Request) -> KeyframeService:
    return request.app.state.keyframes


def _hls(request: Request) -> HlsSessionManager:
    return request.app.state.hls


def _subtitles(request: Request) -> SubtitleService:
    return request.app.state.subtitles


# --- media file ------------------------------------------------------------------------------


@router.get(
    "/media-files/{media_file_id}",
    response_model=MediaFile,
    responses={404: {"model": ErrorResponse, "description": "not_found | file_missing"}},
)
def get_media_file(media_file_id: int, session: ReadSession, _user: CurrentProfile) -> MediaFile:
    return catalog.media_file_out(*_present_file(session, media_file_id))


# --- playback ----------------------------------------------------------------------------------


@router.post(
    "/media-files/{media_file_id}/playback",
    response_model=PlaybackResponse,
    responses={
        404: {"model": ErrorResponse, "description": "not_found | file_missing"},
        422: {"model": ErrorResponse, "description": "invalid_audio_stream"},
        500: {"model": ErrorResponse, "description": "transcoder_failed (keyframe_index_failed)"},
        503: {
            "model": ErrorResponse,
            "description": "preparing | too_many_sessions | hls_disk_full",
        },
    },
)
async def start_playback(
    media_file_id: int,
    request: Request,
    db: Db,
    user: CurrentProfile,
    body: Annotated[PlaybackRequest, Body()],
) -> Any:
    hls = _hls(request)
    # Step 0: the previous session is stopped whatever the outcome of this request.
    await hls.stop_owned(body.previous_session_id, user.id)
    loaded = await asyncio.to_thread(_load, db, media_file_id)
    if not await asyncio.to_thread(os.path.isfile, loaded.path):
        raise _file_missing()
    try:
        decision = decide(loaded.probe, body.client_caps, body.audio_stream_index)
    except InvalidAudioStream as exc:
        raise ApiError(
            422,
            "invalid_audio_stream",
            str(exc),
            {"audio_stream_index": body.audio_stream_index},
        ) from exc
    if isinstance(decision, Unsupported):
        return PlaybackUnsupported(reason=decision.reason, detail=decision.detail)
    probe = loaded.probe
    assert probe is not None and probe.video is not None
    if isinstance(decision, DirectPlay):
        _subtitles(request).prefetch(loaded.subtitle_source())
        return PlaybackDirect(
            url=f"/api/media-files/{loaded.id}/stream",
            audio_stream_index=decision.audio_stream_index,
            duration_seconds=probe.duration_seconds,
            subtitle_tracks=loaded.subtitle_tracks,
        )

    info = FileInfo(
        loaded.id,
        loaded.path,
        loaded.size_bytes,
        loaded.mtime_ns,
        probe.container,
        probe.video.index,
        probe.start_seconds,
    )
    try:
        keyframes = await _keyframes(request).times_for_playback(info)
    except KeyframesPreparing as exc:
        raise ApiError(
            503,
            "preparing",
            "Preparing video",
            {
                "retry_after_seconds": PREPARING_RETRY_AFTER,
                "estimated_seconds": exc.estimated_seconds,
            },
            {"Retry-After": str(PREPARING_RETRY_AFTER)},
        ) from None
    except KeyframeIndexFailed as exc:
        raise ApiError(
            500, "transcoder_failed", "The streaming conversion failed", {"detail": exc.detail}
        ) from None

    duration = probe.duration_seconds
    assert duration is not None
    seg = request.app.state.settings.playback.hls_segment_seconds
    plan = plan_segments(keyframes, duration, seg)
    checked = check_plan(plan, seg)
    if not checked.ok:
        return PlaybackUnsupported(
            reason="keyframes_too_sparse",
            detail=(
                "Keyframes are too far apart for streaming conversion (longest segment "
                f"{checked.longest:.1f} s); the file can only be played directly"
            ),
        )
    session = await hls.create_session(
        SessionSpec(
            profile_id=user.id,
            media_file_id=loaded.id,
            path=loaded.path,
            size_bytes=loaded.size_bytes,
            probe=probe,
            keyframes=keyframes,
            plan=plan,
            audio_stream_index=decision.audio_stream_index,
            audio_action=decision.audio_action,
            start_position_seconds=min(body.start_position_seconds, duration),
        )
    )
    _subtitles(request).prefetch(loaded.subtitle_source())
    return PlaybackHls(
        url=f"/api/hls/{session.session_id}/index.m3u8",
        session_id=session.session_id,
        audio_stream_index=decision.audio_stream_index,
        audio_action=decision.audio_action,
        duration_seconds=duration,
        subtitle_tracks=loaded.subtitle_tracks,
    )


# --- direct stream -----------------------------------------------------------------------------


def _stat_stream(db: Database, media_file_id: int) -> tuple[str, int, str | None]:
    loaded = _load(db, media_file_id, with_tracks=False)
    try:
        size = os.stat(loaded.path).st_size
    except OSError:
        raise _file_missing() from None
    container = loaded.probe.container if loaded.probe else loaded.container
    return loaded.path, size, container


async def stream_media(media_file_id: int, request: Request, db: Db, _user: MediaUser) -> Response:
    path, size, container = await asyncio.to_thread(_stat_stream, db, media_file_id)
    headers = {"Accept-Ranges": "bytes"}
    try:
        byte_range = parse_range(request.headers.get("range"), size)
    except RangeNotSatisfiable:
        raise ApiError(
            416,
            "range_not_satisfiable",
            "Requested range not satisfiable",
            headers={"Content-Range": f"bytes */{size}", **headers},
        ) from None
    if byte_range is None:
        status, start, length = 200, 0, size
    else:
        status, start, length = 206, byte_range.start, byte_range.length
        headers["Content-Range"] = byte_range.content_range(size)
    headers["Content-Length"] = str(length)
    media_type = content_type(container)
    if request.method == "HEAD":
        return Response(status_code=status, headers=headers, media_type=media_type)
    return StreamingResponse(
        file_chunks(path, start, length), status_code=status, headers=headers, media_type=media_type
    )


_STREAM_RESPONSES: dict[int | str, dict[str, Any]] = {
    200: {"content": {"video/mp4": {}, "video/x-matroska": {}}},
    206: {"description": "Partial content"},
    404: {"model": ErrorResponse, "description": "not_found | file_missing"},
    416: {"model": ErrorResponse, "description": "range_not_satisfiable"},
}
for _method in ("GET", "HEAD"):
    router.add_api_route(
        "/media-files/{media_file_id}/stream",
        stream_media,
        methods=[_method],
        response_class=StreamingResponse,
        responses=_STREAM_RESPONSES,
        operation_id=f"stream_media_{_method.lower()}",
    )


# --- subtitles ---------------------------------------------------------------------------------


@router.get(
    "/media-files/{media_file_id}/subtitles/{track_file}",
    response_class=FileResponse,
    responses={
        200: {"content": {"text/vtt": {}}},
        404: {"model": ErrorResponse, "description": "subtitle_not_found | not_found"},
        422: {"model": ErrorResponse, "description": "subtitle_not_text"},
        500: {"model": ErrorResponse, "description": "subtitle_extraction_failed"},
        503: {"model": ErrorResponse, "description": "subtitle_pending"},
        504: {"model": ErrorResponse, "description": "subtitle_timeout"},
    },
)
async def get_subtitles(
    media_file_id: int, track_file: str, request: Request, db: Db, _user: MediaUser
) -> FileResponse:
    if not track_file.endswith(".vtt"):
        raise ApiError(404, "subtitle_not_found", "Subtitle track not found")
    loaded = await asyncio.to_thread(_load, db, media_file_id, with_tracks=False)
    path = await _subtitles(request).get_vtt(loaded.subtitle_source(), track_file[:-4])
    return FileResponse(path, media_type="text/vtt; charset=utf-8")


# --- HLS ---------------------------------------------------------------------------------------

_HLS_ERRORS: Responses = {
    404: {"model": ErrorResponse, "description": "session_not_found | segment_not_found"},
    500: {"model": ErrorResponse, "description": "transcoder_failed"},
    504: {"model": ErrorResponse, "description": "segment_timeout"},
}


@router.get(
    "/hls/{session_id}/index.m3u8",
    response_class=Response,
    responses={200: {"content": {"application/vnd.apple.mpegurl": {}}}, **_HLS_ERRORS},
)
async def hls_playlist(session_id: str, request: Request) -> Response:
    text = _hls(request).playlist(session_id)
    return Response(text, media_type="application/vnd.apple.mpegurl", headers=NO_CACHE)


@router.get(
    "/hls/{session_id}/init.mp4",
    response_class=Response,
    responses={200: {"content": {"video/mp4": {}}}, **_HLS_ERRORS},
)
async def hls_init(session_id: str, request: Request) -> Response:
    data = await _hls(request).init_segment(session_id)
    return Response(data, media_type="video/mp4", headers=NO_CACHE)


@router.get(
    "/hls/{session_id}/seg_{n:int}.m4s",
    response_class=Response,
    responses={200: {"content": {"video/mp4": {}}}, **_HLS_ERRORS},
)
async def hls_segment(session_id: str, n: int, request: Request) -> Response:
    # Streamed from the fd opened before the segment can be evicted: never read whole.
    opened = await _hls(request).open_segment(session_id, n)
    return StreamingResponse(
        open_file_chunks(opened.file, opened.size),
        media_type="video/mp4",
        headers={**NO_CACHE, "Content-Length": str(opened.size)},
    )


@router.post(
    "/hls/{session_id}/keepalive",
    status_code=204,
    response_class=Response,
    responses={
        404: {"model": ErrorResponse, "description": "session_not_found"},
        500: {"model": ErrorResponse, "description": "transcoder_failed"},
    },
)
async def hls_keepalive(session_id: str, request: Request) -> Response:
    _hls(request).keepalive(session_id)
    return Response(status_code=204)


@router.delete("/hls/{session_id}", status_code=204, response_class=Response)
async def hls_delete(session_id: str, request: Request) -> Response:
    await _hls(request).delete(session_id)
    return Response(status_code=204)
