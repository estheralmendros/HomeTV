"""Ratings and household activity: PUT/DELETE /api/ratings/{films|shows}/{id},
POST .../dismiss-prompt, GET /api/activity, the detail rating block and ``my_rating`` on every
summary-producing endpoint."""

from datetime import UTC, datetime, timedelta

import pytest
from sqlalchemy import select

from hometv.db import models as m
from tests.factories import (
    T0,
    add_cast_credit,
    add_file,
    add_film,
    add_person,
    add_rating,
    add_show,
    add_watchlist_entry,
)

ACTIVITY_KEYS = {"profile", "kind", "item", "stars", "rated_at"}
PROFILE_REF_KEYS = {"id", "name", "avatar_id"}
OTHER_RATING_KEYS = {"profile", "stars", "rated_at"}
FILM_SUMMARY_KEYS = {
    "type",
    "id",
    "title",
    "year",
    "poster",
    "backdrop",
    "overview",
    "match_status",
    "added_at",
    "progress",
    "my_rating",
}
SHOW_SUMMARY_KEYS = {
    "type",
    "id",
    "title",
    "year",
    "poster",
    "backdrop",
    "overview",
    "match_status",
    "added_at",
    "season_count",
    "episode_count",
    "unwatched_episode_count",
    "my_rating",
}


def _seed(db) -> tuple[int, int]:
    with db.write_session() as s:
        film = add_film(s, "Dune", year=2021, poster="/abc.jpg")
        show = add_show(s, "The Office", episodes={1: 2})[0]
        return film.id, show.id


def _url(kind: str, item_id: int | str, suffix: str = "") -> str:
    return f"/api/ratings/{kind}/{item_id}{suffix}"


async def _detail(client, who, kind: str, item_id: int) -> dict:
    r = await client.get(f"/api/{kind}/{item_id}", headers=who.headers)
    assert r.status_code == 200, r.text
    return r.json()


async def _activity(client, who, query: str = "") -> dict:
    r = await client.get(f"/api/activity{query}", headers=who.headers)
    assert r.status_code == 200, r.text
    return r.json()


async def _rate(client, who, kind: str, item_id: int, stars) -> None:
    r = await client.put(_url(kind, item_id), json={"stars": stars}, headers=who.headers)
    assert (r.status_code, r.content) == (204, b""), r.text


# --- Writes ----------------------------------------------------------------------------------


@pytest.mark.parametrize("kind", ["films", "shows"])
async def test_put_rates_and_detail_shows_mine_others_and_average(
    client, db, user, profile_factory, kind
):
    film_id, show_id = _seed(db)
    item_id = film_id if kind == "films" else show_id
    ana = profile_factory("Ana", avatar_id="avatar-02")
    await _rate(client, user, kind, item_id, 4.5)
    mine = await _detail(client, user, kind, item_id)
    assert (mine["my_rating"], mine["ratings"], mine["household_average"]) == (4.5, [], None)

    other = await _detail(client, ana, kind, item_id)
    assert other["my_rating"] is None
    [kids] = other["ratings"]
    assert set(kids) == OTHER_RATING_KEYS and set(kids["profile"]) == PROFILE_REF_KEYS
    assert kids["profile"] == {"id": user.id, "name": "Kids", "avatar_id": "avatar-04"}
    assert kids["stars"] == 4.5 and kids["rated_at"].endswith("Z")
    assert other["household_average"] is None

    await _rate(client, ana, kind, item_id, 4)  # an int JSON number
    for who, mine_stars, other_name in ((user, 4.5, "Ana"), (ana, 4.0, "Kids")):
        d = await _detail(client, who, kind, item_id)
        assert d["my_rating"] == mine_stars
        assert [o["profile"]["name"] for o in d["ratings"]] == [other_name]
        assert d["household_average"] == {"stars": 4.3, "count": 2}  # 4.25 half up


@pytest.mark.parametrize("stars", [0.5, 1, 1.5, 2, 2.5, 3, 3.5, 4, 4.5, 5, 5.0])
async def test_every_half_step_is_accepted(client, db, user, stars):
    film_id, _ = _seed(db)
    await _rate(client, user, "films", film_id, stars)
    assert (await _detail(client, user, "films", film_id))["my_rating"] == float(stars)


