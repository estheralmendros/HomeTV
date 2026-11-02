"""Web SPA serving: static web/dist, SPA fallback, stale-asset 404, missing-dist placeholder."""

from pathlib import Path

import httpx
import pytest

INDEX = "<!doctype html><html><head><title>HomeTV</title></head><body>app</body></html>"
IMMUTABLE = "public, max-age=31536000, immutable"


@pytest.fixture
def dist(tmp_path: Path) -> Path:
    root = tmp_path / "dist"
    (root / "assets").mkdir(parents=True)
    (root / "index.html").write_text(INDEX)
    (root / "assets" / "index-abc123.js").write_text("console.log('hi');")
    (root / "assets" / "index-abc123.css").write_text("body{}")
    (root / "favicon.ico").write_bytes(b"\x00\x00\x01\x00")
    (tmp_path / "secret.txt").write_text("outside dist")
    return root


@pytest.fixture
def settings(make_settings, dist):
    return make_settings(server={"web_dist": dist})


async def test_root_serves_index_with_no_cache(client):
    r = await client.get("/")
    assert r.status_code == 200
    assert r.text == INDEX
    assert r.headers["content-type"].startswith("text/html")
    assert r.headers["cache-control"] == "no-cache"


@pytest.mark.parametrize("path", ["/films/12", "/shows/3/season/1", "/admin", "/index"])
async def test_unknown_non_api_path_falls_back_to_index(client, path):
    r = await client.get(path)
    assert r.status_code == 200
    assert r.text == INDEX
    assert r.headers["cache-control"] == "no-cache"


async def test_existing_asset_is_immutable(client):
    r = await client.get("/assets/index-abc123.js")
    assert r.status_code == 200
    assert r.text == "console.log('hi');"
    assert "javascript" in r.headers["content-type"]
    assert r.headers["cache-control"] == IMMUTABLE
    css = await client.get("/assets/index-abc123.css")
    assert css.headers["content-type"].startswith("text/css")
    assert css.headers["cache-control"] == IMMUTABLE


async def test_other_existing_file_is_no_cache(client):
    r = await client.get("/favicon.ico")
    assert r.status_code == 200
    assert r.content == b"\x00\x00\x01\x00"
    assert r.headers["cache-control"] == "no-cache"


async def test_index_html_by_name_is_no_cache(client):
    r = await client.get("/index.html")
    assert r.status_code == 200
    assert r.text == INDEX
    assert r.headers["cache-control"] == "no-cache"


@pytest.mark.parametrize("path", ["/assets/old-chunk.js", "/assets/Player-abc123.js", "/assets/"])
async def test_missing_asset_is_plain_404_never_index(client, path):
    r = await client.get(path)
    assert r.status_code == 404
    assert r.headers["content-type"].startswith("text/plain")
    assert r.headers["cache-control"] == "no-store"
    assert "<html" not in r.text.lower()
    assert r.text != INDEX


async def test_head_returns_headers_without_body(client):
    r = await client.head("/assets/index-abc123.js")
    assert r.status_code == 200
    assert r.content == b""
    assert r.headers["cache-control"] == IMMUTABLE
    fallback = await client.head("/films/1")
    assert fallback.status_code == 200
    assert fallback.content == b""
    assert fallback.headers["cache-control"] == "no-cache"
    missing = await client.head("/assets/nope.js")
    assert missing.status_code == 404


@pytest.mark.parametrize(
    "raw_path",
    [
        b"/../secret.txt",
        b"/%2e%2e/secret.txt",
        b"/assets/..%2f..%2fsecret.txt",
        b"/..%2fsecret.txt",
    ],
)
async def test_path_traversal_is_404(app, raw_path):
    async def call() -> tuple[int, bytes]:
        messages: list[dict] = []
        scope = {
            "type": "http",
            "asgi": {"version": "3.0"},
            "http_version": "1.1",
            "method": "GET",
            "scheme": "http",
            "path": raw_path.decode().replace("%2e", ".").replace("%2f", "/"),
            "raw_path": raw_path,
            "query_string": b"",
            "root_path": "",
            "headers": [(b"host", b"test")],
            "server": ("test", 80),
            "client": ("127.0.0.1", 1),
        }

        async def receive():
            return {"type": "http.request", "body": b"", "more_body": False}

        async def send(message):
            messages.append(message)

        await app(scope, receive, send)
        start = next(m for m in messages if m["type"] == "http.response.start")
        body = b"".join(m.get("body", b"") for m in messages if m["type"] == "http.response.body")
        return start["status"], body

    status, body = await call()
    assert status == 404
    assert b"outside dist" not in body


