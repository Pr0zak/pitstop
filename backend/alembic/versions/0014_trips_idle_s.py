"""Per-trip idle seconds (Task #91).

Adds `trips.idle_s INT` populated by `compute_trip_stats` —
seconds where vehicle_speed < 1 m/s within the trip window.
The bridge service publishes vehicle_speed only while the
engine is running (OBD won't respond otherwise), so a low
speed reading is equivalent to "engine on, vehicle stopped"
without needing to join engine_events.

Backfilled to NULL — the deriver fills these in on its next
cycle for legacy trips; phone-batch trips get them at upload
time via compute_trip_stats which both ingest paths share.

Revision ID: 0014_trips_idle_s
Revises: 0013_phone_drive_uploads
Create Date: 2026-05-10
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0014_trips_idle_s"
down_revision: str | None = "0013_phone_drive_uploads"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column(
        "trips",
        sa.Column("idle_s", sa.Integer(), nullable=True),
    )


def downgrade() -> None:
    op.drop_column("trips", "idle_s")
