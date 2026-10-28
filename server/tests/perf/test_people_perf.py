"""Actor screen latency (actor screen PRD G3/AC "Perf"): ``GET /api/people/{id}`` p95 < 100 ms on
the G3 browse seed plus 3,000 persons, 15 local credits per film and per show, and 300 fetched
persons with 80 TMDB credits each (half of them on catalog titles). No TMDB key: no network.

Not part of the default run: ``cd server && uv run pytest -m perf -s``.
"""

import random
import statistics
import time
from datetime import UTC, date, datetime

import pytest
from sqlalchemy import insert

from hometv import profiles as p
from hometv.db import models as m
from tests.perf.test_browse_perf import FILMS, P95_BUDGET_MS, REQUESTS, SHOWS, _seed

pytestmark = pytest.mark.perf

PERSONS = 3000
CAST_PER_ITEM = 15
FETCHED = 300
CREDITS_PER_FETCHED = 80
FILM_TMDB_BASE, SHOW_TMDB_BASE = 100_000, 110_000  # _item(i) / _item(10_000 + i) in the seed


def _seed_people(db) -> None:
    rng = random.Random(11)
    now = datetime.now(UTC)
    persons = [
        {
            "id": i,
            "tmdb_id": 5_000_000 + i,
            "name": f"Persona {i}",
            "profile_path": f"person{i}.jpg",
            **(
                {
                    "biography": "Biografía " * 40,
                    "biography_language": "es-ES",
                    "birthday": date(1950 + i % 50, 1 + i % 12, 1 + i % 28),
                    "place_of_birth": "Madrid, España",
                    "details_fetched_at": now,
                }
                if i <= FETCHED
                else {}
            ),
        }
        for i in range(1, PERSONS + 1)
    ]
    cast = [
        {
            "person_id": (film * CAST_PER_ITEM + k) % PERSONS + 1,
            "film_id": film,
            "character": f"Personaje {k}",
            "order": k,
        }
        for film in range(1, FILMS + 1)
        for k in range(CAST_PER_ITEM)
    ]
    cast += [
        {
            "person_id": (show * 7 + k) % PERSONS + 1,
            "show_id": show,
            "character": f"Personaje {k}",
            "order": k,
        }
        for show in range(1, SHOWS + 1)
        for k in range(CAST_PER_ITEM)
    ]
    credits = []
    half = CREDITS_PER_FETCHED // 2
    for person in range(1, FETCHED + 1):
        films = rng.sample(range(FILMS), half - 10)
        shows = rng.sample(range(SHOWS), 10)
        hits = [("movie", FILM_TMDB_BASE + i) for i in films]
        hits += [("tv", SHOW_TMDB_BASE + i) for i in shows]
        misses = [("movie" if k % 2 else "tv", 900_000 + person * 100 + k) for k in range(half)]
        credits += [
            {"person_id": person, "media_type": t, "tmdb_id": tid, "character": "Papel"}
            for t, tid in hits + misses
        ]
    with db.write_session() as s:
        s.execute(insert(m.Person), persons)
        s.execute(insert(m.CastCredit), cast)
        s.execute(insert(m.PersonCredit), credits)


@pytest.fixture
def settings(make_settings):
    return make_settings(server={"log_level": "error"})


async def test_people_p95(client, app, db, capsys):
    assert app.state.people.tmdb is None  # no TMDB key: never-fetched persons answer at once
    seeded = _seed(db)
    _seed_people(db)
    tokens: p.TokenService = app.state.tokens
    headers = [
        {"Authorization": f"Bearer {tokens.issue_profile_token(pid).token}"}
        for pid in seeded["profiles"]
    ]
    rng = random.Random(5)
    cases = {
        "GET /api/people/{id} (fetched)": lambda: rng.randint(1, FETCHED),
        "GET /api/people/{id} (never fetched)": lambda: rng.randint(FETCHED + 1, PERSONS),
    }
    results: dict[str, tuple[float, float, float]] = {}
    for name, pick in cases.items():
        for _ in range(5):  # warm-up
            r = await client.get(f"/api/people/{pick()}", headers=headers[0])
            assert r.status_code == 200
        samples, sizes = [], []
        for i in range(REQUESTS):
            person_id = pick()
            start = time.perf_counter()
            response = await client.get(
                f"/api/people/{person_id}", headers=headers[i % len(headers)]
            )
            samples.append((time.perf_counter() - start) * 1000)
            assert response.status_code == 200, response.text
            body = response.json()
            assert body["credits_complete"] is (person_id <= FETCHED)
            sizes.append(len(body["appearances"]))
        p95 = statistics.quantiles(samples, n=100, method="inclusive")[94]
        results[name] = (statistics.median(samples), p95, statistics.mean(sizes))
    with capsys.disabled():
        print(f"\n{'endpoint':40} {'p50 ms':>8} {'p95 ms':>8} {'items':>6}")
        for name, (p50, p95, items) in results.items():
            print(f"{name:40} {p50:8.1f} {p95:8.1f} {items:6.1f}")
    assert results["GET /api/people/{id} (fetched)"][2] > 30  # TMDB credits do hit the catalog
    slow = {name: p95 for name, (_, p95, _) in results.items() if p95 >= P95_BUDGET_MS}
    assert not slow, f"p95 over {P95_BUDGET_MS} ms: {slow}"
