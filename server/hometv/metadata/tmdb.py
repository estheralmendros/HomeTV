"""Async TMDB v3 client (PRD §5 TMDB client).

- Auth: keys starting with ``eyJ`` (v4 read token) -> ``Authorization: Bearer``; else
  ``api_key`` query parameter.
- Rate limit: token bucket ``max_rps`` + at most ``max_in_flight`` (8) concurrent requests;
  image downloads share the limiter.
- Retries: 429 -> sleep ``Retry-After`` (default 2 s) and retry; 5xx/connect/read errors ->
  backoff 1, 2, 4 s then ``TmdbUnavailable``; 401 -> ``TmdbAuthError``; 404 -> ``None``.
- Language fallback: details/season responses with an empty ``overview`` or title/name are
  re-requested in ``fallback_language`` and only empty fields are filled.

``sleep``/``clock`` are injectable so tests never wait.
"""

import asyncio
import logging
import time
from collections.abc import Awaitable, Callable
from typing import Any

import httpx

from hometv.config import Settings
from hometv.text import is_blank

logger = logging.getLogger("hometv.tmdb")

TMDB_BASE_URL = "https://api.themoviedb.org/3"
TMDB_IMAGE_BASE_URL = "https://image.tmdb.org/t/p"
REQUEST_TIMEOUT_SECONDS = 10.0
MAX_IN_FLIGHT = 8
BACKOFF_SECONDS = (1, 2, 4)
DEFAULT_RETRY_AFTER = 2.0
MAX_RATE_LIMITED_RETRIES = 10

Sleep = Callable[[float], Awaitable[None]]


class TmdbError(Exception):
    """Unexpected TMDB response."""


class TmdbAuthError(TmdbError):
    """401: the API key is invalid (enrichment stops, ``tmdb_auth_failed``)."""


class TmdbUnavailable(TmdbError):
    """Transient failure that persisted after the retries (5xx, timeout, network)."""


class TokenBucket:
    def __init__(
        self,
        rate: float,
        capacity: float | None = None,
        *,
        clock: Callable[[], float] = time.monotonic,
        sleep: Sleep = asyncio.sleep,
    ) -> None:
        self.rate = float(rate)
        self.capacity = float(capacity if capacity is not None else max(rate, 1))
        self._tokens = self.capacity
        self._clock = clock
        self._sleep = sleep
        self._updated = clock()
        self._lock = asyncio.Lock()

    async def acquire(self) -> None:
        async with self._lock:
            while True:
                now = self._clock()
                self._tokens = min(self.capacity, self._tokens + (now - self._updated) * self.rate)
                self._updated = now
                if self._tokens >= 1:
                    self._tokens -= 1
                    return
                await self._sleep((1 - self._tokens) / self.rate)


def _retry_after(response: httpx.Response) -> float:
    value = response.headers.get("Retry-After")
    try:
        return max(0.0, float(value)) if value is not None else DEFAULT_RETRY_AFTER
    except ValueError:
        return DEFAULT_RETRY_AFTER


def _fill_empty(target: dict[str, Any], source: dict[str, Any], keys: tuple[str, ...]) -> None:
    for key in keys:
        if is_blank(target.get(key)) and not is_blank(source.get(key)):
            target[key] = source[key]


