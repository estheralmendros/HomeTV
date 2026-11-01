"""PUT/DELETE /api/watchlist/{films|shows}/{id}, GET /api/watchlist, ``in_watchlist`` on detail
("Mi lista", FR-7/FR-8)."""

from datetime import timedelta

import pytest
from sqlalchemy import select

from hometv.db import models as m
from tests.factories import T0, add_file, add_film, add_progress, add_show, add_watchlist_entry

ENTRY_KEYS = {"kind", "item", "added_to_list_at"}
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
PROGRESS_KEYS = {
    "position_seconds",
    "duration_seconds",
    "watched",
    "watched_at",
    "media_file_id",
    "updated_at",
}


def _seed(db) -> tuple[int, int]:
    with db.write_session() as s:
        film = add_film(s, "Dune", year=2021, poster="/abc.jpg")
        show = add_show(s, "The Office", episodes={1: 2})[0]
        return film.id, show.id


async def _detail(client, who, kind: str, item_id: int) -> dict:
    r = await client.get(f"/api/{kind}/{item_id}", headers=who.headers)
    assert r.status_code == 200, r.text
    return r.json()


async def _list(client, who, query: str = "") -> dict:
    r = await client.get(f"/api/watchlist{query}", headers=who.headers)
    assert r.status_code == 200, r.text
    return r.json()


@pytest.mark.parametrize("kind", ["films", "shows"])
async def test_put_and_delete_toggle_in_watchlist(client, db, user, kind):
    film_id, show_id = _seed(db)
    item_id = film_id if kind == "films" else show_id
    url = f"/api/watchlist/{kind}/{item_id}"
    assert (await _detail(client, user, kind, item_id))["in_watchlist"] is False
    for _ in range(2):  # repeated calls included
        r = await client.put(url, headers=user.headers)
        assert (r.status_code, r.content) == (204, b"")
        assert (await _detail(client, user, kind, item_id))["in_watchlist"] is True
    for _ in range(2):
        r = await client.delete(url, headers=user.headers)
        assert (r.status_code, r.content) == (204, b"")
        assert (await _detail(client, user, kind, item_id))["in_watchlist"] is False


async def test_list_shape(client, db, user):
    film_id, show_id = _seed(db)
    with db.write_session() as s:
        file_id = s.scalar(select(m.MediaFile.id).where(m.MediaFile.film_id == film_id))
        add_progress(s, user.id, film_id=film_id, watched=True, media_file_id=file_id)
        add_watchlist_entry(s, user.id, film=s.get(m.Film, film_id), added_at=T0)
        add_watchlist_entry(
            s, user.id, show=s.get(m.Show, show_id), added_at=T0 - timedelta(days=1)
        )
    page = await _list(client, user)
    assert set(page) == {"items", "page", "page_size", "total"}
    assert (page["page"], page["page_size"], page["total"]) == (1, 50, 2)
    film, show = page["items"]
    assert set(film) == set(show) == ENTRY_KEYS
    assert (film["kind"], film["item"]["type"], film["item"]["id"]) == ("film", "film", film_id)
    assert (show["kind"], show["item"]["type"], show["item"]["id"]) == ("show", "show", show_id)
    assert set(film["item"]) == FILM_SUMMARY_KEYS  # no in_watchlist on summaries
    assert set(show["item"]) == SHOW_SUMMARY_KEYS
    assert film["added_to_list_at"] == "2026-01-01T00:00:00Z"
    assert show["added_to_list_at"] == "2025-12-31T00:00:00Z"
    assert film["item"]["poster"] is not None and set(film["item"]["poster"]) == {"small", "large"}
    assert set(film["item"]["progress"]) == PROGRESS_KEYS
    assert film["item"]["progress"]["watched"] is True
    assert (show["item"]["episode_count"], show["item"]["unwatched_episode_count"]) == (2, 2)


async def test_lists_are_per_profile(client, db, user, admin):
    film_id, show_id = _seed(db)
    assert (
        await client.put(f"/api/watchlist/films/{film_id}", headers=user.headers)
    ).status_code == 204
    assert (
        await client.put(f"/api/watchlist/shows/{show_id}", headers=user.headers)
    ).status_code == 204
    assert (await _list(client, user))["total"] == 2
    assert await _list(client, admin) == {"items": [], "page": 1, "page_size": 50, "total": 0}
    assert (await _detail(client, admin, "films", film_id))["in_watchlist"] is False
    assert (await _detail(client, admin, "shows", show_id))["in_watchlist"] is False
    # admin's DELETE never touches user's entry
    assert (
        await client.delete(f"/api/watchlist/films/{film_id}", headers=admin.headers)
    ).status_code == 204
    assert (await _detail(client, user, "films", film_id))["in_watchlist"] is True


