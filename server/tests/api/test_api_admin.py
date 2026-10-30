"""/api/admin/*: unlock, auth matrix, scan, unmatched, TMDB proxies, profiles CRUD."""

import asyncio
import json
from datetime import UTC, datetime, timedelta
from pathlib import Path

import pytest
import respx
from sqlalchemy import select

from hometv import profiles as p
from hometv import scheduler as scheduler_module
from hometv.db import models as m
from hometv.metadata.tmdb import TMDB_BASE_URL
from hometv.scanner import ensure_libraries
from tests.factories import add_file, add_film, add_watchlist_entry

FIXTURES = Path(__file__).resolve().parent.parent / "fixtures" / "tmdb"


def load(name: str) -> dict:
    return json.loads((FIXTURES / name).read_text(encoding="utf-8"))


def _err(r) -> tuple[int, str]:
    return r.status_code, r.json()["error"]["code"]


# --- Unlock --------------------------------------------------------------------------------------


async def test_unlock_success_returns_fixed_15_minute_token(client, admin, clock):
    r = await client.post("/api/admin/unlock", json={"pin": "1234"}, headers=admin.headers)
    assert r.status_code == 200
    data = r.json()
    assert set(data) == {"admin_token", "expires_at"}
    expected = datetime.fromtimestamp(clock.now, UTC) + timedelta(minutes=15)
    assert data["expires_at"] == expected.strftime("%Y-%m-%dT%H:%M:%SZ")
    headers = {**admin.headers, "X-Admin-Token": data["admin_token"]}
    assert (await client.get("/api/admin/scan", headers=headers)).status_code == 200


async def test_unlock_errors(client, admin, user):
    r = await client.post("/api/admin/unlock", json={"pin": "1234"})
    assert _err(r) == (401, "unauthorized")
    r = await client.post("/api/admin/unlock", json={"pin": "0000"}, headers=admin.headers)
    assert _err(r) == (401, "invalid_pin")
    r = await client.post("/api/admin/unlock", json={"pin": "1234"}, headers=user.headers)
    assert _err(r) == (403, "forbidden")
    r = await client.post("/api/admin/unlock", json={"pin": "12"}, headers=admin.headers)
    assert _err(r) == (422, "validation_error")
    # The admin token travels only in X-Admin-Token: a profile token is not an admin token.
    r = await client.get("/api/admin/scan", headers={**admin.headers, "X-Admin-Token": admin.token})
    assert _err(r) == (401, "admin_locked")


async def test_unlock_rate_limit(client, admin, clock):
    for _ in range(5):
        r = await client.post("/api/admin/unlock", json={"pin": "0000"}, headers=admin.headers)
        assert _err(r) == (401, "invalid_pin")
        clock.advance(2)
    # 6th attempt within 60 s is refused, even with the correct PIN.
    r = await client.post("/api/admin/unlock", json={"pin": "1234"}, headers=admin.headers)
    assert _err(r) == (429, "too_many_attempts")
    assert r.headers["Retry-After"] == "50"  # oldest failure at t=0 leaves at t=60; now t=10
    clock.advance(50)
    r = await client.post("/api/admin/unlock", json={"pin": "1234"}, headers=admin.headers)
    assert r.status_code == 200


async def test_unlock_rate_limit_holds_under_concurrent_attempts(client, admin, monkeypatch):
    # A slow verify keeps every attempt in flight at once: the limiter must count an attempt
    # before awaiting scrypt, so parallel requests cannot all pass the check.
    import time

    from hometv.api import routes_admin

    calls: list[str] = []

    def slow_wrong_verify(pin: str, pin_hash: str) -> bool:
        calls.append(pin)
        time.sleep(0.05)
        return False

    monkeypatch.setattr(routes_admin, "verify_pin", slow_wrong_verify)
    results = await asyncio.gather(
        *(
            client.post("/api/admin/unlock", json={"pin": "0000"}, headers=admin.headers)
            for _ in range(10)
        )
    )
    codes = sorted(_err(r) for r in results)
    assert codes == [(401, "invalid_pin")] * 5 + [(429, "too_many_attempts")] * 5
    assert len(calls) == 5


