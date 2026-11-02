"""Profiles & auth.

- Avatar set (``AVATARS``), PIN hashing (``hash_pin`` / ``verify_pin``, scrypt).
- ``TokenService``: profile tokens (identity, ``auth.token_ttl_days``) and admin tokens (fixed
  ``auth.admin_token_ttl_minutes`` from issue, bound to ``pid`` and ``admin_token_version``).
  The clock is injectable (``clock`` attribute, seconds since the epoch).
- ``UnlockLimiter``: in-memory per-profile limiter for ``/api/admin/unlock``
  (5 failures in a rolling 60 s window; success resets; injectable monotonic clock).
- ``plan_pin_change``: the pure PIN lifecycle rules; profile CRUD (``bootstrap``,
  ``create_profile``, ``update_profile``, ``delete_profile``) applies them in one write
  transaction each and raises ``ApiError`` with the documented codes.
"""

import hashlib
import hmac
import math
import secrets
import time
from collections import deque
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from typing import Any

from itsdangerous import BadSignature, URLSafeTimedSerializer
from itsdangerous.timed import TimestampSigner
from sqlalchemy import func, select
from sqlalchemy.orm import Session

from hometv.api.errors import ApiError
from hometv.db import models as m
from hometv.db.engine import Database

AVATAR_COLORS = (
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
)
AVATARS: tuple[tuple[str, str], ...] = tuple(
    (f"avatar-{i:02d}", color) for i, color in enumerate(AVATAR_COLORS, start=1)
)
AVATAR_IDS = frozenset(avatar_id for avatar_id, _ in AVATARS)

MAX_PROFILES = 12
NAME_MAX_LENGTH = 30
PIN_PATTERN = r"^[0-9]{4}$"  # ASCII digits only (\d is Unicode-aware)

Clock = Callable[[], float]

# --- PIN hashing ---------------------------------------------------------------------------------

_SCRYPT_N, _SCRYPT_R, _SCRYPT_P = 2**14, 8, 1


def _scrypt(pin: str, salt: bytes, n: int, r: int, p: int) -> bytes:
    return hashlib.scrypt(pin.encode(), salt=salt, n=n, r=r, p=p, maxmem=64 * 1024 * 1024)


def hash_pin(pin: str) -> str:
    """``scrypt$16384$8$1$<salt_hex>$<hash_hex>`` with a fresh 16-byte salt."""
    salt = secrets.token_bytes(16)
    digest = _scrypt(pin, salt, _SCRYPT_N, _SCRYPT_R, _SCRYPT_P)
    return f"scrypt${_SCRYPT_N}${_SCRYPT_R}${_SCRYPT_P}${salt.hex()}${digest.hex()}"


def verify_pin(pin: str, stored: str | None) -> bool:
    if not stored:
        return False
    try:
        scheme, n, r, p, salt_hex, hash_hex = stored.split("$")
        if scheme != "scrypt":
            return False
        expected = bytes.fromhex(hash_hex)
        actual = _scrypt(pin, bytes.fromhex(salt_hex), int(n), int(r), int(p))
    except ValueError:
        return False
    return hmac.compare_digest(actual, expected)


# --- Tokens --------------------------------------------------------------------------------------

PROFILE_SALT = "hometv-session"
ADMIN_SALT = "hometv-admin"


@dataclass(frozen=True)
class IssuedToken:
    token: str
    expires_at: datetime


