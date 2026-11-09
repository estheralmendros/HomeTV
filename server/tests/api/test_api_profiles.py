"""System & profiles endpoints."""

import pytest

PROFILE_KEYS = {"id", "name", "avatar_id", "is_admin", "created_at"}
DEFAULT_STYLE = {
    "size": "medium",
    "color": "white",
    "edge": "outline",
    "edge_color": "black",
    "position": "low",
}
STYLE = {
    "size": "large",
    "color": "yellow",
    "edge": "shadow",
    "edge_color": "dark_gray",
    "position": "high",
}


def _iso_z(value: str) -> bool:
    return value.endswith("Z") and "T" in value


async def test_profiles_list_public_ordered_by_id_without_pin(client, admin, user):
    r = await client.get("/api/profiles")
    assert r.status_code == 200
    items = r.json()["items"]
    assert [x["id"] for x in items] == [admin.id, user.id]
    assert all(set(x) == PROFILE_KEYS for x in items)
    assert items[0] == {
        "id": admin.id,
        "name": "Esther",
        "avatar_id": "avatar-05",
        "is_admin": True,
        "created_at": items[0]["created_at"],
    }
    assert _iso_z(items[0]["created_at"])


async def test_bootstrap_once_with_pin(client):
    body = {"name": " Esther ", "avatar_id": "avatar-05", "pin": "1234"}
    r = await client.post("/api/profiles/bootstrap", json=body)
    assert r.status_code == 201
    data = r.json()
    assert set(data) == {"profile", "token", "expires_at"}
    assert set(data["profile"]) == PROFILE_KEYS
    assert data["profile"]["name"] == "Esther" and data["profile"]["is_admin"] is True
    assert _iso_z(data["expires_at"])
    me = await client.get("/api/me", headers={"Authorization": f"Bearer {data['token']}"})
    assert me.status_code == 200
    mine = me.json()
    assert mine.pop("subtitle_style") == DEFAULT_STYLE
    assert mine == data["profile"]
    assert (await client.get("/api/info")).json()["needs_bootstrap"] is False

    r = await client.post("/api/profiles/bootstrap", json=body)
    assert r.status_code == 409
    assert r.json()["error"]["code"] == "already_bootstrapped"


@pytest.mark.parametrize(
    "body",
    [
        {"name": "Esther", "avatar_id": "avatar-05"},
        {"name": "Esther", "avatar_id": "avatar-05", "pin": "123"},
        {"name": "Esther", "avatar_id": "avatar-05", "pin": "12345"},
        {"name": "Esther", "avatar_id": "avatar-05", "pin": "12a4"},
        {"name": "Esther", "avatar_id": "avatar-05", "pin": "١٢٣٤"},  # non-ASCII digits
        {"name": "Esther", "avatar_id": "avatar-13", "pin": "1234"},
        {"name": "   ", "avatar_id": "avatar-05", "pin": "1234"},
        {"name": "x" * 31, "avatar_id": "avatar-05", "pin": "1234"},
    ],
)
async def test_bootstrap_validation(client, body):
    r = await client.post("/api/profiles/bootstrap", json=body)
    assert r.status_code == 422
    assert r.json()["error"]["code"] == "validation_error"


async def test_session_for_any_profile_without_pin(client, admin, user):
    for who in (admin, user):
        r = await client.post(f"/api/profiles/{who.id}/session")
        assert r.status_code == 200
        data = r.json()
        assert set(data) == {"profile", "token", "expires_at"}
        assert data["profile"]["id"] == who.id
        me = await client.get("/api/me", headers={"Authorization": f"Bearer {data['token']}"})
        assert me.json()["id"] == who.id
    # A body with a PIN is ignored (even a wrong one).
    r = await client.post(f"/api/profiles/{admin.id}/session", json={"pin": "0000"})
    assert r.status_code == 200
    r = await client.post(f"/api/profiles/{admin.id}/session", content=b"not json")
    assert r.status_code == 200


async def test_session_unknown_profile(client):
    r = await client.post("/api/profiles/999/session")
    assert r.status_code == 404
    assert r.json()["error"]["code"] == "profile_not_found"


async def test_me_requires_valid_token(client, user):
    r = await client.get("/api/me")
    assert r.status_code == 401 and r.json()["error"]["code"] == "unauthorized"
    r = await client.get("/api/me", headers={"Authorization": "Bearer nope"})
    assert r.status_code == 401
    r = await client.get("/api/me", headers={"Authorization": f"Basic {user.token}"})
    assert r.status_code == 401
    r = await client.get("/api/me", headers=user.headers)
    assert r.status_code == 200 and r.json()["name"] == "Kids"


async def test_profile_token_expires_after_ttl(client, clock, app, db):
    from hometv import profiles as p

    profile = p.create_profile(db, name="Kids", avatar_id="avatar-01", is_admin=False, pin=None)
    token = app.state.tokens.issue_profile_token(profile.id).token
    headers = {"Authorization": f"Bearer {token}"}
    clock.advance(30 * 86400)
    assert (await client.get("/api/me", headers=headers)).status_code == 200
    clock.advance(1)
    r = await client.get("/api/me", headers=headers)
    assert r.status_code == 401 and r.json()["error"]["code"] == "unauthorized"


