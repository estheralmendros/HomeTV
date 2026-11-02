import pytest

from hometv import __version__
from hometv.db import models as m

AVATAR_COLORS = [
    "#E50914",
    "#F5A623",
    "#F8E71C",
    "#7ED321",
    "#417505",
    "#50E3C2",
    "#4A90E2",
    "#0F3C8C",
    "#9013FE",
    "#BD10E0",
    "#FF6F91",
    "#8B572A",
]
ATTRIBUTION = (
    "Este producto utiliza la API de TMDB, pero no está respaldado ni certificado por TMDB."
)


async def test_health_ok(client):
    r = await client.get("/api/health")
    assert r.status_code == 200
    assert r.json() == {"status": "ok"}


async def test_health_503_when_db_unreachable(client, app, monkeypatch):
    monkeypatch.setattr(app.state.db, "ping", lambda: False)
    r = await client.get("/api/health")
    assert r.status_code == 503
    assert r.json() == {"status": "error"}


async def test_info_exact_shape_without_tmdb_key(client):
    r = await client.get("/api/info")
    assert r.status_code == 200
    assert r.json() == {
        "name": "HomeTV",
        "version": "0.1.0",
        "needs_bootstrap": True,
        "tmdb_configured": False,
        "tmdb_attribution": ATTRIBUTION,
        "avatars": [{"id": f"avatar-{i:02d}", "color": c} for i, c in enumerate(AVATAR_COLORS, 1)],
    }
    assert __version__ == "0.1.0"


class TestWithTmdbKey:
    @pytest.fixture
    def settings(self, make_settings):
        return make_settings(tmdb={"api_key": "abc"})

    async def test_info_tmdb_configured_and_bootstrapped(self, client, db):
        with db.write_session() as s:
            s.add(m.Profile(name="Esther", avatar_id="avatar-05", is_admin=True, pin_hash="x"))
        body = (await client.get("/api/info")).json()
        assert body["tmdb_configured"] is True
        assert body["needs_bootstrap"] is False


async def test_openapi_and_docs_under_api(client):
    r = await client.get("/api/openapi.json")
    assert r.status_code == 200
    spec = r.json()
    assert "/api/health" in spec["paths"]
    assert "/api/info" in spec["paths"]
    schemas = spec["components"]["schemas"]
    assert {"InfoResponse", "HealthResponse", "Avatar", "ErrorResponse"} <= set(schemas)
    info_props = schemas["InfoResponse"]["properties"]
    assert set(info_props) == {
        "name",
        "version",
        "needs_bootstrap",
        "tmdb_configured",
        "tmdb_attribution",
        "avatars",
    }
    docs = await client.get("/api/docs")
    assert docs.status_code == 200
    assert "swagger" in docs.text.lower()
    # Outside /api these are SPA paths (SPA fallback), never FastAPI's default docs/schema.
    root_docs = await client.get("/docs")
    assert "swagger" not in root_docs.text.lower()
    root_schema = await client.get("/openapi.json")
    assert root_schema.headers["content-type"].startswith("text/html")
    assert '"openapi"' not in root_schema.text
