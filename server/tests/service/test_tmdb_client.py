"""TmdbClient (PRD §5 TMDB client) with respx mocks and JSON fixtures."""

import json
from pathlib import Path

import httpx
import pytest
import respx

from hometv.metadata.tmdb import (
    BIOGRAPHY_LANGUAGE_KEY,
    TMDB_BASE_URL,
    TMDB_IMAGE_BASE_URL,
    TmdbAuthError,
    TmdbClient,
    TmdbUnavailable,
    TokenBucket,
)

FIXTURES = Path(__file__).resolve().parent.parent / "fixtures" / "tmdb"


def load(name: str) -> dict:
    return json.loads((FIXTURES / name).read_text(encoding="utf-8"))


class Sleeps:
    def __init__(self) -> None:
        self.calls: list[float] = []

    async def __call__(self, seconds: float) -> None:
        self.calls.append(seconds)


@pytest.fixture
def sleeps() -> Sleeps:
    return Sleeps()


@pytest.fixture
async def client(sleeps):
    c = TmdbClient("v3key", sleep=sleeps, max_rps=1000)
    yield c
    await c.aclose()


@respx.mock
async def test_v3_key_is_sent_as_query_param(client):
    route = respx.get(f"{TMDB_BASE_URL}/search/movie").respond(json=load("search_movie_dune.json"))
    results = await client.search_movie("Dune", 2021)
    assert [r["id"] for r in results] == [438631, 841]
    params = dict(route.calls[0].request.url.params)
    assert params == {
        "api_key": "v3key",
        "query": "Dune",
        "year": "2021",
        "language": "es-ES",
        "include_adult": "false",
    }
    assert "authorization" not in route.calls[0].request.headers


@respx.mock
async def test_v4_token_is_sent_as_bearer(sleeps):
    route = respx.get(f"{TMDB_BASE_URL}/search/tv").respond(json=load("search_tv_office.json"))
    c = TmdbClient("eyJhbGciOi.token", sleep=sleeps)
    try:
        results = await c.search_tv("The Office", None, language="en-US")
    finally:
        await c.aclose()
    assert len(results) == 2
    request = route.calls[0].request
    assert request.headers["authorization"] == "Bearer eyJhbGciOi.token"
    params = dict(request.url.params)
    assert "api_key" not in params
    assert params["language"] == "en-US"
    assert "first_air_date_year" not in params


@respx.mock
async def test_search_tv_sends_first_air_date_year(client):
    route = respx.get(f"{TMDB_BASE_URL}/search/tv").respond(json={"results": []})
    assert await client.search_tv("The Office", 2005) == []
    assert route.calls[0].request.url.params["first_air_date_year"] == "2005"


@respx.mock
async def test_429_waits_retry_after_and_retries(client, sleeps):
    route = respx.get(f"{TMDB_BASE_URL}/search/movie").mock(
        side_effect=[
            httpx.Response(429, headers={"Retry-After": "3"}),
            httpx.Response(429),
            httpx.Response(200, json=load("search_movie_dune.json")),
        ]
    )
    results = await client.search_movie("Dune", None)
    assert len(results) == 2
    assert route.call_count == 3
    assert sleeps.calls == [3.0, 2.0]


@respx.mock
async def test_5xx_is_retried_three_times_with_backoff_then_unavailable(client, sleeps):
    route = respx.get(f"{TMDB_BASE_URL}/movie/438631").respond(503)
    with pytest.raises(TmdbUnavailable):
        await client.movie_details(438631)
    assert route.call_count == 4
    assert sleeps.calls == [1, 2, 4]


@respx.mock
async def test_5xx_then_success(client, sleeps):
    route = respx.get(f"{TMDB_BASE_URL}/search/movie").mock(
        side_effect=[httpx.Response(500), httpx.Response(200, json={"results": []})]
    )
    assert await client.search_movie("x", None) == []
    assert route.call_count == 2
    assert sleeps.calls == [1]


