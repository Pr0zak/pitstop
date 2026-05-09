"""Device → vehicle slug mapping.

Lets the WiCAN OBD device publish under any topic id (default is its MAC
address) without the user having to reconfigure the device every time
they get a new one. The ingest worker checks this table when the topic's
slug doesn't match a vehicle directly — so existing setups (where the
WiCAN was configured with a real slug like "pilot19") keep working
without a row, and devices using the default MAC topic get auto-routed
once the user maps them once.

Revision ID: 0004_device_vehicle_map
Revises: 0003_client_logs
Create Date: 2026-05-09
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0004_device_vehicle_map"
down_revision: str | None = "0003_client_logs"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "device_vehicle_map",
        sa.Column("device_id", sa.Text, primary_key=True),
        sa.Column(
            "vehicle_id",
            sa.dialects.postgresql.UUID(as_uuid=True),
            sa.ForeignKey("vehicles.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column(
            "kind",
            sa.Text,
            nullable=False,
            server_default="wican",
            comment="device class: 'wican' | 'phone' | 'manual'",
        ),
        sa.Column(
            "label",
            sa.Text,
            nullable=True,
            comment="human-friendly name shown in the admin UI",
        ),
        sa.Column(
            "first_seen_at",
            sa.DateTime(timezone=True),
            nullable=False,
            server_default=sa.func.now(),
        ),
        sa.Column(
            "last_seen_at",
            sa.DateTime(timezone=True),
            nullable=True,
        ),
    )
    # Index for the cache miss path — when the ingest worker can't find
    # a vehicle by slug, it falls through to look up by device_id here.
    op.create_index(
        "ix_device_vehicle_map_vehicle_id",
        "device_vehicle_map",
        ["vehicle_id"],
    )


def downgrade() -> None:
    op.drop_index("ix_device_vehicle_map_vehicle_id")
    op.drop_table("device_vehicle_map")
