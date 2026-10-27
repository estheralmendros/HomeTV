"""Shared fixtures.

- ``make_settings(**overrides)``: Settings rooted in a tmp data dir (nested dict overrides
  are deep-merged, e.g. ``make_settings(tmdb={"api_key": "k"})``).
- ``settings``: default test settings (no TMDB key, no media roots, scan on startup off, change
  detection off: ``scan.watch_enabled=False``; watcher tests enable it with a fake backend).
- ``app``: ``create_app(settings)`` with its lifespan running (migrations applied).
- ``client``: ``httpx.AsyncClient`` over ``ASGITransport(app)``, base URL ``http://test``.
- ``db``: the app's ``Database`` (use ``db.write_session()`` to seed rows).
- ``ffmpeg_bin`` / ``ffprobe_bin``: paths to real binaries; tests marked ``integration`` are
  skipped automatically when either is missing (except those also marked ``inotify``, which
  need no ffmpeg and skip themselves off Linux).
"""

import os
import shutil
from collections.abc import AsyncIterator, Callable
from pathlib import Path
from typing import Any

import httpx
import pytest
from fastapi import FastAPI

from hometv.config import Settings
from hometv.db.engine import Database
from hometv.main import create_app

FFMPEG = os.environ.get("HOMETV_FFMPEG_PATH", "ffmpeg")
FFPROBE = os.environ.get("HOMETV_FFPROBE_PATH", "ffprobe")


def _binaries_available() -> bool:
    return shutil.which(FFMPEG) is not None and shutil.which(FFPROBE) is not None


def pytest_collection_modifyitems(config: pytest.Config, items: list[pytest.Item]) -> None:
    if _binaries_available():
        return
    skip = pytest.mark.skip(reason="ffmpeg/ffprobe not installed")
    for item in items:
        if "integration" in item.keywords and "inotify" not in item.keywords:
            item.add_marker(skip)


@pytest.fixture(autouse=True)
def _isolate_env(monkeypatch: pytest.MonkeyPatch) -> None:
    for key in list(os.environ):
        if key.startswith("HOMETV_") and key not in ("HOMETV_FFMPEG_PATH", "HOMETV_FFPROBE_PATH"):
            monkeypatch.delenv(key)
    monkeypatch.delenv("WEB_CONCURRENCY", raising=False)


@pytest.fixture
def ffmpeg_bin() -> str:
    path = shutil.which(FFMPEG)
    if path is None:
        pytest.skip("ffmpeg not installed")
    return path


@pytest.fixture
def ffprobe_bin() -> str:
    path = shutil.which(FFPROBE)
    if path is None:
        pytest.skip("ffprobe not installed")
    return path


def _deep_merge(base: dict[str, Any], overrides: dict[str, Any]) -> dict[str, Any]:
    merged = dict(base)
    for key, value in overrides.items():
        if isinstance(value, dict) and isinstance(merged.get(key), dict):
            merged[key] = _deep_merge(merged[key], value)
        else:
            merged[key] = value
    return merged


@pytest.fixture
def data_dir(tmp_path: Path) -> Path:
    return tmp_path / "data"


@pytest.fixture
def make_settings(tmp_path: Path, data_dir: Path) -> Callable[..., Settings]:
    def factory(**overrides: Any) -> Settings:
        base: dict[str, Any] = {
            "data_dir": data_dir,
            "scan": {"on_startup": False, "interval_hours": 0, "watch_enabled": False},
            "auth": {"secret_key": "test-secret-key"},
            "server": {"web_dist": tmp_path / "web-dist", "log_level": "warning"},
        }
        return Settings(**_deep_merge(base, overrides))

    return factory


@pytest.fixture
def settings(make_settings: Callable[..., Settings]) -> Settings:
    return make_settings()


@pytest.fixture
async def app(settings: Settings) -> AsyncIterator[FastAPI]:
    application = create_app(settings)
    async with application.router.lifespan_context(application):
        yield application


@pytest.fixture
async def client(app: FastAPI) -> AsyncIterator[httpx.AsyncClient]:
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as c:
        yield c


@pytest.fixture
def db(app: FastAPI) -> Database:
    return app.state.db