@respx.mock
async def test_network_errors_are_transient(client, sleeps):
    route = respx.get(f"{TMDB_BASE_URL}/search/movie").mock(side_effect=httpx.ConnectError("boom"))
    with pytest.raises(TmdbUnavailable):
        await client.search_movie("x", None)
    assert route.call_count == 4


@respx.mock
async def test_401_raises_auth_error_without_retry(client):
    route = respx.get(f"{TMDB_BASE_URL}/search/movie").respond(401)
    with pytest.raises(TmdbAuthError):
        await client.search_movie("x", None)
    assert route.call_count == 1


@respx.mock
async def test_404_returns_none(client):
    respx.get(f"{TMDB_BASE_URL}/movie/1").respond(404)
    respx.get(f"{TMDB_BASE_URL}/tv/1/season/9").respond(404)
    assert await client.movie_details(1) is None
    assert await client.tv_season(1, 9) is None


@respx.mock
async def test_details_fill_empty_fields_from_fallback_language(client):
    es = respx.get(f"{TMDB_BASE_URL}/movie/438631", params={"language": "es-ES"}).respond(
        json=load("movie_438631.json")
    )
    en = respx.get(f"{TMDB_BASE_URL}/movie/438631", params={"language": "en-US"}).respond(
        json=load("movie_438631_en.json")
    )
    data = await client.movie_details(438631)
    assert data["overview"].startswith("Paul Atreides")
    assert data["tagline"] == "Más allá del miedo, el destino aguarda."  # not empty: kept
    assert data["genres"][0]["name"] == "Ciencia ficción"
    assert len(data["credits"]["cast"]) == 20
    assert es.calls[0].request.url.params["append_to_response"] == "credits"
    assert en.call_count == 1


@respx.mock
async def test_no_fallback_request_when_languages_equal(sleeps):
    route = respx.get(f"{TMDB_BASE_URL}/movie/438631").respond(json=load("movie_438631.json"))
    c = TmdbClient("k", language="en-US", fallback_language="en-US", sleep=sleeps)
    try:
        data = await c.movie_details(438631)
    finally:
        await c.aclose()
    assert data["overview"] == ""
    assert route.call_count == 1


@respx.mock
async def test_season_episodes_fill_from_fallback(client):
    respx.get(f"{TMDB_BASE_URL}/tv/2316/season/2", params={"language": "es-ES"}).respond(
        json=load("tv_2316_season_2.json")
    )
    respx.get(f"{TMDB_BASE_URL}/tv/2316/season/2", params={"language": "en-US"}).respond(
        json=load("tv_2316_season_2_en.json")
    )
    data = await client.tv_season(2316, 2)
    assert data["name"] == "Temporada 2"
    assert data["overview"] == "Season two of The Office."
    eps = {e["episode_number"]: e for e in data["episodes"]}
    assert eps[1]["overview"] == "Michael..."
    assert eps[3]["name"] == "Olimpiadas de oficina"
    assert eps[3]["overview"] == "Ryan's first sale..."


@respx.mock
async def test_tv_details_appends_credits(client):
    route = respx.get(f"{TMDB_BASE_URL}/tv/2316").respond(json=load("tv_2316.json"))
    data = await client.tv_details(2316)
    assert data["name"] == "The Office"
    assert route.calls[0].request.url.params["append_to_response"] == "credits"


# --- person_details (actor screen FR-4) ------------------------------------------------------

PERSON_URL = f"{TMDB_BASE_URL}/person/1190668"


