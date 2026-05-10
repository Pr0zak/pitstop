"""Phone-canonical batch drive upload: source/incomplete + idempotency ledger.

Task #116 — the foundation for the phone-owns-drive-boundaries
architecture (#115). Adds:

- `trips.source` text — where the trip came from. Existing rows
  backfill to 'deriver'; new phone-batch trips will be 'phone_batch'.
  trip_deriver (#119) skips ranges that already have a phone_batch
  trip.
- `trips.incomplete` boolean — true when the phone sealed a drive
  without an engine_off event (phone died mid-drive). Frontend can
  badge these.
- `phone_drive_uploads` ledger — keyed on the phone-side
  `client_drive_uuid`. Lets `/ingest/drive` detect retries without
  touching the trip row at all. Records `device_id`, `received_at`,
  and the accepted `frame_count` for support / debugging.

Revision ID: 0013_phone_drive_uploads
Revises: 0012_vehicle_epa_mpg
Create Date: 2026-05-10
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision: str = "0013_phone_drive_uploads"
down_revision: str | None = "0012_vehicle_epa_mpg"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    # Unique constraints on gps_points / engine_events so the phone
    # batch ingest's ON CONFLICT DO NOTHING can dedupe against
    # already-streamed bridge data without erroring. Timescale
    # requires the hypertable partition column (time) to be part of
    # any unique constraint.
    op.execute(
        "CREATE UNIQUE INDEX IF NOT EXISTS uq_gps_points_vehicle_time "
        "ON gps_points (vehicle_id, time)"
    )
    op.execute(
        "CREATE UNIQUE INDEX IF NOT EXISTS uq_engine_events_vehicle_time_state "
        "ON engine_events (vehicle_id, time, state)"
    )

    op.add_column(
        "trips",
        sa.Column(
            "source",
            sa.Text(),
            nullable=False,
            server_default=sa.text("'deriver'"),
        ),
    )
    op.add_column(
        "trips",
        sa.Column(
            "incomplete",
            sa.Boolean(),
            nullable=False,
            server_default=sa.text("false"),
        ),
    )
    op.create_index(
        "ix_trips_source", "trips", ["vehicle_id", "source", "started_at"]
    )

    # Idempotency ledger for phone uploads.
    op.create_table(
        "phone_drive_uploads",
        sa.Column(
            "client_drive_uuid",
            postgresql.UUID(as_uuid=True),
            primary_key=True,
        ),
        sa.Column(
            "trip_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("trips.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("device_id", sa.Text(), nullable=True),
        sa.Column(
            "received_at",
            sa.DateTime(timezone=True),
            nullable=False,
            server_default=sa.func.now(),
        ),
        sa.Column("frame_count", sa.Integer(), nullable=False),
    )
    op.create_index(
        "ix_phone_drive_uploads_received",
        "phone_drive_uploads",
        [sa.text("received_at DESC")],
    )


def downgrade() -> None:
    op.drop_index(
        "ix_phone_drive_uploads_received", table_name="phone_drive_uploads"
    )
    op.drop_table("phone_drive_uploads")
    op.drop_index("ix_trips_source", table_name="trips")
    op.drop_column("trips", "incomplete")
    op.drop_column("trips", "source")
    op.execute("DROP INDEX IF EXISTS uq_engine_events_vehicle_time_state")
    op.execute("DROP INDEX IF EXISTS uq_gps_points_vehicle_time")
