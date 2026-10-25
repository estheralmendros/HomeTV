"""Settings (pydantic-settings) + YAML loader + startup preparation (PRD §1).

Precedence (highest first): ``HOMETV_*`` environment variables -> YAML file
(``HOMETV_CONFIG_FILE``, else ``{data_dir}/config.yaml`` if present) -> defaults.

``Settings`` itself only validates what it is given (init kwargs), so tests can build it
directly; ``load_settings()`` does the env/YAML merge; ``prepare_runtime()`` performs the
startup side effects (guards, directories, HLS wipe, secret key).
"""

import logging
import os
import posixpath
import re
import secrets
import shutil
from collections.abc import Mapping
from pathlib import Path
from typing import Annotated, Any, Literal

import yaml
from pydantic import (
    BaseModel,
    BeforeValidator,
    ConfigDict,
    Field,
    ValidationError,
    field_validator,
    model_validator,
)
from pydantic_settings import BaseSettings, PydanticBaseSettingsSource, SettingsConfigDict

from hometv.text import empty_to_none

logger = logging.getLogger("hometv.config")

SESSION_ID_RE = re.compile(r"^[0-9a-f]{32}$")
DB_FILENAME = "hometv.db"
SECRET_FILENAME = "secret.key"

LogLevel = Literal["debug", "info", "warning", "error", "critical"]
# ``""`` (e.g. an empty env var) means unset for optional values.
EmptyIsNone = BeforeValidator(empty_to_none)


class ConfigError(Exception):
    """Invalid configuration: startup must abort. The message names the offending key."""


def _split_csv(value: Any) -> Any:
    if isinstance(value, str):
        return [item.strip() for item in value.split(",") if item.strip()]
    return value


def _normalize_root(path: str) -> str:
    expanded = os.path.expanduser(path)
    return posixpath.normpath(os.path.abspath(expanded))


class _Section(BaseModel):
    model_config = ConfigDict(extra="ignore")


class LibrariesSettings(BaseModel):
    model_config = ConfigDict(extra="forbid")  # unknown library type aborts startup

    films: list[str] = Field(default_factory=list)
    shows: list[str] = Field(default_factory=list)

    @field_validator("films", "shows", mode="before")
    @classmethod
    def _csv(cls, value: Any) -> Any:
        return [] if value is None else _split_csv(value)

    @field_validator("films", "shows")
    @classmethod
    def _normalize(cls, roots: list[str]) -> list[str]:
        return [_normalize_root(r) for r in roots]


class TmdbSettings(_Section):
    api_key: Annotated[str | None, EmptyIsNone] = None
    language: str = "es-ES"
    fallback_language: str = "en-US"
    max_rps: int = Field(20, gt=0)
    cast_limit: int = Field(15, ge=0)


class ScanSettings(_Section):
    interval_hours: int = Field(6, ge=0)
    on_startup: bool = True
    probe_concurrency: int = Field(2, ge=1)
    missing_grace_days: int = Field(7, ge=0)


class PlaybackSettings(_Section):
    max_sessions: int = Field(4, ge=0)
    hls_dir: Annotated[Path | None, EmptyIsNone] = None  # filled with {data_dir}/hls by Settings
    hls_segment_seconds: int = Field(6, ge=1)
    hls_ahead_seconds: int = Field(120, ge=0)
    hls_behind_seconds: int = Field(60, ge=0)
    hls_max_disk_mb: int = Field(4096, ge=0)
    session_idle_timeout_seconds: int = Field(120, ge=1)


class AuthSettings(_Section):
    secret_key: Annotated[str | None, EmptyIsNone] = None
    token_ttl_days: int = Field(30, ge=1)
    admin_token_ttl_minutes: int = Field(15, ge=1)


class ServerSettings(_Section):
    host: str = "0.0.0.0"
    port: int = Field(8000, ge=1, le=65535)
    web_dist: Path = Path("/app/web/dist")
    cors_origins: list[str] = Field(default_factory=list)
    log_level: LogLevel = "info"

    @field_validator("cors_origins", mode="before")
    @classmethod
    def _csv(cls, value: Any) -> Any:
        return [] if value is None else _split_csv(value)

    @field_validator("log_level", mode="before")
    @classmethod
    def _lower(cls, value: Any) -> Any:
        return value.lower() if isinstance(value, str) else value


class FfmpegSettings(_Section):
    ffmpeg_path: str = "ffmpeg"
    ffprobe_path: str = "ffprobe"