async def test_unlock_success_clears_counter(client, admin):
    for _ in range(4):
        await client.post("/api/admin/unlock", json={"pin": "0000"}, headers=admin.headers)
    r = await client.post("/api/admin/unlock", json={"pin": "1234"}, headers=admin.headers)
    assert r.status_code == 200
    for _ in range(4):
        r = await client.post("/api/admin/unlock", json={"pin": "0000"}, headers=admin.headers)
        assert _err(r) == (401, "invalid_pin")
    r = await client.post("/api/admin/unlock", json={"pin": "1234"}, headers=admin.headers)
    assert r.status_code == 200


# --- Auth matrix ---------------------------------------------------------------------------------

# (method, path template, concrete path, body). {uid} = a non-admin profile id.
ADMIN_ROUTES = [
    ("POST", "/api/admin/scan", "/api/admin/scan", None),
    ("GET", "/api/admin/scan", "/api/admin/scan", None),
    ("GET", "/api/admin/unmatched", "/api/admin/unmatched", None),
    (
        "POST",
        "/api/admin/unmatched/{entry_id}/resolve",
        "/api/admin/unmatched/1/resolve",
        {"tmdb_id": 1},
    ),
    ("GET", "/api/admin/tmdb/search", "/api/admin/tmdb/search?type=movie&q=dune", None),
    ("GET", "/api/admin/tmdb/tv/{tmdb_id}/seasons", "/api/admin/tmdb/tv/2316/seasons", None),
    (
        "GET",
        "/api/admin/tmdb/tv/{tmdb_id}/seasons/{season_number}",
        "/api/admin/tmdb/tv/2316/seasons/2",
        None,
    ),
    ("POST", "/api/admin/films/{film_id}/match", "/api/admin/films/1/match", {"tmdb_id": 1}),
    ("POST", "/api/admin/shows/{show_id}/match", "/api/admin/shows/1/match", {"tmdb_id": 1}),
    (
        "POST",
        "/api/admin/profiles",
        "/api/admin/profiles",
        {"name": "New", "avatar_id": "avatar-02"},
    ),
    ("PATCH", "/api/admin/profiles/{profile_id}", "/api/admin/profiles/{uid}", {"name": "Kid"}),
    ("DELETE", "/api/admin/profiles/{profile_id}", "/api/admin/profiles/{uid}", None),
    ("GET", "/api/admin/libraries", "/api/admin/libraries", None),
    ("PUT", "/api/admin/libraries", "/api/admin/libraries", {"films": [], "shows": []}),
    ("POST", "/api/admin/libraries/plan", "/api/admin/libraries/plan", {"films": [], "shows": []}),
    ("GET", "/api/admin/libraries/browse", "/api/admin/libraries/browse", None),
]


def test_matrix_covers_every_admin_route(app):
    declared = {
        (method.upper(), path)
        for path, operations in app.openapi()["paths"].items()
        if path.startswith("/api/admin") and path != "/api/admin/unlock"
        for method in operations
    }
    assert declared == {(method, template) for method, template, _, _ in ADMIN_ROUTES}


