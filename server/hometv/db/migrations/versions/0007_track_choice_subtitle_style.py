"""Remembered audio/subtitle choice per profile and title; per-profile subtitle style.

Revision ID: 0007
Revises: 0006
Create Date: 2026-11-09

- new ``track_choice`` (one row per profile and film or show; nullable audio and subtitle parts;
  partial unique indexes led by the title column).
- ``profile`` gains ``subtitle_size``, ``subtitle_color``, ``subtitle_edge``,
  ``subtitle_edge_color``, ``subtitle_position`` (NOT NULL with defaults and a column CHECK).
  Plain ``ALTER TABLE ... ADD COLUMN``: ``profile`` must never be batch-recreated, since with
  foreign_keys=ON dropping it cascades to every progress, watchlist, rating and choice row.
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0007"
down_revision: str | None = "0006"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

# Spelled as literals on purpose (a migration never imports app code); a unit test checks they
# equal ``hometv/subtitle_style.py``.
_STYLE_COLUMNS: tuple[tuple[str, str, tuple[str, ...]], ...] = (
    ("subtitle_size", "medium", ("small", "medium", "large")),
    ("subtitle_color", "white", ("white", "yellow", "green", "cyan", "magenta", "orange")),
    ("subtitle_edge", "outline", ("outline", "shadow", "none")),
    ("subtitle_edge_color", "black", ("black", "dark_gray", "white")),
    ("subtitle_position", "low", ("low", "middle", "high")),
)

_NO_SUBTITLE_TRAITS = (
    "subtitle_language IS NULL AND subtitle_title IS NULL AND subtitle_source IS NULL"
    " AND subtitle_kind IS NULL AND subtitle_forced = 0 AND subtitle_hearing_impaired = 0"
)

_TABLE = "track_choice"


def _check(sql: str, name: str) -> sa.CheckConstraint:
    return sa.CheckConstraint(sql, name=op.f(f"ck_{_TABLE}_{name}"))


def upgrade() -> None:
    op.create_table(
        _TABLE,
        sa.Column("id", sa.Integer(), nullable=False),
        sa.Column("profile_id", sa.Integer(), nullable=False),
        sa.Column("film_id", sa.Integer(), nullable=True),
        sa.Column("show_id", sa.Integer(), nullable=True),
        sa.Column("audio_language", sa.String(), nullable=True),
        sa.Column("audio_title", sa.String(), nullable=True),
        sa.Column("audio_channels", sa.Integer(), nullable=True),
        sa.Column("subtitle_mode", sa.String(), nullable=True),
        sa.Column("subtitle_language", sa.String(), nullable=True),
        sa.Column("subtitle_title", sa.String(), nullable=True),
        sa.Column("subtitle_forced", sa.Boolean(), server_default=sa.text("0"), nullable=False),
        sa.Column(
            "subtitle_hearing_impaired", sa.Boolean(), server_default=sa.text("0"), nullable=False
        ),
        sa.Column("subtitle_source", sa.String(), nullable=True),
        sa.Column("subtitle_kind", sa.String(), nullable=True),
        sa.Column(
            "updated_at",
            sa.DateTime(),
            server_default=sa.text("(CURRENT_TIMESTAMP)"),
            nullable=False,
        ),
        _check("(film_id IS NULL) != (show_id IS NULL)", "exactly_one_target"),
        _check(
            "subtitle_mode IS NULL OR subtitle_mode IN ('off', 'track')", "subtitle_mode_values"
        ),
        _check(
            "subtitle_source IS NULL OR subtitle_source IN ('embedded', 'external')",
            "subtitle_source_values",
        ),
        _check(
            "subtitle_kind IS NULL OR subtitle_kind IN ('text', 'image')", "subtitle_kind_values"
        ),
        _check(
            "(subtitle_mode IS 'track') = (subtitle_language IS NOT NULL"
            " AND subtitle_source IS NOT NULL AND subtitle_kind IS NOT NULL)",
            "subtitle_track_fields",
        ),
        _check(f"subtitle_mode IS NOT 'off' OR ({_NO_SUBTITLE_TRAITS})", "subtitle_off_shape"),
        _check(f"subtitle_mode IS NOT NULL OR ({_NO_SUBTITLE_TRAITS})", "subtitle_none_shape"),
        _check(
            "audio_language IS NOT NULL OR (audio_title IS NULL AND audio_channels IS NULL)",
            "audio_part_shape",
        ),
        _check("audio_channels IS NULL OR audio_channels >= 1", "audio_channels_positive"),
        _check("audio_language IS NOT NULL OR subtitle_mode IS NOT NULL", "some_part"),
        sa.ForeignKeyConstraint(
            ["profile_id"],
            ["profile.id"],
            name=op.f(f"fk_{_TABLE}_profile_id_profile"),
            ondelete="CASCADE",
        ),
        sa.ForeignKeyConstraint(
            ["film_id"], ["film.id"], name=op.f(f"fk_{_TABLE}_film_id_film"), ondelete="CASCADE"
        ),
        sa.ForeignKeyConstraint(
            ["show_id"], ["show.id"], name=op.f(f"fk_{_TABLE}_show_id_show"), ondelete="CASCADE"
        ),
        sa.PrimaryKeyConstraint("id", name=op.f(f"pk_{_TABLE}")),
    )
    for column in ("film_id", "show_id"):
        op.create_index(
            f"uq_{_TABLE}_{column}_profile_id",
            _TABLE,
            [column, "profile_id"],
            unique=True,
            sqlite_where=sa.text(f"{column} IS NOT NULL"),
        )

    # Explicit SQL so the CHECK is part of the column definition (existing rows get the default).
    for name, default, values in _STYLE_COLUMNS:
        allowed = ", ".join(f"'{v}'" for v in values)
        op.execute(
            f"ALTER TABLE profile ADD COLUMN {name} VARCHAR NOT NULL DEFAULT '{default}' "
            f"CONSTRAINT ck_profile_{name}_values CHECK ({name} IN ({allowed}))"
        )


def downgrade() -> None:
    op.drop_index(f"uq_{_TABLE}_show_id_profile_id", table_name=_TABLE)
    op.drop_index(f"uq_{_TABLE}_film_id_profile_id", table_name=_TABLE)
    op.drop_table(_TABLE)
    # Plain ALTER TABLE DROP COLUMN (SQLite >= 3.35), never a batch recreate of ``profile``.
    for name, _default, _values in reversed(_STYLE_COLUMNS):
        op.execute(f"ALTER TABLE profile DROP COLUMN {name}")
