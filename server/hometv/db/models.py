"""SQLAlchemy ORM models: the full PRD Data Model.

Conventions:
- ``id INTEGER PRIMARY KEY`` unless noted; FKs ``ON DELETE CASCADE`` unless noted.
- Timestamps are UTC ``DATETIME`` (stored naive UTC, read back as aware UTC via ``UTCDateTime``).
- Any schema change requires a new Alembic revision in ``db/migrations/versions``; the test
  ``test_orm_metadata_matches_migration`` fails when models and migrations diverge.
"""

from datetime import UTC, date, datetime
from typing import Any, ClassVar, Literal, get_args

from sqlalchemy import (
    JSON,
    Boolean,
    CheckConstraint,
    Date,
    DateTime,
    Float,
    ForeignKey,
    Index,
    Integer,
    LargeBinary,
    MetaData,
    String,
    UniqueConstraint,
    func,
    text,
)
from sqlalchemy.engine import Dialect
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column
from sqlalchemy.types import TypeDecorator

NAMING_CONVENTION = {
    "ix": "ix_%(table_name)s_%(column_0_N_name)s",
    "uq": "uq_%(table_name)s_%(column_0_N_name)s",
    "ck": "ck_%(table_name)s_%(constraint_name)s",
    "fk": "fk_%(table_name)s_%(column_0_name)s_%(referred_table_name)s",
    "pk": "pk_%(table_name)s",
}


def utcnow() -> datetime:
    return datetime.now(UTC)


class UTCDateTime(TypeDecorator[datetime]):
    """Stores naive UTC; returns aware UTC datetimes."""

    impl = DateTime
    cache_ok = True

    def process_bind_param(self, value: datetime | None, dialect: Dialect) -> datetime | None:
        if value is None:
            return None
        if value.tzinfo is not None:
            value = value.astimezone(UTC)
        return value.replace(tzinfo=None)

    def process_result_value(self, value: datetime | None, dialect: Dialect) -> datetime | None:
        if value is None:
            return None
        return value.replace(tzinfo=UTC) if value.tzinfo is None else value.astimezone(UTC)


class Base(DeclarativeBase):
    metadata = MetaData(naming_convention=NAMING_CONVENTION)
    type_annotation_map: ClassVar[dict[Any, Any]] = {
        datetime: UTCDateTime(),
        date: Date(),
        str: String(),
        float: Float(),
        dict[str, Any]: JSON(),
        list[Any]: JSON(),
    }


def _now_col(**kw: Any) -> Any:
    return mapped_column(
        UTCDateTime(), nullable=False, default=utcnow, server_default=func.current_timestamp(), **kw
    )


def _fk(target: str, ondelete: str = "CASCADE", nullable: bool = False, **kw: Any) -> Any:
    return mapped_column(Integer, ForeignKey(target, ondelete=ondelete), nullable=nullable, **kw)


_EXACTLY_ONE_FILM_SHOW = "(film_id IS NULL) != (show_id IS NULL)"


ScanTrigger = Literal["startup", "scheduled", "manual", "libraries", "watch"]
SCAN_TRIGGERS: tuple[str, ...] = get_args(ScanTrigger)
"""``scan_run.trigger`` values (single source for the CHECK and the API ``Literal``)."""