async def test_filters_sorts_and_paging(client, db, user):
    with db.write_session() as s:
        items = [
            ("film", add_film(s, "Zodiac", year=2007)),
            ("show", add_show(s, "The Office", year=2005)[0]),
            ("film", add_film(s, "Alien", year=1979)),
            ("show", add_show(s, "Breaking Bad", year=2008)[0]),
        ]
        for i, (kind, item) in enumerate(items):
            add_watchlist_entry(s, user.id, **{kind: item}, added_at=T0 + timedelta(minutes=i))

    def names(page: dict) -> list[str]:
        return [e["item"]["title"] for e in page["items"]]

    assert names(await _list(client, user)) == ["Breaking Bad", "Alien", "The Office", "Zodiac"]
    assert names(await _list(client, user, "?sort=title")) == [
        "Alien",
        "Breaking Bad",
        "The Office",
        "Zodiac",
    ]
    assert names(await _list(client, user, "?sort=year")) == [
        "Breaking Bad",
        "Zodiac",
        "The Office",
        "Alien",
    ]
    films = await _list(client, user, "?type=films")
    assert (names(films), films["total"]) == (["Alien", "Zodiac"], 2)
    shows = await _list(client, user, "?type=shows&sort=title")
    assert (names(shows), shows["total"]) == (["Breaking Bad", "The Office"], 2)
    page2 = await _list(client, user, "?sort=title&page=2&page_size=3")
    assert (names(page2), page2["page"], page2["page_size"], page2["total"]) == (
        ["Zodiac"],
        2,
        3,
        4,
    )


async def test_watched_filter(client, db, user, admin):
    with db.write_session() as s:
        seen = add_film(s, "Alien")
        fresh = add_film(s, "Zodiac")
        done, done_eps = add_show(s, "Breaking Bad", episodes={1: 1})
        partial, partial_eps = add_show(s, "The Office", episodes={1: 2})
        add_progress(s, user.id, film_id=seen.id, watched=True)
        add_progress(s, admin.id, film_id=fresh.id, watched=True)
        add_progress(s, user.id, episode_id=done_eps[(1, 1)].id, watched=True)
        add_progress(s, user.id, episode_id=partial_eps[(1, 1)].id, watched=True)
        for item in (seen, fresh):
            add_watchlist_entry(s, user.id, film=item, added_at=T0)
        for item in (done, partial):
            add_watchlist_entry(s, user.id, show=item, added_at=T0)

    def names(page: dict) -> tuple[list[str], int]:
        return [e["item"]["title"] for e in page["items"]], page["total"]

    assert names(await _list(client, user, "?sort=title&watched=true")) == (
        ["Alien", "Breaking Bad"],
        2,
    )
    assert names(await _list(client, user, "?sort=title&watched=false")) == (
        ["The Office", "Zodiac"],
        2,
    )
    assert names(await _list(client, user, "?type=films&watched=false")) == (["Zodiac"], 1)
    assert names(await _list(client, user, "?type=shows&watched=true")) == (["Breaking Bad"], 1)
    assert (await _list(client, user, "?sort=title"))["total"] == 4


@pytest.mark.parametrize(
    ("query", "same_as"),
    [
        ("?sort=title&order=asc", "?sort=title"),
        ("?sort=title&order=desc", "?sort=title"),
        ("?sort=added&order=desc", "?sort=added"),
        ("?sort=added&order=asc", "?sort=added"),
        ("?order=asc", ""),
    ],
)
async def test_order_is_not_a_parameter(client, db, user, query, same_as):
    with db.write_session() as s:
        # added order (newest first): C, A, B; title order: A, B, C
        for minutes, title in [(1, "B title"), (2, "A title"), (3, "C title")]:
            add_watchlist_entry(
                s, user.id, film=add_film(s, title), added_at=T0 + timedelta(minutes=minutes)
            )
    with_order = await _list(client, user, query)
    without = await _list(client, user, same_as)
    assert with_order == without
    assert len(without["items"]) == 3


async def test_hidden_titles_are_not_listed_nor_counted(client, db, user):
    with db.write_session() as s:
        film = add_film(s, "Dune")
        hidden = add_film(s, "Gone", files=0)
        missing_file = add_file(s, film_id=hidden.id).id
        add_watchlist_entry(s, user.id, film=film, added_at=T0)
        add_watchlist_entry(s, user.id, film=hidden, added_at=T0 + timedelta(days=1))
        s.get(m.MediaFile, missing_file).missing_since = T0
    page = await _list(client, user)
    assert ([e["item"]["title"] for e in page["items"]], page["total"]) == (["Dune"], 1)
    with db.write_session() as s:
        s.get(m.MediaFile, missing_file).missing_since = None
    page = await _list(client, user)
    assert [(e["item"]["title"], e["added_to_list_at"]) for e in page["items"]] == [
        ("Gone", "2026-01-02T00:00:00Z"),
        ("Dune", "2026-01-01T00:00:00Z"),
    ]


