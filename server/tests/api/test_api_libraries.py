"""/api/admin/libraries*: GET, POST plan, PUT, GET browse (HOMETV-1)."""

import asyncio
from pathlib import Path

import httpx
import pytest
from sqlalchemy import func, select

from hometv import profiles as p
from hometv import scheduler as scheduler_module
from hometv.db import models as m
from hometv.main import create_app
from tests.factories import add_file, add_film, add_show

URL = "/api/admin/libraries"


@pytest.fixture
def media(tmp_path: Path) -> Path:
    base = tmp_path / "media"
    for sub in ("Films", "Films4K", "TV", "Docs", "series/Naruto", "X"):
        (base / sub).mkdir(parents=True)
    return base


@pytest.fixture
def settings(make_settings, media):
    return make_settings(libraries={"browse_root": str(media)}, scan={"missing_grace_days": 5})


@pytest.fixture
def slow_scans(monkeypatch):
    """Scans block until ``release`` is set (``scheduler.wait_idle()`` after setting it)."""
    release = asyncio.Event()

    class SlowScanner:
        def __init__(self, *args, **kwargs) -> None:
            pass

        async def run(self, report, run_id):
            await release.wait()
            return report

    monkeypatch.setattr(scheduler_module, "Scanner", SlowScanner)
    return release


def _err(r) -> tuple[int, str]:
    return r.status_code, r.json()["error"]["code"]


def _runs(db) -> list[str]:
    with db.read_session() as s:
        return list(s.scalars(select(m.ScanRun.trigger).order_by(m.ScanRun.id)))


def _store(app, films=(), shows=()) -> None:
    app.state.libraries.save([str(p) for p in films], [str(p) for p in shows], confirm_relinks=True)


def _films_under(db, root: Path, count: int, *, missing: bool = False, sub: str = "") -> None:
    with db.write_session() as s:
        for i in range(count):
            film = add_film(s, f"Film {root.name} {sub} {i} {missing}", files=0)
            rel = f"{sub}/f{film.id}.mkv" if sub else f"f{film.id}.mkv"
            add_file(s, film_id=film.id, root=str(root), rel=rel, missing=missing)


def _show_under(db, root: Path, sub: str, title: str) -> None:
    with db.write_session() as s:
        _, eps = add_show(s, title, episodes={1: 1}, with_files=False)
        for ep in eps.values():
            add_file(s, episode_ids=[ep.id], root=str(root), rel=f"{sub}/{title}.S01E01.mkv")


async def _finish(app, release=None) -> None:
    if release is not None:
        release.set()
    await app.state.scheduler.wait_idle()


# --- GET -----------------------------------------------------------------------------------------


async def test_get_returns_folders_status_counts_and_settings(client, app, db, admin, media):
    _store(app, films=[media / "Films", media / "Films4K"], shows=[media / "TV"])
    _films_under(db, media / "Films", 2)
    _films_under(db, media / "Films", 1, missing=True)
    _films_under(db, media / "Films4K", 1)
    _show_under(db, media / "TV", "Lost", "Lost")
    (media / "Films4K").rmdir()  # disk unplugged: still listed, counted from present rows

    r = await client.get(URL, headers=admin.admin_headers)

    assert r.status_code == 200
    assert r.json() == {
        "films": [
            {"path": f"{media}/Films", "status": "available", "title_count": 2},
            {"path": f"{media}/Films4K", "status": "not_found", "title_count": 1},
        ],
        "shows": [{"path": f"{media}/TV", "status": "available", "title_count": 1}],
        "browse_root": {"path": str(media), "available": True},
        "ignored_env": [],
        "missing_grace_days": 5,
    }


# --- PUT -----------------------------------------------------------------------------------------


async def test_put_valid_change_saves_and_starts_a_libraries_scan(
    client, app, db, admin, media, slow_scans
):
    body = {"films": [f"{media}/Films", f"{media}/Docs/"], "shows": [f"{media}/TV"]}
    r = await client.put(URL, json=body, headers=admin.admin_headers)
    assert r.status_code == 200, r.text
    data = r.json()
    assert [f["path"] for f in data["films"]] == [f"{media}/Films", f"{media}/Docs"]
    assert data["scan"]["trigger"] == "libraries" and data["scan"]["state"] == "running"
    assert app.state.libraries.current().shows == (f"{media}/TV",)
    assert app.state.scheduler.running
    await _finish(app, slow_scans)
    assert _runs(db) == ["libraries"]


async def test_put_while_scanning_is_409_and_writes_nothing(
    client, app, db, admin, media, slow_scans
):
    _store(app, films=[media / "Films"])
    started = (await client.post("/api/admin/scan", headers=admin.admin_headers)).json()
    r = await client.put(
        URL, json={"films": [f"{media}/Docs"], "shows": []}, headers=admin.admin_headers
    )
    assert _err(r) == (409, "scan_in_progress")
    assert r.json()["error"]["details"]["scan"]["id"] == started["id"]
    assert app.state.libraries.current().films == (f"{media}/Films",)
    await _finish(app, slow_scans)