class TokenService:
    def __init__(
        self,
        secret_key: str,
        *,
        token_ttl_days: int = 30,
        admin_token_ttl_minutes: int = 15,
        clock: Clock = time.time,
    ) -> None:
        self.clock = clock
        self.profile_ttl = int(timedelta(days=token_ttl_days).total_seconds())
        self.admin_ttl = int(timedelta(minutes=admin_token_ttl_minutes).total_seconds())
        service = self

        class _Signer(TimestampSigner):
            def get_timestamp(self) -> int:
                return int(service.clock())

        def serializer(salt: str) -> URLSafeTimedSerializer:
            return URLSafeTimedSerializer(secret_key, salt=salt, signer=_Signer)

        self._profile = serializer(PROFILE_SALT)
        self._admin = serializer(ADMIN_SALT)

    def _issue(self, serializer: URLSafeTimedSerializer, payload: dict, ttl: int) -> IssuedToken:
        issued_at = int(self.clock())
        token = serializer.dumps(payload)
        return IssuedToken(token, datetime.fromtimestamp(issued_at + ttl, UTC))

    @staticmethod
    def _load(serializer: URLSafeTimedSerializer, token: str | None, ttl: int) -> Any:
        if not token:
            return None
        try:
            return serializer.loads(token, max_age=ttl)
        except BadSignature:  # includes SignatureExpired / BadTimeSignature
            return None

    def issue_profile_token(self, profile_id: int) -> IssuedToken:
        return self._issue(self._profile, {"pid": profile_id}, self.profile_ttl)

    def profile_id_from_token(self, token: str | None) -> int | None:
        """The token's ``pid`` if the signature is OK and it is not expired (existence of the
        profile is checked by the caller)."""
        data = self._load(self._profile, token, self.profile_ttl)
        pid = data.get("pid") if isinstance(data, dict) else None
        return pid if isinstance(pid, int) else None

    def issue_admin_token(self, profile_id: int, admin_token_version: int) -> IssuedToken:
        payload = {"pid": profile_id, "av": admin_token_version}
        return self._issue(self._admin, payload, self.admin_ttl)

    def verify_admin_token(
        self, token: str | None, *, profile_id: int, admin_token_version: int
    ) -> bool:
        data = self._load(self._admin, token, self.admin_ttl)
        if not isinstance(data, dict):
            return False
        return data.get("pid") == profile_id and data.get("av") == admin_token_version

    @classmethod
    def from_settings(cls, settings: Any) -> "TokenService":
        auth = settings.auth
        if not auth.secret_key:
            raise ValueError("auth.secret_key must be resolved (prepare_runtime) before use")
        return cls(
            auth.secret_key,
            token_ttl_days=auth.token_ttl_days,
            admin_token_ttl_minutes=auth.admin_token_ttl_minutes,
        )


# --- Unlock limiter ------------------------------------------------------------------------------


class UnlockLimiter:
    def __init__(
        self,
        max_failures: int = 5,
        window_seconds: float = 60.0,
        clock: Clock = time.monotonic,
    ) -> None:
        self.max_failures = max_failures
        self.window = window_seconds
        self.clock = clock
        self._failures: dict[int, deque[float]] = {}

    def _prune(self, profile_id: int, now: float) -> deque[float]:
        failures = self._failures.setdefault(profile_id, deque())
        while failures and failures[0] + self.window <= now:
            failures.popleft()
        return failures

    def retry_after(self, profile_id: int) -> int | None:
        """Seconds until the oldest counted failure leaves the window, or ``None`` if allowed."""
        now = self.clock()
        failures = self._prune(profile_id, now)
        if len(failures) < self.max_failures:
            return None
        return max(1, math.ceil(failures[0] + self.window - now))

    def record_failure(self, profile_id: int) -> None:
        now = self.clock()
        self._prune(profile_id, now).append(now)

    def acquire(self, profile_id: int) -> int | None:
        """Atomically check and reserve one attempt: returns ``retry_after`` when refused,
        else counts the attempt as a failure up front (``reset`` clears it on success). Callers
        reserve before awaiting the PIN check, so concurrent attempts cannot all pass."""
        retry_after = self.retry_after(profile_id)
        if retry_after is None:
            self.record_failure(profile_id)
        return retry_after

    def reset(self, profile_id: int) -> None:
        self._failures.pop(profile_id, None)


# --- PIN lifecycle (pure) ------------------------------------------------------------------------


@dataclass(frozen=True)
class PinPlan:
    set_pin: str | None = None  # new PIN to hash and store
    clear_pin: bool = False
    bump_version: bool = False


def _pin_required() -> ApiError:
    return ApiError(422, "pin_required", "Admin profiles require a 4-digit PIN")


def _pin_not_allowed() -> ApiError:
    return ApiError(422, "pin_not_allowed", "Only admin profiles have a PIN")


def plan_pin_change(
    *, was_admin: bool | None, is_admin: bool, pin_sent: bool, pin: str | None
) -> PinPlan:
    """PIN lifecycle. ``was_admin=None`` means create. ``pin_sent`` distinguishes an
    absent ``pin`` from ``"pin": null``."""
    if is_admin:
        if was_admin is True and not pin_sent:
            return PinPlan()
        if pin is None:
            raise _pin_required()
        return PinPlan(set_pin=pin, bump_version=was_admin is not None)
    if pin is not None:
        raise _pin_not_allowed()
    if was_admin:
        return PinPlan(clear_pin=True, bump_version=True)
    return PinPlan()


# --- Profile CRUD --------------------------------------------------------------------------------


def _clean_name(name: str) -> str:
    return name.strip()


def _ensure_name_free(session: Session, name: str, exclude_id: int | None = None) -> None:
    folded = name.casefold()
    for profile_id, existing in session.execute(select(m.Profile.id, m.Profile.name)):
        if profile_id != exclude_id and existing.casefold() == folded:
            raise ApiError(409, "name_taken", f"A profile named {name!r} already exists")


