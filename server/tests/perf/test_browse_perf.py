"""Browse-endpoint latency (PRD G3): p95 < 100 ms on 2,000 films / 200 shows / 10,000 episodes /
5 profiles, measured in-process over 200 requests per endpoint (warm SQLite).

Not part of the default run: ``cd server && uv run pytest -m perf -s``.
"""

import random
import statistics
import time
from datetime import timedelta

import pytest
from sqlalchemy import insert, select

from hometv import profiles as p
from hometv.db import models as m
from hometv.scanner import ensure_libraries
from hometv.text import normalize_title, search_text, sort_title
from tests.factories import T0, probe_json

pytestmark = pytest.mark.perf

FILMS, SHOWS, SEASONS, EPISODES_PER_SEASON, PROFILES = 2000, 200, 5, 10, 5
REQUESTS = 200
P95_BUDGET_MS = 100.0
WORDS = ["Amanecer", "Noche", "Río", "Ciudad", "Último", "Viaje", "Sombra", "Mar", "Fuego", "Luz"]
GENRES = [(18, "Drama"), (35, "Comedia"), (878, "Ciencia ficción"), (28, "Acción"), (27, "Terror")]


def _title(i: int) -> str:
    return f"{'The ' if i % 7 == 0 else ''}{WORDS[i % 10]} {WORDS[(i // 10) % 10]} {i}"


def _item(i: int, library_id: int, **extra) -> dict:
    title = _title(i)
    return {
        "library_id": library_id,
        "match_status": "matched",
        "parsed_title": title,
        "parsed_title_norm": normalize_title(title),
        "parsed_year": 1950 + i % 75,
        "tmdb_id": 100000 + i,
        "title": title,
        "original_title": title,
        "sort_title": sort_title(title),
        "search_text": search_text(title, title),
        "year": None if i % 50 == 0 else 1950 + i % 75,
        "overview": "Sinopsis " * 20,
        "poster_path": f"poster{i}.jpg",
        "backdrop_path": f"backdrop{i}.jpg",
        **extra,
    }


def _file(n: int, library_id: int, film_id: int | None, root: str) -> dict:
    probe = probe_json(duration=5400.0 if film_id else 1320.0)
    return {
        "id": n,
        "library_id": library_id,
        "root_path": root,
        "path": f"{root}/{n}.mkv",
        "relative_path": f"{n}.mkv",
        "size_bytes": 1_000_000 + n,
        "mtime_ns": n,
        "container": "mkv",
        "duration_seconds": probe["duration_seconds"],
        "video_codec": "h264",
        "width": 1920,
        "height": 1080,
        "probe": probe,
        "film_id": film_id,
        "added_at": T0 + timedelta(minutes=n),
        "updated_at": T0,
    }


