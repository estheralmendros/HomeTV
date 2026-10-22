"""API-test fixtures: profiles with tokens.

- ``profile_factory(name, is_admin=False, pin=None, avatar_id="avatar-01") -> Who``: creates a
  profile through ``hometv.profiles`` and issues a profile token (and, for admins, an admin
  token) with the app's ``TokenService``.
- ``user`` (non-admin "Kids") and ``admin`` ("Esther", PIN 1234) are ready-made ``Who``s.
- ``Who.headers`` = Bearer header; ``Who.admin_headers`` = Bearer + ``X-Admin-Token``.
- ``clock``: a ``FakeClock`` installed on ``app.state.tokens`` and ``app.state.unlock_limiter``
  (advance it to expire tokens). ``profile_factory`` depends on it, so every token issued
  through the fixtures uses the fake clock.
"""

from collections.abc import Callable
from dataclasses import dataclass

import pytest

from hometv import profiles as p


@dataclass
class Who:
    id: int
    name: str
    token: str
    admin_token: str | None = None

    @property
    def headers(self) -> dict[str, str]:
        return {"Authorization": f"Bearer {self.token}"}

    @property
    def admin_headers(self) -> dict[str, str]:
        assert self.admin_token is not None
        return {**self.headers, "X-Admin-Token": self.admin_token}


class FakeClock:
    def __init__(self, start: float = 1_800_000_000.0) -> None:
        self.now = start

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds


@pytest.fixture
def clock(app) -> FakeClock:
    fake = FakeClock()
    app.state.tokens.clock = fake
    app.state.unlock_limiter.clock = fake
    return fake


@pytest.fixture
def profile_factory(app, db, clock) -> Callable[..., Who]:
    def factory(
        name: str = "Kids",
        is_admin: bool = False,
        pin: str | None = None,
        avatar_id: str = "avatar-01",
    ) -> Who:
        if is_admin and pin is None:
            pin = "1234"
        profile = p.create_profile(db, name=name, avatar_id=avatar_id, is_admin=is_admin, pin=pin)
        tokens: p.TokenService = app.state.tokens
        who = Who(profile.id, profile.name, tokens.issue_profile_token(profile.id).token)
        if is_admin:
            who.admin_token = tokens.issue_admin_token(
                profile.id, profile.admin_token_version
            ).token
        return who

    return factory


@pytest.fixture
def admin(profile_factory) -> Who:
    return profile_factory("Esther", is_admin=True, pin="1234", avatar_id="avatar-05")


@pytest.fixture
def user(profile_factory) -> Who:
    return profile_factory("Kids", avatar_id="avatar-04")
