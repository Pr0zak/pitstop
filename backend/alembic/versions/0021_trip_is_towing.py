"""trips.is_towing — mark trips made under tow

A towing trip's fuel economy is not comparable to an ordinary one, so the
flag exists to make those trips identifiable at a glance rather than to be
silently averaged in with the rest.

Deliberately its own boolean rather than a value in `trips.category`:
towing is a LOAD CONDITION, orthogonal to whatever the trip was for. A
towing trip is still a commute or a road trip, and a single free-text
column cannot hold both.

NOT NULL DEFAULT false so every existing row and every future insert has a
definite answer — a nullable flag would force every consumer to decide what
"unknown" means, and there is no such state here: a trip either was towing
or it wasn't.

Revision ID: 0021_trip_is_towing
Revises: 0020_vehicle_odometer_offset
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision: str = "0021_trip_is_towing"
down_revision: str | None = "0020_vehicle_odometer_offset"
branch_labels: str | None = None
depends_on: str | None = None


def upgrade() -> None:
    op.add_column(
        "trips",
        sa.Column(
            "is_towing",
            sa.Boolean(),
            nullable=False,
            server_default=sa.false(),
        ),
    )
    # Partial index: the flag is expected to be rare, so an index over only
    # the true rows stays tiny while still serving "show me towing trips".
    op.create_index(
        "ix_trips_is_towing",
        "trips",
        ["vehicle_id", "started_at"],
        unique=False,
        postgresql_where=sa.text("is_towing"),
    )


def downgrade() -> None:
    op.drop_index("ix_trips_is_towing", table_name="trips")
    op.drop_column("trips", "is_towing")
