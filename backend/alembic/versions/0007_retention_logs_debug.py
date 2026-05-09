"""Separate debug-level log retention.

Adds retention_logs_debug_days. The retention worker treats this as the
cutoff for `level='debug'` rows specifically; all other levels follow
the broader retention_logs_days. Lets the user keep a long tail of
warn/error/info while debug — which dominates volume (~90% of rows in
practice) — ages out fast.

NULL = no auto-purge for that level. We backfill an aggressive default
of 1 day on existing rows so the migration is self-cleaning.

Revision ID: 0007_retention_logs_debug
Revises: 0006_fuel_market
Create Date: 2026-05-09
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0007_retention_logs_debug"
down_revision: str | None = "0006_fuel_market"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column(
        "settings",
        sa.Column("retention_logs_debug_days", sa.Integer, nullable=True),
    )
    # Sensible default for the singleton: 1 day for debug. Existing
    # warn/info/error retention is unchanged (still NULL until the user
    # sets it from Settings → Storage).
    op.execute(
        "UPDATE settings SET retention_logs_debug_days = 1 WHERE id = 1"
    )


def downgrade() -> None:
    op.drop_column("settings", "retention_logs_debug_days")