async def test_put_invalid_is_422_with_every_entry_and_no_scan(client, app, db, admin, media):
    body = {
        "films": [f"{media}/Nope", "media/x", f"{media}/Films"],
        "shows": [f"{media}/Films/Sub", f"{media}/Films"],
    }
    (media / "Films" / "Sub").mkdir()
    r = await client.put(URL, json=body, headers=admin.admin_headers)
    assert _err(r) == (422, "invalid_library_folders")
    assert r.json()["error"]["details"]["folders"] == [
        {"library": "films", "path": f"{media}/Nope", "code": "not_found", "other": None},
        {"library": "films", "path": "media/x", "code": "not_absolute", "other": None},
        {
            "library": "shows",
            "path": f"{media}/Films/Sub",
            "code": "overlaps_folder",
            "other": f"{media}/Films",
        },
        {
            "library": "shows",
            "path": f"{media}/Films",
            "code": "duplicate",
            "other": f"{media}/Films",
        },
    ]
    assert _runs(db) == []
    assert not app.state.scheduler.running
    assert app.state.libraries.current().films == ()


async def test_put_same_sets_in_another_order_is_a_noop(client, app, db, admin, media):
    _store(app, films=[media / "Films", media / "Docs"], shows=[media / "TV"])
    body = {"films": [f"{media}/Docs", f"{media}/Films/"], "shows": [f"{media}/TV"]}
    r = await client.put(URL, json=body, headers=admin.admin_headers)
    assert r.status_code == 200
    assert r.json()["scan"] is None
    assert _runs(db) == []


async def test_put_move_needs_confirm_relinks(client, app, db, admin, media, slow_scans):
    _store(app, films=[media / "X"])
    _films_under(db, media / "X", 2)
    body = {"films": [], "shows": [f"{media}/X"]}
    r = await client.put(URL, json=body, headers=admin.admin_headers)
    assert _err(r) == (409, "relinks_unconfirmed")
    assert r.json()["error"]["details"] == {
        "moves": [
            {
                "from_library": "films",
                "from_path": f"{media}/X",
                "to_library": "shows",
                "to_path": f"{media}/X",
                "title_count": 2,
            }
        ],
        "relayouts": [],
    }
    assert _runs(db) == [] and not app.state.scheduler.running
    assert app.state.libraries.current().films == (f"{media}/X",)

    r = await client.put(URL, json={**body, "confirm_relinks": True}, headers=admin.admin_headers)
    assert r.status_code == 200, r.text
    assert r.json()["scan"]["trigger"] == "libraries"
    assert app.state.libraries.current().shows == (f"{media}/X",)
    await _finish(app, slow_scans)


async def test_put_series_relayout_needs_confirm_relinks(client, app, db, admin, media, slow_scans):
    _store(app, shows=[media])
    _show_under(db, media, "series/Naruto", "Naruto")
    body = {"films": [], "shows": [f"{media}/series"]}
    (media / "series").mkdir(exist_ok=True)
    r = await client.put(URL, json=body, headers=admin.admin_headers)
    assert _err(r) == (409, "relinks_unconfirmed")
    assert r.json()["error"]["details"]["relayouts"] == [
        {"from_path": str(media), "to_path": f"{media}/series", "title_count": 1}
    ]
    r = await client.put(URL, json={**body, "confirm_relinks": True}, headers=admin.admin_headers)
    assert r.status_code == 200
    assert r.json()["scan"]["trigger"] == "libraries"
    await _finish(app, slow_scans)
    assert _runs(db) == ["libraries"]


@pytest.mark.parametrize(
    "body",
    [
        {"films": "/media/x", "shows": []},
        {"films": [f"/m/{i}" for i in range(51)], "shows": []},
        {"films": [""], "shows": []},
        {"films": ["/" + "a" * 4096], "shows": []},
        {"films": ["/media/a\x00b"], "shows": []},
        {"films": []},
    ],
    ids=["not-a-list", "51-entries", "empty", "too-long", "nul", "missing-shows"],
)
async def test_put_and_plan_body_shape_is_validated(client, admin, body):
    for method, url in (("PUT", URL), ("POST", f"{URL}/plan")):
        r = await client.request(method, url, json=body, headers=admin.admin_headers)
        assert _err(r) == (422, "validation_error")


# --- POST plan -----------------------------------------------------------------------------------