async def test_rerating_replaces_and_moves_the_row_to_the_top(client, db, user, admin):
    film_id, show_id = _seed(db)
    await _rate(client, user, "films", film_id, 2)
    await _rate(client, admin, "shows", show_id, 5)
    page = await _activity(client, user)
    assert [(e["kind"], e["stars"]) for e in page["items"]] == [("show", 5.0), ("film", 2.0)]
    await _rate(client, user, "films", film_id, 3.5)
    page = await _activity(client, user)
    assert [(e["kind"], e["stars"]) for e in page["items"]] == [("film", 3.5), ("show", 5.0)]
    with db.read_session() as s:
        assert len(s.scalars(select(m.Rating)).all()) == 2


@pytest.mark.parametrize("kind", ["films", "shows"])
async def test_delete_and_dismiss_are_idempotent_204s(client, db, user, kind):
    film_id, show_id = _seed(db)
    item_id = film_id if kind == "films" else show_id
    await _rate(client, user, kind, item_id, 3)
    for _ in range(2):
        r = await client.delete(_url(kind, item_id), headers=user.headers)
        assert (r.status_code, r.content) == (204, b"")
        assert (await _detail(client, user, kind, item_id))["my_rating"] is None
    for _ in range(2):
        r = await client.post(_url(kind, item_id, "/dismiss-prompt"), headers=user.headers)
        assert (r.status_code, r.content) == (204, b"")
    assert (await _activity(client, user))["items"] == []
    with db.read_session() as s:
        assert len(s.scalars(select(m.RatingDismissal)).all()) == 1  # DELETE recorded it


async def test_unknown_and_hidden_titles_are_404_on_every_write(client, db, user):
    with db.write_session() as s:
        hidden = add_film(s, "Gone", files=0)
        add_file(s, film_id=hidden.id, missing=True)
        ghost = add_show(s, "Ghost", with_files=False)[0].id
        hidden_id = hidden.id
    cases = [
        ("films", 999, "Film 999 not found"),
        ("films", hidden_id, f"Film {hidden_id} not found"),
        ("shows", 999, "Show 999 not found"),
        ("shows", ghost, f"Show {ghost} not found"),
    ]
    for kind, item_id, message in cases:
        for method, suffix, body in (
            ("PUT", "", {"stars": 3}),
            ("DELETE", "", None),
            ("POST", "/dismiss-prompt", None),
        ):
            r = await client.request(
                method, _url(kind, item_id, suffix), json=body, headers=user.headers
            )
            assert r.status_code == 404, (method, kind, item_id)
            error = r.json()["error"]
            assert (error["code"], error["message"]) == ("not_found", message)


@pytest.mark.parametrize(
    "body",
    [
        {"stars": 0},
        {"stars": 5.5},
        {"stars": 4.3},
        {"stars": "4.5"},
        {"stars": True},
        {"stars": None},
        {},
        None,
    ],
)
async def test_bad_bodies_are_validation_errors(client, db, user, body):
    film_id, _ = _seed(db)
    kwargs = {} if body is None else {"json": body}
    r = await client.put(_url("films", film_id), headers=user.headers, **kwargs)
    assert r.status_code == 422
    assert r.json()["error"]["code"] == "validation_error"


@pytest.mark.parametrize(
    ("method", "url"),
    [
        ("PUT", "/api/ratings/films/abc"),
        ("DELETE", "/api/ratings/shows/abc"),
        ("POST", "/api/ratings/films/abc/dismiss-prompt"),
        ("GET", "/api/activity?page=0"),
        ("GET", "/api/activity?page=1000001"),
        ("GET", "/api/activity?page_size=0"),
        ("GET", "/api/activity?page_size=201"),
    ],
)
async def test_validation_errors(client, user, method, url):
    kwargs = {"json": {"stars": 3}} if method == "PUT" else {}
    r = await client.request(method, url, headers=user.headers, **kwargs)
    assert r.status_code == 422
    assert r.json()["error"]["code"] == "validation_error"


