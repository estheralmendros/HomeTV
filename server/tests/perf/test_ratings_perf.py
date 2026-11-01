"""Ratings latency (ratings PRD §7 / G5 / S-AC-15) on the G3 seed (``test_browse_perf._seed``:
5 profiles, 2,000 films, 200 shows x 5 seasons x 10 episodes) plus the "Mi lista" seed
(``test_watchlist_perf._seed_lists``) and 2,000 ratings (each profile rates 320 films and 80
shows, random ``stars_x2``/``rated_at``; films 1-20 and shows 1-5 rated by all five) and 200
dismissals. 200 requests per case (in-process, warm SQLite):

- detail of titles rated by every profile, activity pages 1..40 and ``page_size=200``, film
  list, "Mi lista", home and search: p95 < 100 ms;
- alternating ``PUT``/``DELETE /api/ratings/films/{id}``: p95 < 50 ms;
- prompt computation (``_show_last_episode_id`` + ``_rating_prompt`` with the prompt open, in a
  write session): p95 < 10 ms; ``PUT /api/progress`` reaching the rule on a show's last
  episode: p95 < 100 ms.

Not part of the default run: ``cd server && uv run pytest -m perf -s``.
"""

import random
import statistics
import time
from collections.abc import Callable
from datetime import timedelta

import pytest
from sqlalchemy import insert, select

from hometv import profiles as p
from hometv.db import models as m
from hometv.watch import _rating_prompt, _show_last_episode_id
from tests.factories import T0
from tests.perf.test_browse_perf import FILMS, SHOWS, WORDS, _seed
from tests.perf.test_watchlist_perf import _seed_lists
from tests.stubs import capture_selects

pytestmark = pytest.mark.perf

REQUESTS = 200
READ_BUDGET_MS = 100.0
WRITE_BUDGET_MS = 50.0
PROMPT_BUDGET_MS = 10.0
RATED_FILMS, RATED_SHOWS = 320, 80
SHARED_FILMS, SHARED_SHOWS = range(1, 21), range(1, 6)
DISMISSALS = 200


@pytest.fixture
def settings(make_settings):
    return make_settings(server={"log_level": "error"})


