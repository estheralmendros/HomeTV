"""Artwork cache: download on miss once, then serve from disk."""

import asyncio

import httpx
import pytest
import respx

from hometv.metadata.artwork import (
    ArtworkCache,
    ArtworkNotFound,
    InvalidArtworkName,
    InvalidArtworkSize,
    image,
    tmdb_file_name,
)
from hometv.metadata.tmdb import TMDB_IMAGE_BASE_URL, TmdbClient, TmdbUnavailable


async def _no_sleep(_seconds: float) -> None:
    return None


@pytest.fixture
async def tmdb():
    c = TmdbClient("k", sleep=_no_sleep)
    yield c
    await c.aclose()


@pytest.fixture
def cache(tmp_path, tmdb) -> ArtworkCache:
    return ArtworkCache(tmp_path / "artwork", tmdb)


@respx.mock
async def test_miss_downloads_once_then_serves_from_disk(cache, tmp_path):
    route = respx.get(f"{TMDB_IMAGE_BASE_URL}/w342/abc123.jpg").respond(content=b"JPEG")
    path = await cache.ensure("w342", "abc123.jpg")
    assert path == tmp_path / "artwork" / "w342" / "abc123.jpg"
    assert path.read_bytes() == b"JPEG"
    assert not list(path.parent.glob("*.tmp"))
    again = await cache.ensure("w342", "abc123.jpg")
    assert again == path
    assert route.call_count == 1


@respx.mock
async def test_concurrent_misses_are_deduplicated(cache):
    async def slow(_request):
        await asyncio.sleep(0.05)
        return httpx.Response(200, content=b"PNG")

    route = respx.get(f"{TMDB_IMAGE_BASE_URL}/w185/p_1.png").mock(side_effect=slow)
    paths = await asyncio.gather(*(cache.ensure("w185", "p_1.png") for _ in range(5)))
    assert len(set(paths)) == 1
    assert route.call_count == 1


@respx.mock
async def test_tmdb_404_is_artwork_not_found(cache):
    respx.get(f"{TMDB_IMAGE_BASE_URL}/w300/gone.jpg").respond(404)
    with pytest.raises(ArtworkNotFound):
        await cache.ensure("w300", "gone.jpg")


@respx.mock
async def test_tmdb_down_propagates_unavailable(cache):
    respx.get(f"{TMDB_IMAGE_BASE_URL}/w300/x.jpg").respond(500)
    with pytest.raises(TmdbUnavailable):
        await cache.ensure("w300", "x.jpg")


async def test_without_client_a_miss_is_not_found(tmp_path):
    cache = ArtworkCache(tmp_path / "artwork", None)
    with pytest.raises(ArtworkNotFound):
        await cache.ensure("w342", "abc.jpg")
    (tmp_path / "artwork" / "w342").mkdir(parents=True)
    (tmp_path / "artwork" / "w342" / "abc.jpg").write_bytes(b"x")
    assert (await cache.ensure("w342", "abc.jpg")).read_bytes() == b"x"


@pytest.mark.parametrize("size", ["w92", "original", "", "../w342"])
async def test_invalid_size(cache, size):
    with pytest.raises(InvalidArtworkSize):
        cache.validate(size, "abc.jpg")


@pytest.mark.parametrize("name", ["../x.jpg", "a.gif", "a b.jpg", ".jpg", "abc", "a/b.jpg"])
async def test_invalid_file_name(cache, name):
    with pytest.raises(InvalidArtworkName):
        cache.validate("w342", name)


@respx.mock
async def test_prefetch_downloads_eager_sizes_and_ignores_failures(cache, tmp_path):
    poster = respx.get(url__regex=rf"{TMDB_IMAGE_BASE_URL}/w(342|780)/poster\.jpg").respond(
        content=b"P"
    )
    backdrop = respx.get(url__regex=rf"{TMDB_IMAGE_BASE_URL}/w(780|1280)/bd\.jpg").respond(404)
    await cache.prefetch(poster="poster.jpg", backdrop="bd.jpg")
    assert poster.call_count == 2
    assert backdrop.call_count == 2
    assert (tmp_path / "artwork" / "w342" / "poster.jpg").exists()
    assert (tmp_path / "artwork" / "w780" / "poster.jpg").exists()


@respx.mock
async def test_h632_profile_size_is_valid_and_downloaded_on_demand(cache, tmp_path):
    cache.validate("h632", "abc.jpg")
    route = respx.get(f"{TMDB_IMAGE_BASE_URL}/h632/abc.jpg").respond(content=b"IMG")
    assert not (tmp_path / "artwork" / "h632").exists()
    path = await cache.ensure("h632", "abc.jpg")
    assert path == tmp_path / "artwork" / "h632" / "abc.jpg"
    assert path.read_bytes() == b"IMG"
    assert route.call_count == 1


def test_tmdb_file_name_and_image_object():
    assert tmdb_file_name("/abc123.jpg") == "abc123.jpg"
    assert tmdb_file_name(None) is None
    assert tmdb_file_name("/weird name.jpg") is None
    assert image("poster", "abc.jpg") == {
        "small": "/api/artwork/w342/abc.jpg",
        "large": "/api/artwork/w780/abc.jpg",
    }
    assert image("backdrop", "b.jpg") == {
        "small": "/api/artwork/w780/b.jpg",
        "large": "/api/artwork/w1280/b.jpg",
    }
    assert image("still", "s.jpg")["small"] == "/api/artwork/w300/s.jpg"
    assert image("profile", "p.jpg") == {
        "small": "/api/artwork/w185/p.jpg",
        "large": "/api/artwork/h632/p.jpg",
    }
    assert image("poster", None) is None
