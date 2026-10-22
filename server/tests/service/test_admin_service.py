"""Admin service functions (Phase 3 wires the routes): unmatched list/count, TMDB proxies."""

import json
from pathlib import Path

import pytest
import respx
from sqlalchemy import select

from hometv.admin import TmdbProxy, list_unmatched, unmatched_count
from hometv.api.errors import ApiError
from hometv.db import models as m
from hometv.metadata.tmdb import TMDB_BASE_URL, TmdbClient
from hometv.scanner import Scanner

from .conftest import MediaTree

FIXTURES = Path(__file__).resolve().parent.parent / "fixtures" / "tmdb"


def load(name: str) -> dict:
    return json.loads((FIXTURES / name).read_text(encoding="utf-8"))


async def _no_sleep(_s: float) -> None:
    return None


@pytest.fixture
async def client():
    c = TmdbClient("k", sleep=_no_sleep, max_rps=1000)
    yield c
    await c.aclose()


async def _seed(mdb, media: MediaTree, media_settings, stub_probe):
    media.add("Kill Bill Vol 1 (2003)/Kill.Bill.Vol.1.2003.cd1.avi")
    media.add("Dune (2021).mkv")
    gone = media.add("1080p.mkv")  # no_title
    media.show("The Office/Season 2/The.Office.S02E03.mkv")
    media.show("Lost/Lost 01.mkv")  # no_season
    settings = media_settings()
    await Scanner(mdb, settings, probe_fn=stub_probe).run()
    with mdb.write_session() as s:
        film = s.scalar(select(m.Film).where(m.Film.title == "Dune"))
        show = s.scalar(select(m.Show).where(m.Show.title == "The Office"))
        film.match_status = show.match_status = "unmatched"
        s.add(
            m.UnmatchedEntry(
                film_id=film.id, reason="low_confidence", detail="0 exact-title candidates"
            )
        )
        s.add(
            m.UnmatchedEntry(
                show_id=show.id,
                reason="low_confidence",
                detail="2 exact-title candidates: 2316, 2996",
            )
        )
    return settings, gone


async def test_unmatched_entries_have_exact_shapes_and_filters(
    mdb, media: MediaTree, media_settings, stub_probe
):
    settings, gone = await _seed(mdb, media, media_settings, stub_probe)

    with mdb.read_session() as s:
        page = list_unmatched(s)
        assert unmatched_count(s) == 5
    assert page.total == 5
    by_reason = {}
    for item in page.items:
        dumped = item.model_dump(mode="json")
        by_reason.setdefault((dumped["library"], dumped["reason"]), []).append(dumped)
    [kill_bill] = by_reason[("films", "multi_part")]
    assert kill_bill["target"] == {
        "type": "media_file",
        "id": kill_bill["target"]["id"],
        "relative_path": "Kill Bill Vol 1 (2003)/Kill.Bill.Vol.1.2003.cd1.avi",
    }
    assert kill_bill["parsed"] == {
        "kind": "film",
        "title": "Kill Bill Vol 1",
        "year": 2003,
        "part": 1,
    }
    [film_entry] = by_reason[("films", "low_confidence")]
    assert film_entry["target"] == {
        "type": "film",
        "id": film_entry["target"]["id"],
        "title": "Dune",
        "year": 2021,
    }
    assert film_entry["parsed"] == {"kind": "film", "title": "Dune", "year": 2021, "part": None}
    [show_entry] = by_reason[("shows", "low_confidence")]
    assert show_entry["target"]["type"] == "show"
    assert show_entry["detail"] == "2 exact-title candidates: 2316, 2996"
    assert show_entry["parsed"] == {
        "kind": "episode",
        "show_title": "The Office",
        "show_year": None,
        "season": 2,
        "episodes": [3],
    }
    assert by_reason[("films", "no_title")][0]["parsed"] is None
    assert by_reason[("shows", "no_season")][0]["parsed"] is None
    assert set(page.items[0].model_dump().keys()) == {
        "id",
        "library",
        "reason",
        "detail",
        "target",
        "parsed",
        "created_at",
    }
    created = [i.created_at for i in page.items]
    assert created == sorted(created, reverse=True)

    with mdb.read_session() as s:
        assert {i.reason for i in list_unmatched(s, library="shows").items} == {
            "low_confidence",
            "no_season",
        }
        films_low = list_unmatched(s, library="films", reason="low_confidence")
        assert [i.target.type for i in films_low.items] == ["film"]
        paged = list_unmatched(s, page=2, page_size=2)
        assert (paged.page, paged.page_size, paged.total, len(paged.items)) == (2, 2, 5, 2)

    # Entries of absent files are excluded from the list and the count.
    gone.unlink()
    await Scanner(mdb, settings, probe_fn=stub_probe).run()
    with mdb.read_session() as s:
        assert unmatched_count(s) == 4
        assert list_unmatched(s).total == 4


