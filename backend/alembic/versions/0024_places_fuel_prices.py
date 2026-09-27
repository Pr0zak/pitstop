"""settings.places_api_key + places_api_usage — live nearby fuel prices

The "Nearby prices" card asks Google Places API (New) Nearby Search for
gas stations around a point and reads each one's ``fuelOptions``. That
field bills as "Nearby Search Enterprise + Atmosphere": 1,000 free calls
a month, then $40 per 1,000. Two pieces of state make that safe:

- ``settings.places_api_key`` — the user's Google Cloud key. Server-side
  only; the API reports ``key_set: bool`` and never echoes it.
- ``places_api_usage`` — one row per calendar month (UTC) counting the
  calls actually sent to Google. The service reserves a slot with a
  conditional upsert *before* calling out, so the monthly cap holds even
  under concurrent requests.

Prices themselves are never written to the DB: Google's terms restrict
storing Places content, so they live only in an in-memory cache.

Revision ID: 0024_places_fuel_prices
Revises: 0023_vehicle_redline_rpm
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision: str = "0024_places_fuel_prices"
down_revision: str | None = "0023_vehicle_redline_rpm"
branch_labels: str | None = None
depends_on: str | None = None


def upgrade() -> None:
    op.add_column(
        "settings",
        sa.Column("places_api_key", sa.Text(), nullable=True),
    )
    op.create_table(
        "places_api_usage",
        sa.Column("month", sa.Date(), primary_key=True),
        sa.Column("calls", sa.Integer(), nullable=False, server_default="0"),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            nullable=False,
            server_default=sa.text("now()"),
        ),
    )


def downgrade() -> None:
    op.drop_table("places_api_usage")
    op.drop_column("settings", "places_api_key")