# --- Auth ------------------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("method", "path"),
    [
        ("GET", "/api/activity"),
        ("PUT", "/api/ratings/films/{film}"),
        ("DELETE", "/api/ratings/films/{film}"),
        ("POST", "/api/ratings/films/{film}/dismiss-prompt"),
        ("PUT", "/api/ratings/shows/{show}"),
        ("DELETE", "/api/ratings/shows/{show}"),
        ("POST", "/api/ratings/shows/{show}/dismiss-prompt"),
    ],
)
async def test_bearer_required_and_query_token_rejected(client, db, user, method, path):
    film_id, show_id = _seed(db)
    url = path.format(film=film_id, show=show_id)
    body = {"json": {"stars": 3}} if method == "PUT" else {}
    for kwargs in ({}, {"params": {"token": user.token}}):
        r = await client.request(method, url, **kwargs, **body)
        assert r.status_code == 401, (url, kwargs)
        assert r.json()["error"]["code"] == "unauthorized"
    with db.read_session() as s:
        assert s.scalars(select(m.Rating)).all() == []
        assert s.scalars(select(m.RatingDismissal)).all() == []


async def test_writes_act_on_the_token_profile_only(client, db, user, admin):
    """An admin has no power over other profiles' rows: every write is the token's own."""
    film_id, _ = _seed(db)
    await _rate(client, user, "films", film_id, 2)
    r = await client.delete(_url("films", film_id), headers=admin.admin_headers)
    assert r.status_code == 204
    d = await _detail(client, user, "films", film_id)
    assert d["my_rating"] == 2.0
    with db.read_session() as s:
        [dismissal] = s.scalars(select(m.RatingDismissal)).all()
        assert dismissal.profile_id == admin.id


async def test_deleted_profile_token_is_unauthorized(client, db, admin, profile_factory):
    film_id, _ = _seed(db)
    kids = profile_factory("Kids2")
    await _rate(client, kids, "films", film_id, 3)
    r = await client.delete(f"/api/admin/profiles/{kids.id}", headers=admin.admin_headers)
    assert r.status_code == 204, r.text
    for method, url in (
        ("PUT", _url("films", film_id)),
        ("DELETE", _url("films", film_id)),
        ("POST", _url("films", film_id, "/dismiss-prompt")),
        ("GET", "/api/activity"),
    ):
        body = {"json": {"stars": 3}} if method == "PUT" else {}
        r = await client.request(method, url, headers=kids.headers, **body)
        assert (r.status_code, r.json()["error"]["code"]) == (401, "unauthorized")
    assert (await _activity(client, admin))["items"] == []  # cascaded


async def test_non_admin_profile_writes_without_admin_token(client, db, user):
    film_id, show_id = _seed(db)
    await _rate(client, user, "shows", show_id, 1)
    r = await client.post(_url("films", film_id, "/dismiss-prompt"), headers=user.headers)
    assert r.status_code == 204


# --- Activity --------------------------------------------------------------------------------


async def test_activity_shape_and_defaults(client, db, user, admin):
    film_id, show_id = _seed(db)
    with db.write_session() as s:
        add_rating(s, admin.id, film=s.get(m.Film, film_id), stars_x2=4, rated_at=T0)
        add_rating(
            s, user.id, show=s.get(m.Show, show_id), stars_x2=10, rated_at=T0 - timedelta(hours=1)
        )
        add_rating(
            s, user.id, film=s.get(m.Film, film_id), stars_x2=9, rated_at=T0 - timedelta(days=1)
        )
    page = await _activity(client, user)
    assert set(page) == {"items", "page", "page_size", "total"}
    assert (page["page"], page["page_size"], page["total"]) == (1, 50, 3)
    first, second, third = page["items"]
    assert set(first) == set(second) == ACTIVITY_KEYS
    assert first["profile"] == {"id": admin.id, "name": "Esther", "avatar_id": "avatar-05"}
    assert (first["kind"], first["stars"], first["rated_at"]) == (
        "film",
        2.0,
        "2026-01-01T00:00:00Z",
    )
    assert set(first["item"]) == FILM_SUMMARY_KEYS
    assert first["item"]["my_rating"] == 4.5  # the viewer's, not the row's
    assert set(first["item"]["poster"]) == {"small", "large"}
    assert (second["kind"], second["item"]["type"], second["stars"]) == ("show", "show", 5.0)
    assert set(second["item"]) == SHOW_SUMMARY_KEYS
    assert (third["profile"]["id"], third["stars"]) == (user.id, 4.5)
    small = await _activity(client, user, "?page=2&page_size=2")
    assert [e["stars"] for e in small["items"]] == [4.5] and small["total"] == 3
    assert (await _activity(client, user, "?page_size=200"))["page_size"] == 200


