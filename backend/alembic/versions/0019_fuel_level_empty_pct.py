"""Per-vehicle fuel-level EMPTY reference — the low end of the sender curve.

0016 calibrated only the FULL end (``fuel_level_calibration_pct``): the
raw reading observed on a physically full tank. Everything below that was
assumed linear down to a raw 0 == empty tank. On the 2019 Pilot that
assumption is wrong by a lot — the sender still reads ~5.7% with the tank
dry, so the estimate reads high exactly when it matters.

Measured on 2026-08-03: the sender read 14.902% when the tank actually
held 2.10 gal of a 19.5 gal tank (10.8%). Fitting that against the full
point (91.373% == 100%) puts the sender's dry reading at ~5.7%, i.e. the
one-point map over-reported remaining fuel by 51% at that level (3.18 gal
vs a measured 2.10 gal).

NULL keeps the previous one-point behaviour, so vehicles render unchanged
until they get an is_full fillup deep enough to calibrate the low end.

Revision ID: 0019_fuel_level_empty_pct
Revises: 0018_trip_fuel_applied
Create Date: 2026-08-03
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0019_fuel_level_empty_pct"
down_revision: str | None = "0018_trip_fuel_applied"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column(
        "vehicles",
        sa.Column("fuel_level_empty_pct", sa.Float(), nullable=True),
    )


def downgrade() -> None:
    op.drop_column("vehicles", "fuel_level_empty_pct")