@pytest.mark.parametrize(
    ("method", "path", "body"),
    [(method, path, body) for method, _, path, body in ADMIN_ROUTES],
    ids=[f"{method} {template}" for method, template, _, _ in ADMIN_ROUTES],
)
async def test_admin_auth_matrix(
    client, app, db, admin, user, profile_factory, clock, method, path, body
):
    path = path.format(uid=user.id)
    tokens: p.TokenService = app.state.tokens
    other_admin = profile_factory("Javi", is_admin=True, pin="4321")

    async def call(headers):
        return await client.request(method, path, json=body, headers=headers)

    # No Bearer -> 401 unauthorized (even with a valid admin token).
    assert _err(await call({})) == (401, "unauthorized")
    assert _err(await call({"X-Admin-Token": admin.admin_token})) == (401, "unauthorized")
    # Non-admin Bearer -> 403 forbidden.
    assert _err(await call(user.headers)) == (403, "forbidden")
    # Admin Bearer without X-Admin-Token -> admin_locked.
    assert _err(await call(admin.headers)) == (401, "admin_locked")
    # Expired (> 15 min after issue).
    expired = tokens.issue_admin_token(admin.id, 1).token
    clock.advance(15 * 60 + 1)
    assert _err(await call({**admin.headers, "X-Admin-Token": expired})) == (401, "admin_locked")
    # Another profile's admin token.
    foreign = tokens.issue_admin_token(other_admin.id, 1).token
    assert _err(await call({**admin.headers, "X-Admin-Token": foreign})) == (401, "admin_locked")
    # Revoked: PIN changed (admin_token_version bumped).
    before = tokens.issue_admin_token(admin.id, 1).token
    p.update_profile(db, admin.id, {"pin": "5678"})
    assert _err(await call({**admin.headers, "X-Admin-Token": before})) == (401, "admin_locked")
    # Revoked: demoted then promoted again (two bumps) -> the old token stays revoked.
    javi_token = tokens.issue_admin_token(other_admin.id, 1).token
    p.update_profile(db, other_admin.id, {"is_admin": False})
    demoted = {**other_admin.headers, "X-Admin-Token": javi_token}
    assert _err(await call(demoted)) == (403, "forbidden")  # no longer admin: forbidden first
    p.update_profile(db, other_admin.id, {"is_admin": True, "pin": "4321"})
    assert _err(await call(demoted)) == (401, "admin_locked")
    # The admin token is never accepted from the query string.
    fresh = tokens.issue_admin_token(admin.id, 2).token
    sep = "&" if "?" in path else "?"
    r = await client.request(
        method,
        f"{path}{sep}X-Admin-Token={fresh}&admin_token={fresh}&x_admin_token={fresh}",
        json=body,
        headers=admin.headers,
    )
    assert _err(r) == (401, "admin_locked")
    # Both valid -> past auth.
    r = await call({**admin.headers, "X-Admin-Token": fresh})
    assert r.status_code not in (401, 403), r.text
    await app.state.scheduler.wait_idle()


# --- Scan ----------------------------------------------------------------------------------------


async def test_scan_trigger_status_and_conflict(client, app, admin, monkeypatch):
    release = asyncio.Event()

    class SlowScanner:
        def __init__(self, *args, **kwargs) -> None:
            pass

        async def run(self, report, run_id):
            await release.wait()
            return report

    monkeypatch.setattr(scheduler_module, "Scanner", SlowScanner)
    idle = await client.get("/api/admin/scan", headers=admin.admin_headers)
    assert idle.status_code == 200
    assert idle.json()["state"] == "idle" and idle.json()["unmatched_count"] == 0

    r = await client.post("/api/admin/scan", headers=admin.admin_headers)
    assert r.status_code == 202
    started = r.json()
    assert started["state"] == "running" and started["trigger"] == "manual"
    assert "unmatched_count" in started

    r = await client.post("/api/admin/scan", headers=admin.admin_headers)
    assert _err(r) == (409, "scan_in_progress")
    assert r.json()["error"]["details"]["scan"]["id"] == started["id"]

    release.set()
    await app.state.scheduler.wait_idle()
    done = (await client.get("/api/admin/scan", headers=admin.admin_headers)).json()
    assert done["id"] == started["id"] and done["state"] == "completed"
    assert done["phase"] == "done"


# --- Scan: change-triggered runs (HOMETV-1 prd2 FR-23) ------------------------------------------


async def test_idle_scan_status_has_null_scope_and_a_watcher(client, admin):
    body = (await client.get("/api/admin/scan", headers=admin.admin_headers)).json()
    assert body["scope"] is None
    assert body["watcher"] == {"state": "disabled", "unavailable_roots": [], "detail": None}


async def test_watch_run_status_has_trigger_and_scope_full_run_has_none(client, app, admin):
    from hometv.changes.scope import ScanScope

    scope = ScanScope("/media/películas", "A", True)
    app.state.scheduler.request_scoped([scope])
    await app.state.scheduler.wait_idle()
    body = (await client.get("/api/admin/scan", headers=admin.admin_headers)).json()
    assert (body["trigger"], body["state"]) == ("watch", "completed")
    assert body["scope"] == [
        {"root": "/media/películas", "path": "/media/películas/A", "recursive": True}
    ]
    r = await client.post("/api/admin/scan", headers=admin.admin_headers)
    assert r.status_code == 202 and r.json()["scope"] is None
    await app.state.scheduler.wait_idle()
    body = (await client.get("/api/admin/scan", headers=admin.admin_headers)).json()
    assert (body["trigger"], body["scope"]) == ("manual", None)


