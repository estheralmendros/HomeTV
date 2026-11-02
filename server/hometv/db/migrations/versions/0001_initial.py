"""Initial schema: the full data model.

Revision ID: 0001
Revises:
Create Date: 2026-10-22

Timestamps are UTC DATETIME (stored naive UTC; hometv.db.models.UTCDateTime adds tzinfo on read).
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0001"
down_revision: str | None = None
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "genre",
        sa.Column("id", sa.Integer(), autoincrement=False, nullable=False),
        sa.Column("name", sa.String(), nullable=False),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_genre")),
    )
    op.create_table(
        "library",
        sa.Column("id", sa.Integer(), nullable=False),
        sa.Column("type", sa.String(), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(),
            server_default=sa.text("(CURRENT_TIMESTAMP)"),
            nullable=False,
        ),
        sa.CheckConstraint("type IN ('films','shows')", name=op.f("ck_library_type")),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_library")),
        sa.UniqueConstraint("type", name=op.f("uq_library_type")),
    )
    op.create_table(
        "person",
        sa.Column("id", sa.Integer(), nullable=False),
        sa.Column("tmdb_id", sa.Integer(), nullable=False),
        sa.Column("name", sa.String(), nullable=False),
        sa.Column("profile_path", sa.String(), nullable=True),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_person")),
        sa.UniqueConstraint("tmdb_id", name=op.f("uq_person_tmdb_id")),
    )
    op.create_table(
        "profile",
        sa.Column("id", sa.Integer(), nullable=False),
        sa.Column("name", sa.String(collation="NOCASE"), nullable=False),
        sa.Column("avatar_id", sa.String(), nullable=False),
        sa.Column("pin_hash", sa.String(), nullable=True),
        sa.Column("is_admin", sa.Boolean(), server_default=sa.text("0"), nullable=False),
        sa.Column("admin_token_version", sa.Integer(), server_default=sa.text("1"), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(),
            server_default=sa.text("(CURRENT_TIMESTAMP)"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "(is_admin = 1 AND pin_hash IS NOT NULL) OR (is_admin = 0 AND pin_hash IS NULL)",
            name=op.f("ck_profile_admin_pin"),
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_profile")),
        sa.UniqueConstraint("name", name=op.f("uq_profile_name")),
        sqlite_autoincrement=True,
    )
    op.create_table(
        "scan_run",
        sa.Column("id", sa.Integer(), nullable=False),
        sa.Column("trigger", sa.String(), nullable=False),
        sa.Column("state", sa.String(), nullable=False),
        sa.Column("phase", sa.String(), nullable=False),
        sa.Column(
            "started_at",
            sa.DateTime(),
            server_default=sa.text("(CURRENT_TIMESTAMP)"),
            nullable=False,
        ),
        sa.Column("finished_at", sa.DateTime(), nullable=True),
        sa.Column("counts", sa.JSON(), server_default=sa.text("'{}'"), nullable=False),
        sa.Column("processed", sa.Integer(), server_default=sa.text("0"), nullable=False),
        sa.Column("total", sa.Integer(), nullable=True),
        sa.Column("errors", sa.JSON(), server_default=sa.text("'[]'"), nullable=False),
        sa.Column("error", sa.String(), nullable=True),
        sa.CheckConstraint(
            "state IN ('running','completed','failed')", name=op.f("ck_scan_run_state")
        ),
        sa.CheckConstraint(
            "trigger IN ('startup','scheduled','manual')", name=op.f("ck_scan_run_trigger")
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_scan_run")),
    )
    op.create_table(
        "film",
        sa.Column("library_id", sa.Integer(), nullable=False),
        sa.Column("imdb_id", sa.String(), nullable=True),
        sa.Column("release_date", sa.Date(), nullable=True),
        sa.Column("tagline", sa.String(), nullable=True),
        sa.Column("runtime_minutes", sa.Integer(), nullable=True),
        sa.Column("id", sa.Integer(), nullable=False),
        sa.Column("match_status", sa.String(), server_default="pending", nullable=False),
        sa.Column("parsed_title", sa.String(), nullable=False),
        sa.Column("parsed_title_norm", sa.String(), nullable=False),
        sa.Column("parsed_year", sa.Integer(), nullable=True),
        sa.Column("tmdb_id", sa.Integer(), nullable=True),
        sa.Column("title", sa.String(), nullable=False),
        sa.Column("original_title", sa.String(), nullable=True),
        sa.Column("sort_title", sa.String(), nullable=False),
        sa.Column("search_text", sa.String(), nullable=False),
        sa.Column("year", sa.Integer(), nullable=True),
        sa.Column("overview", sa.String(), nullable=True),
        sa.Column("vote_average", sa.Float(), nullable=True),
        sa.Column("poster_path", sa.String(), nullable=True),
        sa.Column("backdrop_path", sa.String(), nullable=True),
        sa.Column(
            "added_at", sa.DateTime(), server_default=sa.text("(CURRENT_TIMESTAMP)"), nullable=False
        ),
        sa.Column("metadata_updated_at", sa.DateTime(), nullable=True),
        sa.ForeignKeyConstraint(
            ["library_id"],
            ["library.id"],
            name=op.f("fk_film_library_id_library"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_film")),
    )
    with op.batch_alter_table("film", schema=None) as batch_op:
        batch_op.create_index("ix_film_added_at", ["added_at"], unique=False)
        batch_op.create_index(
            "ix_film_library_id_parsed_title_norm_parsed_year",
            ["library_id", "parsed_title_norm", "parsed_year"],
            unique=False,
        )
        batch_op.create_index("ix_film_sort_title", ["sort_title"], unique=False)
        batch_op.create_index("ix_film_year", ["year"], unique=False)
        batch_op.create_index(
            "uq_film_library_id_tmdb_id",
            ["library_id", "tmdb_id"],
            unique=True,
            sqlite_where=sa.text("tmdb_id IS NOT NULL"),
        )

    op.create_table(
        "show",
        sa.Column("library_id", sa.Integer(), nullable=False),
        sa.Column("first_air_date", sa.Date(), nullable=True),
        sa.Column("status", sa.String(), nullable=True),
        sa.Column(
            "content_updated_at",
            sa.DateTime(),
            server_default=sa.text("(CURRENT_TIMESTAMP)"),
            nullable=False,
        ),
        sa.Column("id", sa.Integer(), nullable=False),
        sa.Column("match_status", sa.String(), server_default="pending", nullable=False),
        sa.Column("parsed_title", sa.String(), nullable=False),
        sa.Column("parsed_title_norm", sa.String(), nullable=False),
        sa.Column("parsed_year", sa.Integer(), nullable=True),
        sa.Column("tmdb_id", sa.Integer(), nullable=True),
        sa.Column("title", sa.String(), nullable=False),
        sa.Column("original_title", sa.String(), nullable=True),
        sa.Column("sort_title", sa.String(), nullable=False),
        sa.Column("search_text", sa.String(), nullable=False),
        sa.Column("year", sa.Integer(), nullable=True),
        sa.Column("overview", sa.String(), nullable=True),
        sa.Column("vote_average", sa.Float(), nullable=True),
        sa.Column("poster_path", sa.String(), nullable=True),
        sa.Column("backdrop_path", sa.String(), nullable=True),
        sa.Column(
            "added_at", sa.DateTime(), server_default=sa.text("(CURRENT_TIMESTAMP)"), nullable=False
        ),
        sa.Column("metadata_updated_at", sa.DateTime(), nullable=True),
        sa.ForeignKeyConstraint(
            ["library_id"],
            ["library.id"],
            name=op.f("fk_show_library_id_library"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_show")),
    )
    with op.batch_alter_table("show", schema=None) as batch_op:
        batch_op.create_index("ix_show_content_updated_at", ["content_updated_at"], unique=False)
        batch_op.create_index(
            "ix_show_library_id_parsed_title_norm_parsed_year",
            ["library_id", "parsed_title_norm", "parsed_year"],
            unique=False,
        )
        batch_op.create_index("ix_show_sort_title", ["sort_title"], unique=False)
        batch_op.create_index("ix_show_year", ["year"], unique=False)
        batch_op.create_index(
            "uq_show_library_id_tmdb_id",
            ["library_id", "tmdb_id"],
            unique=True,
            sqlite_where=sa.text("tmdb_id IS NOT NULL"),
        )

    op.create_table(
        "cast_credit",
        sa.Column("id", sa.Integer(), nullable=False),
        sa.Column("person_id", sa.Integer(), nullable=False),
        sa.Column("film_id", sa.Integer(), nullable=True),
        sa.Column("show_id", sa.Integer(), nullable=True),
        sa.Column("character", sa.String(), nullable=True),
        sa.Column("order", sa.Integer(), nullable=False),
        sa.CheckConstraint(
            "(film_id IS NULL) != (show_id IS NULL)", name=op.f("ck_cast_credit_exactly_one_target")
        ),
        sa.ForeignKeyConstraint(
            ["film_id"], ["film.id"], name=op.f("fk_cast_credit_film_id_film"), ondelete="CASCADE"
        ),
        sa.ForeignKeyConstraint(
            ["person_id"],
            ["person.id"],
            name=op.f("fk_cast_credit_person_id_person"),
            ondelete="CASCADE",
        ),
        sa.ForeignKeyConstraint(
            ["show_id"], ["show.id"], name=op.f("fk_cast_credit_show_id_show"), ondelete="CASCADE"
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_cast_credit")),
    )
    with op.batch_alter_table("cast_credit", schema=None) as batch_op:
        batch_op.create_index("ix_cast_credit_film_id_order", ["film_id", "order"], unique=False)
        batch_op.create_index("ix_cast_credit_show_id_order", ["show_id", "order"], unique=False)

    op.create_table(
        "film_genre",
        sa.Column("film_id", sa.Integer(), nullable=False),
        sa.Column("genre_id", sa.Integer(), nullable=False),
        sa.ForeignKeyConstraint(
            ["film_id"], ["film.id"], name=op.f("fk_film_genre_film_id_film"), ondelete="CASCADE"
        ),
        sa.ForeignKeyConstraint(
            ["genre_id"],
            ["genre.id"],
            name=op.f("fk_film_genre_genre_id_genre"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("film_id", "genre_id", name=op.f("pk_film_genre")),
    )
    op.create_table(
        "item_alias",
        sa.Column("id", sa.Integer(), nullable=False),
        sa.Column("library_id", sa.Integer(), nullable=False),
        sa.Column("parsed_title_norm", sa.String(), nullable=False),
        sa.Column("parsed_year", sa.Integer(), nullable=True),
        sa.Column("film_id", sa.Integer(), nullable=True),
        sa.Column("show_id", sa.Integer(), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(),
            server_default=sa.text("(CURRENT_TIMESTAMP)"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "(film_id IS NULL) != (show_id IS NULL)", name=op.f("ck_item_alias_exactly_one_target")
        ),
        sa.ForeignKeyConstraint(
            ["film_id"], ["film.id"], name=op.f("fk_item_alias_film_id_film"), ondelete="CASCADE"
        ),
        sa.ForeignKeyConstraint(
            ["library_id"],
            ["library.id"],
            name=op.f("fk_item_alias_library_id_library"),
            ondelete="CASCADE",
        ),
        sa.ForeignKeyConstraint(
            ["show_id"], ["show.id"], name=op.f("fk_item_alias_show_id_show"), ondelete="CASCADE"
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_item_alias")),
    )
    # NULL year compared via COALESCE(parsed_year, -1) (expression index; not autogenerated).
    op.create_index(
        "uq_item_alias_key",
        "item_alias",
        ["library_id", "parsed_title_norm", sa.text("coalesce(parsed_year, -1)")],
        unique=True,
    )
    op.create_table(
        "media_file",
        sa.Column("id", sa.Integer(), nullable=False),
        sa.Column("library_id", sa.Integer(), nullable=False),
        sa.Column("root_path", sa.String(), nullable=False),
        sa.Column("path", sa.String(), nullable=False),
        sa.Column("relative_path", sa.String(), nullable=False),
        sa.Column("size_bytes", sa.Integer(), nullable=False),
        sa.Column("mtime_ns", sa.Integer(), nullable=False),
        sa.Column("container", sa.String(), nullable=True),
        sa.Column("duration_seconds", sa.Float(), nullable=True),
        sa.Column("video_codec", sa.String(), nullable=True),
        sa.Column("width", sa.Integer(), nullable=True),
        sa.Column("height", sa.Integer(), nullable=True),
        sa.Column("hdr", sa.String(), nullable=True),
        sa.Column("probe", sa.JSON(), nullable=True),
        sa.Column("probe_error", sa.String(), nullable=True),
        sa.Column("parsed", sa.JSON(), nullable=True),
        sa.Column("manual_match", sa.JSON(), nullable=True),
        sa.Column("film_id", sa.Integer(), nullable=True),
        sa.Column("part_number", sa.Integer(), nullable=True),
        sa.Column("missing_since", sa.DateTime(), nullable=True),
        sa.Column(
            "added_at", sa.DateTime(), server_default=sa.text("(CURRENT_TIMESTAMP)"), nullable=False
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(),
            server_default=sa.text("(CURRENT_TIMESTAMP)"),
            nullable=False,
        ),
        sa.ForeignKeyConstraint(
            ["film_id"], ["film.id"], name=op.f("fk_media_file_film_id_film"), ondelete="SET NULL"
        ),
        sa.ForeignKeyConstraint(
            ["library_id"],
            ["library.id"],
            name=op.f("fk_media_file_library_id_library"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_media_file")),
        sa.UniqueConstraint("path", name=op.f("uq_media_file_path")),
    )
    with op.batch_alter_table("media_file", schema=None) as batch_op:
        batch_op.create_index("ix_media_file_film_id", ["film_id"], unique=False)
        batch_op.create_index(
            "ix_media_file_library_id_root_path", ["library_id", "root_path"], unique=False
        )
        batch_op.create_index("ix_media_file_missing_since", ["missing_since"], unique=False)

    op.create_table(
        "season",
        sa.Column("id", sa.Integer(), nullable=False),
        sa.Column("show_id", sa.Integer(), nullable=False),
        sa.Column("season_number", sa.Integer(), nullable=False),
        sa.Column("name", sa.String(), nullable=True),
        sa.Column("overview", sa.String(), nullable=True),
        sa.Column("poster_path", sa.String(), nullable=True),
        sa.Column("air_date", sa.Date(), nullable=True),
        sa.Column("metadata_fetched_at", sa.DateTime(), nullable=True),
        sa.ForeignKeyConstraint(
            ["show_id"], ["show.id"], name=op.f("fk_season_show_id_show"), ondelete="CASCADE"
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_season")),
        sa.UniqueConstraint(
            "show_id", "season_number", name=op.f("uq_season_show_id_season_number")
        ),
    )
    op.create_table(
        "show_genre",
        sa.Column("show_id", sa.Integer(), nullable=False),
        sa.Column("genre_id", sa.Integer(), nullable=False),
        sa.ForeignKeyConstraint(
            ["genre_id"],
            ["genre.id"],
            name=op.f("fk_show_genre_genre_id_genre"),
            ondelete="CASCADE",
        ),
        sa.ForeignKeyConstraint(
            ["show_id"], ["show.id"], name=op.f("fk_show_genre_show_id_show"), ondelete="CASCADE"
        ),
        sa.PrimaryKeyConstraint("show_id", "genre_id", name=op.f("pk_show_genre")),
    )
    op.create_table(
        "episode",
        sa.Column("id", sa.Integer(), nullable=False),
        sa.Column("season_id", sa.Integer(), nullable=False),
        sa.Column("show_id", sa.Integer(), nullable=False),
        sa.Column("season_number", sa.Integer(), nullable=False),
        sa.Column("episode_number", sa.Integer(), nullable=False),
        sa.Column("title", sa.String(), nullable=True),
        sa.Column("overview", sa.String(), nullable=True),
        sa.Column("still_path", sa.String(), nullable=True),
        sa.Column("air_date", sa.Date(), nullable=True),
        sa.Column("runtime_minutes", sa.Integer(), nullable=True),
        sa.Column(
            "added_at", sa.DateTime(), server_default=sa.text("(CURRENT_TIMESTAMP)"), nullable=False
        ),
        sa.ForeignKeyConstraint(
            ["season_id"],
            ["season.id"],
            name=op.f("fk_episode_season_id_season"),
            ondelete="CASCADE",
        ),
        sa.ForeignKeyConstraint(
            ["show_id"], ["show.id"], name=op.f("fk_episode_show_id_show"), ondelete="CASCADE"
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_episode")),
        sa.UniqueConstraint(
            "season_id", "episode_number", name=op.f("uq_episode_season_id_episode_number")
        ),
    )
    with op.batch_alter_table("episode", schema=None) as batch_op:
        batch_op.create_index(
            "ix_episode_show_id_season_number_episode_number",
            ["show_id", "season_number", "episode_number"],
            unique=False,
        )

    op.create_table(
        "keyframe_index",
        sa.Column("media_file_id", sa.Integer(), autoincrement=False, nullable=False),
        sa.Column("source", sa.String(), nullable=False),
        sa.Column("times", sa.LargeBinary(), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(),
            server_default=sa.text("(CURRENT_TIMESTAMP)"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "source IN ('mkv_cues','mp4_stss','ffprobe')", name=op.f("ck_keyframe_index_source")
        ),
        sa.ForeignKeyConstraint(
            ["media_file_id"],
            ["media_file.id"],
            name=op.f("fk_keyframe_index_media_file_id_media_file"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("media_file_id", name=op.f("pk_keyframe_index")),
    )
    op.create_table(
        "subtitle_file",
        sa.Column("id", sa.Integer(), nullable=False),
        sa.Column("media_file_id", sa.Integer(), nullable=False),
        sa.Column("path", sa.String(), nullable=False),
        sa.Column("language", sa.String(), server_default="und", nullable=False),
        sa.Column("forced", sa.Boolean(), server_default=sa.text("0"), nullable=False),
        sa.Column("hearing_impaired", sa.Boolean(), server_default=sa.text("0"), nullable=False),
        sa.Column("format", sa.String(), nullable=False),
        sa.Column("kind", sa.String(), nullable=False),
        sa.Column("size_bytes", sa.Integer(), nullable=False),
        sa.Column("mtime_ns", sa.Integer(), nullable=False),
        sa.CheckConstraint("kind IN ('text','image')", name=op.f("ck_subtitle_file_kind")),
        sa.ForeignKeyConstraint(
            ["media_file_id"],
            ["media_file.id"],
            name=op.f("fk_subtitle_file_media_file_id_media_file"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_subtitle_file")),
        sa.UniqueConstraint("path", name=op.f("uq_subtitle_file_path")),
    )
    with op.batch_alter_table("subtitle_file", schema=None) as batch_op:
        batch_op.create_index("ix_subtitle_file_media_file_id", ["media_file_id"], unique=False)

    op.create_table(
        "unmatched_entry",
        sa.Column("id", sa.Integer(), nullable=False),
        sa.Column("media_file_id", sa.Integer(), nullable=True),
        sa.Column("film_id", sa.Integer(), nullable=True),
        sa.Column("show_id", sa.Integer(), nullable=True),
        sa.Column("reason", sa.String(), nullable=False),
        sa.Column("detail", sa.String(), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(),
            server_default=sa.text("(CURRENT_TIMESTAMP)"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "reason IN ('no_title','no_season','no_episode_number','multi_part','probe_failed','no_tmdb_results','low_confidence')",
            name=op.f("ck_unmatched_entry_reason"),
        ),
        sa.CheckConstraint(
            "(media_file_id IS NOT NULL) + (film_id IS NOT NULL) + (show_id IS NOT NULL) = 1",
            name=op.f("ck_unmatched_entry_exactly_one_target"),
        ),
        sa.ForeignKeyConstraint(
            ["film_id"],
            ["film.id"],
            name=op.f("fk_unmatched_entry_film_id_film"),
            ondelete="CASCADE",
        ),
        sa.ForeignKeyConstraint(
            ["media_file_id"],
            ["media_file.id"],
            name=op.f("fk_unmatched_entry_media_file_id_media_file"),
            ondelete="CASCADE",
        ),
        sa.ForeignKeyConstraint(
            ["show_id"],
            ["show.id"],
            name=op.f("fk_unmatched_entry_show_id_show"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_unmatched_entry")),
        sa.UniqueConstraint("film_id", name=op.f("uq_unmatched_entry_film_id")),
        sa.UniqueConstraint("show_id", name=op.f("uq_unmatched_entry_show_id")),
    )
    with op.batch_alter_table("unmatched_entry", schema=None) as batch_op:
        batch_op.create_index(
            "uq_unmatched_entry_media_file_id_reason",
            ["media_file_id", "reason"],
            unique=True,
            sqlite_where=sa.text("media_file_id IS NOT NULL"),
        )

    op.create_table(
        "media_file_episode",
        sa.Column("media_file_id", sa.Integer(), nullable=False),
        sa.Column("episode_id", sa.Integer(), nullable=False),
        sa.ForeignKeyConstraint(
            ["episode_id"],
            ["episode.id"],
            name=op.f("fk_media_file_episode_episode_id_episode"),
            ondelete="CASCADE",
        ),
        sa.ForeignKeyConstraint(
            ["media_file_id"],
            ["media_file.id"],
            name=op.f("fk_media_file_episode_media_file_id_media_file"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("media_file_id", "episode_id", name=op.f("pk_media_file_episode")),
    )
    with op.batch_alter_table("media_file_episode", schema=None) as batch_op:
        batch_op.create_index("ix_media_file_episode_episode_id", ["episode_id"], unique=False)

    op.create_table(
        "playback_progress",
        sa.Column("id", sa.Integer(), nullable=False),
        sa.Column("profile_id", sa.Integer(), nullable=False),
        sa.Column("film_id", sa.Integer(), nullable=True),
        sa.Column("episode_id", sa.Integer(), nullable=True),
        sa.Column("media_file_id", sa.Integer(), nullable=True),
        sa.Column("position_seconds", sa.Float(), server_default=sa.text("0"), nullable=False),
        sa.Column("duration_seconds", sa.Float(), server_default=sa.text("0"), nullable=False),
        sa.Column("watched", sa.Boolean(), server_default=sa.text("0"), nullable=False),
        sa.Column("watched_at", sa.DateTime(), nullable=True),
        sa.Column(
            "updated_at",
            sa.DateTime(),
            server_default=sa.text("(CURRENT_TIMESTAMP)"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "(film_id IS NULL) != (episode_id IS NULL)",
            name=op.f("ck_playback_progress_exactly_one_target"),
        ),
        sa.ForeignKeyConstraint(
            ["episode_id"],
            ["episode.id"],
            name=op.f("fk_playback_progress_episode_id_episode"),
            ondelete="CASCADE",
        ),
        sa.ForeignKeyConstraint(
            ["film_id"],
            ["film.id"],
            name=op.f("fk_playback_progress_film_id_film"),
            ondelete="CASCADE",
        ),
        sa.ForeignKeyConstraint(
            ["media_file_id"],
            ["media_file.id"],
            name=op.f("fk_playback_progress_media_file_id_media_file"),
            ondelete="SET NULL",
        ),
        sa.ForeignKeyConstraint(
            ["profile_id"],
            ["profile.id"],
            name=op.f("fk_playback_progress_profile_id_profile"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_playback_progress")),
    )
    with op.batch_alter_table("playback_progress", schema=None) as batch_op:
        batch_op.create_index(
            "ix_playback_progress_profile_id_watched_updated_at",
            ["profile_id", "watched", "updated_at"],
            unique=False,
        )
        batch_op.create_index(
            "uq_playback_progress_profile_id_episode_id",
            ["profile_id", "episode_id"],
            unique=True,
            sqlite_where=sa.text("episode_id IS NOT NULL"),
        )
        batch_op.create_index(
            "uq_playback_progress_profile_id_film_id",
            ["profile_id", "film_id"],
            unique=True,
            sqlite_where=sa.text("film_id IS NOT NULL"),
        )


def downgrade() -> None:
    with op.batch_alter_table("playback_progress", schema=None) as batch_op:
        batch_op.drop_index(
            "uq_playback_progress_profile_id_film_id", sqlite_where=sa.text("film_id IS NOT NULL")
        )
        batch_op.drop_index(
            "uq_playback_progress_profile_id_episode_id",
            sqlite_where=sa.text("episode_id IS NOT NULL"),
        )
        batch_op.drop_index("ix_playback_progress_profile_id_watched_updated_at")

    op.drop_table("playback_progress")
    with op.batch_alter_table("media_file_episode", schema=None) as batch_op:
        batch_op.drop_index("ix_media_file_episode_episode_id")

    op.drop_table("media_file_episode")
    with op.batch_alter_table("unmatched_entry", schema=None) as batch_op:
        batch_op.drop_index(
            "uq_unmatched_entry_media_file_id_reason",
            sqlite_where=sa.text("media_file_id IS NOT NULL"),
        )

    op.drop_table("unmatched_entry")
    with op.batch_alter_table("subtitle_file", schema=None) as batch_op:
        batch_op.drop_index("ix_subtitle_file_media_file_id")

    op.drop_table("subtitle_file")
    op.drop_table("keyframe_index")
    with op.batch_alter_table("episode", schema=None) as batch_op:
        batch_op.drop_index("ix_episode_show_id_season_number_episode_number")

    op.drop_table("episode")
    op.drop_table("show_genre")
    op.drop_table("season")
    with op.batch_alter_table("media_file", schema=None) as batch_op:
        batch_op.drop_index("ix_media_file_missing_since")
        batch_op.drop_index("ix_media_file_library_id_root_path")
        batch_op.drop_index("ix_media_file_film_id")

    op.drop_table("media_file")
    op.drop_index("uq_item_alias_key", table_name="item_alias")
    op.drop_table("item_alias")
    op.drop_table("film_genre")
    with op.batch_alter_table("cast_credit", schema=None) as batch_op:
        batch_op.drop_index("ix_cast_credit_show_id_order")
        batch_op.drop_index("ix_cast_credit_film_id_order")

    op.drop_table("cast_credit")
    with op.batch_alter_table("show", schema=None) as batch_op:
        batch_op.drop_index(
            "uq_show_library_id_tmdb_id", sqlite_where=sa.text("tmdb_id IS NOT NULL")
        )
        batch_op.drop_index("ix_show_year")
        batch_op.drop_index("ix_show_sort_title")
        batch_op.drop_index("ix_show_library_id_parsed_title_norm_parsed_year")
        batch_op.drop_index("ix_show_content_updated_at")

    op.drop_table("show")
    with op.batch_alter_table("film", schema=None) as batch_op:
        batch_op.drop_index(
            "uq_film_library_id_tmdb_id", sqlite_where=sa.text("tmdb_id IS NOT NULL")
        )
        batch_op.drop_index("ix_film_year")
        batch_op.drop_index("ix_film_sort_title")
        batch_op.drop_index("ix_film_library_id_parsed_title_norm_parsed_year")
        batch_op.drop_index("ix_film_added_at")

    op.drop_table("film")
    op.drop_table("scan_run")
    op.drop_table("profile")
    op.drop_table("person")
    op.drop_table("library")
    op.drop_table("genre")
