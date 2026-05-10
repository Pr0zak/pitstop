"""Vehicle purchase price + date for lifetime cost-of-ownership.

Adds nullable `purchase_price` (NUMERIC) and `purchase_date` (DATE)
columns to `vehicles`. The Overview's lifetime COO card uses these
together with cumulative fuel + maintenance totals to compute a
true $/mile-lifetime number. Both nullable so existing records keep
working — the COO card only shows the headline number when
purchase_price is set.

Revision ID: 0011_vehicle_purchase
Revises: 0010_vehicle_latest_odo
Create Date: 2026-05-10
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0011_vehicle_purchase"
down_revision: str | None = "0010_vehicle_latest_odo"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.add_column(
        "vehicles",
        sa.Column("purchase_price", sa.Numeric(10, 2), nullable=True),
    )
    op.add_column(
        "vehicles",
        sa.Column("purchase_date", sa.Date(), nullable=True),
    )


def downgrade() -> None:
    op.drop_column("vehicles", "purchase_date")
    op.drop_column("vehicles", "purchase_price")
