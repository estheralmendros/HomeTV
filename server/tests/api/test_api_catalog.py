"""GET /api/home, /api/search, /api/genres."""

from datetime import timedelta

import pytest

from tests.factories import T0, add_episode, add_file, add_film, add_progress, add_show

BACKDROP = {"small": "/api/artwork/w780/ob.jpg", "large": "/api/artwork/w1280/ob.jpg"}


async def _home(client, who, query: str = "") -> dict:
    r = await client.get(f"/api/home{query}", headers=who.headers)
    assert r.status_code == 200, r.text
    return r.json()


async def test_home_empty_library(client, user):
    home = await _home(client, user)
    assert home == {
        "continue_watching": [],
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
    assert home["continue_watching"] == [] and "next_up" not in home


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
    assert set(episode) == set(detail) - {"previous_episode_id", "next_episode_id"}
    assert "track_choice" in episode and episode["track_choice"] is None
    assert {k: v for k, v in episode.items() if k != "media_files"} == {
        k: v
        for k, v in detail.items()
        if k not in ("media_files", "previous_episode_id", "next_episode_id")
    }
    assert [x["id"] for x in (await _home(client, user, "?limit=1"))["continue_watching"]] == [
        ep_id
    ]


async def test_next_up_episodes_join_continue_watching(client, db, user):
    """One Seguir viendo row with in-progress items and each started show's next episode."""
    with db.write_session() as s:
        _office, oe = add_show(s, "Office", episodes={0: 1, 1: 3}, backdrop="ob.jpg")
        _lost, le = add_show(s, "Lost", episodes={1: 2})
        _busy, be = add_show(s, "Busy", episodes={1: 2})
        _done, de = add_show(s, "Done", episodes={1: 1})
        _specials, se = add_show(s, "Specials", episodes={0: 1, 1: 1})
        _skip, ke = add_show(s, "Skip", episodes={1: 3})
        dune = add_film(s, "Dune")
        day = timedelta(days=1)
        add_progress(s, user.id, episode_id=oe[(1, 1)].id, watched=True, watched_at=T0 + day)
        add_progress(s, user.id, episode_id=le[(1, 1)].id, watched=True, watched_at=T0 + 2 * day)
        add_progress(s, user.id, episode_id=be[(1, 1)].id, watched=True, watched_at=T0)
        add_progress(s, user.id, episode_id=be[(1, 2)].id, position=300.0, updated_at=T0 + 3 * day)
        add_progress(s, user.id, film_id=dune.id, position=600.0, updated_at=T0 + 1.5 * day)
        add_progress(s, user.id, episode_id=de[(1, 1)].id, watched=True)  # no next episode
        add_progress(s, user.id, episode_id=se[(0, 1)].id, watched=True)  # specials only
        # Skip: E3 abandoned at T0, E1 watched later -> the latest activity wins: the
        # show's one card is its Next Up (E2), dated by that watched_at.
        add_progress(s, user.id, episode_id=ke[(1, 1)].id, watched=True, watched_at=T0 + 4 * day)
        add_progress(s, user.id, episode_id=ke[(1, 3)].id, position=200.0, updated_at=T0)
        busy, lost, office, skip = be[(1, 2)].id, le[(1, 2)].id, oe[(1, 2)].id, ke[(1, 2)].id
        dune_id = dune.id
    home = await _home(client, user)
    assert "next_up" not in home
    # Newest first: in-progress by progress updated_at, Next Up by the show's latest watched_at.
    assert [(x["type"], x["id"]) for x in home["continue_watching"]] == [
        ("episode", skip),
        ("episode", busy),
        ("episode", lost),
        ("film", dune_id),
        ("episode", office),
    ]
    office_next = home["continue_watching"][4]
    assert office_next["show_backdrop"] == BACKDROP and office_next["progress"] is None
    detail = (await client.get(f"/api/episodes/{office}", headers=user.headers)).json()
    assert office_next["media_files"] == detail["media_files"]
    assert len(office_next["media_files"]) == 1
    limited = (await _home(client, user, "?limit=2"))["continue_watching"]
    assert [x["id"] for x in limited] == [skip, busy]


def _cw(home: dict) -> list[tuple[str, int]]:
    return [(x["type"], x["id"]) for x in home["continue_watching"]]


async def test_one_card_per_show_with_two_in_progress_episodes(client, db, user):
    """Only the most recently updated in-progress episode of a show, limit still filled."""
    day = timedelta(days=1)
    with db.write_session() as s:
        _show, eps = add_show(s, "Office", episodes={1: 3})
        dune = add_film(s, "Dune")
        add_progress(s, user.id, episode_id=eps[(1, 1)].id, position=100.0, updated_at=T0 + day)
        add_progress(s, user.id, episode_id=eps[(1, 3)].id, position=100.0, updated_at=T0 + 3 * day)
        add_progress(s, user.id, film_id=dune.id, position=600.0, updated_at=T0)
        newer, dune_id = eps[(1, 3)].id, dune.id
    assert _cw(await _home(client, user)) == [("episode", newer), ("film", dune_id)]
    assert _cw(await _home(client, user, "?limit=2")) == [("episode", newer), ("film", dune_id)]


async def test_old_abandoned_episode_does_not_hide_show_watched_today(client, db, user):
    """The show's latest watched_at is newer than its in-progress episode -> Next Up."""
    day = timedelta(days=1)
    with db.write_session() as s:
        _show, eps = add_show(s, "Office", episodes={1: 4})
        add_progress(s, user.id, episode_id=eps[(1, 3)].id, position=100.0, updated_at=T0)
        add_progress(s, user.id, episode_id=eps[(1, 1)].id, watched=True, watched_at=T0 + 10 * day)
        films = [add_film(s, f"Film {i}") for i in range(3)]
        for i, film in enumerate(films):
            add_progress(s, user.id, film_id=film.id, position=600.0, updated_at=T0 + (i + 1) * day)
        next_up, film_ids = eps[(1, 2)].id, [f.id for f in films]
    assert _cw(await _home(client, user, "?limit=2")) == [
        ("episode", next_up),
        ("film", film_ids[2]),
    ]
    assert _cw(await _home(client, user)) == [
        ("episode", next_up),
        *[("film", f) for f in reversed(film_ids)],
    ]


async def test_in_progress_newer_than_watched_represents_show(client, db, user):
    day = timedelta(days=1)
    with db.write_session() as s:
        _show, eps = add_show(s, "Office", episodes={1: 4})
        add_progress(s, user.id, episode_id=eps[(1, 1)].id, watched=True, watched_at=T0)
        add_progress(s, user.id, episode_id=eps[(1, 3)].id, position=100.0, updated_at=T0 + day)
        in_progress = eps[(1, 3)].id
    assert _cw(await _home(client, user)) == [("episode", in_progress)]


async def test_watched_newer_without_next_up_falls_back_to_in_progress(client, db, user):
    """Next is the in-progress episode itself (no Next Up): its in-progress card stays."""
    day = timedelta(days=1)
    with db.write_session() as s:
        _show, eps = add_show(s, "Office", episodes={1: 3})
        add_progress(s, user.id, episode_id=eps[(1, 2)].id, position=100.0, updated_at=T0)
        add_progress(s, user.id, episode_id=eps[(1, 1)].id, watched=True, watched_at=T0 + day)
        in_progress = eps[(1, 2)].id
    assert _cw(await _home(client, user)) == [("episode", in_progress)]


async def test_continue_watching_ties(client, db, user):
    """Equal dates: in-progress before Next Up, then film before episode, then the higher id."""
    with db.write_session() as s:
        films = [add_film(s, f"Film {i}") for i in range(2)]
        _a, ae = add_show(s, "A", episodes={1: 2})
        _b, be = add_show(s, "B", episodes={1: 2})
        for film in films:
            add_progress(s, user.id, film_id=film.id, position=600.0, updated_at=T0)
        add_progress(s, user.id, episode_id=ae[(1, 1)].id, position=100.0, updated_at=T0)
        add_progress(s, user.id, episode_id=be[(1, 1)].id, watched=True, watched_at=T0)
        ids = (films[0].id, films[1].id, ae[(1, 1)].id, be[(1, 2)].id)
    assert _cw(await _home(client, user)) == [
        ("film", ids[1]),
        ("film", ids[0]),
        ("episode", ids[2]),
        ("episode", ids[3]),
    ]


async def test_null_watched_at_does_not_break_home(client, db, user):
    """A watched row without watched_at (legacy data) sorts as the oldest activity."""
    with db.write_session() as s:
        _a, ae = add_show(s, "A", episodes={1: 2})
        _b, be = add_show(s, "B", episodes={1: 2})
        legacy = add_progress(s, user.id, episode_id=ae[(1, 1)].id, watched=True)
        legacy.watched_at = None
        add_progress(s, user.id, episode_id=be[(1, 1)].id, watched=True, watched_at=T0)
        ids = (be[(1, 2)].id, ae[(1, 2)].id)
    assert _cw(await _home(client, user)) == [("episode", ids[0]), ("episode", ids[1])]


async def test_next_up_skips_missing_candidate(client, db, user):
    """Visibility: a Next Up candidate without a present file is skipped."""
    with db.write_session() as s:
        show, eps = add_show(s, "Office", episodes={1: 1})
        gone = add_episode(s, show, 1, 2, with_file=False)
        add_file(s, episode_ids=[gone.id], missing=True)
        third = add_episode(s, show, 1, 3)
        add_progress(s, user.id, episode_id=eps[(1, 1)].id, watched=True)
        third_id = third.id
    assert _cw(await _home(client, user)) == [("episode", third_id)]


async def test_show_with_only_missing_watched_episodes_has_no_card(client, db, user):
    with db.write_session() as s:
        show, _eps = add_show(s, "Office", episodes={1: 0})
        gone = add_episode(s, show, 1, 1, with_file=False)
        add_file(s, episode_ids=[gone.id], missing=True)
        add_episode(s, show, 1, 2)
        add_progress(s, user.id, episode_id=gone.id, watched=True)
    assert _cw(await _home(client, user)) == []


async def test_missing_in_progress_episode_neither_shows_nor_suppresses(client, db, user):
    day = timedelta(days=1)
    with db.write_session() as s:
        show, eps = add_show(s, "Office", episodes={1: 2})
        gone = add_episode(s, show, 1, 3, with_file=False)
        add_file(s, episode_ids=[gone.id], missing=True)
        add_progress(s, user.id, episode_id=eps[(1, 1)].id, watched=True, watched_at=T0)
        add_progress(s, user.id, episode_id=gone.id, position=100.0, updated_at=T0 + day)
        next_id = eps[(1, 2)].id
    assert _cw(await _home(client, user)) == [("episode", next_id)]


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