class Library(Base):
    __tablename__ = "library"
    __table_args__ = (CheckConstraint("type IN ('films','shows')", name="type"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    type: Mapped[str] = mapped_column(String, nullable=False, unique=True)
    created_at: Mapped[datetime] = _now_col()
    # Set when this library's folder list was seeded (never cleared): seeding runs once.
    folders_seeded_at: Mapped[datetime | None]


class LibraryFolder(Base):
    """A folder read by a library. ``path`` is normalized (not realpath), unique across both
    libraries; display order within a library is ``id``."""

    __tablename__ = "library_folder"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    library_id: Mapped[int] = _fk("library.id")
    path: Mapped[str] = mapped_column(String, nullable=False, unique=True)
    created_at: Mapped[datetime] = _now_col()


class MediaFile(Base):
    __tablename__ = "media_file"
    __table_args__ = (
        Index("ix_media_file_film_id", "film_id"),
        Index("ix_media_file_library_id_root_path", "library_id", "root_path"),
        Index("ix_media_file_missing_since", "missing_since"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    library_id: Mapped[int] = _fk("library.id")
    root_path: Mapped[str] = mapped_column(String, nullable=False)
    path: Mapped[str] = mapped_column(String, nullable=False, unique=True)
    relative_path: Mapped[str] = mapped_column(String, nullable=False)
    size_bytes: Mapped[int] = mapped_column(Integer, nullable=False)
    mtime_ns: Mapped[int] = mapped_column(Integer, nullable=False)
    container: Mapped[str | None]
    duration_seconds: Mapped[float | None]
    video_codec: Mapped[str | None]
    width: Mapped[int | None] = mapped_column(Integer)
    height: Mapped[int | None] = mapped_column(Integer)
    hdr: Mapped[str | None]
    probe: Mapped[dict[str, Any] | None]
    probe_error: Mapped[str | None]
    parsed: Mapped[dict[str, Any] | None]
    manual_match: Mapped[dict[str, Any] | None]
    film_id: Mapped[int | None] = _fk("film.id", ondelete="SET NULL", nullable=True)
    part_number: Mapped[int | None] = mapped_column(Integer)
    missing_since: Mapped[datetime | None]
    added_at: Mapped[datetime] = _now_col()
    updated_at: Mapped[datetime] = _now_col(onupdate=utcnow)


class KeyframeIndex(Base):
    __tablename__ = "keyframe_index"
    __table_args__ = (
        CheckConstraint("source IN ('mkv_cues','mp4_stss','ffprobe')", name="source"),
    )

    media_file_id: Mapped[int] = mapped_column(
        Integer,
        ForeignKey("media_file.id", ondelete="CASCADE"),
        primary_key=True,
        autoincrement=False,
    )
    source: Mapped[str] = mapped_column(String, nullable=False)
    times: Mapped[bytes] = mapped_column(LargeBinary, nullable=False)  # zlib float64 LE array
    created_at: Mapped[datetime] = _now_col()


class ItemAlias(Base):
    __tablename__ = "item_alias"
    __table_args__ = (
        CheckConstraint(_EXACTLY_ONE_FILM_SHOW, name="exactly_one_target"),
        Index(
            "uq_item_alias_key",
            "library_id",
            "parsed_title_norm",
            text("coalesce(parsed_year, -1)"),
            unique=True,
        ),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    library_id: Mapped[int] = _fk("library.id")
    parsed_title_norm: Mapped[str] = mapped_column(String, nullable=False)
    parsed_year: Mapped[int | None] = mapped_column(Integer)
    film_id: Mapped[int | None] = _fk("film.id", nullable=True)
    show_id: Mapped[int | None] = _fk("show.id", nullable=True)
    created_at: Mapped[datetime] = _now_col()


class MediaFileEpisode(Base):
    __tablename__ = "media_file_episode"
    __table_args__ = (Index("ix_media_file_episode_episode_id", "episode_id"),)

    media_file_id: Mapped[int] = mapped_column(
        Integer, ForeignKey("media_file.id", ondelete="CASCADE"), primary_key=True
    )
    episode_id: Mapped[int] = mapped_column(
        Integer, ForeignKey("episode.id", ondelete="CASCADE"), primary_key=True
    )


class SubtitleFile(Base):
    __tablename__ = "subtitle_file"
    __table_args__ = (
        CheckConstraint("kind IN ('text','image')", name="kind"),
        Index("ix_subtitle_file_media_file_id", "media_file_id"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    media_file_id: Mapped[int] = _fk("media_file.id")
    path: Mapped[str] = mapped_column(String, nullable=False, unique=True)
    language: Mapped[str] = mapped_column(
        String, nullable=False, default="und", server_default="und"
    )
    forced: Mapped[bool] = mapped_column(
        Boolean, nullable=False, default=False, server_default=text("0")
    )
    hearing_impaired: Mapped[bool] = mapped_column(
        Boolean, nullable=False, default=False, server_default=text("0")
    )
    format: Mapped[str] = mapped_column(String, nullable=False)
    kind: Mapped[str] = mapped_column(String, nullable=False)
    size_bytes: Mapped[int] = mapped_column(Integer, nullable=False)
    mtime_ns: Mapped[int] = mapped_column(Integer, nullable=False)


class _ItemColumns:
    """Columns shared by film and show."""

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    match_status: Mapped[str] = mapped_column(
        String, nullable=False, default="pending", server_default="pending"
    )
    parsed_title: Mapped[str] = mapped_column(String, nullable=False)
    parsed_title_norm: Mapped[str] = mapped_column(String, nullable=False)
    parsed_year: Mapped[int | None] = mapped_column(Integer)
    tmdb_id: Mapped[int | None] = mapped_column(Integer)
    title: Mapped[str] = mapped_column(String, nullable=False)
    original_title: Mapped[str | None]
    sort_title: Mapped[str] = mapped_column(String, nullable=False)
    search_text: Mapped[str] = mapped_column(String, nullable=False)
    year: Mapped[int | None] = mapped_column(Integer)
    overview: Mapped[str | None]
    vote_average: Mapped[float | None]
    poster_path: Mapped[str | None]
    backdrop_path: Mapped[str | None]
    added_at: Mapped[datetime] = _now_col()
    metadata_updated_at: Mapped[datetime | None]


class Film(_ItemColumns, Base):
    __tablename__ = "film"
    __table_args__ = (
        Index(
            "uq_film_library_id_tmdb_id",
            "library_id",
            "tmdb_id",
            unique=True,
            sqlite_where=text("tmdb_id IS NOT NULL"),
        ),
        Index(
            "ix_film_library_id_parsed_title_norm_parsed_year",
            "library_id",
            "parsed_title_norm",
            "parsed_year",
        ),
        Index("ix_film_sort_title", "sort_title"),
        Index("ix_film_year", "year"),
        Index("ix_film_added_at", "added_at"),
    )

    library_id: Mapped[int] = _fk("library.id")
    imdb_id: Mapped[str | None]
    release_date: Mapped[date | None]
    tagline: Mapped[str | None]
    runtime_minutes: Mapped[int | None] = mapped_column(Integer)


class Show(_ItemColumns, Base):
    __tablename__ = "show"
    __table_args__ = (
        Index(
            "uq_show_library_id_tmdb_id",
            "library_id",
            "tmdb_id",
            unique=True,
            sqlite_where=text("tmdb_id IS NOT NULL"),
        ),
        Index(
            "ix_show_library_id_parsed_title_norm_parsed_year",
            "library_id",
            "parsed_title_norm",
            "parsed_year",
        ),
        Index("ix_show_sort_title", "sort_title"),
        Index("ix_show_year", "year"),
        Index("ix_show_content_updated_at", "content_updated_at"),
    )

    library_id: Mapped[int] = _fk("library.id")
    first_air_date: Mapped[date | None]
    status: Mapped[str | None]
    content_updated_at: Mapped[datetime] = _now_col()


class Season(Base):
    __tablename__ = "season"
    __table_args__ = (UniqueConstraint("show_id", "season_number"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    show_id: Mapped[int] = _fk("show.id")
    season_number: Mapped[int] = mapped_column(Integer, nullable=False)
    name: Mapped[str | None]
    overview: Mapped[str | None]
    poster_path: Mapped[str | None]
    air_date: Mapped[date | None]
    metadata_fetched_at: Mapped[datetime | None]


class Episode(Base):
    __tablename__ = "episode"
    __table_args__ = (
        UniqueConstraint("season_id", "episode_number"),
        Index(
            "ix_episode_show_id_season_number_episode_number",
            "show_id",
            "season_number",
            "episode_number",
        ),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    season_id: Mapped[int] = _fk("season.id")
    show_id: Mapped[int] = _fk("show.id")
    season_number: Mapped[int] = mapped_column(Integer, nullable=False)
    episode_number: Mapped[int] = mapped_column(Integer, nullable=False)
    title: Mapped[str | None]
    overview: Mapped[str | None]
    still_path: Mapped[str | None]
    air_date: Mapped[date | None]
    runtime_minutes: Mapped[int | None] = mapped_column(Integer)
    added_at: Mapped[datetime] = _now_col()


class Person(Base):
    __tablename__ = "person"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    tmdb_id: Mapped[int] = mapped_column(Integer, nullable=False, unique=True)
    name: Mapped[str] = mapped_column(String, nullable=False)
    profile_path: Mapped[str | None]
    # TMDB person details (``people.py``); ``name``/``profile_path`` stay owned by enrichment.
    biography: Mapped[str | None]
    biography_language: Mapped[str | None]
    birthday: Mapped[date | None]
    deathday: Mapped[date | None]
    place_of_birth: Mapped[str | None]
    # Last successful TMDB person fetch (a TMDB 404 included); NULL = never fetched.
    details_fetched_at: Mapped[datetime | None]


class PersonCredit(Base):
    """One TMDB acting credit of a person (``combined_credits.cast``), matched at read time."""

    __tablename__ = "person_credit"
    __table_args__ = (
        CheckConstraint("media_type IN ('movie','tv')", name="media_type"),
        Index(
            "uq_person_credit_person_id_media_type_tmdb_id",
            "person_id",
            "media_type",
            "tmdb_id",
            unique=True,
        ),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    person_id: Mapped[int] = _fk("person.id")
    media_type: Mapped[str] = mapped_column(String, nullable=False)
    tmdb_id: Mapped[int] = mapped_column(Integer, nullable=False)
    character: Mapped[str | None]


class CastCredit(Base):
    __tablename__ = "cast_credit"
    __table_args__ = (
        CheckConstraint(_EXACTLY_ONE_FILM_SHOW, name="exactly_one_target"),
        Index("ix_cast_credit_film_id_order", "film_id", "order"),
        Index("ix_cast_credit_show_id_order", "show_id", "order"),
        Index("ix_cast_credit_person_id", "person_id"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    person_id: Mapped[int] = _fk("person.id")
    film_id: Mapped[int | None] = _fk("film.id", nullable=True)
    show_id: Mapped[int | None] = _fk("show.id", nullable=True)
    character: Mapped[str | None]
    order: Mapped[int] = mapped_column(Integer, nullable=False)


class Genre(Base):
    __tablename__ = "genre"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=False)  # TMDB id
    name: Mapped[str] = mapped_column(String, nullable=False)


class FilmGenre(Base):
    __tablename__ = "film_genre"

    film_id: Mapped[int] = mapped_column(
        Integer, ForeignKey("film.id", ondelete="CASCADE"), primary_key=True
    )
    genre_id: Mapped[int] = mapped_column(
        Integer, ForeignKey("genre.id", ondelete="CASCADE"), primary_key=True
    )


class ShowGenre(Base):
    __tablename__ = "show_genre"

    show_id: Mapped[int] = mapped_column(
        Integer, ForeignKey("show.id", ondelete="CASCADE"), primary_key=True
    )
    genre_id: Mapped[int] = mapped_column(
        Integer, ForeignKey("genre.id", ondelete="CASCADE"), primary_key=True
    )


class Profile(Base):
    __tablename__ = "profile"
    __table_args__ = (
        CheckConstraint(
            "(is_admin = 1 AND pin_hash IS NOT NULL) OR (is_admin = 0 AND pin_hash IS NULL)",
            name="admin_pin",
        ),
        {"sqlite_autoincrement": True},
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    name: Mapped[str] = mapped_column(String(collation="NOCASE"), nullable=False, unique=True)
    avatar_id: Mapped[str] = mapped_column(String, nullable=False)
    pin_hash: Mapped[str | None]
    is_admin: Mapped[bool] = mapped_column(
        Boolean, nullable=False, default=False, server_default=text("0")
    )
    admin_token_version: Mapped[int] = mapped_column(
        Integer, nullable=False, default=1, server_default=text("1")
    )
    created_at: Mapped[datetime] = _now_col()


class PlaybackProgress(Base):
    __tablename__ = "playback_progress"
    __table_args__ = (
        CheckConstraint("(film_id IS NULL) != (episode_id IS NULL)", name="exactly_one_target"),
        Index(
            "uq_playback_progress_profile_id_film_id",
            "profile_id",
            "film_id",
            unique=True,
            sqlite_where=text("film_id IS NOT NULL"),
        ),
        Index(
            "uq_playback_progress_profile_id_episode_id",
            "profile_id",
            "episode_id",
            unique=True,
            sqlite_where=text("episode_id IS NOT NULL"),
        ),
        Index(
            "ix_playback_progress_profile_id_watched_updated_at",
            "profile_id",
            "watched",
            "updated_at",
        ),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    profile_id: Mapped[int] = _fk("profile.id")
    film_id: Mapped[int | None] = _fk("film.id", nullable=True)
    episode_id: Mapped[int | None] = _fk("episode.id", nullable=True)
    media_file_id: Mapped[int | None] = _fk("media_file.id", ondelete="SET NULL", nullable=True)
    position_seconds: Mapped[float] = mapped_column(
        Float, nullable=False, default=0.0, server_default=text("0")
    )
    duration_seconds: Mapped[float] = mapped_column(
        Float, nullable=False, default=0.0, server_default=text("0")
    )
    watched: Mapped[bool] = mapped_column(
        Boolean, nullable=False, default=False, server_default=text("0")
    )
    watched_at: Mapped[datetime | None]
    updated_at: Mapped[datetime] = _now_col(onupdate=utcnow)


class WatchlistEntry(Base):
    """One title of a profile's "Mi lista" (a film or a whole show); ``added_at`` is the list's
    own timestamp (API ``added_to_list_at``)."""

    __tablename__ = "watchlist_entry"
    __table_args__ = (
        CheckConstraint(_EXACTLY_ONE_FILM_SHOW, name="exactly_one_target"),
        Index(
            "uq_watchlist_entry_profile_id_film_id",
            "profile_id",
            "film_id",
            unique=True,
            sqlite_where=text("film_id IS NOT NULL"),
        ),
        Index(
            "uq_watchlist_entry_profile_id_show_id",
            "profile_id",
            "show_id",
            unique=True,
            sqlite_where=text("show_id IS NOT NULL"),
        ),
        Index("ix_watchlist_entry_profile_id_added_at", "profile_id", "added_at"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    profile_id: Mapped[int] = _fk("profile.id")
    film_id: Mapped[int | None] = _fk("film.id", nullable=True)
    show_id: Mapped[int | None] = _fk("show.id", nullable=True)
    added_at: Mapped[datetime] = _now_col()


class Rating(Base):
    """A profile's ½-5 star rating of a film or a whole show; ``stars_x2`` = stars x 2 (1-10).
    The unique indexes lead with the title column: they serve the upsert, the detail read (every
    rating of one title), the summary batch (one profile, many titles) and the title FK cascade."""

    __tablename__ = "rating"
    __table_args__ = (
        CheckConstraint(_EXACTLY_ONE_FILM_SHOW, name="exactly_one_target"),
        CheckConstraint("stars_x2 BETWEEN 1 AND 10", name="stars_x2_range"),
        Index(
            "uq_rating_film_id_profile_id",
            "film_id",
            "profile_id",
            unique=True,
            sqlite_where=text("film_id IS NOT NULL"),
        ),
        Index(
            "uq_rating_show_id_profile_id",
            "show_id",
            "profile_id",
            unique=True,
            sqlite_where=text("show_id IS NOT NULL"),
        ),
        Index("ix_rating_rated_at_id", "rated_at", "id"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    profile_id: Mapped[int] = _fk("profile.id")
    film_id: Mapped[int | None] = _fk("film.id", nullable=True)
    show_id: Mapped[int | None] = _fk("show.id", nullable=True)
    stars_x2: Mapped[int] = mapped_column(Integer, nullable=False)
    rated_at: Mapped[datetime] = _now_col()


class RatingDismissal(Base):
    """ "Ahora no" on the rating prompt (or a removed rating): silences the prompt for this
    profile and title; never shown. The first ``dismissed_at`` is kept."""

    __tablename__ = "rating_dismissal"
    __table_args__ = (
        CheckConstraint(_EXACTLY_ONE_FILM_SHOW, name="exactly_one_target"),
        Index(
            "uq_rating_dismissal_film_id_profile_id",
            "film_id",
            "profile_id",
            unique=True,
            sqlite_where=text("film_id IS NOT NULL"),
        ),
        Index(
            "uq_rating_dismissal_show_id_profile_id",
            "show_id",
            "profile_id",
            unique=True,
            sqlite_where=text("show_id IS NOT NULL"),
        ),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    profile_id: Mapped[int] = _fk("profile.id")
    film_id: Mapped[int | None] = _fk("film.id", nullable=True)
    show_id: Mapped[int | None] = _fk("show.id", nullable=True)
    dismissed_at: Mapped[datetime] = _now_col()


UNMATCHED_REASONS = (
    "no_title",
    "no_season",
    "no_episode_number",
    "multi_part",
    "probe_failed",
    "no_tmdb_results",
    "low_confidence",
)


class UnmatchedEntry(Base):
    __tablename__ = "unmatched_entry"
    __table_args__ = (
        CheckConstraint(
            "(media_file_id IS NOT NULL) + (film_id IS NOT NULL) + (show_id IS NOT NULL) = 1",
            name="exactly_one_target",
        ),
        CheckConstraint(
            "reason IN (" + ",".join(f"'{r}'" for r in UNMATCHED_REASONS) + ")", name="reason"
        ),
        Index(
            "uq_unmatched_entry_media_file_id_reason",
            "media_file_id",
            "reason",
            unique=True,
            sqlite_where=text("media_file_id IS NOT NULL"),
        ),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    media_file_id: Mapped[int | None] = _fk("media_file.id", nullable=True)
    film_id: Mapped[int | None] = _fk("film.id", nullable=True, unique=True)
    show_id: Mapped[int | None] = _fk("show.id", nullable=True, unique=True)
    reason: Mapped[str] = mapped_column(String, nullable=False)
    detail: Mapped[str | None]
    created_at: Mapped[datetime] = _now_col()


class ScanRun(Base):
    __tablename__ = "scan_run"
    __table_args__ = (
        CheckConstraint(
            "trigger IN (" + ",".join(f"'{t}'" for t in SCAN_TRIGGERS) + ")", name="trigger"
        ),
        CheckConstraint("state IN ('running','completed','failed')", name="state"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    trigger: Mapped[str] = mapped_column(String, nullable=False)
    state: Mapped[str] = mapped_column(String, nullable=False)
    phase: Mapped[str] = mapped_column(String, nullable=False)
    started_at: Mapped[datetime] = _now_col()
    finished_at: Mapped[datetime | None]
    counts: Mapped[dict[str, Any]] = mapped_column(
        JSON, nullable=False, default=dict, server_default=text("'{}'")
    )
    processed: Mapped[int] = mapped_column(
        Integer, nullable=False, default=0, server_default=text("0")
    )
    total: Mapped[int | None] = mapped_column(Integer)
    errors: Mapped[list[Any]] = mapped_column(
        JSON, nullable=False, default=list, server_default=text("'[]'")
    )
    error: Mapped[str | None]
    # ``watch`` runs: ``[ScanScope.to_json(), ...]`` (merge_scopes order); SQL NULL otherwise.
    scope: Mapped[list[Any] | None] = mapped_column(JSON(none_as_null=True))
