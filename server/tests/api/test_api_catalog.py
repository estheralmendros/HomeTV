"""GET /api/home, /api/search, /api/genres (PRD §9 home rows, §10, REST "Catalog")."""

from datetime import timedelta

import pytest

from tests.factories import T0, add_file, add_film, add_progress, add_show

BACKDROP = {"small": "/api/artwork/w780/ob.jpg", "large": "/api/artwork/w1280/ob.jpg"}


async def _home(client, who, query: str = "") -> dict:
    r = await client.get(f"/api/home{query}", headers=who.headers)
    assert r.status_code == 200, r.text
    return r.json()


async def test_home_empty_library(client, user):
    home = await _home(client, user)
    assert home == {
        "continue_watching": [],
        "next_up": [],
        "recently_added": [],
        "library": {"films": 0, "shows": 0, "empty": True},
    }


async def test_home_library_counts_with_empty_rows(client, db, user):
    with db.write_session() as s:
        add_film(s, "Dune")
        gone = add_film(s, "Gone", files=0)
        add_file(s, film_id=gone.id, missing=True)
        add_show(s, "Office", episodes={1: 1})
    home = await _home(client, user)
    assert home["library"] == {"films": 1, "shows": 1, "empty": False}
    assert home["continue_watching"] == [] and home["next_up"] == []


async def test_continue_watching(client, db, user, admin):
    with db.write_session() as s:
        dune = add_film(s, "Dune", backdrop="db.jpg", overview="Arena")
        short = add_film(s, "Short")
        done = add_film(s, "Done")
        _show, eps = add_show(s, "Office", episodes={1: 2}, backdrop="ob.jpg", poster="op.jpg")
        add_progress(s, user.id, film_id=dune.id, position=600.0, updated_at=T0)
        add_progress(s, user.id, film_id=short.id, position=29.0)  # < 30 s
        add_progress(s, user.id, film_id=done.id, position=900.0, watched=True)
        add_progress(
            s,
            user.id,
            episode_id=eps[(1, 2)].id,
            position=120.0,
            updated_at=T0 + timedelta(hours=1),
        )
        add_progress(s, admin.id, film_id=short.id, position=500.0)  # other profile
        dune_id, ep_id = dune.id, eps[(1, 2)].id
    home = await _home(client, user)
    cw = home["continue_watching"]
    assert [(x["type"], x["id"]) for x in cw] == [("episode", ep_id), ("film", dune_id)]
    assert cw[1]["backdrop"] == {
        "small": "/api/artwork/w780/db.jpg",
        "large": "/api/artwork/w1280/db.jpg",
    }
    assert cw[1]["overview"] == "Arena"
    episode = cw[0]
    assert episode["show_backdrop"] == BACKDROP
    assert episode["progress"]["position_seconds"] == 120.0
    detail = (await client.get(f"/api/episodes/{ep_id}", headers=user.headers)).json()
    assert episode["media_files"] == detail["media_files"]
    assert {k: v for k, v in episode.items() if k != "media_files"} == {
        k: v
        for k, v in detail.items()
        if k not in ("media_files", "previous_episode_id", "next_episode_id")
    }
    assert [x["id"] for x in (await _home(client, user, "?limit=1"))["continue_watching"]] == [
        ep_id
    ]


async def test_next_up(client, db, user):
    with db.write_session() as s:
        _office, oe = add_show(s, "Office", episodes={0: 1, 1: 3}, backdrop="ob.jpg")
        _lost, le = add_show(s, "Lost", episodes={1: 2})
        _busy, be = add_show(s, "Busy", episodes={1: 2})
        _done, de = add_show(s, "Done", episodes={1: 1})
        _specials, se = add_show(s, "Specials", episodes={0: 1, 1: 1})
        add_progress(
            s, user.id, episode_id=oe[(1, 1)].id, watched=True, watched_at=T0 + timedelta(days=1)
        )
        add_progress(
            s, user.id, episode_id=le[(1, 1)].id, watched=True, watched_at=T0 + timedelta(days=2)
        )
        add_progress(s, user.id, episode_id=be[(1, 1)].id, watched=True)
        add_progress(s, user.id, episode_id=be[(1, 2)].id, position=300.0)  # in progress
        add_progress(s, user.id, episode_id=de[(1, 1)].id, watched=True)
        add_progress(s, user.id, episode_id=se[(0, 1)].id, watched=True)  # specials only
        ids = oe[(1, 2)].id, le[(1, 2)].id, be[(1, 2)].id
    home = await _home(client, user)
    # Ordered by the show's latest watched_at DESC; in-progress next goes to Continue Watching.
    assert [x["id"] for x in home["next_up"]] == [ids[1], ids[0]]
    assert [x["id"] for x in home["continue_watching"]] == [ids[2]]
    office_next = home["next_up"][1]
    assert office_next["type"] == "episode" and office_next["show_backdrop"] == BACKDROP
    assert office_next["progress"] is None
    detail = (await client.get(f"/api/episodes/{ids[0]}", headers=user.headers)).json()
    assert office_next["media_files"] == detail["media_files"]
    assert len(office_next["media_files"]) == 1


