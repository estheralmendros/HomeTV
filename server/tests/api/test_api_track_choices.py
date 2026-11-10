"""Remembered audio/subtitle choice: PUT /api/track-choices/{films|shows}/{id} and
``track_choice`` on film detail, episode detail and the "Seguir viendo" episodes of /api/home."""

import pytest
from sqlalchemy import select

from hometv.db import models as m
from tests.factories import add_file, add_film, add_progress, add_show

CHOICE = {
    "audio": {"language": "eng", "title": None, "channels": 6},
    "subtitles": {
        "mode": "track",
        "language": "spa",
        "title": None,
        "forced": False,
        "hearing_impaired": False,
        "source": "embedded",
        "kind": "text",
    },
}
OFF_FULL = {
    "mode": "off",
    "language": None,
    "title": None,
    "forced": False,
    "hearing_impaired": False,
    "source": None,
    "kind": None,
}


def _seed(db) -> tuple[int, int, int]:
    with db.write_session() as s:
        film = add_film(s, "Dune", year=2021)
        show, eps = add_show(s, "The Office", episodes={1: 2})
        return film.id, show.id, eps[(1, 2)].id


def _url(kind: str, item_id: int | str) -> str:
    return f"/api/track-choices/{kind}/{item_id}"


async def _put(client, who, kind: str, item_id: int, body: dict) -> None:
    r = await client.put(_url(kind, item_id), json=body, headers=who.headers)
    assert (r.status_code, r.content) == (204, b""), r.text


async def _get(client, who, path: str) -> dict:
    r = await client.get(path, headers=who.headers)
    assert r.status_code == 200, r.text
    return r.json()


async def test_put_then_details_carry_the_choice_for_that_profile_only(client, db, user, admin):
    film_id, show_id, episode_id = _seed(db)
    await _put(client, user, "films", film_id, CHOICE)
    await _put(
        client,
        user,
        "shows",
        show_id,
        {"audio": {"language": "eng", "title": None, "channels": 2}, "subtitles": {"mode": "off"}},
    )
    assert (await _get(client, user, f"/api/films/{film_id}"))["track_choice"] == CHOICE
    episode = await _get(client, user, f"/api/episodes/{episode_id}")
    assert episode["track_choice"] == {
        "audio": {"language": "eng", "title": None, "channels": 2},
        "subtitles": OFF_FULL,
    }
    assert "track_choice" not in await _get(client, user, f"/api/shows/{show_id}")
    # Another profile never sees it.
    assert (await _get(client, admin, f"/api/films/{film_id}"))["track_choice"] is None
    assert (await _get(client, admin, f"/api/episodes/{episode_id}"))["track_choice"] is None


async def test_put_replaces_the_whole_choice(client, db, user):
    film_id, _, _ = _seed(db)
    await _put(client, user, "films", film_id, CHOICE)
    await _put(client, user, "films", film_id, {"subtitles": {"mode": "off"}})
    assert (await _get(client, user, f"/api/films/{film_id}"))["track_choice"] == {
        "audio": None,
        "subtitles": OFF_FULL,
    }
    await _put(client, user, "films", film_id, {"audio": {"language": " ENG "}, "subtitles": None})
    assert (await _get(client, user, f"/api/films/{film_id}"))["track_choice"] == {
        "audio": {"language": "eng", "title": None, "channels": None},
        "subtitles": None,
    }
    with db.read_session() as s:
        assert len(s.scalars(select(m.TrackChoice)).all()) == 1


async def test_home_episodes_carry_their_shows_choice(client, db, user):
    film_id, show_id, episode_id = _seed(db)
    with db.write_session() as s:
        _, other_eps = add_show(s, "Lost")
        add_progress(s, user.id, episode_id=episode_id, position=120, duration=1320)
        add_progress(s, user.id, episode_id=other_eps[(1, 1)].id, position=120, duration=1320)
        add_progress(s, user.id, film_id=film_id, position=120)
    await _put(client, user, "shows", show_id, CHOICE)
    await _put(client, user, "films", film_id, CHOICE)
    cards = (await _get(client, user, "/api/home"))["continue_watching"]
    by_show = {c["show_title"]: c["track_choice"] for c in cards if c["type"] == "episode"}
    assert by_show == {"The Office": CHOICE, "Lost": None}
    film_card = next(c for c in cards if c["type"] == "film")
    assert "track_choice" not in film_card  # films in "Seguir viendo" stay FilmSummary


async def test_unknown_and_hidden_titles_are_404(client, db, user):
    with db.write_session() as s:
        hidden = add_film(s, "Gone", files=0)
        add_file(s, film_id=hidden.id, missing=True)
        ghost = add_show(s, "Ghost", with_files=False)[0].id
        hidden_id = hidden.id
    for kind, item_id, message in (
        ("films", 999, "Film 999 not found"),
        ("films", hidden_id, f"Film {hidden_id} not found"),
        ("shows", 999, "Show 999 not found"),
        ("shows", ghost, f"Show {ghost} not found"),
    ):
        r = await client.put(_url(kind, item_id), json=CHOICE, headers=user.headers)
        assert r.status_code == 404, (kind, item_id)
        error = r.json()["error"]
        assert (error["code"], error["message"]) == ("not_found", message)