async def test_plan_shapes_and_it_ignores_the_scan_lock(client, app, db, admin, media, slow_scans):
    _store(app, films=[media / "Films", media / "X"], shows=[media / "TV"])
    _films_under(db, media / "Films", 3)
    _films_under(db, media / "X", 1)
    _show_under(db, media / "TV", "Lost", "Lost")
    (media / "TV" / "Sub").mkdir()
    await client.post("/api/admin/scan", headers=admin.admin_headers)  # holds the lock
    body = {"films": [str(media / "TV" / "Sub")], "shows": [str(media / "X")]}

    r = await client.post(f"{URL}/plan", json=body, headers=admin.admin_headers)

    assert r.status_code == 200, r.text
    assert r.json() == {
        "removed": [
            {"library": "films", "path": f"{media}/Films", "title_count": 3},
            {"library": "shows", "path": f"{media}/TV", "title_count": 1},
        ],
        "covered": [],
        "moved": [
            {
                "from_library": "films",
                "from_path": f"{media}/X",
                "to_library": "shows",
                "to_path": f"{media}/X",
                "title_count": 1,
            }
        ],
        "relayout": [],
    }
    assert app.state.libraries.current().films == (f"{media}/Films", f"{media}/X")
    await _finish(app, slow_scans)


async def test_plan_covered_and_invalid(client, app, db, admin, media):
    (media / "Films" / "MKV").mkdir()
    _store(app, films=[media / "Films" / "MKV"])
    _films_under(db, media / "Films" / "MKV", 2)
    r = await client.post(
        f"{URL}/plan", json={"films": [f"{media}/Films"], "shows": []}, headers=admin.admin_headers
    )
    assert r.json()["covered"] == [
        {"library": "films", "path": f"{media}/Films/MKV", "by": f"{media}/Films", "title_count": 2}
    ]
    r = await client.post(
        f"{URL}/plan", json={"films": ["rel"], "shows": []}, headers=admin.admin_headers
    )
    assert _err(r) == (422, "invalid_library_folders")
    assert r.json()["error"]["details"]["folders"] == [
        {"library": "films", "path": "rel", "code": "not_absolute", "other": None}
    ]
    with db.read_session() as s:
        assert s.scalar(select(func.count()).select_from(m.ScanRun)) == 0


# --- Browse --------------------------------------------------------------------------------------


async def test_browse_default_subfolder_and_errors(client, app, admin, media, tmp_path):
    (media / ".hidden").mkdir()
    (media / "Films" / "a.mkv").write_bytes(b"x")
    outside = tmp_path / "outside"
    outside.mkdir()
    (media / "escape").symlink_to(outside, target_is_directory=True)
    locked = media / "locked"
    locked.mkdir()

    r = await client.get(f"{URL}/browse", headers=admin.admin_headers)
    assert r.status_code == 200
    body = r.json()
    assert body["path"] == str(media) and body["parent"] is None
    assert [e["name"] for e in body["entries"]] == [
        "Docs",
        "Films",
        "Films4K",
        "locked",
        "series",
        "TV",
        "X",
    ]

    r = await client.get(
        f"{URL}/browse", params={"path": f"{media}/series"}, headers=admin.admin_headers
    )
    assert r.json() == {
        "path": f"{media}/series",
        "parent": str(media),
        "entries": [{"name": "Naruto", "path": f"{media}/series/Naruto"}],
    }

    async def code(path):
        return _err(
            await client.get(f"{URL}/browse", params={"path": path}, headers=admin.admin_headers)
        )

    assert await code(f"{media}/../outside") == (403, "outside_browse_root")
    assert await code(f"{media}/escape") == (403, "outside_browse_root")
    assert await code(f"{media}/nope") == (404, "folder_not_found")
    assert await code("relative/path") == (422, "validation_error")
    locked.chmod(0o000)
    try:
        assert await code(str(locked)) == (403, "folder_not_readable")
    finally:
        locked.chmod(0o755)


async def test_browse_unavailable_when_the_base_is_missing(make_settings, tmp_path):
    app = create_app(make_settings(libraries={"browse_root": str(tmp_path / "nope")}))
    async with app.router.lifespan_context(app):
        profile = p.create_profile(
            app.state.db, name="A", avatar_id="avatar-01", is_admin=True, pin="1234"
        )
        tokens = app.state.tokens
        headers = {
            "Authorization": f"Bearer {tokens.issue_profile_token(profile.id).token}",
            "X-Admin-Token": tokens.issue_admin_token(
                profile.id, profile.admin_token_version
            ).token,
        }
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as c:
            r = await c.get(f"{URL}/browse", headers=headers)
            assert _err(r) == (409, "browse_unavailable")
            r = await c.get(URL, headers=headers)
            assert r.json()["browse_root"] == {"path": str(tmp_path / "nope"), "available": False}


# --- Non-admin responses never contain a folder path ---------------------------------------------


async def test_non_admin_responses_never_contain_a_stored_folder(client, app, db, user, media):
    _store(app, films=[media / "Films"], shows=[media / "TV"])
    with db.write_session() as s:
        film = add_film(s, "Dune", files=0)
        add_file(s, film_id=film.id, root=str(media / "Films"), rel="Dune.mkv")
    _show_under(db, media / "TV", "Lost", "Lost")
    paths = [
        "/api/me",
        "/api/info",
        "/api/home",
        "/api/films",
        f"/api/films/{film.id}",
        "/api/shows",
        "/api/search?q=dune",
    ]
    for path in paths:
        r = await client.get(path, headers=user.headers)
        assert r.status_code == 200, path
        assert str(media) not in r.text, path
