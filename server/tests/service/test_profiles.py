"""Profiles & auth (PRD §8): PIN hashing, tokens, unlock limiter, PIN lifecycle rules."""

from datetime import UTC, datetime, timedelta

import pytest
from sqlalchemy import select

from hometv import profiles as p
from hometv.api.errors import ApiError
from hometv.db import models as m


class FakeClock:
    def __init__(self, start: float = 1_800_000_000.0) -> None:
        self.now = start

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds


# --- PIN hashing ---------------------------------------------------------------------------------


def test_pin_hash_format_and_verify():
    stored = p.hash_pin("1234")
    scheme, n, r, par, salt_hex, hash_hex = stored.split("$")
    assert (scheme, n, r, par) == ("scrypt", "16384", "8", "1")
    assert len(bytes.fromhex(salt_hex)) == 16
    assert len(hash_hex) > 0
    assert p.verify_pin("1234", stored)
    assert not p.verify_pin("4321", stored)
    assert not p.verify_pin("1234", None)
    assert not p.verify_pin("1234", "garbage")


def test_pin_hash_uses_a_random_salt():
    assert p.hash_pin("1234") != p.hash_pin("1234")


# --- Profile tokens ------------------------------------------------------------------------------


@pytest.fixture
def clock() -> FakeClock:
    return FakeClock()


@pytest.fixture
def tokens(clock: FakeClock) -> p.TokenService:
    return p.TokenService("secret", token_ttl_days=30, admin_token_ttl_minutes=15, clock=clock)


def test_profile_token_roundtrip_and_expiry(tokens: p.TokenService, clock: FakeClock):
    issued = tokens.issue_profile_token(7)
    assert issued.expires_at == datetime.fromtimestamp(clock.now, UTC) + timedelta(days=30)
    assert tokens.profile_id_from_token(issued.token) == 7
    clock.advance(30 * 86400)
    assert tokens.profile_id_from_token(issued.token) == 7  # age == TTL is still valid
    clock.advance(1)
    assert tokens.profile_id_from_token(issued.token) is None


def test_profile_token_rejects_tampering_other_secret_and_admin_tokens(
    tokens: p.TokenService, clock: FakeClock
):
    issued = tokens.issue_profile_token(7)
    assert tokens.profile_id_from_token(issued.token + "x") is None
    assert tokens.profile_id_from_token("") is None
    other = p.TokenService("other-secret", clock=clock)
    assert other.profile_id_from_token(issued.token) is None
    admin = tokens.issue_admin_token(7, 1)
    assert tokens.profile_id_from_token(admin.token) is None  # different salt


# --- Admin tokens --------------------------------------------------------------------------------


def test_admin_token_fixed_15_minutes(tokens: p.TokenService, clock: FakeClock):
    issued = tokens.issue_admin_token(3, 1)
    assert issued.expires_at == datetime.fromtimestamp(clock.now, UTC) + timedelta(minutes=15)
    assert tokens.verify_admin_token(issued.token, profile_id=3, admin_token_version=1)
    clock.advance(14 * 60)
    # Use does not extend validity.
    assert tokens.verify_admin_token(issued.token, profile_id=3, admin_token_version=1)
    clock.advance(60)
    assert tokens.verify_admin_token(issued.token, profile_id=3, admin_token_version=1)
    clock.advance(1)
    assert not tokens.verify_admin_token(issued.token, profile_id=3, admin_token_version=1)


def test_admin_token_pid_binding_and_version_revocation(tokens: p.TokenService):
    issued = tokens.issue_admin_token(3, 2)
    assert not tokens.verify_admin_token(issued.token, profile_id=4, admin_token_version=2)
    assert not tokens.verify_admin_token(issued.token, profile_id=3, admin_token_version=3)
    assert not tokens.verify_admin_token(None, profile_id=3, admin_token_version=2)
    profile_token = tokens.issue_profile_token(3)
    assert not tokens.verify_admin_token(profile_token.token, profile_id=3, admin_token_version=2)


# --- Unlock limiter ------------------------------------------------------------------------------


def test_limiter_blocks_after_five_failures_in_window(clock: FakeClock):
    limiter = p.UnlockLimiter(clock=clock)
    for i in range(5):
        assert limiter.retry_after(1) is None
        limiter.record_failure(1)
        clock.advance(5 if i < 4 else 0)
    # Failures at t=0,5,10,15,20 -> oldest leaves the window at t=60.
    assert limiter.retry_after(1) == 40
    assert limiter.retry_after(2) is None  # per profile
    clock.advance(39.5)
    assert limiter.retry_after(1) == 1  # rounded up
    clock.advance(0.5)
    assert limiter.retry_after(1) is None  # the oldest failure left the rolling window


