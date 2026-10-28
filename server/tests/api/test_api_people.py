"""GET /api/people/{person_id} (actor screen FR-11..13)."""

from datetime import UTC, date, datetime, timedelta
from typing import Any

from hometv.metadata.tmdb import BIOGRAPHY_LANGUAGE_KEY, TmdbError
from hometv.people import PersonService
from tests.factories import (
    add_cast_credit,
    add_film,
    add_person,
    add_person_credit,
    add_progress,
    add_show,
)

PERSON_KEYS = {
    "person_id",
    "name",
    "profile_image",
    "biography",
    "biography_language",
    "birthday",
    "deathday",
    "place_of_birth",
    "credits_complete",
    "appearances",
}


def _seed(db) -> dict[str, int]:
    with db.write_session() as s:
        person = add_person(
            s,
            "Timothée Chalamet",
            profile_path="tc.jpg",
            biography="Actor estadounidense.",
            biography_language="es-ES",
            birthday=date(1995, 12, 27),
            place_of_birth="Manhattan, New York City, New York, USA",
            details_fetched_at=datetime.now(UTC),
        )
        dune = add_film(s, "Dune", year=2021, tmdb_id=438631, poster="p.jpg")
        wonka = add_film(s, "Wonka", year=2023, tmdb_id=787699)
        homeland, episodes = add_show(s, "Homeland", year=2011, tmdb_id=1407, episodes={1: 3})
        add_cast_credit(s, person, film=dune, character="Paul Atreides")
        add_person_credit(s, person, "movie", 787699, character="Willy Wonka")
        add_person_credit(s, person, "tv", 1407, character="Finn Walden")
        return {
            "person": person.id,
            "dune": dune.id,
            "wonka": wonka.id,
            "homeland": homeland.id,
            "episode": episodes[(1, 1)].id,
        }


async def test_shape_and_appearances(client, db, user):
    ids = _seed(db)
    r = await client.get(f"/api/people/{ids['person']}", headers=user.headers)
    assert r.status_code == 200, r.text
    body = r.json()
    assert set(body) == PERSON_KEYS
    assert body["person_id"] == ids["person"]
    assert body["profile_image"] == {
        "small": "/api/artwork/w185/tc.jpg",
        "large": "/api/artwork/h632/tc.jpg",
    }
    assert body["birthday"] == "1995-12-27" and body["deathday"] is None
    assert body["biography_language"] == "es-ES"
    assert body["credits_complete"] is True
    got = [
        (a["kind"], a["item"]["type"], a["item"]["id"], a["character"]) for a in body["appearances"]
    ]
    assert got == [
        ("film", "film", ids["wonka"], "Willy Wonka"),
        ("film", "film", ids["dune"], "Paul Atreides"),
        ("show", "show", ids["homeland"], "Finn Walden"),
    ]
    for appearance in body["appearances"]:
        assert set(appearance) == {"kind", "item", "character"}
    show = body["appearances"][2]["item"]
    assert {"season_count", "episode_count", "unwatched_episode_count"} <= set(show)


async def test_progress_is_per_profile(client, db, profile_factory):
    a = profile_factory("A")
    b = profile_factory("B", avatar_id="avatar-02")
    ids = _seed(db)
    with db.write_session() as s:
        add_progress(s, a.id, film_id=ids["dune"], position=600, duration=5400)
        add_progress(s, a.id, episode_id=ids["episode"], watched=True, duration=1320)

    async def items(who) -> dict[str, Any]:
        r = await client.get(f"/api/people/{ids['person']}", headers=who.headers)
        assert r.status_code == 200, r.text
        return {x["item"]["title"]: x["item"] for x in r.json()["appearances"]}

    for_a, for_b = await items(a), await items(b)
    assert for_a["Dune"]["progress"]["position_seconds"] == 600
    assert for_b["Dune"]["progress"] is None
    assert for_a["Homeland"]["unwatched_episode_count"] == 2
    assert for_b["Homeland"]["unwatched_episode_count"] == 3


async def test_unknown_person_is_404_envelope(client, user):
    r = await client.get("/api/people/999", headers=user.headers)
    assert r.status_code == 404
    assert r.json() == {
        "error": {"code": "not_found", "message": "Person 999 not found", "details": {}}
    }


async def test_non_integer_id_is_422(client, user):
    r = await client.get("/api/people/abc", headers=user.headers)
    assert r.status_code == 422
    assert r.json()["error"]["code"] == "validation_error"


async def test_bearer_only(client, db, user):
    ids = _seed(db)
    r = await client.get(f"/api/people/{ids['person']}")
    assert r.status_code == 401
    r = await client.get(f"/api/people/{ids['person']}?token={user.token}")
    assert r.status_code == 401


