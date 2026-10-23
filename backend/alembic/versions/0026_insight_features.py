"""insight_trip_features / insight_day_features — precomputed Insights inputs

The Insights cards (ADR-027) correlate the raw PID stream with trips,
weather and fill-ups. Re-reading every reading on each request does not
scale with years of data, so a worker (workers/insight_features.py)
summarises each trip and each UTC day ONCE into a small JSONB document,
and /analytics/insights reads only these rows.

* insight_trip_features: one row per trip. ``trip_ended_at`` records the
  trip's end time when the row was computed; the deriver extends an
  in-progress trip in place (same id), so a mismatch means "recompute".
  Deleted trips (merges, purges) take their row with them (CASCADE).
* insight_day_features: one row per vehicle per UTC day for the signals
  that are not tied to one trip (battery at rest, fuel trims, airflow,
  tire pressure, misfires, ATF temperature, wheel speed vs GPS).

``version`` is the feature-extraction version; bumping
insight_features.FEATURE_VERSION makes the worker recompute every row
whose raw data still exists. Rows outlive the raw readings, so the
retention purge no longer erases what Insights has learned.

Revision ID: 0026_insight_features
Revises: 0025_vehicle_tire_placard
"""

from __future__ import annotations

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

revision: str = "0026_insight_features"
down_revision: str | None = "0025_vehicle_tire_placard"
branch_labels: str | None = None
depends_on: str | None = None


def upgrade() -> None:
    op.create_table(
        "insight_trip_features",
        sa.Column("trip_id", postgresql.UUID(as_uuid=True),
                  sa.ForeignKey("trips.id", ondelete="CASCADE"), primary_key=True),
        sa.Column("vehicle_id", postgresql.UUID(as_uuid=True),
                  sa.ForeignKey("vehicles.id", ondelete="CASCADE"), nullable=False),
        sa.Column("started_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("trip_ended_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("version", sa.SmallInteger(), nullable=False),
        sa.Column("features", postgresql.JSONB(), nullable=False),
        sa.Column("computed_at", sa.DateTime(timezone=True), nullable=False,
                  server_default=sa.func.now()),
    )
    op.create_index("ix_insight_trip_features_vehicle", "insight_trip_features",
                    ["vehicle_id", "started_at"])
    op.create_table(
        "insight_day_features",
        sa.Column("vehicle_id", postgresql.UUID(as_uuid=True),
                  sa.ForeignKey("vehicles.id", ondelete="CASCADE"), primary_key=True),
        sa.Column("day", sa.Date(), primary_key=True),
        sa.Column("version", sa.SmallInteger(), nullable=False),
        sa.Column("features", postgresql.JSONB(), nullable=False),
        sa.Column("computed_at", sa.DateTime(timezone=True), nullable=False,
                  server_default=sa.func.now()),
    )


def downgrade() -> None:
    op.drop_table("insight_day_features")
    op.drop_index("ix_insight_trip_features_vehicle", table_name="insight_trip_features")
    op.drop_table("insight_trip_features")
