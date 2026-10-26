import logging
import stat

import pytest
from sqlalchemy import select

from hometv.config import ConfigError
from hometv.db.engine import HEAD_REVISION
from hometv.main import create_app, run


async def test_lifespan_runs_migrations_and_prepares_data_dir(app, data_dir):
    from alembic.migration import MigrationContext

    db = app.state.db
    assert db.path == data_dir / "hometv.db"
    with db.read_engine.connect() as conn:
        assert MigrationContext.configure(conn).get_current_revision() == HEAD_REVISION
    assert (data_dir / "artwork").is_dir()
    assert (data_dir / "subtitles").is_dir()
    assert (data_dir / "hls").is_dir()


async def test_lifespan_generates_secret_key_when_unset(make_settings, data_dir):
    app = create_app(make_settings(auth={"secret_key": None}))
    async with app.router.lifespan_context(app):
        key = app.state.settings.auth.secret_key
    assert key and (data_dir / "secret.key").read_text() == key
    assert stat.S_IMODE((data_dir / "secret.key").stat().st_mode) == 0o600


async def test_startup_aborts_on_bad_hls_dir(make_settings, data_dir):
    app = create_app(make_settings(playback={"hls_dir": data_dir}))
    with pytest.raises(ConfigError, match="HOMETV_HLS_DIR"):
        async with app.router.lifespan_context(app):
            pass


async def test_shutdown_wipes_session_dirs_only(make_settings, data_dir):
    app = create_app(make_settings())
    async with app.router.lifespan_context(app):
        hls = app.state.settings.hls_dir
        (hls / ("ab" * 16)).mkdir()
        (hls / "keep.txt").write_text("x")
    assert not (hls / ("ab" * 16)).exists()
    assert (hls / "keep.txt").exists()


async def test_request_log_line_without_query_string(client, caplog):
    with caplog.at_level(logging.INFO, logger="hometv.access"):
        r = await client.get("/api/health?token=supersecret")
    assert r.status_code == 200
    lines = [rec.getMessage() for rec in caplog.records if rec.name == "hometv.access"]
    assert len(lines) == 1
    assert lines[0].startswith("GET /api/health 200 ")
    assert lines[0].endswith("ms")
    assert "supersecret" not in caplog.text


def test_run_refuses_multiple_workers(monkeypatch):
    monkeypatch.setenv("WEB_CONCURRENCY", "2")
    with pytest.raises(SystemExit, match="single worker"):
        run()


def test_run_starts_single_worker_uvicorn(monkeypatch, tmp_path):
    calls = {}

    def fake_run(app, **kwargs):
        calls["app"] = app
        calls.update(kwargs)

    monkeypatch.setenv("HOMETV_DATA_DIR", str(tmp_path / "data"))
    monkeypatch.setenv("HOMETV_HOST", "127.0.0.1")
    monkeypatch.setenv("HOMETV_PORT", "8123")
    monkeypatch.setattr("uvicorn.run", fake_run)
    run()
    assert calls["host"] == "127.0.0.1"
    assert calls["port"] == 8123
    assert calls["workers"] == 1
    assert calls["proxy_headers"] is True


# --- Stored library folders (HOMETV-1) ----------------------------------------------------------

SESSION = "ab" * 16


def _store_folder(data_dir, path, library="films") -> None:
    from hometv.db import models as m
    from hometv.db.engine import Database, run_migrations
    from hometv.scanner import ensure_libraries

    data_dir.mkdir(parents=True, exist_ok=True)
    db = Database(data_dir / "hometv.db")
    try:
        run_migrations(db)
        with db.write_session() as s:
            libs = ensure_libraries(s)
            s.add(m.LibraryFolder(library_id=libs[library], path=str(path)))
            for lib in s.scalars(select(m.Library)):
                lib.folders_seeded_at = m.utcnow()
    finally:
        db.dispose()


async def test_lifespan_seeds_and_exposes_library_folders(make_settings, tmp_path):
    films = tmp_path / "films"
    films.mkdir()
    app = create_app(make_settings(libraries={"films": [str(films)]}))
    async with app.router.lifespan_context(app):
        assert app.state.libraries.current().films == (str(films),)
        assert app.state.scheduler.folders == app.state.libraries.current


@pytest.mark.parametrize("which", ["data", "hls"])
async def test_stored_folder_guard_aborts_before_touching_the_hls_dir(
    make_settings, data_dir, tmp_path, which
):
    media = tmp_path / "media"
    hls = media / "hls" if which == "hls" else tmp_path / "hls"
    data = media / "data" if which == "data" else data_dir
    hls.mkdir(parents=True)
    (hls / SESSION).mkdir()
    _store_folder(data, media)
    app = create_app(make_settings(data_dir=data, playback={"hls_dir": hls}))
    key = "HOMETV_HLS_DIR" if which == "hls" else "HOMETV_DATA_DIR"
    with pytest.raises(ConfigError, match=key) as exc:
        async with app.router.lifespan_context(app):
            pass
    assert str(media) in str(exc.value)
    assert (hls / SESSION).is_dir()  # no wipe at startup, none in the finally


@pytest.mark.parametrize("target", ["run_migrations", "seed"])
async def test_failing_migration_or_seed_leaves_the_hls_dir_alone(
    make_settings, tmp_path, monkeypatch, target
):
    from hometv import main as main_module
    from hometv.libraries import LibraryFolders

    hls = tmp_path / "hls"
    (hls / SESSION).mkdir(parents=True)

    def boom(*args, **kwargs):
        raise RuntimeError("boom")

    if target == "run_migrations":
        monkeypatch.setattr(main_module, "run_migrations", boom)
    else:
        monkeypatch.setattr(LibraryFolders, "seed_once", boom)
    app = create_app(make_settings(playback={"hls_dir": hls}))
    with pytest.raises(RuntimeError, match="boom"):
        async with app.router.lifespan_context(app):
            pass
    assert (hls / SESSION).is_dir()


async def test_env_root_guard_still_aborts_before_anything_is_written(make_settings, tmp_path):
    media = tmp_path / "media"
    media.mkdir()
    data = media / "data"
    app = create_app(make_settings(data_dir=data, libraries={"films": [str(media)]}))
    with pytest.raises(ConfigError, match="HOMETV_DATA_DIR"):
        async with app.router.lifespan_context(app):
            pass
    assert not data.exists()