class Settings(BaseSettings):
    """Validated settings. Only init kwargs are read here; see ``load_settings``."""

    model_config = SettingsConfigDict(extra="ignore")

    data_dir: Path = Path("/data")
    libraries: LibrariesSettings = Field(default_factory=LibrariesSettings)
    tmdb: TmdbSettings = Field(default_factory=TmdbSettings)
    scan: ScanSettings = Field(default_factory=ScanSettings)
    playback: PlaybackSettings = Field(default_factory=PlaybackSettings)
    auth: AuthSettings = Field(default_factory=AuthSettings)
    server: ServerSettings = Field(default_factory=ServerSettings)
    ffmpeg: FfmpegSettings = Field(default_factory=FfmpegSettings)

    @classmethod
    def settings_customise_sources(
        cls,
        settings_cls: type[BaseSettings],
        init_settings: PydanticBaseSettingsSource,
        env_settings: PydanticBaseSettingsSource,
        dotenv_settings: PydanticBaseSettingsSource,
        file_secret_settings: PydanticBaseSettingsSource,
    ) -> tuple[PydanticBaseSettingsSource, ...]:
        # Env + YAML merging (with flat HOMETV_* names) is done by load_settings().
        return (init_settings,)

    @model_validator(mode="after")
    def _default_hls_dir(self) -> "Settings":
        if self.playback.hls_dir is None:
            self.playback.hls_dir = self.data_dir / "hls"
        return self

    @property
    def hls_dir(self) -> Path:
        assert self.playback.hls_dir is not None
        return self.playback.hls_dir

    @property
    def db_path(self) -> Path:
        return self.data_dir / DB_FILENAME

    @property
    def artwork_dir(self) -> Path:
        return self.data_dir / "artwork"

    @property
    def subtitles_dir(self) -> Path:
        return self.data_dir / "subtitles"

    @property
    def tmdb_configured(self) -> bool:
        return bool(self.tmdb.api_key)

    @property
    def media_roots(self) -> list[str]:
        return [*self.libraries.films, *self.libraries.shows]


# Flat env var -> nested settings path (Configuration Reference).
ENV_MAP: dict[str, tuple[str, ...]] = {
    "HOMETV_DATA_DIR": ("data_dir",),
    "HOMETV_FILMS_PATHS": ("libraries", "films"),
    "HOMETV_SHOWS_PATHS": ("libraries", "shows"),
    "HOMETV_TMDB_API_KEY": ("tmdb", "api_key"),
    "HOMETV_TMDB_LANGUAGE": ("tmdb", "language"),
    "HOMETV_TMDB_FALLBACK_LANGUAGE": ("tmdb", "fallback_language"),
    "HOMETV_TMDB_MAX_RPS": ("tmdb", "max_rps"),
    "HOMETV_CAST_LIMIT": ("tmdb", "cast_limit"),
    "HOMETV_SCAN_INTERVAL_HOURS": ("scan", "interval_hours"),
    "HOMETV_SCAN_ON_STARTUP": ("scan", "on_startup"),
    "HOMETV_SCAN_PROBE_CONCURRENCY": ("scan", "probe_concurrency"),
    "HOMETV_SCAN_MISSING_GRACE_DAYS": ("scan", "missing_grace_days"),
    "HOMETV_MAX_SESSIONS": ("playback", "max_sessions"),
    "HOMETV_HLS_DIR": ("playback", "hls_dir"),
    "HOMETV_HLS_SEGMENT_SECONDS": ("playback", "hls_segment_seconds"),
    "HOMETV_HLS_AHEAD_SECONDS": ("playback", "hls_ahead_seconds"),
    "HOMETV_HLS_BEHIND_SECONDS": ("playback", "hls_behind_seconds"),
    "HOMETV_HLS_MAX_DISK_MB": ("playback", "hls_max_disk_mb"),
    "HOMETV_SESSION_IDLE_TIMEOUT_SECONDS": ("playback", "session_idle_timeout_seconds"),
    "HOMETV_SECRET_KEY": ("auth", "secret_key"),
    "HOMETV_TOKEN_TTL_DAYS": ("auth", "token_ttl_days"),
    "HOMETV_ADMIN_TOKEN_TTL_MINUTES": ("auth", "admin_token_ttl_minutes"),
    "HOMETV_HOST": ("server", "host"),
    "HOMETV_PORT": ("server", "port"),
    "HOMETV_WEB_DIST": ("server", "web_dist"),
    "HOMETV_CORS_ORIGINS": ("server", "cors_origins"),
    "HOMETV_LOG_LEVEL": ("server", "log_level"),
    "HOMETV_FFMPEG_PATH": ("ffmpeg", "ffmpeg_path"),
    "HOMETV_FFPROBE_PATH": ("ffmpeg", "ffprobe_path"),
}
_PATH_TO_ENV = {path: env for env, path in ENV_MAP.items()}


def load_settings(environ: Mapping[str, str] | None = None) -> Settings:
    """Build Settings from env + YAML + defaults. Raises ConfigError naming the bad key."""
    env = dict(os.environ if environ is None else environ)
    data: dict[str, Any] = _read_yaml(env)
    sources: dict[tuple[str, ...], str] = {}

    for name, path in ENV_MAP.items():
        if name in env:
            _set_nested(data, path, env[name])
            sources[path] = name

    try:
        return Settings(**data)
    except ValidationError as exc:
        err = exc.errors()[0]
        loc = tuple(str(p) for p in err["loc"])
        key = _key_for(loc, sources)
        raise ConfigError(f"Invalid configuration value for {key}: {err['msg']}") from exc


