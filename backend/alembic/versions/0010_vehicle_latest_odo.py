"""Lifetime mileage tracking on vehicles.

Adds latest_odo_km + latest_odo_at columns and bootstraps them from
existing pid_readings (WiCAN AutoPID has been recording odometer for
months) and from the highest fillup odo per vehicle.

Maintained going forward by:
- A worker pass on each cycle scanning fresh pid_readings.
- Fillup save handler (when odo > stored value).

Revision ID: 0010_vehicle_latest_odo
Revises: 0009_weather_columns
Create Date: 2026-05-10
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0010_vehicle_latest_odo"
down_revision: str | None = "0009_weather_columns"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column(
        "vehicles",
        sa.Column("latest_odo_km", sa.REAL(), nullable=True),
    )
    op.add_column(
        "vehicles",
        sa.Column("latest_odo_at", sa.DateTime(timezone=True), nullable=True),
    )
    # Bootstrap from pid_readings (WiCAN AutoPID odometer history).
    op.execute(
        """
        UPDATE vehicles v
        SET latest_odo_km = sub.odo,
            latest_odo_at = sub.t
          FROM (
            SELECT vehicle_id,
                   max(value_num) AS odo,
                   max(time)      AS t
              FROM pid_readings
             WHERE metric = 'odometer'
               AND value_num IS NOT NULL
             GROUP BY vehicle_id
          ) sub
         WHERE v.id = sub.vehicle_id
        """
    )
    # Also fold in the highest fillup odo (in case fillup history is
    # newer than any pid_readings value — common on first-day setup).
    # Fillups store odo in either mi or km depending on dist_unit;
    # convert mi → km when needed.
    op.execute(
        """
        UPDATE vehicles v
        SET latest_odo_km = GREATEST(
                COALESCE(v.latest_odo_km, 0),
                CASE WHEN v.dist_unit = 1
                     THEN sub.odo * 1.609344
                     ELSE sub.odo
                END
            ),
            latest_odo_at = COALESCE(v.latest_odo_at, sub.t)
          FROM (
            SELECT vehicle_id, max(odo) AS odo, max(fillup_date) AS t
              FROM fillups
             WHERE odo IS NOT NULL
             GROUP BY vehicle_id
          ) sub
         WHERE v.id = sub.vehicle_id
        """
    )


def downgrade() -> None:
    op.drop_column("vehicles", "latest_odo_at")
    op.drop_column("vehicles", "latest_odo_km")