async def test_swapped_watcher_status_is_reflected(client, app, admin):
    from hometv.api.schemas import WatcherStatus

    error = WatcherStatus(
        state="error",
        unavailable_roots=[],
        detail="inotify watch limit reached (fs.inotify.max_user_watches)",
    )
    app.state.scheduler.watcher_status = lambda: error
    body = (await client.get("/api/admin/scan", headers=admin.admin_headers)).json()
    assert body["watcher"] == error.model_dump(mode="json")


async def test_watcher_detail_with_a_lone_surrogate_still_returns_200(client, app, admin):
    import os

    from hometv.api.schemas import WatcherStatus

    bad = os.fsdecode(b"\xff")
    status = WatcherStatus(state="error", unavailable_roots=[], detail=f"OSError: {bad}")
    app.state.scheduler.watcher_status = lambda: status
    r = await client.get("/api/admin/scan", headers=admin.admin_headers)
    assert r.status_code == 200
    assert r.json()["watcher"]["detail"] == "OSError: \\udcff"


async def test_undecodable_scope_never_reaches_the_api(client, app, admin):
    import os

    from hometv.changes.scope import ScanScope

    valid = ScanScope("/media/MKV", "", False)
    bad = ScanScope("/media/MKV", os.fsdecode(b"\xff"), True)
    app.state.scheduler.request_scoped([valid, bad])
    await app.state.scheduler.wait_idle()
    r = await client.get("/api/admin/scan", headers=admin.admin_headers)
    assert r.status_code == 200
    assert r.json()["scope"] == [valid.to_json()]


async def test_conflict_during_a_watch_run_carries_trigger_and_scope(
    client, app, admin, monkeypatch
):
    from hometv.changes.scope import ScanScope

    release = asyncio.Event()
    started = asyncio.Event()

    class SlowScanner:
        def __init__(self, *args, **kwargs) -> None:
            pass

        async def run(self, report, run_id):
            started.set()
            await release.wait()
            return report

    monkeypatch.setattr(scheduler_module, "Scanner", SlowScanner)
    scope = ScanScope("/media/series", "Breaking Bad", True)
    app.state.scheduler.request_scoped([scope])
    await started.wait()
    r = await client.post("/api/admin/scan", headers=admin.admin_headers)
    assert _err(r) == (409, "scan_in_progress")
    scan = r.json()["error"]["details"]["scan"]
    assert scan["trigger"] == "watch"
    assert scan["scope"] == [scope.to_json()]
    assert "watcher" in scan
    release.set()
    await app.state.scheduler.wait_idle()


async def test_openapi_exposes_scan_scope_and_watcher_status(client):
    schemas = (await client.get("/api/openapi.json")).json()["components"]["schemas"]
    assert {"ScanScopeOut", "WatcherStatus"} <= set(schemas)
    assert "watch" in schemas["ScanStatus"]["properties"]["trigger"]["anyOf"][0]["enum"]


# --- Unmatched -----------------------------------------------------------------------------------


def _seed_unmatched(db) -> tuple[int, int]:
    with db.write_session() as s:
        libs = ensure_libraries(s)
        film = m.Film(
            library_id=libs["films"],
            match_status="unmatched",
            parsed_title="Dune",
            parsed_title_norm="dune",
            parsed_year=2021,
            title="Dune",
            year=2021,
            sort_title="dune",
            search_text="dune",
        )
        show = m.Show(
            library_id=libs["shows"],
            match_status="unmatched",
            parsed_title="The Office",
            parsed_title_norm="the office",
            title="The Office",
            sort_title="office",
            search_text="the office",
        )
        s.add_all([film, show])
        s.flush()
        s.add(m.UnmatchedEntry(film_id=film.id, reason="low_confidence", detail="x"))
        s.add(m.UnmatchedEntry(show_id=show.id, reason="no_tmdb_results"))
        return film.id, show.id