@pytest.mark.parametrize(
    "body",
    [
        {"audio": None, "subtitles": None},
        {},
        None,
        {"subtitles": {"mode": "track", "source": "embedded", "kind": "text"}},
        {"subtitles": {"mode": "track", "language": "spa", "source": "embedded"}},
        {"subtitles": {"mode": "track", "language": "spa", "kind": "text"}},
        {"subtitles": {"mode": "off", "language": "spa"}},
        {"subtitles": {"mode": "off", "kind": "text"}},
        {"subtitles": {**CHOICE["subtitles"], "source": "web"}},
        {"subtitles": {**CHOICE["subtitles"], "kind": "bitmap"}},
        {"subtitles": {**CHOICE["subtitles"], "forced": "yes"}},
        {"audio": {"language": "eng", "channels": 0}},
        {"audio": {"language": "eng", "channels": True}},
        {"audio": {"language": "eng", "channels": "6"}},
        {"audio": {"language": "eng", "channels": 6.0}},
        {"audio": {"language": "eng", "title": "t" * 1001}},
        {"audio": {"language": "eng"}, "video": 1},
    ],
)
async def test_bad_bodies_are_validation_errors(client, db, user, body):
    film_id, _, _ = _seed(db)
    kwargs = {} if body is None else {"json": body}
    r = await client.put(_url("films", film_id), headers=user.headers, **kwargs)
    assert r.status_code == 422
    assert r.json()["error"]["code"] == "validation_error"


async def test_non_integer_id_is_a_validation_error(client, user):
    r = await client.put(_url("films", "abc"), json=CHOICE, headers=user.headers)
    assert (r.status_code, r.json()["error"]["code"]) == (422, "validation_error")


@pytest.mark.parametrize("kind", ["films", "shows"])
async def test_bearer_required_and_query_token_rejected(client, db, user, kind):
    film_id, show_id, _ = _seed(db)
    url = _url(kind, film_id if kind == "films" else show_id)
    for kwargs in ({}, {"params": {"token": user.token}}):
        r = await client.put(url, json=CHOICE, **kwargs)
        assert r.status_code == 401, kwargs
        assert r.json()["error"]["code"] == "unauthorized"
    with db.read_session() as s:
        assert s.scalars(select(m.TrackChoice)).all() == []


async def test_deleted_profile_token_is_unauthorized(client, db, admin, profile_factory):
    film_id, _, _ = _seed(db)
    kids = profile_factory("Kids2")
    await _put(client, kids, "films", film_id, CHOICE)
    r = await client.delete(f"/api/admin/profiles/{kids.id}", headers=admin.admin_headers)
    assert r.status_code == 204, r.text
    r = await client.put(_url("films", film_id), json=CHOICE, headers=kids.headers)
    assert (r.status_code, r.json()["error"]["code"]) == (401, "unauthorized")
    with db.read_session() as s:
        assert s.scalars(select(m.TrackChoice)).all() == []  # cascaded


async def test_non_admin_profile_writes_and_admins_write_their_own_row(client, db, user, admin):
    film_id, _, _ = _seed(db)
    await _put(client, user, "films", film_id, CHOICE)
    r = await client.put(
        _url("films", film_id), json={"subtitles": {"mode": "off"}}, headers=admin.admin_headers
    )
    assert r.status_code == 204
    assert (await _get(client, user, f"/api/films/{film_id}"))["track_choice"] == CHOICE
    with db.read_session() as s:
        owners = sorted(r.profile_id for r in s.scalars(select(m.TrackChoice)))
    assert owners == sorted([user.id, admin.id])


async def test_routes_and_models_in_openapi(client):
    spec = (await client.get("/api/openapi.json")).json()
    paths = spec["paths"]
    for path in ("/api/track-choices/films/{film_id}", "/api/track-choices/shows/{show_id}"):
        assert set(paths[path]) == {"put"}
        assert paths[path]["put"]["tags"] == ["track-choices"]
        assert {"204", "401", "404", "422"} <= set(paths[path]["put"]["responses"])
    schemas = spec["components"]["schemas"]
    assert set(schemas["TrackChoice"]["properties"]) == {"audio", "subtitles"}
    assert set(schemas["AudioChoice"]["properties"]) == {"language", "title", "channels"}
    assert set(schemas["SubtitleChoice"]["properties"]) == set(OFF_FULL)
    for detail in ("FilmDetail", "EpisodeDetail", "HomeEpisode"):
        assert "track_choice" in schemas[detail]["required"]
    for unchanged in ("ShowDetail", "EpisodeSummary", "FilmSummary", "Profile"):
        assert "track_choice" not in schemas[unchanged]["properties"]
