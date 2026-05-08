"""Centralized client-log depot.

Adds the ``client_logs`` table used by the phone app, web frontend, future
WiCAN scripts, and the backend itself to ship structured log lines to the
server. Not a hypertable — volume is modest and DELETEs (retention pruning)
should remain cheap.

Revision ID: 0003_client_logs
Revises: 0002_fuel_schema
Create Date: 2026-05-08
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

# revision identifiers, used by Alembic.
revision: str = "0003_client_logs"
down_revision: str | None = "0002_fuel_schema"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "client_logs",
        sa.Column(
            "id",
            postgresql.UUID(as_uuid=True),
            primary_key=True,
            server_default=sa.text("gen_random_uuid()"),
        ),
        sa.Column(
            "ts",
            sa.DateTime(timezone=True),
            nullable=False,
            server_default=sa.func.now(),
        ),
        sa.Column("source", sa.Text(), nullable=False),
        sa.Column("level", sa.Text(), nullable=False),
        sa.Column("message", sa.Text(), nullable=False),
        sa.Column(
            "vehicle_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("vehicles.id", ondelete="SET NULL"),
            nullable=True,
        ),
        sa.Column("device_id", sa.Text(), nullable=True),
        sa.Column(
            "context",
            postgresql.JSONB(),
            nullable=False,
            server_default=sa.text("'{}'::jsonb"),
        ),
        sa.Column("client_ts", sa.DateTime(timezone=True), nullable=True),
        sa.CheckConstraint(
            "source IN ('phone', 'web', 'backend', 'wican', 'test')",
            name="client_logs_source_check",
        ),
        sa.CheckConstraint(
            "level IN ('debug', 'info', 'warn', 'error')",
            name="client_logs_level_check",
        ),
    )
    op.create_index(
        "ix_client_logs_ts",
        "client_logs",
        [sa.text("ts DESC")],
    )
    op.create_index(
        "ix_client_logs_source_level_ts",
        "client_logs",
        ["source", "level", sa.text("ts DESC")],
    )
    op.create_index(
        "ix_client_logs_vehicle_ts",
        "client_logs",
        ["vehicle_id", sa.text("ts DESC")],
    )


def downgrade() -> None:
    op.drop_index("ix_client_logs_vehicle_ts", table_name="client_logs")
    op.drop_index("ix_client_logs_source_level_ts", table_name="client_logs")
    op.drop_index("ix_client_logs_ts", table_name="client_logs")
    op.drop_table("client_logs")
