"""Change-triggered scoped scans.

Revision ID: 0003
Revises: 0002
Create Date: 2026-10-27

- ``scan_run.trigger`` accepts ``watch``;
- new nullable JSON ``scan_run.scope`` (the folders of a ``watch`` run; NULL for full runs).
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0003"
down_revision: str | None = "0002"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

_TRIGGERS_NEW = "trigger IN ('startup','scheduled','manual','libraries','watch')"
_TRIGGERS_OLD = "trigger IN ('startup','scheduled','manual','libraries')"


def upgrade() -> None:
    # scan_run has no inbound foreign keys: recreating it is safe.
    with op.batch_alter_table("scan_run", schema=None, recreate="always") as batch_op:
        batch_op.add_column(sa.Column("scope", sa.JSON(), nullable=True))
        batch_op.drop_constraint(op.f("ck_scan_run_trigger"), type_="check")
        batch_op.create_check_constraint(op.f("ck_scan_run_trigger"), _TRIGGERS_NEW)


def downgrade() -> None:
    op.execute("UPDATE scan_run SET trigger = 'scheduled' WHERE trigger = 'watch'")
    with op.batch_alter_table("scan_run", schema=None, recreate="always") as batch_op:
        batch_op.drop_constraint(op.f("ck_scan_run_trigger"), type_="check")
        batch_op.create_check_constraint(op.f("ck_scan_run_trigger"), _TRIGGERS_OLD)
        batch_op.drop_column("scope")