async def test_activity_hides_hidden_titles(client, db, user):
    film_id, _ = _seed(db)
    await _rate(client, user, "films", film_id, 3)
    with db.write_session() as s:
        for f in s.scalars(select(m.MediaFile).where(m.MediaFile.film_id == film_id)):
            f.missing_since = datetime.now(UTC)
    page = await _activity(client, user)
    assert (page["items"], page["total"]) == ([], 0)
    r = await client.get(f"/api/films/{film_id}", headers=user.headers)
    assert r.status_code == 404
    with db.write_session() as s:
        for f in s.scalars(select(m.MediaFile).where(m.MediaFile.film_id == film_id)):
            f.missing_since = None
    assert (await _activity(client, user))["total"] == 1


# --- my_rating on every summary --------------------------------------------------------------


async def test_my_rating_on_every_summary_endpoint(client, db, user, admin):
    with db.write_session() as s:
        person = add_person(s, "Timothée Chalamet")
        film = add_film(s, "Dune", added_at=datetime.now(UTC), tmdb_id=438631)
        show = add_show(s, "Dune: Prophecy", episodes={1: 1}, tmdb_id=90228)[0]
        add_cast_credit(s, person, film=film)
        add_cast_credit(s, person, show=show)
        for kw in ({"film": film}, {"show": show}):
            add_watchlist_entry(s, user.id, **kw)
            add_rating(s, admin.id, stars_x2=1, **kw)  # someone else's: never on my cards
        add_rating(s, user.id, film=film, stars_x2=7)
        add_rating(s, user.id, show=show, stars_x2=3)
        ids = {"film": film.id, "show": show.id, "person": person.id}

    expected = {("film", ids["film"]): 3.5, ("show", ids["show"]): 1.5}

    def check(items: list[dict]) -> None:
        assert items
        for item in items:
            assert item["my_rating"] == expected[(item["type"], item["id"])], item

    get = lambda url: client.get(url, headers=user.headers)  # noqa: E731
    check((await get("/api/films")).json()["items"])
    check((await get("/api/shows")).json()["items"])
    search = (await get("/api/search?q=dune")).json()
    check(search["films"] + search["shows"])
    check((await get("/api/home")).json()["recently_added"])
    check([e["item"] for e in (await get("/api/watchlist")).json()["items"]])
    check([a["item"] for a in (await get(f"/api/people/{ids['person']}")).json()["appearances"]])
    check([e["item"] for e in (await get("/api/activity")).json()["items"]])


# --- OpenAPI ---------------------------------------------------------------------------------


async def test_routes_and_models_in_openapi(client):
    spec = (await client.get("/api/openapi.json")).json()
    paths = spec["paths"]
    expected = {
        "/api/activity": {"get"},
        "/api/ratings/films/{film_id}": {"put", "delete"},
        "/api/ratings/shows/{show_id}": {"put", "delete"},
        "/api/ratings/films/{film_id}/dismiss-prompt": {"post"},
        "/api/ratings/shows/{show_id}/dismiss-prompt": {"post"},
    }
    for path, methods in expected.items():
        assert set(paths[path]) == methods
        for method in methods:
            assert paths[path][method]["tags"] == ["ratings"]
    get_params = {p["name"] for p in paths["/api/activity"]["get"]["parameters"]}
    assert get_params == {"page", "page_size"}
    schemas = spec["components"]["schemas"]
    assert set(schemas["ActivityEntry"]["properties"]) == ACTIVITY_KEYS
    assert set(schemas["RatingRequest"]["properties"]) == {"stars"}
    assert set(schemas["RatingPrompt"]["properties"]) == {"kind", "id", "title", "poster"}
    assert set(schemas["OtherRating"]["properties"]) == OTHER_RATING_KEYS
    assert set(schemas["HouseholdAverage"]["properties"]) == {"stars", "count"}
    assert set(schemas["ProfileRef"]["properties"]) == PROFILE_REF_KEYS
    for detail in ("FilmDetail", "ShowDetail"):
        assert {"my_rating", "ratings", "household_average"} <= set(schemas[detail]["required"])
    for summary in ("FilmSummary", "ShowSummary"):
        assert "my_rating" in schemas[summary]["required"]
    assert "rating_prompt" in schemas["ProgressReport"]["required"]
    assert "rating_prompt" in schemas["WatchedResponse"]["required"]
    progress_put = paths["/api/progress"]["put"]["responses"]["200"]["content"]
    assert progress_put["application/json"]["schema"]["$ref"].endswith("/ProgressReport")