async def test_unknown_api_paths_are_json_404_never_spa(client):
    for method in ("GET", "HEAD", "POST", "PUT", "DELETE"):
        r = await client.request(method, "/api/nope/deeper")
        assert r.status_code == 404, method
        if method != "HEAD":
            assert r.json()["error"]["code"] == "not_found"
    r = await client.get("/api")
    assert r.status_code == 404
    assert r.json()["error"]["code"] == "not_found"


async def test_known_api_routes_still_win(client):
    assert (await client.get("/api/health")).status_code == 200
    r = await client.post("/api/health")
    assert r.status_code == 405
    assert r.json()["error"]["code"] == "method_not_allowed"


async def test_openapi_and_docs_are_not_shadowed(client):
    r = await client.get("/api/openapi.json")
    assert r.status_code == 200
    assert "/{path}" not in r.json()["paths"]
    assert (await client.get("/api/docs")).status_code == 200


async def test_non_get_on_spa_path_is_405(client):
    r = await client.post("/films/1")
    assert r.status_code == 405


async def test_dist_changes_are_picked_up_without_restart(client, dist):
    (dist / "assets" / "new-def456.js").write_text("new")
    r = await client.get("/assets/new-def456.js")
    assert r.status_code == 200
    (dist / "index.html").write_text("<html>v2</html>")
    assert (await client.get("/films/1")).text == "<html>v2</html>"


class TestMissingDist:
    @pytest.fixture
    def settings(self, make_settings, tmp_path):
        return make_settings(server={"web_dist": tmp_path / "no-such-dist"})

    async def test_root_returns_placeholder_page(self, client):
        r = await client.get("/")
        assert r.status_code == 200
        assert r.headers["content-type"].startswith("text/html")
        assert "Web UI not built" in r.text
        assert r.headers["cache-control"] == "no-cache"

    async def test_other_paths_also_return_placeholder(self, client):
        r = await client.get("/films/3")
        assert r.status_code == 200
        assert "Web UI not built" in r.text

    async def test_assets_still_404(self, client):
        r = await client.get("/assets/index-abc.js")
        assert r.status_code == 404
        assert r.headers["content-type"].startswith("text/plain")

    async def test_api_unaffected(self, client):
        assert (await client.get("/api/health")).status_code == 200
        assert (await client.get("/api/nope")).json()["error"]["code"] == "not_found"


class TestDistWithoutIndex:
    @pytest.fixture
    def settings(self, make_settings, tmp_path):
        root = tmp_path / "half-dist"
        root.mkdir()
        return make_settings(server={"web_dist": root})

    async def test_placeholder_when_index_missing(self, client):
        r = await client.get("/")
        assert r.status_code == 200
        assert "Web UI not built" in r.text


async def test_cors_disabled_by_default(client):
    r = await client.get("/api/health", headers={"Origin": "http://localhost:5173"})
    assert "access-control-allow-origin" not in r.headers


class TestCors:
    @pytest.fixture
    def settings(self, make_settings, dist):
        return make_settings(server={"web_dist": dist, "cors_origins": ["http://localhost:5173"]})

    async def test_cors_enabled_for_configured_origin(self, app):
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as c:
            r = await c.options(
                "/api/health",
                headers={
                    "Origin": "http://localhost:5173",
                    "Access-Control-Request-Method": "GET",
                },
            )
        assert r.status_code == 200
        assert r.headers["access-control-allow-origin"] == "http://localhost:5173"
