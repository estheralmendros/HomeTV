"""Library folders managed from Administración (HOMETV-1).

Revision ID: 0002
Revises: 0001
Create Date: 2026-10-26

- new ``library_folder`` (path unique across both libraries);
- ``library.folders_seeded_at`` (seeding runs once);
- ``scan_run.trigger`` accepts ``libraries``.
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0002"
down_revision: str | None = "0001"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

_TRIGGERS_NEW = "trigger IN ('startup','scheduled','manual','libraries')"
_TRIGGERS_OLD = "trigger IN ('startup','scheduled','manual')"


def upgrade() -> None:
    op.create_table(
        "library_folder",
        sa.Column("id", sa.Integer(), nullable=False),
        sa.Column("library_id", sa.Integer(), nullable=False),
        sa.Column("path", sa.String(), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(),
            server_default=sa.text("(CURRENT_TIMESTAMP)"),
            nullable=False,
        ),
        sa.ForeignKeyConstraint(
            ["library_id"],
            ["library.id"],
            name=op.f("fk_library_folder_library_id_library"),
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("id", name=op.f("pk_library_folder")),
        sa.UniqueConstraint("path", name=op.f("uq_library_folder_path")),
    )
    # ADD COLUMN only: batch "auto" mode alters in place. ``library`` must never be recreated
    # (with foreign_keys=ON, dropping it would cascade to every media row).
    with op.batch_alter_table("library", schema=None) as batch_op:
        batch_op.add_column(sa.Column("folders_seeded_at", sa.DateTime(), nullable=True))
    # scan_run has no inbound foreign keys: recreating it is safe.
    with op.batch_alter_table("scan_run", schema=None, recreate="always") as batch_op:
        batch_op.drop_constraint(op.f("ck_scan_run_trigger"), type_="check")
        batch_op.create_check_constraint(op.f("ck_scan_run_trigger"), _TRIGGERS_NEW)


def downgrade() -> None:
    op.execute("UPDATE scan_run SET trigger = 'manual' WHERE trigger = 'libraries'")
    with op.batch_alter_table("scan_run", schema=None, recreate="always") as batch_op:
        batch_op.drop_constraint(op.f("ck_scan_run_trigger"), type_="check")
        batch_op.create_check_constraint(op.f("ck_scan_run_trigger"), _TRIGGERS_OLD)
    # Plain ALTER TABLE DROP COLUMN (SQLite >= 3.35), never a batch recreate of ``library``.
    op.execute("ALTER TABLE library DROP COLUMN folders_seeded_at")
    op.drop_table("library_folder")
