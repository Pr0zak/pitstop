"""EIA weekly retail gasoline price snapshots.

The eia_fetcher worker pulls the U.S. Energy Information Administration's
free weekly retail-gasoline CSV every Monday and writes one row per
(region, week_of, grade) tuple. Used by /analytics/eia-weekly so the
frontend Overview hero card can show "$/gal vs your region's average."

Region codes match EIA's Petroleum Administration for Defense Districts
(PADD) plus the U.S. average:
    us, east_coast, midwest, gulf_coast, rocky_mtn, west_coast, california

Grades: regular, midgrade, premium, all (all = volume-weighted).

Revision ID: 0006_fuel_market
Revises: 0005_retention_settings
Create Date: 2026-05-09
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0006_fuel_market"
down_revision: str | None = "0005_retention_settings"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    op.create_table(
        "fuel_market_weekly",
        sa.Column("region", sa.Text, nullable=False),
        sa.Column("week_of", sa.Date, nullable=False),
        sa.Column("grade", sa.Text, nullable=False),
        sa.Column("price_usd_per_gal", sa.Numeric(6, 4), nullable=False),
        sa.Column(
            "fetched_at",
            sa.DateTime(timezone=True),
            nullable=False,
            server_default=sa.func.now(),
        ),
        sa.PrimaryKeyConstraint("region", "week_of", "grade"),
    )
    op.create_index(
        "ix_fuel_market_weekly_week",
        "fuel_market_weekly",
        ["week_of"],
    )


def downgrade() -> None:
    op.drop_index("ix_fuel_market_weekly_week")
    op.drop_table("fuel_market_weekly")
