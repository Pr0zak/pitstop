"""vehicles.redline_rpm — per-vehicle tachometer redline

Lets the web + phone gauges draw the RPM arc's red zone (and scale the
dial) from the actual engine rather than a hard-coded guess. User-set,
nullable: NULL means "not configured" and the clients fall back to
their own default.

The CHECK bounds the value to something a road engine could plausibly
have, so a typo (65000, 65) is refused at the DB rather than silently
redrawing every gauge; the API validates the same range first.

Revision ID: 0023_vehicle_redline_rpm
Revises: 0022_trip_gps_only
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision: str = "0023_vehicle_redline_rpm"
down_revision: str | None = "0022_trip_gps_only"
branch_labels: str | None = None
depends_on: str | None = None


def upgrade() -> None:
    op.add_column(
        "vehicles",
        sa.Column("redline_rpm", sa.Integer(), nullable=True),
    )
    op.create_check_constraint(
        "ck_vehicles_redline_rpm_range",
        "vehicles",
        "redline_rpm IS NULL OR (redline_rpm >= 1000 AND redline_rpm <= 20000)",
    )


def downgrade() -> None:
    op.drop_constraint(
        "ck_vehicles_redline_rpm_range", "vehicles", type_="check"
    )
    op.drop_column("vehicles", "redline_rpm")