async def test_unmatched_list_filters_and_count(client, db, admin):
    film_id, show_id = _seed_unmatched(db)
    h = admin.admin_headers
    page = (await client.get("/api/admin/unmatched", headers=h)).json()
    assert (page["total"], page["page"], page["page_size"]) == (2, 1, 50)
    films = (await client.get("/api/admin/unmatched?library=films", headers=h)).json()
    assert [i["target"] for i in films["items"]] == [
        {"type": "film", "id": film_id, "title": "Dune", "year": 2021}
    ]
    shows = (await client.get("/api/admin/unmatched?reason=no_tmdb_results", headers=h)).json()
    assert [i["target"]["id"] for i in shows["items"]] == [show_id]
    none = (
        await client.get("/api/admin/unmatched?library=shows&reason=low_confidence", headers=h)
    ).json()
    assert none["items"] == [] and none["total"] == 0
    r = await client.get("/api/admin/unmatched?reason=bogus", headers=h)
    assert _err(r) == (422, "validation_error")
    r = await client.get("/api/admin/unmatched?page_size=201", headers=h)
    assert _err(r) == (422, "validation_error")
    status = (await client.get("/api/admin/scan", headers=h)).json()
    assert status["unmatched_count"] == 2


async def test_resolve_errors_via_api(client, db, admin):
    film_id, _ = _seed_unmatched(db)
    with db.read_session() as s:
        entry_id = s.scalar(select(m.UnmatchedEntry.id).where(m.UnmatchedEntry.film_id == film_id))
    h = admin.admin_headers
    r = await client.post("/api/admin/unmatched/999/resolve", json={"tmdb_id": 1}, headers=h)
    assert _err(r) == (404, "not_found")
    r = await client.post(
        f"/api/admin/unmatched/{entry_id}/resolve", json={"tmdb_id": 1}, headers=h
    )
    assert _err(r) == (409, "not_a_file_entry")
    r = await client.post(f"/api/admin/unmatched/{entry_id}/resolve", json={}, headers=h)
    assert _err(r) == (422, "validation_error")


# --- TMDB proxies --------------------------------------------------------------------------------


