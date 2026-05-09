"""Auto-purge retention settings on the singleton settings row.

Adds two NULL-able integer columns: retention_readings_days and
retention_logs_days. NULL = no auto-purge (manual button still works);
positive integer = nightly cron drops rows older than that.

The retention worker (workers/retention.py) reads these and runs the
same drop_chunks / DELETE path as POST /admin/purge/readings and
/admin/purge/logs.

Revision ID: 0005_retention_settings
Revises: 0004_device_vehicle_map
Create Date: 2026-05-09
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0005_retention_settings"
down_revision: str | None = "0004_device_vehicle_map"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column(
        "settings",
        sa.Column("retention_readings_days", sa.Integer, nullable=True),
    )
    op.add_column(
        "settings",
        sa.Column("retention_logs_days", sa.Integer, nullable=True),
    )


def downgrade() -> None:
    op.drop_column("settings", "retention_logs_days")
    op.drop_column("settings", "retention_readings_days")
