"""Per-record weather columns on fillups + trips.

5 nullable columns each, populated by the realtime hook
(`services/weather.py`) on fillup save / trip close, and by the
backfill worker (`workers/weather_backfiller.py`) for historical
records.

Source: Open-Meteo (free, no API key, archive back to 1940 via
ERA5 with ~5-day publication lag — realtime path uses /v1/forecast
with past_days=1, backfill path uses /v1/archive once ERA5 lands).

Schema rationale (Task #78 research):
- weather_temp_c (real, ~4 B): cold-weather MPG drop is the
  headline use case.
- weather_humidity_pct (smallint): air density → MAF / fuel trim.
- weather_precip_mm (real): numeric beats string for histograms;
  covers rain + snow at varying intensities.
- weather_wind_kph (real): headwind effect on highway MPG.
- weather_code (smallint): WMO 0..99 — Vue lookup table renders
  icon + condition string. Canonical form, no separate string.

~16 B/row × few thousand rows = trivial. TimescaleDB compresses
chunks. Skipping pressure / cloud / dewpoint — none move the
needle for vehicle analytics; humidity + temp lets the client
derive feels-like if needed.

Revision ID: 0009_weather_columns
Revises: 0008_gps_engine_events
Create Date: 2026-05-09
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0009_weather_columns"
down_revision: str | None = "0008_gps_engine_events"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


_TABLES = ("fillups", "trips")
_COLS = (
    ("weather_temp_c", sa.REAL()),
    ("weather_humidity_pct", sa.SmallInteger()),
    ("weather_precip_mm", sa.REAL()),
    ("weather_wind_kph", sa.REAL()),
    ("weather_code", sa.SmallInteger()),
)


def upgrade() -> None:
    for table in _TABLES:
        for name, col_type in _COLS:
            op.add_column(table, sa.Column(name, col_type, nullable=True))


def downgrade() -> None:
    for table in _TABLES:
        for name, _ in _COLS:
            op.drop_column(table, name)