class TestTmdbProxies:
    @pytest.fixture
    def settings(self, make_settings):
        return make_settings(tmdb={"api_key": "k"})

    @pytest.fixture
    def tmdb(self):
        with respx.mock(assert_all_called=False) as router:
            yield router

    async def test_seasons_and_season(self, client, admin, tmdb):
        seasons = tmdb.get(f"{TMDB_BASE_URL}/tv/2316").respond(json=load("tv_2316.json"))
        tmdb.get(f"{TMDB_BASE_URL}/tv/2316/season/2").respond(json=load("tv_2316_season_2_en.json"))
        h = admin.admin_headers
        r = await client.get("/api/admin/tmdb/tv/2316/seasons", headers=h)
        assert r.status_code == 200
        data = r.json()
        assert data["tmdb_id"] == 2316 and data["year"] == 2005
        assert [s["season_number"] for s in data["seasons"]] == [1, 2, 0]
        assert data["seasons"][1] == {
            "season_number": 2,
            "name": "Temporada 2",
            "episode_count": 22,
            "air_date": "2005-09-20",
        }
        await client.get("/api/admin/tmdb/tv/2316/seasons", headers=h)
        assert seasons.call_count == 1  # cache hit

        r = await client.get("/api/admin/tmdb/tv/2316/seasons/2", headers=h)
        assert r.json() == {
            "tmdb_id": 2316,
            "season_number": 2,
            "name": "Season 2",
            "episodes": [
                {"episode_number": 1, "title": "The Dundies", "air_date": "2005-09-20"},
                {"episode_number": 3, "title": "Office Olympics", "air_date": "2005-10-04"},
            ],
        }
        r = await client.get("/api/admin/tmdb/tv/2316/seasons/-1", headers=h)
        assert _err(r) == (422, "validation_error")

    async def test_search_and_errors(self, client, admin, tmdb, monkeypatch):
        from hometv.metadata import tmdb as tmdb_module

        monkeypatch.setattr(tmdb_module, "BACKOFF_SECONDS", (0, 0, 0))
        tmdb.get(f"{TMDB_BASE_URL}/search/movie").respond(json=load("search_movie_dune.json"))
        tmdb.get(f"{TMDB_BASE_URL}/tv/1").respond(404)
        tmdb.get(f"{TMDB_BASE_URL}/tv/2").respond(500)
        h = admin.admin_headers
        r = await client.get("/api/admin/tmdb/search?type=movie&q=dune&year=2021", headers=h)
        assert r.status_code == 200
        assert r.json()["results"][0]["tmdb_id"] == 438631
        for bad in ("?type=movie", "?type=movie&q=", f"?type=movie&q={'x' * 101}", "?type=x&q=a"):
            r = await client.get(f"/api/admin/tmdb/search{bad}", headers=h)
            assert _err(r) == (422, "validation_error"), bad
        assert _err(await client.get("/api/admin/tmdb/tv/1/seasons", headers=h)) == (
            404,
            "tmdb_not_found",
        )
        assert _err(await client.get("/api/admin/tmdb/tv/2/seasons", headers=h)) == (
            502,
            "tmdb_unavailable",
        )

    async def test_match_film_returns_detail_and_merges(self, client, db, admin, tmdb):
        tmdb.get(f"{TMDB_BASE_URL}/movie/438631", params={"language": "es-ES"}).respond(
            json=load("movie_438631.json")
        )
        tmdb.get(f"{TMDB_BASE_URL}/movie/438631", params={"language": "en-US"}).respond(
            json=load("movie_438631_en.json")
        )
        tmdb.get(url__regex=r"^https://image\.tmdb\.org/.*").respond(content=b"IMG")
        with db.write_session() as s:
            dune = add_film(s, "Dune", year=2021, match_status="pending")
            duna = add_film(s, "Duna", year=2021, match_status="unmatched")
            add_watchlist_entry(s, admin.id, film=duna)
            dune_id, duna_id = dune.id, duna.id
        h = admin.admin_headers
        r = await client.post(
            f"/api/admin/films/{dune_id}/match", json={"tmdb_id": 438631}, headers=h
        )
        assert r.status_code == 200, r.text
        detail = r.json()
        assert (detail["type"], detail["id"], detail["tmdb_id"], detail["match_status"]) == (
            "film",
            dune_id,
            438631,
            "matched",
        )
        assert len(detail["media_files"]) == 1 and detail["cast"]
        assert detail["in_watchlist"] is False
        r = await client.post(
            f"/api/admin/films/{duna_id}/match", json={"tmdb_id": 438631}, headers=h
        )
        assert r.status_code == 200 and r.json()["id"] == dune_id  # survivor of the merge
        assert len(r.json()["media_files"]) == 2
        assert r.json()["in_watchlist"] is True  # the loser's entry moved to the survivor
        r = await client.post("/api/admin/films/999/match", json={"tmdb_id": 438631}, headers=h)
        assert _err(r) == (404, "not_found")
        r = await client.post(f"/api/admin/films/{dune_id}/match", json={"tmdb_id": 0}, headers=h)
        assert _err(r) == (422, "validation_error")

    async def test_resolve_show_file_and_unmatched_count_drops(self, client, db, admin, tmdb):
        tmdb.get(f"{TMDB_BASE_URL}/tv/2316").respond(json=load("tv_2316.json"))
        tmdb.get(f"{TMDB_BASE_URL}/tv/2316/season/2").respond(json=load("tv_2316_season_2.json"))
        tmdb.get(url__regex=r"^https://image\.tmdb\.org/.*").respond(content=b"IMG")
        with db.write_session() as s:
            f = add_file(s, episode_ids=[], name="Lost 01.mkv")
            f.library_id = ensure_libraries(s)["shows"]
            entry = m.UnmatchedEntry(media_file_id=f.id, reason="no_season")
            s.add(entry)
            s.flush()
            entry_id, file_id = entry.id, f.id
        h = admin.admin_headers
        assert (await client.get("/api/admin/scan", headers=h)).json()["unmatched_count"] == 1
        body = {"tmdb_id": 2316, "season_number": 2, "episode_numbers": [3, 4]}
        r = await client.post(f"/api/admin/unmatched/{entry_id}/resolve", json=body, headers=h)
        assert r.status_code == 200, r.text
        target = r.json()["target"]
        assert target["type"] == "show"
        assert (await client.get("/api/admin/scan", headers=h)).json()["unmatched_count"] == 0
        season = (
            await client.get(f"/api/shows/{target['id']}/seasons/2", headers=admin.headers)
        ).json()
        assert [e["episode_number"] for e in season["episodes"]] == [3, 4]
        ep = (
            await client.get(f"/api/episodes/{season['episodes'][0]['id']}", headers=admin.headers)
        ).json()
        assert [x["id"] for x in ep["media_files"]] == [file_id]


