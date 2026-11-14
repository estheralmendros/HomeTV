"""``media_file.parser_version``: which parser version produced a row's ``parsed``.

Revision ID: 0008
Revises: 0007
Create Date: 2026-11-14

NULL = version 1 (rows written before this revision). The scanner stamps ``PARSER_VERSION`` on
every parse and reparses films-library rows with an older stamp. Plain ADD/DROP COLUMN: no
batch recreate of ``media_file`` (it would cascade to keyframe indexes and subtitle rows).
"""

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0008"
down_revision: str | None = "0007"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column("media_file", sa.Column("parser_version", sa.Integer(), nullable=True))


def downgrade() -> None:
    op.drop_column("media_file", "parser_version")
