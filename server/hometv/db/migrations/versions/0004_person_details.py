"""Actor screen: TMDB person details and filmography (HOMETV-1).

Revision ID: 0004
Revises: 0003
Create Date: 2026-10-28

- ``person`` gains ``biography``, ``biography_language``, ``birthday``, ``deathday``,
  ``place_of_birth``, ``details_fetched_at`` (all nullable);
- new ``person_credit`` (TMDB acting credits per person, unique per media type + TMDB id);
- new index ``ix_cast_credit_person_id``.
"""

from collections.abc import Sequence
from typing import Any

import sqlalchemy as sa
from alembic import op

revision: str = "0004"
down_revision: str | None = "0003"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

_PERSON_COLUMNS: tuple[tuple[str, sa.types.TypeEngine[Any]], ...] = (
    ("biography", sa.String()),
    ("biography_language", sa.String()),
    ("birthday", sa.Date()),
    ("deathday", sa.Date()),
    ("place_of_birth", sa.String()),
    ("details_fetched_at", sa.DateTime()),
)


def upgrade() -> None:
    # Plain ADD COLUMN (nullable, no default): SQLite alters ``person`` in place. ``person`` must
    # never be batch-recreated: with foreign_keys=ON, dropping it cascades to every cast_credit.
    for name, type_ in _PERSON_COLUMNS:
        op.add_column("person", sa.Column(name, type_, nullable=True))
    op.create_table(
        "person_credit",
        sa.Column("id", sa.Integer(), nullable=False),
        sa.Column("person_id", sa.Integer(), nullable=False),
        sa.Column("media_type", sa.String(), nullable=False),
        sa.Column("tmdb_id", sa.Integer(), nullable=False),
        sa.Column("character", sa.String(), nullable=True),
        sa.CheckConstraint(
            "media_type IN ('movie','tv')", name=op.f("ck_person_credit_media_type")
        ),
        sa.ForeignKeyConstraint(
            ["person_id"],
            ["person.id"],
            name=op.f("fk_person_credit_person_id_person"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_person_credit")),
    )
    op.create_index(
        "uq_person_credit_person_id_media_type_tmdb_id",
        "person_credit",
        ["person_id", "media_type", "tmdb_id"],
        unique=True,
    )
    op.create_index("ix_cast_credit_person_id", "cast_credit", ["person_id"], unique=False)


def downgrade() -> None:
    op.drop_index("ix_cast_credit_person_id", table_name="cast_credit")
    op.drop_index("uq_person_credit_person_id_media_type_tmdb_id", table_name="person_credit")
    op.drop_table("person_credit")
    # Plain ALTER TABLE DROP COLUMN (SQLite >= 3.35), never a batch recreate of ``person``.
    for name, _type in reversed(_PERSON_COLUMNS):
        op.execute(f"ALTER TABLE person DROP COLUMN {name}")
