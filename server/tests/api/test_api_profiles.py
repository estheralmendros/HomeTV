"""System & profiles endpoints."""

import pytest

PROFILE_KEYS = {"id", "name", "avatar_id", "is_admin", "created_at"}


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
    assert me.status_code == 200 and me.json() == data["profile"]
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
