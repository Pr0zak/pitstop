"""Vehicle EPA combined MPG for "rated vs actual" comparison.

Adds nullable `epa_mpg_combined` column to `vehicles`. The Analytics
view's MPG chart renders this as a horizontal reference line so the
user can see at a glance how their actual fuel economy compares to
the EPA sticker (Task #90).

Revision ID: 0012_vehicle_epa_mpg
Revises: 0011_vehicle_purchase
Create Date: 2026-05-10
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0012_vehicle_epa_mpg"
down_revision: str | None = "0011_vehicle_purchase"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column(
        "vehicles",
        sa.Column("epa_mpg_combined", sa.Numeric(5, 2), nullable=True),
    )


def downgrade() -> None:
    op.drop_column("vehicles", "epa_mpg_combined")