class TmdbClient:
    def __init__(
        self,
        api_key: str,
        *,
        language: str = "es-ES",
        fallback_language: str = "en-US",
        max_rps: float = 20,
        max_in_flight: int = MAX_IN_FLIGHT,
        base_url: str = TMDB_BASE_URL,
        image_base_url: str = TMDB_IMAGE_BASE_URL,
        timeout: float = REQUEST_TIMEOUT_SECONDS,
        sleep: Sleep = asyncio.sleep,
        http: httpx.AsyncClient | None = None,
    ) -> None:
        self.api_key = api_key
        self.language = language
        self.fallback_language = fallback_language
        self.base_url = base_url.rstrip("/")
        self.image_base_url = image_base_url.rstrip("/")
        self._sleep = sleep
        self._bucket = TokenBucket(max_rps, sleep=sleep)
        self._in_flight = asyncio.Semaphore(max_in_flight)
        self._http = http or httpx.AsyncClient(timeout=timeout)
        self._bearer = api_key.startswith("eyJ")

    @classmethod
    def from_settings(cls, settings: Settings) -> "TmdbClient | None":
        if not settings.tmdb.api_key:
            return None
        return cls(
            settings.tmdb.api_key,
            language=settings.tmdb.language,
            fallback_language=settings.tmdb.fallback_language,
            max_rps=settings.tmdb.max_rps,
        )

    async def aclose(self) -> None:
        await self._http.aclose()

    # -- transport --

    async def _get(
        self, url: str, params: dict[str, Any] | None = None, *, authed: bool = True
    ) -> httpx.Response | None:
        query = {k: v for k, v in (params or {}).items() if v is not None}
        headers: dict[str, str] = {}
        if authed:
            if self._bearer:
                headers["Authorization"] = f"Bearer {self.api_key}"
            else:
                query = {"api_key": self.api_key, **query}
        transient_attempts = 0
        rate_limited = 0
        while True:
            await self._bucket.acquire()
            failure: str
            async with self._in_flight:
                try:
                    response = await self._http.get(url, params=query, headers=headers)
                except (httpx.TimeoutException, httpx.TransportError) as exc:
                    response = None
                    failure = f"{type(exc).__name__}: {exc}"
            if response is not None:
                status = response.status_code
                if status == 429:
                    rate_limited += 1
                    if rate_limited > MAX_RATE_LIMITED_RETRIES:
                        raise TmdbUnavailable("rate limited")
                    await self._sleep(_retry_after(response))
                    continue
                if status == 401:
                    raise TmdbAuthError("TMDB rejected the API key (401)")
                if status == 404:
                    return None
                if 200 <= status < 300:
                    return response
                if status < 500:
                    raise TmdbError(f"unexpected TMDB status {status}")
                failure = f"HTTP {status}"
            if transient_attempts >= len(BACKOFF_SECONDS):
                raise TmdbUnavailable(failure)
            await self._sleep(BACKOFF_SECONDS[transient_attempts])
            transient_attempts += 1

    async def _get_json(self, path: str, params: dict[str, Any]) -> dict[str, Any] | None:
        response = await self._get(f"{self.base_url}{path}", params)
        if response is None:
            return None
        try:
            data = response.json()
        except ValueError as exc:
            raise TmdbUnavailable("invalid JSON from TMDB") from exc
        if not isinstance(data, dict):
            raise TmdbUnavailable("unexpected JSON from TMDB")
        return data

    def _needs_fallback(self) -> bool:
        return bool(self.fallback_language) and self.fallback_language != self.language

    # -- API calls --

    async def search_movie(
        self, query: str, year: int | None = None, *, language: str | None = None
    ) -> list[dict[str, Any]]:
        data = await self._get_json(
            "/search/movie",
            {
                "query": query,
                "year": year,
                "language": language or self.language,
                "include_adult": "false",
            },
        )
        return list((data or {}).get("results") or [])

    async def search_tv(
        self, query: str, year: int | None = None, *, language: str | None = None
    ) -> list[dict[str, Any]]:
        data = await self._get_json(
            "/search/tv",
            {"query": query, "first_air_date_year": year, "language": language or self.language},
        )
        return list((data or {}).get("results") or [])

    async def movie_details(self, tmdb_id: int) -> dict[str, Any] | None:
        return await self._details(f"/movie/{tmdb_id}", ("title", "overview", "tagline"))

    async def tv_details(self, tmdb_id: int) -> dict[str, Any] | None:
        return await self._details(f"/tv/{tmdb_id}", ("name", "overview"))

    async def _details(self, path: str, fields: tuple[str, ...]) -> dict[str, Any] | None:
        data = await self._get_json(
            path, {"append_to_response": "credits", "language": self.language}
        )
        if data is None:
            return None
        if self._needs_fallback() and any(is_blank(data.get(k)) for k in fields[:2]):
            fallback = await self._get_json(path, {"language": self.fallback_language})
            if fallback:
                _fill_empty(data, fallback, fields)
        return data

    async def tv_season(self, tmdb_id: int, season_number: int) -> dict[str, Any] | None:
        path = f"/tv/{tmdb_id}/season/{season_number}"
        data = await self._get_json(path, {"language": self.language})
        if data is None:
            return None
        episodes = data.get("episodes") or []
        incomplete = (
            is_blank(data.get("name"))
            or is_blank(data.get("overview"))
            or any(is_blank(e.get("name")) or is_blank(e.get("overview")) for e in episodes)
        )
        if self._needs_fallback() and incomplete:
            fallback = await self._get_json(path, {"language": self.fallback_language})
            if fallback:
                _fill_empty(data, fallback, ("name", "overview"))
                by_number = {e.get("episode_number"): e for e in fallback.get("episodes") or []}
                for episode in episodes:
                    other = by_number.get(episode.get("episode_number"))
                    if other:
                        _fill_empty(episode, other, ("name", "overview"))
        return data

    async def get_image(self, size: str, file_name: str) -> bytes | None:
        """Download ``{image_base}/{size}/{file_name}``; ``None`` on 404."""
        response = await self._get(f"{self.image_base_url}/{size}/{file_name}", authed=False)
        return None if response is None else response.content