async def test_no_tmdb_key_answers_local_credits(app, client, db, user):
    assert app.state.people.tmdb is None  # make_settings has no TMDB key
    with db.write_session() as s:
        person = add_person(s, "Local")
        film = add_film(s, "Dune", tmdb_id=438631)
        add_cast_credit(s, person, film=film, character="Paul")
        pid = person.id
    r = await client.get(f"/api/people/{pid}", headers=user.headers)
    body = r.json()
    assert body["credits_complete"] is False
    assert body["biography"] is None
    assert [(a["item"]["title"], a["character"]) for a in body["appearances"]] == [("Dune", "Paul")]


class StubTmdb:
    def __init__(self, result: dict[str, Any] | Exception | None) -> None:
        self.result = result
        self.calls: list[int] = []

    async def person_details(self, tmdb_id: int) -> dict[str, Any] | None:
        self.calls.append(tmdb_id)
        if isinstance(self.result, Exception):
            raise self.result
        return self.result


async def test_degraded_and_complete_end_to_end(app, client, db, user):
    with db.write_session() as s:
        person = add_person(s, "Actor", tmdb_id=77)
        film = add_film(s, "Local film", year=2001, tmdb_id=1)
        add_film(s, "Tmdb film", year=2002, tmdb_id=2)
        add_cast_credit(s, person, film=film)
        pid = person.id
    down = StubTmdb(TmdbError("down"))
    app.state.people = PersonService(db, down, wait_seconds=0.5)  # type: ignore[arg-type]
    r = await client.get(f"/api/people/{pid}", headers=user.headers)
    assert r.json()["credits_complete"] is False
    assert [a["item"]["title"] for a in r.json()["appearances"]] == ["Local film"]
    assert down.calls == [77]

    up = StubTmdb(
        {
            "biography": "Bio",
            BIOGRAPHY_LANGUAGE_KEY: "es-ES",
            "birthday": "1932-10-08",
            "deathday": "2010-05-03",
            "combined_credits": {"cast": [{"id": 2, "media_type": "movie", "character": "X"}]},
        }
    )
    service = PersonService(db, up, wait_seconds=0.5)  # type: ignore[arg-type]
    app.state.people = service
    try:
        body = (await client.get(f"/api/people/{pid}", headers=user.headers)).json()
    finally:
        await service.stop()
    assert body["credits_complete"] is True
    assert (body["birthday"], body["deathday"]) == ("1932-10-08", "2010-05-03")
    assert [(a["item"]["title"], a["character"]) for a in body["appearances"]] == [
        ("Tmdb film", "X"),
        ("Local film", None),
    ]


async def test_stale_person_answers_from_cache(app, client, db, user):
    with db.write_session() as s:
        person = add_person(
            s,
            "Actor",
            biography="Old",
            details_fetched_at=datetime.now(UTC) - timedelta(days=45),
        )
        pid = person.id
    down = StubTmdb(TmdbError("down"))
    service = PersonService(db, down, wait_seconds=5)  # type: ignore[arg-type]
    app.state.people = service
    body = (await client.get(f"/api/people/{pid}", headers=user.headers)).json()
    await service.wait_idle()
    assert body["credits_complete"] is True and body["biography"] == "Old"
    assert len(down.calls) == 1


async def test_route_in_openapi(client):
    spec = (await client.get("/api/openapi.json")).json()
    op = spec["paths"]["/api/people/{person_id}"]["get"]
    assert {"200", "401", "404", "422"} <= set(op["responses"])
    assert "PersonDetail" in spec["components"]["schemas"]
    assert "PersonAppearance" in spec["components"]["schemas"]


async def test_lifespan_wires_people_and_stop_cancels_fetches(make_settings):
    import asyncio

    from hometv.main import create_app

    app = create_app(make_settings(tmdb={"api_key": "k"}))
    async with app.router.lifespan_context(app):
        people = app.state.people
        assert isinstance(people, PersonService)
        assert people.tmdb is app.state.tmdb is not None
        db = app.state.db
        with db.write_session() as s:
            pid = add_person(s, "Actor", tmdb_id=77).id
        gate = asyncio.Event()
        events: list[str] = []

        async def gated(_tmdb_id: int) -> dict[str, Any]:
            events.append("fetch")
            try:
                await gate.wait()
            except asyncio.CancelledError:
                events.append("fetch_cancelled")
                raise
            return {}

        people.tmdb.person_details = gated
        people.wait_seconds = 0.01
        original_aclose = people.tmdb.aclose

        async def aclose() -> None:
            events.append("tmdb_aclose")
            await original_aclose()

        people.tmdb.aclose = aclose
        await people.detail(pid, 1)
    # people.stop() cancelled the in-flight fetch before tmdb.aclose() ran.
    assert events == ["fetch", "fetch_cancelled", "tmdb_aclose"]