async def test_deleted_profile_token_is_rejected(client, admin, user):
    r = await client.delete(f"/api/admin/profiles/{user.id}", headers=admin.admin_headers)
    assert r.status_code == 204
    assert (await client.get("/api/me", headers=user.headers)).status_code == 401


async def test_pin_and_admin_changes_keep_profile_tokens(client, admin, profile_factory):
    other = profile_factory("Javi", is_admin=True, pin="4321")
    r = await client.patch(
        f"/api/admin/profiles/{other.id}", json={"pin": "9999"}, headers=admin.admin_headers
    )
    assert r.status_code == 200
    r = await client.patch(
        f"/api/admin/profiles/{other.id}", json={"is_admin": False}, headers=admin.admin_headers
    )
    assert r.status_code == 200
    assert (await client.get("/api/me", headers=other.headers)).status_code == 200


# --- /api/me subtitle style -------------------------------------------------------------------


async def test_me_is_the_profile_plus_its_subtitle_style(client, user):
    r = await client.get("/api/me", headers=user.headers)
    assert r.status_code == 200
    data = r.json()
    assert set(data) == PROFILE_KEYS | {"subtitle_style"}
    assert data["subtitle_style"] == DEFAULT_STYLE
    listed = (await client.get("/api/profiles")).json()["items"]
    assert all("subtitle_style" not in x for x in listed)
    assert next(x for x in listed if x["id"] == user.id) == {
        k: v for k, v in data.items() if k != "subtitle_style"
    }


async def test_put_subtitle_style_round_trip_per_profile(client, user, admin):
    r = await client.put("/api/me/subtitle-style", json=STYLE, headers=user.headers)
    assert (r.status_code, r.content) == (204, b"")
    assert (await client.get("/api/me", headers=user.headers)).json()["subtitle_style"] == STYLE
    other = (await client.get("/api/me", headers=admin.headers)).json()["subtitle_style"]
    assert other == DEFAULT_STYLE
    clash = {**STYLE, "color": "white", "edge": "outline", "edge_color": "white"}
    r = await client.put("/api/me/subtitle-style", json=clash, headers=user.headers)
    assert r.status_code == 204  # the white-on-white clash is accepted (renderers resolve it)
    assert (await client.get("/api/me", headers=user.headers)).json()["subtitle_style"] == clash
    # The session responses keep the plain Profile shape.
    session = (await client.post(f"/api/profiles/{user.id}/session")).json()
    assert set(session["profile"]) == PROFILE_KEYS


@pytest.mark.parametrize(
    "body",
    [
        *({**STYLE, field: "purple"} for field in STYLE),
        *({k: v for k, v in STYLE.items() if k != field} for field in STYLE),
        {**STYLE, "font": "serif"},
        {},
        None,
    ],
)
async def test_put_subtitle_style_validation(client, user, body):
    kwargs = {} if body is None else {"json": body}
    r = await client.put("/api/me/subtitle-style", headers=user.headers, **kwargs)
    assert r.status_code == 422
    assert r.json()["error"]["code"] == "validation_error"
    assert (await client.get("/api/me", headers=user.headers)).json()["subtitle_style"] == (
        DEFAULT_STYLE
    )


async def test_put_subtitle_style_needs_a_bearer_profile_token(client, user, admin):
    for kwargs in ({}, {"params": {"token": user.token}}):
        r = await client.put("/api/me/subtitle-style", json=STYLE, **kwargs)
        assert (r.status_code, r.json()["error"]["code"]) == (401, "unauthorized")
    admin_only = {"X-Admin-Token": admin.admin_headers["X-Admin-Token"]}
    r = await client.put("/api/me/subtitle-style", json=STYLE, headers=admin_only)
    assert r.status_code == 401


async def test_put_subtitle_style_for_a_deleted_profile_is_unauthorized(
    client, admin, profile_factory
):
    kids = profile_factory("Kids2")
    r = await client.delete(f"/api/admin/profiles/{kids.id}", headers=admin.admin_headers)
    assert r.status_code == 204
    r = await client.put("/api/me/subtitle-style", json=STYLE, headers=kids.headers)
    assert (r.status_code, r.json()["error"]["code"]) == (401, "unauthorized")


async def test_me_and_style_routes_in_openapi(client):
    spec = (await client.get("/api/openapi.json")).json()
    paths = spec["paths"]
    assert set(paths["/api/me/subtitle-style"]) == {"put"}
    me_ok = paths["/api/me"]["get"]["responses"]["200"]["content"]["application/json"]["schema"]
    assert me_ok == {"$ref": "#/components/schemas/Me"}
    schemas = spec["components"]["schemas"]
    assert set(schemas["Me"]["properties"]) == PROFILE_KEYS | {"subtitle_style"}
    assert set(schemas["SubtitleStyle"]["properties"]) == set(STYLE)
    assert set(schemas["SubtitleStyleRequest"]["required"]) == set(STYLE)