def _admin_count(session: Session) -> int:
    return session.scalar(select(func.count()).where(m.Profile.is_admin.is_(True))) or 0


def _apply_plan(profile: m.Profile, plan: PinPlan, new_hash: str | None) -> None:
    """Apply a plan whose ``set_pin`` was already hashed (outside the write lock)."""
    if new_hash is not None:
        profile.pin_hash = new_hash
    if plan.clear_pin:
        profile.pin_hash = None
    if plan.bump_version:
        profile.admin_token_version = (profile.admin_token_version or 1) + 1


def list_profiles(session: Session) -> list[m.Profile]:
    return list(session.scalars(select(m.Profile).order_by(m.Profile.id)))


def bootstrap(db: Database, *, name: str, avatar_id: str, pin: str) -> m.Profile:
    """Create the first (admin) profile; 409 ``already_bootstrapped`` unless none exist.
    Checked and inserted in one ``BEGIN IMMEDIATE`` transaction; a cheap read check first
    refuses repeated calls after setup without paying for scrypt."""
    with db.read_session() as session:
        if session.scalar(select(func.count()).select_from(m.Profile)):
            raise ApiError(409, "already_bootstrapped", "Profiles already exist")
    pin_hash = hash_pin(pin)  # outside the write lock (scrypt is deliberately slow)
    with db.write_session() as session:
        if session.scalar(select(func.count()).select_from(m.Profile)):
            raise ApiError(409, "already_bootstrapped", "Profiles already exist")
        profile = m.Profile(
            name=_clean_name(name), avatar_id=avatar_id, is_admin=True, pin_hash=pin_hash
        )
        session.add(profile)
        session.flush()
        return profile


def create_profile(
    db: Database, *, name: str, avatar_id: str, is_admin: bool, pin: str | None
) -> m.Profile:
    plan = plan_pin_change(was_admin=None, is_admin=is_admin, pin_sent=pin is not None, pin=pin)
    pin_hash = hash_pin(plan.set_pin) if plan.set_pin is not None else None
    name = _clean_name(name)
    with db.write_session() as session:
        if (session.scalar(select(func.count()).select_from(m.Profile)) or 0) >= MAX_PROFILES:
            raise ApiError(409, "profile_limit_reached", f"At most {MAX_PROFILES} profiles")
        _ensure_name_free(session, name)
        profile = m.Profile(name=name, avatar_id=avatar_id, is_admin=is_admin, pin_hash=pin_hash)
        session.add(profile)
        session.flush()
        return profile


def update_profile(db: Database, profile_id: int, changes: Mapping[str, Any]) -> m.Profile:
    """PATCH semantics: only keys present in ``changes`` are applied (``"pin": None`` differs
    from an absent ``pin``)."""
    with db.read_session() as session:
        current = session.get(m.Profile, profile_id)
        if current is None:
            raise ApiError(404, "not_found", f"Profile {profile_id} not found")
        was_admin = current.is_admin
    is_admin = bool(changes.get("is_admin", was_admin))
    plan = plan_pin_change(
        was_admin=was_admin, is_admin=is_admin, pin_sent="pin" in changes, pin=changes.get("pin")
    )
    new_hash = hash_pin(plan.set_pin) if plan.set_pin is not None else None
    with db.write_session() as session:
        profile = session.get(m.Profile, profile_id)
        if profile is None:
            raise ApiError(404, "not_found", f"Profile {profile_id} not found")
        if profile.is_admin != was_admin:  # changed concurrently: 409, the client retries
            # (re-planning here would hash the PIN under the write lock)
            raise ApiError(409, "conflict", "Profile changed concurrently; retry")
        if "name" in changes:
            name = _clean_name(changes["name"])
            _ensure_name_free(session, name, exclude_id=profile_id)
            profile.name = name
        if "avatar_id" in changes:
            profile.avatar_id = changes["avatar_id"]
        if was_admin and not is_admin and _admin_count(session) <= 1:
            raise ApiError(409, "last_admin", "Cannot demote the only admin profile")
        profile.is_admin = is_admin
        _apply_plan(profile, plan, new_hash)
        session.flush()
        return profile


def delete_profile(db: Database, profile_id: int) -> None:
    with db.write_session() as session:
        profile = session.get(m.Profile, profile_id)
        if profile is None:
            raise ApiError(404, "not_found", f"Profile {profile_id} not found")
        if profile.is_admin and _admin_count(session) <= 1:
            raise ApiError(409, "last_admin", "Cannot delete the only admin profile")
        session.delete(profile)