@respx.mock
async def test_person_details_blank_biography_uses_fallback_without_credits(client):
    es = respx.get(PERSON_URL, params={"language": "es-ES"}).respond(
        json=load("person_1190668.json")
    )
    en = respx.get(PERSON_URL, params={"language": "en-US"}).respond(
        json=load("person_1190668_en.json")
    )
    data = await client.person_details(1190668)
    assert data is not None
    es_params = dict(es.calls[0].request.url.params)
    assert es_params == {
        "api_key": "v3key",
        "append_to_response": "combined_credits",
        "language": "es-ES",
    }
    assert en.call_count == 1
    assert "append_to_response" not in en.calls[0].request.url.params
    assert data["biography"].startswith("Timothée Hal Chalamet is an American actor")
    assert data[BIOGRAPHY_LANGUAGE_KEY] == "en-US"
    assert len(data["combined_credits"]["cast"]) == 6  # credits from the first call


@respx.mock
async def test_person_details_biography_in_primary_language(client):
    payload = load("person_1190668.json") | {"biography": "Actor estadounidense."}
    route = respx.get(PERSON_URL).respond(json=payload)
    data = await client.person_details(1190668)
    assert data is not None
    assert route.call_count == 1
    assert data["biography"] == "Actor estadounidense."
    assert data[BIOGRAPHY_LANGUAGE_KEY] == "es-ES"


@respx.mock
async def test_person_details_blank_in_both_languages(client):
    respx.get(PERSON_URL, params={"language": "es-ES"}).respond(json=load("person_1190668.json"))
    en = respx.get(PERSON_URL, params={"language": "en-US"}).respond(
        json=load("person_1190668_en.json") | {"biography": "  "}
    )
    data = await client.person_details(1190668)
    assert data is not None
    assert en.call_count == 1
    assert data["biography"] == ""
    assert data[BIOGRAPHY_LANGUAGE_KEY] is None


@respx.mock
async def test_person_details_fallback_404_keeps_primary(client):
    respx.get(PERSON_URL, params={"language": "es-ES"}).respond(json=load("person_1190668.json"))
    respx.get(PERSON_URL, params={"language": "en-US"}).respond(404)
    data = await client.person_details(1190668)
    assert data is not None
    assert data[BIOGRAPHY_LANGUAGE_KEY] is None


@respx.mock
async def test_person_details_failed_fallback_fails_the_fetch(client, sleeps):
    respx.get(PERSON_URL, params={"language": "es-ES"}).respond(json=load("person_1190668.json"))
    respx.get(PERSON_URL, params={"language": "en-US"}).respond(503)
    with pytest.raises(TmdbUnavailable):
        await client.person_details(1190668)


@respx.mock
async def test_person_details_no_fallback_when_languages_equal(sleeps):
    route = respx.get(PERSON_URL).respond(json=load("person_1190668.json"))
    c = TmdbClient("k", language="en-US", fallback_language="en-US", sleep=sleeps)
    try:
        data = await c.person_details(1190668)
    finally:
        await c.aclose()
    assert data is not None
    assert route.call_count == 1
    assert data[BIOGRAPHY_LANGUAGE_KEY] is None


@respx.mock
async def test_person_details_404_returns_none(client):
    route = respx.get(PERSON_URL).respond(404)
    assert await client.person_details(1190668) is None
    assert route.call_count == 1


@respx.mock
async def test_person_details_401_raises_auth_error(client):
    respx.get(PERSON_URL).respond(401)
    with pytest.raises(TmdbAuthError):
        await client.person_details(1190668)


@respx.mock
async def test_get_image(client):
    respx.get(f"{TMDB_IMAGE_BASE_URL}/w342/abc.jpg").respond(content=b"JPEG")
    respx.get(f"{TMDB_IMAGE_BASE_URL}/w342/missing.jpg").respond(404)
    assert await client.get_image("w342", "abc.jpg") == b"JPEG"
    assert await client.get_image("w342", "missing.jpg") is None


async def test_token_bucket_spaces_requests():
    now = [0.0]
    slept: list[float] = []

    async def sleep(seconds: float) -> None:
        slept.append(seconds)
        now[0] += seconds

    bucket = TokenBucket(rate=2, clock=lambda: now[0], sleep=sleep)
    for _ in range(4):
        await bucket.acquire()
    assert slept == [0.5, 0.5]
