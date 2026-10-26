"""App factory, lifespan and the single-worker entry point."""

import asyncio
import os
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from hometv import __version__
from hometv.admin import TmdbProxy
from hometv.api import (
    routes_admin,
    routes_artwork,
    routes_catalog,
    routes_films,
    routes_media,
    routes_profiles,
    routes_shows,
    routes_system,
    routes_watch,
)
from hometv.api.errors import install_error_handlers
from hometv.api.schemas import ErrorResponse
from hometv.config import (
    Settings,
    check_library_folders,
    load_settings,
    prepare_hls_dir,
    prepare_runtime,
    wipe_hls_sessions,
)
from hometv.db.engine import Database, run_migrations
from hometv.libraries import LibraryFolders
from hometv.logging import AccessLogMiddleware, configure_logging
from hometv.metadata.artwork import ArtworkCache
from hometv.metadata.tmdb import TmdbClient
from hometv.playback.hls import HlsSessionManager
from hometv.playback.keyframes import KeyframeService
from hometv.playback.subtitles import SubtitleService
from hometv.profiles import TokenService, UnlockLimiter
from hometv.scheduler import Scheduler
from hometv.spa import install_spa


def create_app(settings: Settings | None = None) -> FastAPI:
    """Build the app. ``settings`` defaults to ``load_settings()`` (env + YAML)."""
    initial = settings if settings is not None else load_settings()

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        # Order: env-root guards + data dirs -> migrations -> seed/load the stored library
        # folders -> their guards -> only then create and wipe the HLS dir.
        configure_logging(initial.server.log_level)
        prepared = await asyncio.to_thread(prepare_runtime, initial)
        db = Database(prepared.db_path)
        tmdb = TmdbClient.from_settings(prepared)
        scheduler: Scheduler | None = None
        hls = HlsSessionManager(prepared)
        keyframes = KeyframeService(db, prepared, session_running=hls.any_running)
        subtitles = SubtitleService(prepared)
        hls_ready = False  # the finally wipes the HLS dir only once every guard passed
        try:
            await asyncio.to_thread(run_migrations, db)
            libraries = LibraryFolders(db, prepared)
            await asyncio.to_thread(libraries.seed_once)
            await asyncio.to_thread(check_library_folders, prepared, libraries.current())
            await asyncio.to_thread(prepare_hls_dir, prepared)
            hls_ready = True
            app.state.libraries = libraries
            app.state.settings = prepared
            app.state.db = db
            app.state.tmdb = tmdb
            app.state.tokens = TokenService.from_settings(prepared)
            app.state.unlock_limiter = UnlockLimiter()
            app.state.tmdb_proxy = TmdbProxy(tmdb)
            app.state.artwork = ArtworkCache(prepared.artwork_dir, tmdb)
            app.state.hls = hls
            app.state.keyframes = keyframes
            app.state.subtitles = subtitles
            hls.start()
            keyframes.start()
            scheduler = Scheduler(
                db,
                prepared,
                tmdb=tmdb,
                artwork=app.state.artwork,
                keyframe_hook=keyframes.enqueue,
                folders=libraries.current,
            )
            app.state.scheduler = scheduler
            await scheduler.start()  # no background task when on_startup=false and interval=0
            yield
        finally:
            if scheduler is not None:
                await scheduler.stop()
            await keyframes.stop()
            await hls.shutdown()
            await subtitles.shutdown()
            if tmdb is not None:
                await tmdb.aclose()
            if hls_ready:
                await asyncio.to_thread(wipe_hls_sessions, prepared.hls_dir)
            db.dispose()

    app = FastAPI(
        title="HomeTV",
        version=__version__,
        lifespan=lifespan,
        openapi_url="/api/openapi.json",
        docs_url="/api/docs",
        redoc_url=None,
        responses={422: {"model": ErrorResponse, "description": "Validation error"}},
    )
    app.state.settings = initial
    install_error_handlers(app)
    app.add_middleware(AccessLogMiddleware)
    if initial.server.cors_origins:
        app.add_middleware(
            CORSMiddleware,
            allow_origins=initial.server.cors_origins,
            allow_methods=["*"],
            allow_headers=["*"],
        )
    app.include_router(routes_system.router)
    app.include_router(routes_profiles.router)
    app.include_router(routes_catalog.router)
    app.include_router(routes_films.router)
    app.include_router(routes_shows.router)
    app.include_router(routes_watch.router)
    app.include_router(routes_artwork.router)
    app.include_router(routes_media.router)
    app.include_router(routes_admin.unlock_router)
    app.include_router(routes_admin.router)
    install_spa(app)  # last: catch-all for non-/api GET/HEAD
    return app


def run() -> None:
    """Production entry point: exactly one uvicorn worker (PRD §6 Concurrency model)."""
    workers = os.environ.get("WEB_CONCURRENCY", "1").strip() or "1"
    if workers != "1":
        raise SystemExit(f"HomeTV must run as a single worker (WEB_CONCURRENCY={workers})")
    import uvicorn

    settings = load_settings()
    uvicorn.run(
        create_app(settings),
        host=settings.server.host,
        port=settings.server.port,
        workers=1,
        proxy_headers=True,
        access_log=False,
        log_level=settings.server.log_level,
    )