def test_limiter_acquire_reserves_attempts_up_front(clock: FakeClock):
    limiter = p.UnlockLimiter(clock=clock)
    for _ in range(5):
        assert limiter.acquire(1) is None  # nothing recorded after it: the reservation counts
    assert limiter.acquire(1) == 60
    limiter.reset(1)  # a verified PIN releases every reservation
    assert limiter.acquire(1) is None


def test_limiter_success_resets(clock: FakeClock):
    limiter = p.UnlockLimiter(clock=clock)
    for _ in range(4):
        limiter.record_failure(1)
    limiter.reset(1)
    for _ in range(4):
        limiter.record_failure(1)
    assert limiter.retry_after(1) is None


# --- PIN lifecycle (pure) ------------------------------------------------------------------------


def _code(fn, **kw) -> str:
    with pytest.raises(ApiError) as info:
        fn(**kw)
    return info.value.code


def test_plan_create():
    plan = p.plan_pin_change(was_admin=None, is_admin=True, pin_sent=True, pin="1234")
    assert plan.set_pin == "1234" and plan.clear_pin is False
    assert _code(p.plan_pin_change, was_admin=None, is_admin=True, pin_sent=False, pin=None) == (
        "pin_required"
    )
    assert _code(p.plan_pin_change, was_admin=None, is_admin=False, pin_sent=True, pin="1") == (
        "pin_not_allowed"
    )
    plan = p.plan_pin_change(was_admin=None, is_admin=False, pin_sent=False, pin=None)
    assert plan.set_pin is None and not plan.bump_version


@pytest.mark.parametrize(
    ("was_admin", "is_admin", "pin_sent", "pin", "expected"),
    [
        # admin stays admin
        (True, True, False, None, ("keep", False)),
        (True, True, True, "4321", ("set", True)),
        (True, True, True, None, "pin_required"),
        # promote
        (False, True, True, "4321", ("set", True)),
        (False, True, False, None, "pin_required"),
        (False, True, True, None, "pin_required"),
        # demote
        (True, False, False, None, ("clear", True)),
        (True, False, True, None, ("clear", True)),
        (True, False, True, "1234", "pin_not_allowed"),
        # non-admin stays non-admin
        (False, False, False, None, ("keep", False)),
        (False, False, True, None, ("keep", False)),
        (False, False, True, "1234", "pin_not_allowed"),
    ],
)
def test_plan_update(was_admin, is_admin, pin_sent, pin, expected):
    kw = {"was_admin": was_admin, "is_admin": is_admin, "pin_sent": pin_sent, "pin": pin}
    if isinstance(expected, str):
        assert _code(p.plan_pin_change, **kw) == expected
        return
    plan = p.plan_pin_change(**kw)
    action = "set" if plan.set_pin else "clear" if plan.clear_pin else "keep"
    assert (action, plan.bump_version) == expected


# --- Profile CRUD (DB) ---------------------------------------------------------------------------


def _get(mdb, profile_id: int) -> m.Profile:
    with mdb.read_session() as s:
        profile = s.get(m.Profile, profile_id)
        assert profile is not None
        return profile


def test_bootstrap_once(mdb):
    admin = p.bootstrap(mdb, name="  Esther ", avatar_id="avatar-05", pin="1234")
    assert admin.name == "Esther" and admin.is_admin
    assert p.verify_pin("1234", _get(mdb, admin.id).pin_hash)
    assert _code(p.bootstrap, db=mdb, name="Other", avatar_id="avatar-01", pin="1111") == (
        "already_bootstrapped"
    )


def test_bootstrap_after_setup_skips_scrypt(mdb, monkeypatch):
    p.bootstrap(mdb, name="Esther", avatar_id="avatar-05", pin="1234")
    hashed: list[str] = []
    monkeypatch.setattr(p, "hash_pin", lambda pin: hashed.append(pin) or "x")
    assert _code(p.bootstrap, db=mdb, name="Other", avatar_id="avatar-01", pin="1111") == (
        "already_bootstrapped"
    )
    assert hashed == []  # the cheap read check refuses before hashing


def test_create_rules(mdb):
    p.bootstrap(mdb, name="Esther", avatar_id="avatar-05", pin="1234")
    kids = p.create_profile(mdb, name="Kids", avatar_id="avatar-04", is_admin=False, pin=None)
    assert _get(mdb, kids.id).pin_hash is None
    assert _code(
        p.create_profile, db=mdb, name="kids", avatar_id="avatar-01", is_admin=False, pin=None
    ) == ("name_taken")
    assert _code(
        p.create_profile, db=mdb, name=" ESTHER ", avatar_id="avatar-01", is_admin=False, pin=None
    ) == ("name_taken")
    # Unique case-insensitively, also beyond ASCII.
    p.create_profile(mdb, name="Álvaro", avatar_id="avatar-01", is_admin=False, pin=None)
    assert _code(
        p.create_profile, db=mdb, name="ÁLVARO", avatar_id="avatar-01", is_admin=False, pin=None
    ) == ("name_taken")
    assert _code(
        p.create_profile, db=mdb, name="Javi", avatar_id="avatar-02", is_admin=True, pin=None
    ) == ("pin_required")
    assert _code(
        p.create_profile, db=mdb, name="Javi", avatar_id="avatar-02", is_admin=False, pin="4321"
    ) == ("pin_not_allowed")