@respx.mock
async def test_tmdb_search_shape(client):
    respx.get(f"{TMDB_BASE_URL}/search/movie").respond(json=load("search_movie_dune.json"))
    proxy = TmdbProxy(client)
    response = await proxy.search("movie", "dune", 2021)
    assert response.model_dump()["results"] == [
        {
            "tmdb_id": 438631,
            "title": "Dune",
            "original_title": "Dune",
            "year": 2021,
            "overview": "Paul Atreides...",
            "poster": {
                "small": "/api/artwork/w342/d5NXSklXo0qyIYkgV94XAgMIckC.jpg",
                "large": "/api/artwork/w780/d5NXSklXo0qyIYkgV94XAgMIckC.jpg",
            },
        },
        {
            "tmdb_id": 841,
            "title": "Dune",
            "original_title": "Dune",
            "year": 1984,
            "overview": "1984 film",
            "poster": None,
        },
    ]


@respx.mock
async def test_tmdb_search_tv_uses_names(client):
    respx.get(f"{TMDB_BASE_URL}/search/tv").respond(json=load("search_tv_office.json"))
    response = await TmdbProxy(client).search("tv", "office", None)
    assert [(r.tmdb_id, r.title, r.year) for r in response.results] == [
        (2316, "The Office", 2005),
        (2996, "The Office", 2001),
    ]


@respx.mock
async def test_tv_seasons_specials_last_and_cached(client):
    route = respx.get(f"{TMDB_BASE_URL}/tv/2316").respond(json=load("tv_2316.json"))
    proxy = TmdbProxy(client)
    response = await proxy.tv_seasons(2316)
    assert response.model_dump() == {
        "tmdb_id": 2316,
        "name": "The Office",
        "year": 2005,
        "seasons": [
            {
                "season_number": 1,
                "name": "Temporada 1",
                "episode_count": 6,
                "air_date": "2005-03-24",
            },
            {
                "season_number": 2,
                "name": "Temporada 2",
                "episode_count": 22,
                "air_date": "2005-09-20",
            },
            {"season_number": 0, "name": "Especiales", "episode_count": 30, "air_date": None},
        ],
    }
    await proxy.tv_seasons(2316)
    assert route.call_count == 1


@respx.mock
async def test_tv_season_episodes_and_cache_expiry(client):
    route = respx.get(f"{TMDB_BASE_URL}/tv/2316/season/2").respond(
        json=load("tv_2316_season_2_en.json")
    )
    now = [0.0]
    proxy = TmdbProxy(client, clock=lambda: now[0])
    response = await proxy.tv_season(2316, 2)
    assert response.model_dump() == {
        "tmdb_id": 2316,
        "season_number": 2,
        "name": "Season 2",
        "episodes": [
            {"episode_number": 1, "title": "The Dundies", "air_date": "2005-09-20"},
            {"episode_number": 3, "title": "Office Olympics", "air_date": "2005-10-04"},
        ],
    }
    now[0] = 599
    await proxy.tv_season(2316, 2)
    assert route.call_count == 1
    now[0] = 601
    await proxy.tv_season(2316, 2)
    assert route.call_count == 2


@respx.mock
async def test_proxy_errors(client):
    respx.get(f"{TMDB_BASE_URL}/tv/1").respond(404)
    respx.get(f"{TMDB_BASE_URL}/tv/2316/season/99").respond(404)
    respx.get(f"{TMDB_BASE_URL}/tv/2").respond(500)
    respx.get(f"{TMDB_BASE_URL}/search/movie").respond(503)

    async def code(coro):
        with pytest.raises(ApiError) as exc:
            await coro
        return exc.value.status_code, exc.value.code

    proxy = TmdbProxy(client)
    assert await code(proxy.tv_seasons(1)) == (404, "tmdb_not_found")
    assert await code(proxy.tv_season(2316, 99)) == (404, "tmdb_not_found")
    assert await code(proxy.tv_seasons(2)) == (502, "tmdb_unavailable")
    assert await code(proxy.search("movie", "x", None)) == (502, "tmdb_unavailable")
    assert await code(proxy.tv_season(2316, -1)) == (422, "validation_error")
    assert await code(TmdbProxy(None).search("movie", "x", None)) == (503, "tmdb_not_configured")
    assert await code(TmdbProxy(None).tv_seasons(1)) == (503, "tmdb_not_configured")
