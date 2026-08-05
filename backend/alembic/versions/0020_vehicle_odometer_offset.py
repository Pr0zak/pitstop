"""Per-vehicle odometer offset — reconcile the PCM counter with the dash cluster.

The odometer we ingest over OBD comes from the PCM. The odometer the user
reads (and types into a fillup) comes from the instrument cluster. On this
2019 Pilot those are two separate modules keeping two separate counters,
and the PCM's runs ~51 km (~32 mi) AHEAD of the dash.

That matters because recomputed MPG divides fuel volume by a Δodo. Fillup
odos are dash-sourced; ``vehicles.latest_odo_km`` and the ``odometer``
metric are PCM-sourced. Mix the two in a single delta — e.g. prefill a
fillup form from the live PCM reading while the previous fillup was typed
off the dash — and the delta absorbs the whole 51 km offset, producing a
bogus MPG for that interval and the next one.

``odometer_offset_km`` is the user's measured (PCM − dash) difference in
kilometres, so a positive value means the PCM reads high and clients
subtract it to land on a dash-equivalent number. It is deliberately:

- NULLABLE with no server default — NULL means "not calibrated", which is
  distinct from a genuine measured 0.0 and keeps every existing vehicle
  rendering exactly as it does today.
- PRESENTATION ONLY. Nothing server-side rewrites stored readings;
  ``pid_readings`` stays raw. The offset is applied at prefill/display
  time by whichever client needs a dash-equivalent number.

Revision ID: 0020_vehicle_odometer_offset
Revises: 0019_fuel_level_empty_pct
Create Date: 2026-08-04
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0020_vehicle_odometer_offset"
down_revision: str | None = "0019_fuel_level_empty_pct"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column(
        "vehicles",
        sa.Column("odometer_offset_km", sa.Float(precision=53), nullable=True),
    )


def downgrade() -> None:
    op.drop_column("vehicles", "odometer_offset_km")