def _read_yaml(env: Mapping[str, str]) -> dict[str, Any]:
    explicit = env.get("HOMETV_CONFIG_FILE")
    if explicit:
        path = Path(explicit)
        if not path.is_file():
            raise ConfigError(f"HOMETV_CONFIG_FILE: config file not found: {path}")
    else:
        path = Path(env.get("HOMETV_DATA_DIR") or "/data") / "config.yaml"
        if not path.is_file():
            return {}
    try:
        loaded = yaml.safe_load(path.read_text(encoding="utf-8"))
    except (yaml.YAMLError, OSError) as exc:
        raise ConfigError(f"HOMETV_CONFIG_FILE: cannot read YAML config {path}: {exc}") from exc
    if loaded is None:
        return {}
    if not isinstance(loaded, dict):
        raise ConfigError(f"HOMETV_CONFIG_FILE: YAML config {path} must be a mapping")
    return loaded


def _set_nested(data: dict[str, Any], path: tuple[str, ...], value: Any) -> None:
    node = data
    for part in path[:-1]:
        child = node.get(part)
        if not isinstance(child, dict):
            child = {}
            node[part] = child
        node = child
    node[path[-1]] = value


def _key_for(loc: tuple[str, ...], sources: dict[tuple[str, ...], str]) -> str:
    for length in range(len(loc), 0, -1):
        prefix = loc[:length]
        if prefix in sources:
            return sources[prefix]
    return ".".join(loc)


# --- Startup preparation -------------------------------------------------------------------


def _resolve(path: Path) -> Path:
    return Path(os.path.realpath(path))


def _is_within(child: Path, parent: Path) -> bool:
    return child == parent or parent in child.parents


def check_paths(settings: Settings) -> None:
    """PRD §1 rule 6: never write under a media root; hls_dir must not hold the data dir."""
    data_dir = _resolve(settings.data_dir)
    hls_dir = _resolve(settings.hls_dir)
    for root in settings.media_roots:
        resolved_root = _resolve(Path(root))
        if _is_within(data_dir, resolved_root):
            raise ConfigError(f"HOMETV_DATA_DIR ({data_dir}) is inside media root {root}")
        if _is_within(hls_dir, resolved_root):
            raise ConfigError(f"HOMETV_HLS_DIR ({hls_dir}) is inside media root {root}")
    if _is_within(data_dir, hls_dir):
        raise ConfigError(
            f"HOMETV_HLS_DIR ({hls_dir}) must not equal or contain data_dir ({data_dir})"
        )
    if (hls_dir / DB_FILENAME).exists():
        raise ConfigError(f"HOMETV_HLS_DIR ({hls_dir}) contains a {DB_FILENAME}")


def wipe_hls_sessions(hls_dir: Path) -> None:
    """Delete stale session dirs (names ``^[0-9a-f]{32}$``); leave every other entry alone."""
    try:
        entries = list(os.scandir(hls_dir))
    except FileNotFoundError:
        return
    for entry in entries:
        if not SESSION_ID_RE.match(entry.name):
            logger.info("hls_dir_foreign_entry: %s", entry.name)
            continue
        if entry.is_dir(follow_symlinks=False):
            shutil.rmtree(entry.path, ignore_errors=True)
        else:
            Path(entry.path).unlink(missing_ok=True)


def _resolve_secret_key(settings: Settings) -> str:
    if settings.auth.secret_key:
        return settings.auth.secret_key
    path = settings.data_dir / SECRET_FILENAME
    if path.is_file():
        existing = path.read_text(encoding="utf-8").strip()
        if existing:
            return existing
    key = secrets.token_bytes(32).hex()
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as fh:
        fh.write(key)
    os.chmod(path, 0o600)
    return key


def prepare_runtime(settings: Settings) -> Settings:
    """Startup side effects (PRD §1 rules 2-4, 6). Returns settings with the secret key set."""
    for root in settings.media_roots:
        if not Path(root).is_dir():
            logger.warning("media_root_missing: %s", root)
    check_paths(settings)  # before any directory creation or wipe
    settings.data_dir.mkdir(parents=True, exist_ok=True)
    settings.artwork_dir.mkdir(exist_ok=True)
    settings.subtitles_dir.mkdir(exist_ok=True)
    settings.hls_dir.mkdir(parents=True, exist_ok=True)
    wipe_hls_sessions(settings.hls_dir)
    secret = _resolve_secret_key(settings)
    auth = settings.auth.model_copy(update={"secret_key": secret})
    return settings.model_copy(update={"auth": auth})
