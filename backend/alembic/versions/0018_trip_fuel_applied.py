"""Idempotency flag for the hybrid fuel-level estimator's trip-decrement.

The trip-deriver UPSERTs each trip every cycle (~5 min). The hybrid
fuel-level estimator subtracts ``trip.fuel_used_l`` from a per-vehicle
running estimate exactly ONCE per trip, then this column records when the
debit was applied. The fuel-state worker only processes trips where
``fuel_applied_at IS NULL`` and ``fuel_used_l IS NOT NULL`` and the trip
has settled (``ended_at < now() - interval '2 min'``).

Index supports the worker's selection query.

Revision ID: 0018_trip_fuel_applied
Revises: 0017_fuel_level_estimate
Create Date: 2026-05-24
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0018_trip_fuel_applied"
down_revision: str | None = "0017_fuel_level_estimate"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column(
        "trips",
        sa.Column(
            "fuel_applied_at",
            sa.TIMESTAMP(timezone=True),
            nullable=True,
        ),
    )
    # Partial index: only trips that need processing land here.
    op.execute(
        """
        CREATE INDEX IF NOT EXISTS ix_trips_fuel_applied_pending
        ON trips (vehicle_id, ended_at)
        WHERE fuel_applied_at IS NULL AND fuel_used_l IS NOT NULL
        """
    )


def downgrade() -> None:
    op.execute("DROP INDEX IF EXISTS ix_trips_fuel_applied_pending")
    op.drop_column("trips", "fuel_applied_at")