def test_profile_limit(mdb):
    p.bootstrap(mdb, name="P0", avatar_id="avatar-01", pin="1234")
    for i in range(1, 12):
        p.create_profile(mdb, name=f"P{i}", avatar_id="avatar-01", is_admin=False, pin=None)
    assert _code(
        p.create_profile, db=mdb, name="P12", avatar_id="avatar-01", is_admin=False, pin=None
    ) == ("profile_limit_reached")


def test_update_promote_demote_and_versions(mdb):
    admin = p.bootstrap(mdb, name="Esther", avatar_id="avatar-05", pin="1234")
    kids = p.create_profile(mdb, name="Kids", avatar_id="avatar-04", is_admin=False, pin=None)

    assert _code(p.update_profile, db=mdb, profile_id=kids.id, changes={"is_admin": True}) == (
        "pin_required"
    )
    promoted = p.update_profile(mdb, kids.id, {"is_admin": True, "pin": "4321"})
    assert promoted.is_admin and promoted.admin_token_version == 2
    assert p.verify_pin("4321", _get(mdb, kids.id).pin_hash)

    renamed = p.update_profile(mdb, kids.id, {"name": "Niños", "avatar_id": "avatar-03"})
    assert renamed.name == "Niños" and renamed.admin_token_version == 2  # no bump

    changed = p.update_profile(mdb, kids.id, {"pin": "9999"})
    assert changed.admin_token_version == 3

    assert _code(p.update_profile, db=mdb, profile_id=kids.id, changes={"pin": None}) == (
        "pin_required"
    )
    demoted = p.update_profile(mdb, kids.id, {"is_admin": False})
    assert not demoted.is_admin and demoted.admin_token_version == 4
    assert _get(mdb, kids.id).pin_hash is None

    assert _code(p.update_profile, db=mdb, profile_id=admin.id, changes={"is_admin": False}) == (
        "last_admin"
    )
    assert _code(p.update_profile, db=mdb, profile_id=999, changes={"name": "x"}) == "not_found"
    assert _code(p.update_profile, db=mdb, profile_id=kids.id, changes={"name": "esther"}) == (
        "name_taken"
    )
    # Renaming to the same name with a different case is not a clash with itself.
    assert p.update_profile(mdb, admin.id, {"name": "ESTHER"}).name == "ESTHER"


def test_update_conflicts_when_is_admin_changes_between_plan_and_write(mdb, monkeypatch):
    p.bootstrap(mdb, name="Esther", avatar_id="avatar-05", pin="1234")
    kids = p.create_profile(mdb, name="Kids", avatar_id="avatar-04", is_admin=False, pin=None)
    real_hash = p.hash_pin

    def hash_then_promote_concurrently(pin: str) -> str:
        # Runs after the plan (read on is_admin=False) and before the write transaction.
        with mdb.write_session() as s:
            other = s.get(m.Profile, kids.id)
            other.is_admin, other.pin_hash = True, real_hash("1111")
        return real_hash(pin)

    monkeypatch.setattr(p, "hash_pin", hash_then_promote_concurrently)
    assert _code(
        p.update_profile, db=mdb, profile_id=kids.id, changes={"is_admin": True, "pin": "4321"}
    ) == ("conflict")
    # Nothing from the refused plan was applied: the concurrent PIN stands.
    assert p.verify_pin("1111", _get(mdb, kids.id).pin_hash)


def test_delete_rules(mdb):
    admin = p.bootstrap(mdb, name="Esther", avatar_id="avatar-05", pin="1234")
    kids = p.create_profile(mdb, name="Kids", avatar_id="avatar-04", is_admin=False, pin=None)
    assert _code(p.delete_profile, db=mdb, profile_id=admin.id) == "last_admin"
    p.delete_profile(mdb, kids.id)
    assert _code(p.delete_profile, db=mdb, profile_id=kids.id) == "not_found"
    javi = p.create_profile(mdb, name="Javi", avatar_id="avatar-02", is_admin=True, pin="4321")
    p.delete_profile(mdb, admin.id)
    with mdb.read_session() as s:
        assert [x.id for x in s.scalars(select(m.Profile))] == [javi.id]
    # AUTOINCREMENT: ids are never reused.
    again = p.create_profile(mdb, name="Esther", avatar_id="avatar-05", is_admin=False, pin=None)
    assert again.id > javi.id
