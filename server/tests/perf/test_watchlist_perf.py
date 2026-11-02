""" "Mi lista" latency on the browse seed (``test_browse_perf._seed``) plus 500 list
entries per profile (400 films, 100 shows, random ``added_at``): list (also with the ``watched``
filter) and detail p95 < 100 ms, PUT/DELETE p95 < 50 ms, over 200 requests per case
(in-process, warm SQLite).

Not part of the default run: ``cd server && uv run pytest -m perf -s``.
"""

import random
import statistics
import time
from collections.abc import Callable
from datetime import timedelta

import pytest
from sqlalchemy import insert

from hometv import profiles as p
from hometv.db import models as m
from tests.factories import T0
from tests.perf.test_browse_perf import FILMS, SHOWS, _seed

pytestmark = pytest.mark.perf

REQUESTS = 200
READ_BUDGET_MS = 100.0
WRITE_BUDGET_MS = 50.0
LIST_FILMS, LIST_SHOWS = 400, 100


@pytest.fixture
def settings(make_settings):
    return make_settings(server={"log_level": "error"})


def _seed_lists(db, profile_ids: list[int]) -> dict[int, tuple[list[int], list[int]]]:
    rng = random.Random(11)
    listed: dict[int, tuple[list[int], list[int]]] = {}
    rows = []
    for pid in profile_ids:
        films = rng.sample(range(1, FILMS + 1), LIST_FILMS)
        shows = rng.sample(range(1, SHOWS + 1), LIST_SHOWS)
        listed[pid] = (films, shows)
        for kind, ids in (("film_id", films), ("show_id", shows)):
            rows += [
                {
                    "profile_id": pid,
                    kind: item_id,
                    "added_at": T0 + timedelta(seconds=rng.randint(0, 10**7)),
                }
                for item_id in ids
            ]
    with db.write_session() as s:
        s.execute(insert(m.WatchlistEntry), rows)
    return listed


def _p95(samples: list[float]) -> float:
    return statistics.quantiles(samples, n=100, method="inclusive")[94]


async def test_watchlist_p95(client, app, db, capsys):
    profile_ids = _seed(db)["profiles"]
    listed = _seed_lists(db, profile_ids)
    tokens: p.TokenService = app.state.tokens
    headers = {
        pid: {"Authorization": f"Bearer {tokens.issue_profile_token(pid).token}"}
        for pid in profile_ids
    }
    rng = random.Random(7)

    async def run(method: str, make_path: Callable[[int], str], expect: int) -> list[float]:
        samples = []
        for i in range(REQUESTS + 5):  # 5 warm-up requests
            pid = profile_ids[i % len(profile_ids)]
            path = make_path(i)
            start = time.perf_counter()
            response = await client.request(method, path, headers=headers[pid])
            elapsed = (time.perf_counter() - start) * 1000
            assert response.status_code == expect, (method, path, response.text)
            if i >= 5:
                samples.append(elapsed)
        return samples

    def pid_of(i: int) -> int:
        return profile_ids[i % len(profile_ids)]

    reads: dict[str, Callable[[int], str]] = {
        "GET /api/watchlist": lambda i: "/api/watchlist",
        "GET /api/watchlist?sort=title&page=N": lambda i: (
            f"/api/watchlist?sort=title&page={rng.randint(1, 10)}"
        ),
        "GET /api/watchlist?type=shows&sort=year": lambda i: "/api/watchlist?type=shows&sort=year",
        "GET /api/watchlist?watched=false": lambda i: "/api/watchlist?watched=false",
        "GET /api/watchlist?watched=true&sort=title": lambda i: (
            "/api/watchlist?watched=true&sort=title"
        ),
        "GET /api/watchlist?type=shows&watched=true": lambda i: (
            "/api/watchlist?type=shows&watched=true"
        ),
        "GET /api/films/{listed id}": lambda i: f"/api/films/{rng.choice(listed[pid_of(i)][0])}",
        "GET /api/shows/{listed id}": lambda i: f"/api/shows/{rng.choice(listed[pid_of(i)][1])}",
    }
    results: dict[str, tuple[float, float, float]] = {}
    for name, make_path in reads.items():
        samples = await run("GET", make_path, 200)
        results[name] = (statistics.median(samples), _p95(samples), READ_BUDGET_MS)

    # Alternating PUT/DELETE of one film not in the profile's list (each pair: same profile).
    unlisted = {
        pid: rng.sample(sorted(set(range(1, FILMS + 1)) - set(films)), REQUESTS)
        for pid, (films, _) in listed.items()
    }
    writes: list[float] = []
    for i in range(REQUESTS + 6):
        pair = i // 2
        pid = profile_ids[pair % len(profile_ids)]
        method = "PUT" if i % 2 == 0 else "DELETE"
        path = f"/api/watchlist/films/{unlisted[pid][pair % REQUESTS]}"
        start = time.perf_counter()
        response = await client.request(method, path, headers=headers[pid])
        elapsed = (time.perf_counter() - start) * 1000
        assert response.status_code == 204, (method, path, response.text)
        if i >= 6:
            writes.append(elapsed)
    results["PUT/DELETE /api/watchlist/films/{id}"] = (
        statistics.median(writes),
        _p95(writes),
        WRITE_BUDGET_MS,
    )

    with capsys.disabled():
        print(f"\n{'endpoint':48} {'p50 ms':>8} {'p95 ms':>8} {'budget':>8}")
        for name, (p50, p95, budget) in results.items():
            print(f"{name:48} {p50:8.1f} {p95:8.1f} {budget:8.0f}")
    slow = {name: p95 for name, (_, p95, budget) in results.items() if p95 >= budget}
    assert not slow, f"p95 over budget: {slow}"
