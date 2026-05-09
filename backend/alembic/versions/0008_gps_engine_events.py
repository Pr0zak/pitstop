"""GPS track + engine state event tables for bridge payload v2.

Adds two new hypertables that the phone bridge publishes to directly,
in addition to the existing pid_readings stream.

`gps_points` carries the per-fix location track that lets us draw a
trip route on a map. We split it out from pid_readings (where GPS used
to live as gps_lat/gps_lon rows) so a single SELECT yields a row per
fix instead of needing to JOIN two metrics by timestamp.

`engine_events` records on/off transitions. The trip detector uses
these as authoritative trip boundaries when present; otherwise it
falls back to the existing silence-based heuristic.

Both tables are Timescale hypertables on `time` so retention via
drop_chunks works exactly like pid_readings.

Revision ID: 0008_gps_engine_events
Revises: 0007_retention_logs_debug
Create Date: 2026-05-09
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0008_gps_engine_events"
down_revision: str | None = "0007_retention_logs_debug"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    # asyncpg's prepared-statement protocol can't take multi-statement
    # SQL, so each command needs its own op.execute().
    op.execute(
        """
        CREATE TABLE gps_points (
            time          timestamptz NOT NULL,
            vehicle_id    uuid        NOT NULL REFERENCES vehicles(id) ON DELETE CASCADE,
            lat           double precision NOT NULL,
            lon           double precision NOT NULL,
            alt_m         double precision,
            speed_mps     double precision,
            heading_deg   double precision,
            accuracy_m    double precision,
            source        text NOT NULL DEFAULT 'bridge'
        )
        """
    )
    op.execute(
        "SELECT create_hypertable('gps_points', 'time', if_not_exists => TRUE)"
    )
    op.execute(
        "CREATE INDEX idx_gps_points_vehicle_time ON gps_points (vehicle_id, time DESC)"
    )

    op.execute(
        """
        CREATE TABLE engine_events (
            time        timestamptz NOT NULL,
            vehicle_id  uuid        NOT NULL REFERENCES vehicles(id) ON DELETE CASCADE,
            state       text NOT NULL CHECK (state IN ('on','off')),
            source      text NOT NULL DEFAULT 'bridge'
        )
        """
    )
    op.execute(
        "SELECT create_hypertable('engine_events', 'time', if_not_exists => TRUE)"
    )
    op.execute(
        "CREATE INDEX idx_engine_events_vehicle_time ON engine_events (vehicle_id, time DESC)"
    )


def downgrade() -> None:
    op.execute("DROP TABLE IF EXISTS engine_events CASCADE;")
    op.execute("DROP TABLE IF EXISTS gps_points CASCADE;")
