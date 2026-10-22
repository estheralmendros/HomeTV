import httpx
from fastapi import Query

from hometv.api.errors import ApiError


async def test_unknown_api_path_is_json_404_envelope(client):
    r = await client.get("/api/does-not-exist")
    assert r.status_code == 404
    body = r.json()
    assert body["error"]["code"] == "not_found"
    assert isinstance(body["error"]["message"], str) and body["error"]["message"]
    assert body["error"]["details"] == {}


async def test_method_not_allowed_envelope(client):
    r = await client.post("/api/health")
    assert r.status_code == 405
    assert r.json()["error"]["code"] == "method_not_allowed"


async def test_api_error_envelope(app, client):
    @app.get("/api/_test/teapot")
    def teapot():
        raise ApiError(409, "already_bootstrapped", "Already bootstrapped", {"detail": "x"})

    r = await client.get("/api/_test/teapot")
    assert r.status_code == 409
    assert r.json() == {
        "error": {
            "code": "already_bootstrapped",
            "message": "Already bootstrapped",
            "details": {"detail": "x"},
        }
    }


async def test_api_error_with_headers(app, client):
    @app.get("/api/_test/retry")
    def retry():
        raise ApiError(503, "subtitle_pending", "Subtitle pending", headers={"Retry-After": "10"})

    r = await client.get("/api/_test/retry")
    assert r.status_code == 503
    assert r.headers["retry-after"] == "10"
    assert r.json()["error"]["details"] == {}


async def test_validation_error_envelope(app, client):
    @app.get("/api/_test/items")
    def items(page_size: int = Query(50, ge=1, le=200)):
        return {"page_size": page_size}

    r = await client.get("/api/_test/items", params={"page_size": 0})
    assert r.status_code == 422
    body = r.json()
    assert body["error"]["code"] == "validation_error"
    errors = body["error"]["details"]["errors"]
    assert errors[0]["loc"] == ["query", "page_size"]
    assert isinstance(errors[0]["msg"], str)
    assert set(errors[0]) == {"loc", "msg"}


async def test_unhandled_exception_is_500_envelope(app):
    @app.get("/api/_test/boom")
    def boom():
        raise RuntimeError("secret internals")

    transport = httpx.ASGITransport(app=app, raise_app_exceptions=False)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as c:
        r = await c.get("/api/_test/boom")
    assert r.status_code == 500
    assert r.json()["error"]["code"] == "internal_error"
    assert "secret internals" not in r.text