async def test_tmdb_not_configured(client, admin):
    r = await client.get("/api/admin/tmdb/tv/2316/seasons", headers=admin.admin_headers)
    assert _err(r) == (503, "tmdb_not_configured")


# --- Profiles CRUD -------------------------------------------------------------------------------


async def test_profiles_crud(client, admin):
    h = admin.admin_headers
    r = await client.post(
        "/api/admin/profiles", json={"name": "Kids", "avatar_id": "avatar-04"}, headers=h
    )
    assert r.status_code == 201
    kids = r.json()
    assert set(kids) == {"id", "name", "avatar_id", "is_admin", "created_at"}
    assert kids["is_admin"] is False

    cases = [
        ({"name": "kids", "avatar_id": "avatar-01"}, (409, "name_taken")),
        ({"name": "Javi", "avatar_id": "avatar-02", "is_admin": True}, (422, "pin_required")),
        ({"name": "Javi", "avatar_id": "avatar-02", "pin": "4321"}, (422, "pin_not_allowed")),
        ({"name": "Javi", "avatar_id": "avatar-99"}, (422, "validation_error")),
        (
            {"name": "Javi", "avatar_id": "avatar-02", "is_admin": True, "pin": "43"},
            (
                422,
                "validation_error",
            ),
        ),
    ]
    for body, expected in cases:
        assert _err(await client.post("/api/admin/profiles", json=body, headers=h)) == expected

    url = f"/api/admin/profiles/{kids['id']}"
    assert _err(await client.patch(url, json={"is_admin": True}, headers=h)) == (
        422,
        "pin_required",
    )
    assert _err(await client.patch(url, json={"pin": "1111"}, headers=h)) == (
        422,
        "pin_not_allowed",
    )
    assert _err(await client.patch(url, json={"name": None}, headers=h)) == (
        422,
        "validation_error",
    )
    r = await client.patch(url, json={"is_admin": True, "pin": "1111"}, headers=h)
    assert r.status_code == 200 and r.json()["is_admin"] is True
    assert _err(await client.patch(url, json={"pin": None}, headers=h)) == (422, "pin_required")
    r = await client.patch(url, json={"is_admin": False}, headers=h)
    assert r.json()["is_admin"] is False
    assert _err(await client.patch("/api/admin/profiles/999", json={}, headers=h)) == (
        404,
        "not_found",
    )
    own = f"/api/admin/profiles/{admin.id}"
    assert _err(await client.patch(own, json={"is_admin": False}, headers=h)) == (
        409,
        "last_admin",
    )
    assert _err(await client.delete(own, headers=h)) == (409, "last_admin")
    assert (await client.delete(url, headers=h)).status_code == 204
    assert _err(await client.delete(url, headers=h)) == (404, "not_found")


async def test_editing_own_pin_revokes_own_admin_token(client, admin):
    h = admin.admin_headers
    r = await client.patch(f"/api/admin/profiles/{admin.id}", json={"pin": "9999"}, headers=h)
    assert r.status_code == 200
    assert _err(await client.get("/api/admin/scan", headers=h)) == (401, "admin_locked")
    r = await client.post("/api/admin/unlock", json={"pin": "9999"}, headers=admin.headers)
    assert r.status_code == 200


async def test_profile_limit_via_api(client, admin):
    h = admin.admin_headers
    for i in range(11):
        body = {"name": f"P{i}", "avatar_id": "avatar-01"}
        assert (await client.post("/api/admin/profiles", json=body, headers=h)).status_code == 201
    body = {"name": "P99", "avatar_id": "avatar-01"}
    assert _err(await client.post("/api/admin/profiles", json=body, headers=h)) == (
        409,
        "profile_limit_reached",
    )


async def test_admin_token_is_never_accepted_as_a_query_param(client, admin):
    """AC: X-Admin-Token is header-only (never read from the query string)."""
    token = admin.admin_token
    for params in ({"X-Admin-Token": token}, {"x-admin-token": token}, {"admin_token": token}):
        r = await client.get("/api/admin/scan", params=params, headers=admin.headers)
        assert (r.status_code, r.json()["error"]["code"]) == (401, "admin_locked"), params
    ok = await client.get("/api/admin/scan", headers=admin.admin_headers)
    assert ok.status_code == 200