def _seed(db) -> dict:
    rng = random.Random(42)
    profile_ids = [
        p.create_profile(db, name=f"P{i}", avatar_id="avatar-01", is_admin=False, pin=None).id
        for i in range(PROFILES)
    ]
    with db.write_session() as s:
        libs = ensure_libraries(s)
        s.execute(insert(m.Genre), [{"id": g, "name": n} for g, n in GENRES])
        s.execute(
            insert(m.Film),
            [
                _item(i, libs["films"], id=i + 1, added_at=T0 + timedelta(minutes=i))
                for i in range(FILMS)
            ],
        )
        s.execute(
            insert(m.FilmGenre),
            [{"film_id": i + 1, "genre_id": GENRES[i % 5][0]} for i in range(FILMS)],
        )
        files = [_file(i + 1, libs["films"], i + 1, "/media/films") for i in range(FILMS)]
        files += [  # every 10th film has a second version
            _file(FILMS + i + 1, libs["films"], i * 10 + 1, "/media/films")
            for i in range(FILMS // 10)
        ]
        s.execute(
            insert(m.Show),
            [
                _item(10_000 + i, libs["shows"], id=i + 1, content_updated_at=T0)
                for i in range(SHOWS)
            ],
        )
        s.execute(
            insert(m.ShowGenre),
            [{"show_id": i + 1, "genre_id": GENRES[i % 5][0]} for i in range(SHOWS)],
        )
        seasons, episodes, links = [], [], []
        next_file = len(files) + 1
        for show in range(1, SHOWS + 1):
            for season in range(1, SEASONS + 1):
                season_id = (show - 1) * SEASONS + season
                seasons.append(
                    {
                        "id": season_id,
                        "show_id": show,
                        "season_number": season,
                        "name": f"Temporada {season}",
                    }
                )
                for number in range(1, EPISODES_PER_SEASON + 1):
                    episode_id = len(episodes) + 1
                    episodes.append(
                        {
                            "id": episode_id,
                            "season_id": season_id,
                            "show_id": show,
                            "season_number": season,
                            "episode_number": number,
                            "title": f"Episodio {number}",
                            "still_path": f"still{episode_id}.jpg",
                            "added_at": T0,
                        }
                    )
                    files.append(_file(next_file, libs["shows"], None, "/media/shows"))
                    links.append({"media_file_id": next_file, "episode_id": episode_id})
                    next_file += 1
        s.execute(insert(m.MediaFile), files)
        s.execute(insert(m.Season), seasons)
        s.execute(insert(m.Episode), episodes)
        s.execute(insert(m.MediaFileEpisode), links)
        progress = []
        for pid in profile_ids:
            for film in rng.sample(range(1, FILMS + 1), 40):
                progress.append(
                    {
                        "profile_id": pid,
                        "film_id": film,
                        "position_seconds": rng.uniform(60, 4000),
                        "duration_seconds": 5400.0,
                        "watched": False,
                        "updated_at": T0 + timedelta(seconds=rng.randint(0, 10**6)),
                    }
                )
            for show in rng.sample(range(1, SHOWS + 1), 30):
                first = (show - 1) * SEASONS * EPISODES_PER_SEASON + 1
                for offset in range(rng.randint(1, 20)):
                    progress.append(
                        {
                            "profile_id": pid,
                            "episode_id": first + offset,
                            "position_seconds": 0,
                            "duration_seconds": 1320.0,
                            "watched": True,
                            "watched_at": T0 + timedelta(seconds=rng.randint(0, 10**6)),
                            "updated_at": T0,
                        }
                    )
        s.execute(insert(m.PlaybackProgress), progress)
    with db.read_session() as s:
        assert s.scalar(select(m.Episode.id).order_by(m.Episode.id.desc()).limit(1)) == 10_000
    return {"profiles": profile_ids}


@pytest.fixture
def settings(make_settings):
    return make_settings(server={"log_level": "error"})


async def test_browse_p95(client, app, db, capsys):
    seeded = _seed(db)
    tokens: p.TokenService = app.state.tokens
    headers = [
        {"Authorization": f"Bearer {tokens.issue_profile_token(pid).token}"}
        for pid in seeded["profiles"]
    ]
    rng = random.Random(7)
    total_episodes = SHOWS * SEASONS * EPISODES_PER_SEASON
    endpoints = {
        "GET /api/films": lambda: "/api/films",
        "GET /api/films?sort=added&order=desc&page=N": lambda: (
            f"/api/films?sort=added&order=desc&page={rng.randint(1, 40)}"
        ),
        "GET /api/films?q=&genre=&year_from=": lambda: (
            f"/api/films?q={rng.choice(WORDS)}&genre=18&year_from=1960&sort=year"
        ),
        "GET /api/films/{id}": lambda: f"/api/films/{rng.randint(1, FILMS)}",
        "GET /api/shows": lambda: f"/api/shows?page={rng.randint(1, 4)}",
        "GET /api/shows/{id}": lambda: f"/api/shows/{rng.randint(1, SHOWS)}",
        "GET /api/shows/{id}/seasons/{n}": lambda: (
            f"/api/shows/{rng.randint(1, SHOWS)}/seasons/{rng.randint(1, SEASONS)}"
        ),
        "GET /api/episodes/{id}": lambda: f"/api/episodes/{rng.randint(1, total_episodes)}",
        "GET /api/home": lambda: "/api/home",
        "GET /api/search": lambda: f"/api/search?q={rng.choice(WORDS)}",
        "GET /api/genres": lambda: "/api/genres?type=films",
    }
    results: dict[str, tuple[float, float]] = {}
    for name, make_path in endpoints.items():
        for _ in range(5):  # warm-up
            assert (await client.get(make_path(), headers=headers[0])).status_code == 200
        samples = []
        for i in range(REQUESTS):
            path = make_path()
            start = time.perf_counter()
            response = await client.get(path, headers=headers[i % len(headers)])
            samples.append((time.perf_counter() - start) * 1000)
            assert response.status_code == 200, (path, response.text)
        p95 = statistics.quantiles(samples, n=100, method="inclusive")[94]
        results[name] = (statistics.median(samples), p95)
    with capsys.disabled():
        print(f"\n{'endpoint':48} {'p50 ms':>8} {'p95 ms':>8}")
        for name, (p50, p95) in results.items():
            print(f"{name:48} {p50:8.1f} {p95:8.1f}")
    slow = {name: p95 for name, (_, p95) in results.items() if p95 >= P95_BUDGET_MS}
    assert not slow, f"p95 over {P95_BUDGET_MS} ms: {slow}"
