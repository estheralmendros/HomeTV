"""Pydantic request/response models (the REST contract; reflected in /api/openapi.json)."""

from datetime import UTC, date, datetime
from typing import Annotated, Any, Literal

from pydantic import (
    AfterValidator,
    BaseModel,
    ConfigDict,
    Field,
    PlainSerializer,
    StringConstraints,
    model_validator,
)

from hometv.db.models import ScanTrigger
from hometv.playback.decide import ClientCaps
from hometv.profiles import AVATAR_IDS, NAME_MAX_LENGTH, PIN_PATTERN


def _iso_utc(value: datetime) -> str:
    if value.tzinfo is None:
        value = value.replace(tzinfo=UTC)
    return value.astimezone(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


UtcDateTime = Annotated[datetime, PlainSerializer(_iso_utc, return_type=str, when_used="always")]
"""Timestamp serialized as ISO 8601 UTC with ``Z`` (``"2026-10-21T18:04:05Z"``)."""


# --- Errors --------------------------------------------------------------------------------


class ErrorBody(BaseModel):
    code: str = Field(examples=["not_found"])
    message: str = Field(examples=["Film 42 not found"])
    details: dict[str, Any] = Field(default_factory=dict)


# OpenAPI ``responses=`` documentation of a route (status -> {"model", "description"}).
Responses = dict[int | str, dict[str, Any]]


class ErrorResponse(BaseModel):
    """Error envelope used by every 4xx/5xx JSON error (including validation, 422)."""

    error: ErrorBody


# --- Shared shapes -------------------------------------------------------------------------


class Page[T](BaseModel):
    items: list[T]
    page: int
    page_size: int
    total: int


# --- System --------------------------------------------------------------------------------


class HealthResponse(BaseModel):
    status: Literal["ok", "error"]


class Avatar(BaseModel):
    id: str
    color: str


class InfoResponse(BaseModel):
    name: str
    version: str
    needs_bootstrap: bool
    tmdb_configured: bool
    tmdb_attribution: str
    avatars: list[Avatar]


# --- Profiles & auth -----------------------------------------------------------------------


def _check_avatar(value: str) -> str:
    if value not in AVATAR_IDS:
        raise ValueError("unknown avatar_id (expected avatar-01 ... avatar-12)")
    return value


ProfileName = Annotated[
    str, StringConstraints(strip_whitespace=True, min_length=1, max_length=NAME_MAX_LENGTH)
]
AvatarId = Annotated[str, AfterValidator(_check_avatar)]
Pin = Annotated[str, StringConstraints(pattern=PIN_PATTERN)]


class Profile(BaseModel):
    """No PIN field: every admin has a PIN, no non-admin has one."""

    id: int
    name: str
    avatar_id: str
    is_admin: bool
    created_at: UtcDateTime


class ProfilesResponse(BaseModel):
    items: list[Profile]


class BootstrapRequest(BaseModel):
    name: ProfileName
    avatar_id: AvatarId
    pin: Pin


class SessionResponse(BaseModel):
    profile: Profile
    token: str
    expires_at: UtcDateTime


class ProfileCreateRequest(BaseModel):
    name: ProfileName
    avatar_id: AvatarId
    is_admin: bool = False
    pin: Pin | None = None


class ProfileUpdateRequest(BaseModel):
    """All optional; only sent fields change (``"pin": null`` differs from an absent pin)."""

    name: ProfileName | None = None
    avatar_id: AvatarId | None = None
    is_admin: bool | None = None
    pin: Pin | None = None

    @model_validator(mode="after")
    def _no_null_except_pin(self) -> "ProfileUpdateRequest":
        for field in ("name", "avatar_id", "is_admin"):
            if field in self.model_fields_set and getattr(self, field) is None:
                raise ValueError(f"{field} must not be null")
        return self

    def changes(self) -> dict[str, Any]:
        return {field: getattr(self, field) for field in self.model_fields_set}


class UnlockRequest(BaseModel):
    pin: Pin


class UnlockResponse(BaseModel):
    admin_token: str
    expires_at: UtcDateTime


class Image(BaseModel):
    """Image object: ``/api/artwork/...`` paths (clients append ``?token=``)."""

    small: str
    large: str


# --- Admin: scan ---------------------------------------------------------------------------


class ScanProgress(BaseModel):
    processed: int | None
    total: int | None


def _utf8_safe(value: str) -> str:
    """Lone surrogates (``os.fsdecode`` of non-UTF-8 bytes) as ``\\udcff`` text, so the
    response can always be encoded."""
    return value.encode("utf-8", "backslashreplace").decode("utf-8")


def _utf8_safe_optional(value: str | None) -> str | None:
    return None if value is None else _utf8_safe(value)


class ScanScopeOut(BaseModel):
    """One folder of a change-triggered (``watch``) run."""

    root: str
    path: str  # == root for a root shallow or whole-root scope
    recursive: bool  # false: only the files directly in ``root``


class WatcherStatus(BaseModel):
    """Change detection (HOMETV-1): ``starting`` while the first arming walk runs."""

    model_config = ConfigDict(frozen=True)

    state: Literal["active", "starting", "disabled", "unavailable", "error"]
    unavailable_roots: tuple[Annotated[str, AfterValidator(_utf8_safe)], ...]  # sorted
    detail: Annotated[str | None, AfterValidator(_utf8_safe_optional)]  # English diagnostic


DISABLED_WATCHER = WatcherStatus(state="disabled", unavailable_roots=(), detail=None)


class ScanStatus(BaseModel):
    id: int | None
    state: Literal["idle", "running", "completed", "failed"]
    trigger: ScanTrigger | None
    phase: Literal["walking", "probing", "enriching", "done"] | None
    started_at: UtcDateTime | None
    finished_at: UtcDateTime | None
    progress: ScanProgress
    counts: dict[str, int] | None
    errors: list[str]
    error: str | None
    unmatched_count: int
    scope: list[ScanScopeOut] | None  # null for full runs and for idle; <= 50 entries
    watcher: WatcherStatus


# --- Admin: library folders ----------------------------------------------------------------

LibraryType = Literal["films", "shows"]


def _no_nul(value: str) -> str:
    if "\x00" in value:
        raise ValueError("must not contain a NUL character")
    return value


FolderPath = Annotated[
    str, StringConstraints(min_length=1, max_length=4096), AfterValidator(_no_nul)
]


class LibraryFolderOut(BaseModel):
    path: str
    status: Literal["available", "not_found", "not_readable"]
    title_count: int


class BrowseRoot(BaseModel):
    path: str
    available: bool


class LibrariesResponse(BaseModel):
    films: list[LibraryFolderOut]
    shows: list[LibraryFolderOut]
    browse_root: BrowseRoot
    ignored_env: list[str]
    missing_grace_days: int


class LibrariesPlanRequest(BaseModel):
    films: Annotated[list[FolderPath], Field(max_length=50)]
    shows: Annotated[list[FolderPath], Field(max_length=50)]


class LibrariesSaveRequest(LibrariesPlanRequest):
    confirm_relinks: bool = False


class PlanRemovedOut(BaseModel):
    library: LibraryType
    path: str
    title_count: int


class PlanCoveredOut(BaseModel):
    library: LibraryType
    path: str
    by: str
    title_count: int


class PlanMovedOut(BaseModel):
    from_library: LibraryType
    from_path: str
    to_library: LibraryType
    to_path: str
    title_count: int


class PlanRelayoutOut(BaseModel):
    from_path: str
    to_path: str
    title_count: int


class LibrariesPlan(BaseModel):
    removed: list[PlanRemovedOut]
    covered: list[PlanCoveredOut]
    moved: list[PlanMovedOut]
    relayout: list[PlanRelayoutOut]


class LibrariesSaveResponse(LibrariesResponse):
    scan: ScanStatus | None


class BrowseEntry(BaseModel):
    name: str
    path: str


class BrowseResponse(BaseModel):
    path: str
    parent: str | None
    entries: list[BrowseEntry]


# --- Admin: unmatched ----------------------------------------------------------------------


class UnmatchedShowTarget(BaseModel):
    type: Literal["show"] = "show"
    id: int
    title: str
    year: int | None


class UnmatchedFilmTarget(BaseModel):
    type: Literal["film"] = "film"
    id: int
    title: str
    year: int | None


class UnmatchedFileTarget(BaseModel):
    type: Literal["media_file"] = "media_file"
    id: int
    relative_path: str


class ParsedEpisodeShape(BaseModel):
    kind: Literal["episode"] = "episode"
    show_title: str
    show_year: int | None
    season: int
    episodes: list[int]


class ParsedFilmShape(BaseModel):
    kind: Literal["film"] = "film"
    title: str
    year: int | None
    part: int | None


class UnmatchedEntry(BaseModel):
    id: int
    library: Literal["films", "shows"]
    reason: str
    detail: str | None
    target: Annotated[
        UnmatchedShowTarget | UnmatchedFilmTarget | UnmatchedFileTarget,
        Field(discriminator="type"),
    ]
    parsed: Annotated[ParsedEpisodeShape | ParsedFilmShape, Field(discriminator="kind")] | None = (
        None
    )
    created_at: UtcDateTime


# --- Admin: TMDB proxies / match / resolve -------------------------------------------------


class TmdbSearchResult(BaseModel):
    tmdb_id: int
    title: str
    original_title: str | None
    year: int | None
    overview: str | None
    poster: Image | None


class TmdbSearchResponse(BaseModel):
    results: list[TmdbSearchResult]


class TmdbSeasonSummary(BaseModel):
    season_number: int
    name: str | None
    episode_count: int
    air_date: str | None


class TmdbSeasonsResponse(BaseModel):
    tmdb_id: int
    name: str
    year: int | None
    seasons: list[TmdbSeasonSummary]


class TmdbEpisodeSummary(BaseModel):
    episode_number: int
    title: str | None
    air_date: str | None


class TmdbSeasonResponse(BaseModel):
    tmdb_id: int
    season_number: int
    name: str | None
    episodes: list[TmdbEpisodeSummary]


class MatchRequest(BaseModel):
    tmdb_id: int = Field(gt=0)


class ResolveRequest(BaseModel):
    tmdb_id: int = Field(gt=0)
    season_number: int | None = Field(default=None, ge=0)
    episode_numbers: list[int] | None = Field(default=None, min_length=1)


class ResolveTarget(BaseModel):
    type: Literal["film", "show"]
    id: int


class ResolveResponse(BaseModel):
    target: ResolveTarget


# --- Watch state ---------------------------------------------------------------------------


class Progress(BaseModel):
    position_seconds: float
    duration_seconds: float
    watched: bool
    watched_at: UtcDateTime | None
    media_file_id: int | None
    updated_at: UtcDateTime


class ProgressRequest(BaseModel):
    item_type: Literal["film", "episode"]
    item_id: int
    media_file_id: int
    position_seconds: float = Field(ge=0)
    duration_seconds: float | None = Field(default=None, gt=0)


class WatchedRequest(BaseModel):
    target: Literal["film", "episode", "season", "show"]
    id: int
    watched: bool


class WatchedResponse(BaseModel):
    updated: int


# --- Catalog -------------------------------------------------------------------------------


class VideoInfo(BaseModel):
    codec: str
    width: int | None
    height: int | None
    hdr: Literal["hdr10", "hlg", "dolby_vision"] | None
    frame_rate: float | None


class AudioTrack(BaseModel):
    index: int
    codec: str
    channels: int | None
    channel_layout: str | None
    language: str
    title: str | None
    default: bool


class SubtitleTrack(BaseModel):
    id: str
    source: Literal["embedded", "external"]
    kind: Literal["text", "image"]
    codec: str
    language: str
    title: str | None
    forced: bool
    hearing_impaired: bool
    default: bool
    url: str | None


class MediaFile(BaseModel):
    """A "version" of a film or episode."""

    id: int
    file_name: str
    size_bytes: int
    container: str | None
    duration_seconds: float | None
    label: str
    video: VideoInfo | None
    audio_tracks: list[AudioTrack]
    subtitle_tracks: list[SubtitleTrack]
    part_number: int | None
    probe_error: str | None
    added_at: UtcDateTime


class FilmSummary(BaseModel):
    type: Literal["film"] = "film"
    id: int
    title: str
    year: int | None
    poster: Image | None
    backdrop: Image | None
    overview: str | None
    match_status: str
    added_at: UtcDateTime
    progress: Progress | None


class ShowSummary(BaseModel):
    type: Literal["show"] = "show"
    id: int
    title: str
    year: int | None
    poster: Image | None
    backdrop: Image | None
    overview: str | None
    match_status: str
    added_at: UtcDateTime
    season_count: int
    episode_count: int
    unwatched_episode_count: int


class WatchlistEntry(BaseModel):
    """One title of "Mi lista"; ``kind`` always equals ``item.type``. ``added_to_list_at`` is
    when the profile added it (``item.added_at`` is when the title entered the catalog)."""

    kind: Literal["film", "show"]
    item: Annotated[FilmSummary | ShowSummary, Field(discriminator="type")]
    added_to_list_at: UtcDateTime


class EpisodeSummary(BaseModel):
    type: Literal["episode"] = "episode"
    id: int
    show_id: int
    show_title: str
    season_id: int
    season_number: int
    episode_number: int
    title: str | None
    overview: str | None
    still: Image | None
    air_date: date | None
    runtime_minutes: int | None
    duration_seconds: float | None
    show_poster: Image | None
    show_backdrop: Image | None
    progress: Progress | None


class HomeEpisode(EpisodeSummary):
    media_files: list[MediaFile]


class EpisodeDetail(EpisodeSummary):
    media_files: list[MediaFile]
    previous_episode_id: int | None
    next_episode_id: int | None


class GenreRef(BaseModel):
    id: int
    name: str


class CastMember(BaseModel):
    person_id: int
    name: str
    character: str | None
    profile_image: Image | None


class PersonAppearance(BaseModel):
    """A catalog item of the actor screen; ``kind`` always equals ``item.type``."""

    kind: Literal["film", "show"]
    item: Annotated[FilmSummary | ShowSummary, Field(discriminator="type")]
    character: str | None


class PersonDetail(BaseModel):
    """``GET /api/people/{person_id}``: the actor and their visible, matched catalog titles."""

    person_id: int
    name: str
    profile_image: Image | None
    biography: str | None
    biography_language: str | None
    birthday: date | None
    deathday: date | None
    place_of_birth: str | None
    credits_complete: bool
    appearances: list[PersonAppearance]


class FilmDetail(BaseModel):
    type: Literal["film"] = "film"
    id: int
    title: str
    original_title: str | None
    year: int | None
    release_date: date | None
    overview: str | None
    tagline: str | None
    runtime_minutes: int | None
    vote_average: float | None
    genres: list[GenreRef]
    poster: Image | None
    backdrop: Image | None
    match_status: str
    tmdb_id: int | None
    cast: list[CastMember]
    media_files: list[MediaFile]
    progress: Progress | None
    in_watchlist: bool
    added_at: UtcDateTime


class ShowSeason(BaseModel):
    id: int
    season_number: int
    name: str | None
    poster: Image | None
    air_date: date | None
    episode_count: int
    unwatched_episode_count: int


class ShowDetail(BaseModel):
    type: Literal["show"] = "show"
    id: int
    title: str
    original_title: str | None
    year: int | None
    first_air_date: date | None
    status: str | None
    overview: str | None
    vote_average: float | None
    genres: list[GenreRef]
    poster: Image | None
    backdrop: Image | None
    match_status: str
    tmdb_id: int | None
    cast: list[CastMember]
    seasons: list[ShowSeason]
    next_episode: EpisodeSummary | None
    unwatched_episode_count: int
    in_watchlist: bool
    added_at: UtcDateTime


class SeasonDetail(BaseModel):
    id: int
    show_id: int
    show_title: str
    season_number: int
    name: str | None
    overview: str | None
    poster: Image | None
    air_date: date | None
    episodes: list[EpisodeSummary]


class LibraryCounts(BaseModel):
    films: int
    shows: int
    empty: bool


class HomeResponse(BaseModel):
    # In-progress films + exactly one card per show (its latest in-progress episode or its Next Up
    # episode, whichever has the latest activity), newest first (prd.md §9, Rev 13/14).
    continue_watching: list[Annotated[FilmSummary | HomeEpisode, Field(discriminator="type")]]
    recently_added: list[Annotated[FilmSummary | ShowSummary, Field(discriminator="type")]]
    library: LibraryCounts


class GenreCount(BaseModel):
    id: int
    name: str
    count: int


class GenresResponse(BaseModel):
    items: list[GenreCount]


class SearchResponse(BaseModel):
    films: list[FilmSummary]
    shows: list[ShowSummary]


# --- Media & playback ----------------------------------------------------------------------


class PlaybackRequest(BaseModel):
    client_caps: ClientCaps
    audio_stream_index: int | None = None
    start_position_seconds: float = Field(0.0, ge=0)
    previous_session_id: str | None = None


class PlaybackDirect(BaseModel):
    mode: Literal["direct"] = "direct"
    url: str
    audio_stream_index: int | None
    duration_seconds: float | None
    subtitle_tracks: list[SubtitleTrack]


class PlaybackHls(BaseModel):
    mode: Literal["hls"] = "hls"
    url: str
    session_id: str
    audio_stream_index: int | None
    audio_action: Literal["copy", "aac_stereo", "aac_5_1", "none"]
    duration_seconds: float | None
    subtitle_tracks: list[SubtitleTrack]


class PlaybackUnsupported(BaseModel):
    mode: Literal["unsupported"] = "unsupported"
    reason: Literal[
        "probe_failed",
        "no_video_stream",
        "video_codec_unsupported",
        "hls_not_supported",
        "duration_unknown",
        "keyframes_too_sparse",
    ]
    detail: str


PlaybackResponse = Annotated[
    PlaybackDirect | PlaybackHls | PlaybackUnsupported, Field(discriminator="mode")
]
