"""Per-profile watchlist "Mi lista" (HOMETV-1).

Revision ID: 0005
Revises: 0004
Create Date: 2026-10-28

- new ``watchlist_entry`` (one row per profile and film or show; partial unique indexes per
  target, index on ``(profile_id, added_at)``). No existing table is altered.
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0005"
down_revision: str | None = "0004"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "watchlist_entry",
        sa.Column("id", sa.Integer(), nullable=False),
        sa.Column("profile_id", sa.Integer(), nullable=False),
        sa.Column("film_id", sa.Integer(), nullable=True),
        sa.Column("show_id", sa.Integer(), nullable=True),
        sa.Column(
            "added_at",
            sa.DateTime(),
            server_default=sa.text("(CURRENT_TIMESTAMP)"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "(film_id IS NULL) != (show_id IS NULL)",
            name=op.f("ck_watchlist_entry_exactly_one_target"),
        ),
        sa.ForeignKeyConstraint(
            ["profile_id"],
            ["profile.id"],
            name=op.f("fk_watchlist_entry_profile_id_profile"),
            ondelete="CASCADE",
        ),
        sa.ForeignKeyConstraint(
            ["film_id"],
            ["film.id"],
            name=op.f("fk_watchlist_entry_film_id_film"),
            ondelete="CASCADE",
        ),
        sa.ForeignKeyConstraint(
            ["show_id"],
            ["show.id"],
            name=op.f("fk_watchlist_entry_show_id_show"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_watchlist_entry")),
    )
    op.create_index(
        "uq_watchlist_entry_profile_id_film_id",
        "watchlist_entry",
        ["profile_id", "film_id"],
        unique=True,
        sqlite_where=sa.text("film_id IS NOT NULL"),
    )
    op.create_index(
        "uq_watchlist_entry_profile_id_show_id",
        "watchlist_entry",
        ["profile_id", "show_id"],
        unique=True,
        sqlite_where=sa.text("show_id IS NOT NULL"),
    )
    op.create_index(
        "ix_watchlist_entry_profile_id_added_at",
        "watchlist_entry",
        ["profile_id", "added_at"],
        unique=False,
    )


def downgrade() -> None:
    op.drop_index("ix_watchlist_entry_profile_id_added_at", table_name="watchlist_entry")
    op.drop_index("uq_watchlist_entry_profile_id_show_id", table_name="watchlist_entry")
    op.drop_index("uq_watchlist_entry_profile_id_film_id", table_name="watchlist_entry")
    op.drop_table("watchlist_entry")