async def test_not_found_envelope_for_unknown_and_hidden_ids(client, db, user):
    with db.write_session() as s:
        hidden_film = add_film(s, "Gone", files=0)
        add_file(s, film_id=hidden_film.id, missing=True)
        hidden_show = add_show(s, "Ghost", with_files=False)[0]
        cases = [
            ("films", 999, "Film 999 not found"),
            ("films", hidden_film.id, f"Film {hidden_film.id} not found"),
            ("shows", 999, "Show 999 not found"),
            ("shows", hidden_show.id, f"Show {hidden_show.id} not found"),
        ]
    for kind, item_id, message in cases:
        for method in ("PUT", "DELETE"):
            r = await client.request(
                method, f"/api/watchlist/{kind}/{item_id}", headers=user.headers
            )
            assert r.status_code == 404, (method, kind, item_id)
            error = r.json()["error"]
            assert (error["code"], error["message"]) == ("not_found", message)


@pytest.mark.parametrize(
    ("method", "url"),
    [
        ("PUT", "/api/watchlist/films/abc"),
        ("DELETE", "/api/watchlist/shows/abc"),
        ("GET", "/api/watchlist?type=episodes"),
        ("GET", "/api/watchlist?sort=rating"),
        ("GET", "/api/watchlist?page=0"),
        ("GET", "/api/watchlist?page=1000001"),
        ("GET", f"/api/watchlist?page={2**62}"),
        ("GET", "/api/watchlist?page_size=0"),
        ("GET", "/api/watchlist?page_size=201"),
        ("GET", "/api/watchlist?watched=1"),
        ("GET", "/api/watchlist?watched=yes"),
        ("GET", "/api/watchlist?watched=True"),
        ("GET", "/api/watchlist?watched="),
    ],
)
async def test_validation_errors(client, user, method, url):
    r = await client.request(method, url, headers=user.headers)
    assert r.status_code == 422
    assert r.json()["error"]["code"] == "validation_error"


async def test_page_size_bounds_accepted(client, user):
    assert (await _list(client, user, "?page_size=200"))["page_size"] == 200
    assert (await _list(client, user, "?page_size=1"))["page_size"] == 1


async def test_page_upper_bound_accepted(client, user):
    page = await _list(client, user, "?page=1000000&page_size=200")
    assert (page["page"], page["items"]) == (1_000_000, [])


@pytest.mark.parametrize(
    ("method", "path"),
    [
        ("GET", "/api/watchlist"),
        ("PUT", "/api/watchlist/films/{film}"),
        ("DELETE", "/api/watchlist/films/{film}"),
        ("PUT", "/api/watchlist/shows/{show}"),
        ("DELETE", "/api/watchlist/shows/{show}"),
    ],
)
async def test_bearer_required_and_query_token_rejected(client, db, user, method, path):
    film_id, show_id = _seed(db)
    url = path.format(film=film_id, show=show_id)
    for kwargs in ({}, {"params": {"token": user.token}}):
        r = await client.request(method, url, **kwargs)
        assert r.status_code == 401, (url, kwargs)
        assert r.json()["error"]["code"] == "unauthorized"
    with db.read_session() as s:
        assert s.scalars(select(m.WatchlistEntry)).all() == []


async def test_deleted_profile_token_is_unauthorized(client, db, admin, profile_factory):
    film_id, _ = _seed(db)
    kids = profile_factory("Kids2")
    assert (
        await client.put(f"/api/watchlist/films/{film_id}", headers=kids.headers)
    ).status_code == 204
    r = await client.delete(f"/api/admin/profiles/{kids.id}", headers=admin.admin_headers)
    assert r.status_code == 204, r.text
    for method in ("PUT", "DELETE", "GET"):
        url = "/api/watchlist" if method == "GET" else f"/api/watchlist/films/{film_id}"
        r = await client.request(method, url, headers=kids.headers)
        assert (r.status_code, r.json()["error"]["code"]) == (401, "unauthorized")


async def test_non_admin_profile_writes_without_admin_token(client, db, user):
    film_id, _ = _seed(db)
    r = await client.put(f"/api/watchlist/films/{film_id}", headers=user.headers)
    assert r.status_code == 204


async def test_routes_in_openapi(client):
    spec = (await client.get("/api/openapi.json")).json()
    paths = spec["paths"]
    expected = {
        "/api/watchlist": {"get"},
        "/api/watchlist/films/{film_id}": {"put", "delete"},
        "/api/watchlist/shows/{show_id}": {"put", "delete"},
    }
    for path, methods in expected.items():
        assert set(paths[path]) == methods
        for method in methods:
            assert paths[path][method]["tags"] == ["watchlist"]
    get_params = {p["name"] for p in paths["/api/watchlist"]["get"]["parameters"]}
    assert get_params == {"type", "sort", "watched", "page", "page_size"}
    schemas = spec["components"]["schemas"]
    assert set(schemas["WatchlistEntry"]["properties"]) == ENTRY_KEYS
    assert "in_watchlist" in schemas["FilmDetail"]["required"]
    assert "in_watchlist" in schemas["ShowDetail"]["required"]
    assert "in_watchlist" not in schemas["FilmSummary"]["properties"]
    assert "in_watchlist" not in schemas["ShowSummary"]["properties"]
