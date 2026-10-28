"""GET /api/artwork/{size}/{file_name} (PRD §5 Artwork cache, Media & playback errors)."""

import pytest
import respx

from hometv.metadata import tmdb as tmdb_module
from hometv.metadata.tmdb import TMDB_IMAGE_BASE_URL

CACHE_CONTROL = "private, max-age=31536000, immutable"


@pytest.fixture
def settings(make_settings):
    return make_settings(tmdb={"api_key": "k"})


@pytest.fixture
def images():
    with respx.mock(assert_all_called=False) as router:
        yield router


async def test_miss_downloads_once_then_serves_from_disk(client, app, images, user):
    route = images.get(f"{TMDB_IMAGE_BASE_URL}/w342/abc123.jpg").respond(content=b"JPEGDATA")
    for _ in range(2):
        response = await client.get("/api/artwork/w342/abc123.jpg", headers=user.headers)
        assert response.status_code == 200
        assert response.content == b"JPEGDATA"
        assert response.headers["content-type"] == "image/jpeg"
        assert response.headers["cache-control"] == CACHE_CONTROL
    assert route.call_count == 1
    settings = app.state.settings
    assert (settings.artwork_dir / "w342" / "abc123.jpg").read_bytes() == b"JPEGDATA"


async def test_h632_profile_size_is_served(client, images, user):
    route = images.get(f"{TMDB_IMAGE_BASE_URL}/h632/tc.jpg").respond(content=b"JPEG")
    response = await client.get("/api/artwork/h632/tc.jpg", headers=user.headers)
    assert response.status_code == 200
    assert response.content == b"JPEG"
    assert route.call_count == 1


async def test_svg_content_type(client, images, user):
    images.get(f"{TMDB_IMAGE_BASE_URL}/w185/logo.svg").respond(content=b"<svg/>")
    response = await client.get(f"/api/artwork/w185/logo.svg?token={user.token}")
    assert response.headers["content-type"].startswith("image/svg+xml")
    # Opened directly, an SVG from the app origin must not run script there.
    csp = response.headers["content-security-policy"]
    assert "default-src 'none'" in csp and "sandbox" in csp
    assert response.headers["x-content-type-options"] == "nosniff"


@pytest.mark.parametrize(
    ("path", "code"),
    [
        ("/api/artwork/w92/abc.jpg", "invalid_size"),
        ("/api/artwork/original/abc.jpg", "invalid_size"),
        ("/api/artwork/w342/abc.gif", "invalid_file_name"),
        ("/api/artwork/w342/..secret.jpg", "invalid_file_name"),
    ],
)
async def test_validation_errors(client, path, code, user):
    response = await client.get(path, headers=user.headers)
    assert response.status_code == 422
    assert response.json()["error"]["code"] == code


async def test_encoded_slash_never_reaches_the_handler(client):
    response = await client.get("/api/artwork/w342/..%2F..%2Fhometv.jpg")
    assert response.status_code == 404
    assert response.json()["error"]["code"] == "not_found"


async def test_tmdb_404_is_artwork_not_found(client, images, user):
    images.get(f"{TMDB_IMAGE_BASE_URL}/w300/gone.jpg").respond(404)
    response = await client.get("/api/artwork/w300/gone.jpg", headers=user.headers)
    assert response.status_code == 404
    assert response.json()["error"]["code"] == "artwork_not_found"


async def test_tmdb_down_is_502(client, images, monkeypatch, user):
    monkeypatch.setattr(tmdb_module, "BACKOFF_SECONDS", (0, 0, 0))
    images.get(f"{TMDB_IMAGE_BASE_URL}/w300/x.jpg").respond(500)
    response = await client.get("/api/artwork/w300/x.jpg", headers=user.headers)
    assert response.status_code == 502
    assert response.json()["error"]["code"] == "tmdb_unavailable"


async def test_artwork_requires_a_profile_token(client, images, user):
    images.get(f"{TMDB_IMAGE_BASE_URL}/w342/abc123.jpg").respond(content=b"JPEGDATA")
    path = "/api/artwork/w342/abc123.jpg"
    for kwargs in ({}, {"headers": {"Authorization": "Bearer bad"}}, {"params": {"token": "x"}}):
        response = await client.get(path, **kwargs)
        assert response.status_code == 401
        assert response.json()["error"]["code"] == "unauthorized"
    assert (await client.get(path, params={"token": user.token})).status_code == 200
    # Header wins when both are present.
    both = await client.get(
        path, params={"token": user.token}, headers={"Authorization": "Bearer bad"}
    )
    assert both.status_code == 401
    both = await client.get(path, params={"token": "bad"}, headers=user.headers)
    assert both.status_code == 200


async def test_app_state_wiring(app):
    assert app.state.tmdb is not None
    assert app.state.artwork.client is app.state.tmdb
    assert app.state.scheduler.background_task is None  # disabled by test settings
    assert app.state.tmdb_proxy.client is app.state.tmdb
