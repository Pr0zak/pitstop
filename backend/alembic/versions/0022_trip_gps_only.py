"""trips.gps_only — the trip was recorded with no OBD data at all

A phone in a boat, on a bike, or in someone else's car still produces GPS
movement, and the deriver correctly turns that into a trip. What it cannot
be is a trip of THIS vehicle's engine: there is no OBD stream behind it, so
fuel, RPM, coolant and load are all absent and its distance is not distance
the car drove.

Derived, not user-set: the presence or absence of OBD samples in the trip
window is a fact about the data, and asking a user to assert it invites a
wrong answer. Users tag PURPOSE (`category`); the system tags PROVENANCE.

Measured on the 2026-08-09 boat outing: every GPS-only trip had exactly 0
rows of engine_rpm / coolant_temp / engine_load / vehicle_speed, while the
car trips either side had 677 and 11,416. The discriminator is clean.

Revision ID: 0022_trip_gps_only
Revises: 0021_trip_is_towing
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision: str = "0022_trip_gps_only"
down_revision: str | None = "0021_trip_is_towing"
branch_labels: str | None = None
depends_on: str | None = None


def upgrade() -> None:
    op.add_column(
        "trips",
        sa.Column(
            "gps_only",
            sa.Boolean(),
            nullable=False,
            server_default=sa.false(),
        ),
    )
    # Backfill from the data itself rather than leaving history unlabelled —
    # the whole point is being able to look back and see which trips were
    # never the car. A trip counts as GPS-only when the window contains no
    # engine-derived reading at all.
    op.execute(
        """
        UPDATE trips t SET gps_only = true
        WHERE NOT EXISTS (
            SELECT 1 FROM pid_readings p
             WHERE p.vehicle_id = t.vehicle_id
               AND p.time >= t.started_at
               AND p.time <= COALESCE(t.ended_at, t.started_at)
               AND p.metric IN (
                   'engine_rpm', 'coolant_temp', 'engine_load',
                   'vehicle_speed', 'throttle_position'
               )
        )
        """
    )
    op.create_index(
        "ix_trips_gps_only",
        "trips",
        ["vehicle_id", "started_at"],
        unique=False,
        postgresql_where=sa.text("gps_only"),
    )


def downgrade() -> None:
    op.drop_index("ix_trips_gps_only", table_name="trips")
    op.drop_column("trips", "gps_only")
