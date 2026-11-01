"""Per-profile ratings and rating-prompt dismissals (HOMETV-1).

Revision ID: 0006
Revises: 0005
Create Date: 2026-10-31

- new ``rating`` (one row per profile and film or show; ``stars_x2`` 1-10; partial unique
  indexes led by the title column, index on ``(rated_at, id)`` for the activity order).
- new ``rating_dismissal`` (one row per profile and film or show; same partial unique indexes).
  No existing table is altered.
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0006"
down_revision: str | None = "0005"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def _target_columns() -> list[sa.schema.SchemaItem]:
    return [
        sa.Column("id", sa.Integer(), nullable=False),
        sa.Column("profile_id", sa.Integer(), nullable=False),
        sa.Column("film_id", sa.Integer(), nullable=True),
        sa.Column("show_id", sa.Integer(), nullable=True),
    ]


def _target_constraints(table: str) -> list[sa.schema.SchemaItem]:
    return [
        sa.CheckConstraint(
            "(film_id IS NULL) != (show_id IS NULL)",
            name=op.f(f"ck_{table}_exactly_one_target"),
        ),
        sa.ForeignKeyConstraint(
            ["profile_id"],
            ["profile.id"],
            name=op.f(f"fk_{table}_profile_id_profile"),
            ondelete="CASCADE",
        ),
        sa.ForeignKeyConstraint(
            ["film_id"],
            ["film.id"],
            name=op.f(f"fk_{table}_film_id_film"),
            ondelete="CASCADE",
        ),
        sa.ForeignKeyConstraint(
            ["show_id"],
            ["show.id"],
            name=op.f(f"fk_{table}_show_id_show"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f(f"pk_{table}")),
    ]


def _unique_target_indexes(table: str) -> None:
    for column in ("film_id", "show_id"):
        op.create_index(
            f"uq_{table}_{column}_profile_id",
            table,
            [column, "profile_id"],
            unique=True,
            sqlite_where=sa.text(f"{column} IS NOT NULL"),
        )


def upgrade() -> None:
    op.create_table(
        "rating",
        *_target_columns(),
        sa.Column("stars_x2", sa.Integer(), nullable=False),
        sa.Column(
            "rated_at",
            sa.DateTime(),
            server_default=sa.text("(CURRENT_TIMESTAMP)"),
            nullable=False,
        ),
        *_target_constraints("rating"),
        sa.CheckConstraint("stars_x2 BETWEEN 1 AND 10", name=op.f("ck_rating_stars_x2_range")),
    )
    _unique_target_indexes("rating")
    op.create_index("ix_rating_rated_at_id", "rating", ["rated_at", "id"], unique=False)

    op.create_table(
        "rating_dismissal",
        *_target_columns(),
        sa.Column(
            "dismissed_at",
            sa.DateTime(),
            server_default=sa.text("(CURRENT_TIMESTAMP)"),
            nullable=False,
        ),
        *_target_constraints("rating_dismissal"),
    )
    _unique_target_indexes("rating_dismissal")


def downgrade() -> None:
    op.drop_index("uq_rating_dismissal_show_id_profile_id", table_name="rating_dismissal")
    op.drop_index("uq_rating_dismissal_film_id_profile_id", table_name="rating_dismissal")
    op.drop_table("rating_dismissal")
    op.drop_index("ix_rating_rated_at_id", table_name="rating")
    op.drop_index("uq_rating_show_id_profile_id", table_name="rating")
    op.drop_index("uq_rating_film_id_profile_id", table_name="rating")
    op.drop_table("rating")