def _seed_ratings(db, profile_ids: list[int]) -> dict[int, tuple[set[int], set[int]]]:
    """2,000 ratings and 200 dismissals; returns each profile's rated (films, shows)."""
    rng = random.Random(23)
    rated: dict[int, tuple[set[int], set[int]]] = {}
    rows, dismissals = [], []
    for pid in profile_ids:
        films = set(SHARED_FILMS) | set(
            rng.sample(range(SHARED_FILMS.stop, FILMS + 1), RATED_FILMS - len(SHARED_FILMS))
        )
        shows = set(SHARED_SHOWS) | set(
            rng.sample(range(SHARED_SHOWS.stop, SHOWS + 1), RATED_SHOWS - len(SHARED_SHOWS))
        )
        rated[pid] = (films, shows)
        for kind, ids in (("film_id", films), ("show_id", shows)):
            rows += [
                {
                    "profile_id": pid,
                    kind: item_id,
                    "stars_x2": rng.randint(1, 10),
                    "rated_at": T0 + timedelta(seconds=rng.randint(0, 10**7)),
                }
                for item_id in sorted(ids)
            ]
        unrated = sorted(set(range(1, FILMS + 1)) - films)
        dismissals += [
            {"profile_id": pid, "film_id": film_id, "dismissed_at": T0}
            for film_id in rng.sample(unrated, DISMISSALS // len(profile_ids))
        ]
    with db.write_session() as s:
        s.execute(insert(m.Rating), rows)
        s.execute(insert(m.RatingDismissal), dismissals)
    assert len(rows) == 2000 and len(dismissals) == DISMISSALS
    return rated


def _p95(samples: list[float]) -> float:
    return statistics.quantiles(samples, n=100, method="inclusive")[94]


def _last_episode_files(db) -> dict[int, tuple[int, int]]:
    """show id -> (last episode id, its media file id)."""
    out = {}
    with db.read_session() as s:
        for show_id in range(1, SHOWS + 1):
            episode_id = _show_last_episode_id(s, show_id)
            assert episode_id is not None
            file_id = s.scalar(
                select(m.MediaFileEpisode.media_file_id).where(
                    m.MediaFileEpisode.episode_id == episode_id
                )
            )
            assert file_id is not None
            out[show_id] = (episode_id, file_id)
    return out


async def test_ratings_p95(client, app, db, capsys):
    profile_ids = _seed(db)["profiles"]
    _seed_lists(db, profile_ids)
    rated = _seed_ratings(db, profile_ids)
    tokens: p.TokenService = app.state.tokens
    headers = {
        pid: {"Authorization": f"Bearer {tokens.issue_profile_token(pid).token}"}
        for pid in profile_ids
    }
    rng = random.Random(7)

    def pid_of(i: int) -> int:
        return profile_ids[i % len(profile_ids)]

    async def run(
        method: str, make_path: Callable[[int], str], expect: int, json=None
    ) -> list[float]:
        samples = []
        for i in range(REQUESTS + 5):  # 5 warm-up requests
            path = make_path(i)
            body = json(i) if callable(json) else json
            start = time.perf_counter()
            response = await client.request(method, path, headers=headers[pid_of(i)], json=body)
            elapsed = (time.perf_counter() - start) * 1000
            assert response.status_code == expect, (method, path, response.text)
            if i >= 5:
                samples.append(elapsed)
        return samples

    results: dict[str, tuple[float, float, float]] = {}

    def record(name: str, samples: list[float], budget: float) -> None:
        results[name] = (statistics.median(samples), _p95(samples), budget)

    reads: dict[str, Callable[[int], str]] = {
        "GET /api/films/{rated by 5}": lambda i: f"/api/films/{rng.choice(SHARED_FILMS)}",
        "GET /api/shows/{rated by 5}": lambda i: f"/api/shows/{rng.choice(SHARED_SHOWS)}",
        "GET /api/activity?page=N (1..40)": lambda i: f"/api/activity?page={i % 40 + 1}",
        "GET /api/activity?page_size=200": lambda i: "/api/activity?page_size=200",
        "GET /api/films?page_size=50": lambda i: "/api/films?page_size=50",
        "GET /api/watchlist": lambda i: "/api/watchlist",
        "GET /api/home": lambda i: "/api/home",
        "GET /api/search": lambda i: f"/api/search?q={rng.choice(WORDS)}",
    }
    for name, make_path in reads.items():
        record(name, await run("GET", make_path, 200), READ_BUDGET_MS)

    # Alternating PUT/DELETE of a film the profile has not rated (each pair: same profile).
    unrated = {
        pid: rng.sample(sorted(set(range(1, FILMS + 1)) - films), REQUESTS)
        for pid, (films, _) in rated.items()
    }
    writes: list[float] = []
    for i in range(REQUESTS + 6):
        pair = i // 2
        pid = profile_ids[pair % len(profile_ids)]
        method = "PUT" if i % 2 == 0 else "DELETE"
        path = f"/api/ratings/films/{unrated[pid][pair % REQUESTS]}"
        body = {"stars": rng.randint(1, 10) / 2} if method == "PUT" else None
        start = time.perf_counter()
        response = await client.request(method, path, headers=headers[pid], json=body)
        elapsed = (time.perf_counter() - start) * 1000
        assert response.status_code == 204, (method, path, response.text)
        if i >= 6:
            writes.append(elapsed)
    record("PUT/DELETE /api/ratings/films/{id}", writes, WRITE_BUDGET_MS)

    # Prompt computation on shows the profile has not rated nor dismissed (prompt open).
    open_shows = {
        pid: sorted(set(range(1, SHOWS + 1)) - shows) for pid, (_, shows) in rated.items()
    }
    prompts: list[float] = []
    for i in range(REQUESTS + 5):
        pid = pid_of(i)
        show_id = open_shows[pid][i % len(open_shows[pid])]
        with db.write_session() as s:
            start = time.perf_counter()
            last = _show_last_episode_id(s, show_id)
            prompt = _rating_prompt(s, pid, "show", show_id)
            elapsed = (time.perf_counter() - start) * 1000
        assert last is not None and prompt is not None
        if i >= 5:
            prompts.append(elapsed)
    record("watch._show_last_episode_id + _rating_prompt", prompts, PROMPT_BUDGET_MS)

    last_files = _last_episode_files(db)

    def progress_body(i: int) -> dict:
        pid = pid_of(i)
        episode_id, file_id = last_files[open_shows[pid][i % len(open_shows[pid])]]
        return {
            "item_type": "episode",
            "item_id": episode_id,
            "media_file_id": file_id,
            "position_seconds": 1300.0,  # >= 90 % of 1,320 s
        }

    record(
        "PUT /api/progress (last episode, prompt)",
        await run("PUT", lambda i: "/api/progress", 200, json=progress_body),
        READ_BUDGET_MS,
    )

    with capsys.disabled():
        print(f"\n{'case':48} {'p50 ms':>8} {'p95 ms':>8} {'budget':>8}")
        for name, (p50, p95, budget) in results.items():
            print(f"{name:48} {p50:8.2f} {p95:8.2f} {budget:8.0f}")
    slow = {name: p95 for name, (_, p95, budget) in results.items() if p95 >= budget}
    assert not slow, f"p95 over budget: {slow}"


async def test_activity_page_walks_the_rated_at_index(client, app, db):
    """The activity page reads ``rating`` backwards through ``ix_rating_rated_at_id`` instead of
    sorting the whole table."""
    profile_ids = _seed(db)["profiles"]
    _seed_ratings(db, profile_ids)
    tokens: p.TokenService = app.state.tokens
    token = tokens.issue_profile_token(profile_ids[0]).token
    with capture_selects(db) as statements:
        response = await client.get(
            "/api/activity?page=3", headers={"Authorization": f"Bearer {token}"}
        )
    assert response.status_code == 200
    [(sql, params)] = [
        (sql, params)
        for sql, params in statements
        if "FROM rating" in sql and "ORDER BY rating.rated_at DESC" in sql
    ]
    with db.read_engine.connect() as conn:
        rows = conn.exec_driver_sql("EXPLAIN QUERY PLAN " + sql, params).all()
    plan = " | ".join(str(r[-1]) for r in rows)
    assert "ix_rating_rated_at_id" in plan, plan
    assert "TEMP B-TREE FOR ORDER BY" not in plan, plan
