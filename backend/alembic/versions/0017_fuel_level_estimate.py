"""Hybrid fuel-level estimate state.

Adds three columns to ``vehicles``:

- ``tank_capacity_l`` — usable tank volume in liters. Used to convert
  between sensor percentage and absolute liters, and as the ceiling on a
  ``is_full=true`` fillup reset. Default 80 L (Honda Pilot Elite manual:
  21.4 US gal = 81 L; rounded for headroom and other vehicles).

- ``fuel_level_estimate_l`` — current best estimate of fuel remaining in
  liters. Mutated by three operations (ADR-019 follow-up):
    1. Fillup logged → RESET to ``liters_after_fillup`` (HIGH confidence)
    2. Trip ends → DECREMENT by ``trip.fuel_used_l`` (MEDIUM confidence)
    3. Engine off + sensor stable ≥10 min → SNAP to sensor reading (HIGH)

- ``fuel_level_estimate_updated_at`` — when the estimate was last touched.
  Used to flag stale estimates in the UI; if the state hasn't been touched
  in N days the live tile should fall back to the raw smoothed sensor and
  display a "stale" badge.

Both ``fuel_level_estimate_l`` and ``fuel_level_estimate_updated_at`` are
nullable because we don't have a value to seed for existing vehicles
until either a fillup is logged or the truth-up worker fires. The
``/vehicles`` endpoint coalesces to the raw sensor reading when NULL.

Revision ID: 0017_fuel_level_estimate
Revises: 0016_fuel_level_calibration
Create Date: 2026-05-24
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0017_fuel_level_estimate"
down_revision: str | None = "0016_fuel_level_calibration"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column(
        "vehicles",
        sa.Column(
            "tank_capacity_l",
            sa.Float(),
            nullable=False,
            server_default="80",
        ),
    )
    op.add_column(
        "vehicles",
        sa.Column("fuel_level_estimate_l", sa.Float(), nullable=True),
    )
    op.add_column(
        "vehicles",
        sa.Column(
            "fuel_level_estimate_updated_at",
            sa.TIMESTAMP(timezone=True),
            nullable=True,
        ),
    )


def downgrade() -> None:
    op.drop_column("vehicles", "fuel_level_estimate_updated_at")
    op.drop_column("vehicles", "fuel_level_estimate_l")
    op.drop_column("vehicles", "tank_capacity_l")
