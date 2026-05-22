"""Per-vehicle fuel-level calibration ceiling.

Honda's PID 0x2F caps at ~85% on a physically full tank — the sensor's
float-arm stop sits below the actual fill line. Same issue is documented
on other makes. This column captures the highest observed raw fuel_level
right around an is_full=true fillup; the /vehicles endpoint divides the
current raw reading by it (×100, clamped) so the UI hero card + widget
read 100% when the tank actually is full.

Default 100 so existing vehicles render unchanged until they get their
first post-migration is_full fillup, which auto-calibrates.

Revision ID: 0016_fuel_level_calibration
Revises: 0015_timescale_compression
Create Date: 2026-05-22
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0016_fuel_level_calibration"
down_revision: str | None = "0015_timescale_compression"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column(
        "vehicles",
        sa.Column(
            "fuel_level_calibration_pct",
            sa.Float(),
            nullable=False,
            server_default="100",
        ),
    )


def downgrade() -> None:
    op.drop_column("vehicles", "fuel_level_calibration_pct")