async def test_recently_added_merges_films_and_shows(client, db, user):
    with db.write_session() as s:
        add_film(s, "Old", added_at=T0)
        add_show(
            s,
            "Fresh Show",
            content_updated_at=T0 + timedelta(days=3),
            backdrop="ob.jpg",
            overview="Nuevo",
        )
        add_film(s, "New", added_at=T0 + timedelta(days=2))
        gone = add_film(s, "Gone", files=0, added_at=T0 + timedelta(days=9))
        add_file(s, film_id=gone.id, missing=True)
    home = await _home(client, user)
    ra = home["recently_added"]
    assert [(x["type"], x["title"]) for x in ra] == [
        ("show", "Fresh Show"),
        ("film", "New"),
        ("film", "Old"),
    ]
    assert ra[0]["backdrop"] == BACKDROP and ra[0]["overview"] == "Nuevo"
    assert "season_count" in ra[0] and "progress" in ra[1]
    assert len((await _home(client, user, "?limit=2"))["recently_added"]) == 2


@pytest.mark.parametrize("query", ["?limit=0", "?limit=51"])
async def test_home_limit_validation(client, user, query):
    r = await client.get(f"/api/home{query}", headers=user.headers)
    assert r.status_code == 422


async def test_search(client, db, user):
    with db.write_session() as s:
        add_film(s, "The Office Job", backdrop="fb.jpg", overview="Robo")
        add_film(s, "An Office Party")
        add_film(s, "Dune")
        add_show(s, "The Office", episodes={1: 1}, backdrop="ob.jpg")
        add_show(s, "Oficina", episodes={1: 1})
    r = await client.get("/api/search?q=office", headers=user.headers)
    assert r.status_code == 200
    data = r.json()
    assert set(data) == {"films", "shows"}
    assert [x["title"] for x in data["films"]] == ["The Office Job", "An Office Party"]
    assert data["films"][0]["backdrop"] == {
        "small": "/api/artwork/w780/fb.jpg",
        "large": "/api/artwork/w1280/fb.jpg",
    }
    assert data["films"][0]["overview"] == "Robo"
    assert [x["title"] for x in data["shows"]] == ["The Office"]
    assert data["shows"][0]["backdrop"] == BACKDROP
    data = (await client.get("/api/search?q=office&limit=1", headers=user.headers)).json()
    assert len(data["films"]) == 1
    for bad in ("", "?q=", f"?q={'x' * 101}", "?q=a&limit=0", "?q=a&limit=51"):
        r = await client.get(f"/api/search{bad}", headers=user.headers)
        assert r.status_code == 422, bad


async def test_genres(client, db, user):
    with db.write_session() as s:
        add_film(s, "A", genres=((18, "Drama"), (878, "Ciencia ficción")))
        add_film(s, "B", genres=((18, "Drama"),))
        gone = add_film(s, "C", files=0, genres=((99, "Documental"),))
        add_file(s, film_id=gone.id, missing=True)
        add_show(s, "S", episodes={1: 1}, genres=((35, "Comedia"),))
    r = await client.get("/api/genres?type=films", headers=user.headers)
    assert r.json() == {
        "items": [
            {"id": 878, "name": "Ciencia ficción", "count": 1},
            {"id": 18, "name": "Drama", "count": 2},
        ]
    }
    r = await client.get("/api/genres?type=shows", headers=user.headers)
    assert r.json() == {"items": [{"id": 35, "name": "Comedia", "count": 1}]}
    assert (await client.get("/api/genres", headers=user.headers)).status_code == 422


@pytest.mark.parametrize(
    "path", ["/api/home", "/api/search?q=a", "/api/genres?type=films", "/api/me"]
)
async def test_query_token_rejected_outside_media_endpoints(client, user, path):
    sep = "&" if "?" in path else "?"
    r = await client.get(f"{path}{sep}token={user.token}")
    assert r.status_code == 401 and r.json()["error"]["code"] == "unauthorized"
