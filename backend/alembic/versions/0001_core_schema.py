"""Task #2 — core schema (vehicles, profiles, readings, trips, DTCs, state, settings, lookups).

Creates the TimescaleDB extension, the pid_readings hypertable, and continuous
aggregates pid_hourly + pid_daily with refresh policies. Seeds the lookup_units
reference table and the singleton settings row.

Revision ID: 0001_core_schema
Revises:
Create Date: 2026-05-07
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

# revision identifiers, used by Alembic.
revision: str = "0001_core_schema"
down_revision: str | None = None
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    # --- Extensions ---------------------------------------------------------
    op.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto;")
    op.execute("CREATE EXTENSION IF NOT EXISTS timescaledb;")

    # --- pid_profiles -------------------------------------------------------
    op.create_table(
        "pid_profiles",
        sa.Column(
            "id",
            postgresql.UUID(as_uuid=True),
            primary_key=True,
            server_default=sa.text("gen_random_uuid()"),
        ),
        sa.Column("name", sa.Text(), nullable=False, unique=True),
        sa.Column("description", sa.Text(), nullable=True),
        sa.Column("profile", postgresql.JSONB(), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
    )

    # --- vehicles -----------------------------------------------------------
    op.create_table(
        "vehicles",
        sa.Column(
            "id",
            postgresql.UUID(as_uuid=True),
            primary_key=True,
            server_default=sa.text("gen_random_uuid()"),
        ),
        sa.Column("slug", sa.Text(), nullable=False, unique=True),
        sa.Column("name", sa.Text(), nullable=False),
        sa.Column("description", sa.Text(), nullable=True),
        sa.Column("make", sa.Text(), nullable=True),
        sa.Column("model", sa.Text(), nullable=True),
        sa.Column("year", sa.Integer(), nullable=True),
        sa.Column("vin", sa.Text(), nullable=True),
        sa.Column("plate", sa.Text(), nullable=True),
        sa.Column("fuelio_guid", sa.Text(), unique=True, nullable=True),
        sa.Column(
            "dist_unit", sa.SmallInteger(), nullable=False, server_default=sa.text("1")
        ),
        sa.Column(
            "fuel_unit", sa.SmallInteger(), nullable=False, server_default=sa.text("1")
        ),
        sa.Column(
            "consumption_unit",
            sa.SmallInteger(),
            nullable=False,
            server_default=sa.text("1"),
        ),
        sa.Column(
            "tank_count",
            sa.SmallInteger(),
            nullable=False,
            server_default=sa.text("1"),
        ),
        sa.Column("tank1_type", sa.SmallInteger(), nullable=True),
        sa.Column("tank2_type", sa.SmallInteger(), nullable=True),
        sa.Column("tank1_capacity", sa.Float(), nullable=True),
        sa.Column("tank2_capacity", sa.Float(), nullable=True),
        sa.Column(
            "active", sa.Boolean(), nullable=False, server_default=sa.text("true")
        ),
        sa.Column(
            "pid_profile_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("pid_profiles.id", ondelete="SET NULL"),
            nullable=True,
        ),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.Column("lastupdated_ms", sa.BigInteger(), nullable=True),
        sa.CheckConstraint("slug ~ '^[a-z0-9_-]+$'", name="vehicles_slug_format"),
    )

    # --- pid_readings (hypertable) -----------------------------------------
    op.create_table(
        "pid_readings",
        sa.Column("time", sa.DateTime(timezone=True), nullable=False),
        sa.Column(
            "vehicle_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("vehicles.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("metric", sa.Text(), nullable=False),
        sa.Column("value_num", sa.Float(precision=53), nullable=True),
        sa.Column("value_text", sa.Text(), nullable=True),
        sa.Column(
            "source", sa.Text(), nullable=False, server_default=sa.text("'wican'")
        ),
        sa.PrimaryKeyConstraint(
            "vehicle_id", "metric", "time", name="pk_pid_readings"
        ),
    )
    op.execute(
        "SELECT create_hypertable('pid_readings', 'time', "
        "chunk_time_interval => INTERVAL '7 days');"
    )
    op.create_index(
        "ix_pid_readings_vehicle_time",
        "pid_readings",
        ["vehicle_id", sa.text("time DESC")],
    )
    op.create_index(
        "ix_pid_readings_vehicle_metric_time",
        "pid_readings",
        ["vehicle_id", "metric", sa.text("time DESC")],
    )

    # --- trips --------------------------------------------------------------
    op.create_table(
        "trips",
        sa.Column(
            "id",
            postgresql.UUID(as_uuid=True),
            primary_key=True,
            server_default=sa.text("gen_random_uuid()"),
        ),
        sa.Column(
            "vehicle_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("vehicles.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("started_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("ended_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("duration_s", sa.Integer(), nullable=True),
        sa.Column("distance_km", sa.Float(), nullable=True),
        sa.Column("max_rpm", sa.Float(), nullable=True),
        sa.Column("max_speed_kph", sa.Float(), nullable=True),
        sa.Column("avg_speed_kph", sa.Float(), nullable=True),
        sa.Column("avg_coolant_c", sa.Float(), nullable=True),
        sa.Column("fuel_used_l", sa.Float(), nullable=True),
        sa.Column(
            "dtc_count", sa.Integer(), nullable=False, server_default=sa.text("0")
        ),
        sa.Column("category", sa.Text(), nullable=True),
        sa.Column("notes", sa.Text(), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
    )
    op.create_index(
        "ix_trips_vehicle_started",
        "trips",
        ["vehicle_id", sa.text("started_at DESC")],
    )
    # Partial unique: at most one open trip per vehicle.
    op.create_index(
        "uq_trips_open_per_vehicle",
        "trips",
        ["vehicle_id"],
        unique=True,
        postgresql_where=sa.text("ended_at IS NULL"),
    )

    # --- dtc_events ---------------------------------------------------------
    op.create_table(
        "dtc_events",
        sa.Column(
            "id",
            postgresql.UUID(as_uuid=True),
            primary_key=True,
            server_default=sa.text("gen_random_uuid()"),
        ),
        sa.Column(
            "vehicle_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("vehicles.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column(
            "trip_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("trips.id", ondelete="SET NULL"),
            nullable=True,
        ),
        sa.Column("code", sa.Text(), nullable=False),
        sa.Column("description", sa.Text(), nullable=True),
        sa.Column(
            "seen_at",
            sa.DateTime(timezone=True),
            nullable=False,
            server_default=sa.func.now(),
        ),
        sa.Column("cleared_at", sa.DateTime(timezone=True), nullable=True),
    )
    op.create_index(
        "ix_dtc_events_vehicle_seen",
        "dtc_events",
        ["vehicle_id", sa.text("seen_at DESC")],
    )

    # --- vehicle_state ------------------------------------------------------
    op.create_table(
        "vehicle_state",
        sa.Column(
            "vehicle_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("vehicles.id", ondelete="CASCADE"),
            primary_key=True,
        ),
        sa.Column("last_seen_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("last_metric", sa.Text(), nullable=True),
        sa.Column(
            "latest",
            postgresql.JSONB(),
            nullable=False,
            server_default=sa.text("'{}'::jsonb"),
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
    )

    # --- settings (singleton) ----------------------------------------------
    op.create_table(
        "settings",
        sa.Column(
            "id", sa.Integer(), primary_key=True, server_default=sa.text("1")
        ),
        sa.Column(
            "ha_enabled",
            sa.Boolean(),
            nullable=False,
            server_default=sa.text("false"),
        ),
        sa.Column("ha_url", sa.Text(), nullable=True),
        sa.Column("ha_token", sa.Text(), nullable=True),
        sa.Column(
            "ha_discovery_prefix",
            sa.Text(),
            nullable=False,
            server_default=sa.text("'homeassistant'"),
        ),
        sa.Column(
            "ha_per_pid_toggles",
            postgresql.JSONB(),
            nullable=False,
            server_default=sa.text("'{}'::jsonb"),
        ),
        sa.Column("home_lat", sa.Float(), nullable=True),
        sa.Column("home_lon", sa.Float(), nullable=True),
        sa.Column(
            "disk_alert_pct",
            sa.Integer(),
            nullable=False,
            server_default=sa.text("70"),
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.CheckConstraint("id = 1", name="settings_singleton"),
    )
    op.execute("INSERT INTO settings (id) VALUES (1);")

    # --- lookup_units -------------------------------------------------------
    op.create_table(
        "lookup_units",
        sa.Column("id", sa.SmallInteger(), primary_key=True),
        sa.Column("kind", sa.Text(), nullable=False),
        sa.Column("code", sa.Text(), nullable=False),
        sa.Column("label", sa.Text(), nullable=False),
    )
    # Seed: distinct id per row. Fuelio uses overlapping numeric codes across
    # kinds (e.g. 0 = km AND l), so we encode kind into the PK with a small
    # offset scheme: dist 1-9, fuel 10-19, consumption 20-29, tank 100+.
    op.execute(
        """
        INSERT INTO lookup_units (id, kind, code, label) VALUES
            (1, 'dist', 'km', 'Kilometres'),
            (2, 'dist', 'mi', 'Miles'),
            (10, 'fuel', 'l', 'Litres'),
            (11, 'fuel', 'us_gal', 'US gallons'),
            (12, 'fuel', 'uk_gal', 'UK gallons'),
            (20, 'consumption', 'l_per_100km', 'L/100km'),
            (21, 'consumption', 'mpg_us', 'MPG (US)'),
            (22, 'consumption', 'mpg_uk', 'MPG (UK)'),
            (23, 'consumption', 'km_per_l', 'km/L'),
            (100, 'tank', 'gasoline', 'Gasoline'),
            (101, 'tank', 'diesel', 'Diesel'),
            (102, 'tank', 'lpg', 'LPG');
        """
    )

    # --- continuous aggregates ---------------------------------------------
    # Continuous aggregates can't be created inside a transaction in some
    # Timescale versions; use SET LOCAL to allow them in this migration tx.
    op.execute(
        """
        CREATE MATERIALIZED VIEW pid_hourly
        WITH (timescaledb.continuous) AS
        SELECT
            vehicle_id,
            metric,
            time_bucket(INTERVAL '1 hour', time) AS bucket,
            avg(value_num) AS avg_value,
            min(value_num) AS min_value,
            max(value_num) AS max_value,
            count(*)       AS n
        FROM pid_readings
        WHERE value_num IS NOT NULL
        GROUP BY vehicle_id, metric, bucket
        WITH NO DATA;
        """
    )
    op.execute(
        """
        CREATE MATERIALIZED VIEW pid_daily
        WITH (timescaledb.continuous) AS
        SELECT
            vehicle_id,
            metric,
            time_bucket(INTERVAL '1 day', time) AS bucket,
            avg(value_num) AS avg_value,
            min(value_num) AS min_value,
            max(value_num) AS max_value,
            count(*)       AS n
        FROM pid_readings
        WHERE value_num IS NOT NULL
        GROUP BY vehicle_id, metric, bucket
        WITH NO DATA;
        """
    )
    # Refresh policies: hourly aggregate refreshed every 15 min covering the
    # last 2 hours back to 30 days; daily aggregate refreshed hourly covering
    # last 2 days back to 1 year.
    op.execute(
        """
        SELECT add_continuous_aggregate_policy('pid_hourly',
            start_offset => INTERVAL '30 days',
            end_offset   => INTERVAL '1 hour',
            schedule_interval => INTERVAL '15 minutes');
        """
    )
    op.execute(
        """
        SELECT add_continuous_aggregate_policy('pid_daily',
            start_offset => INTERVAL '365 days',
            end_offset   => INTERVAL '1 day',
            schedule_interval => INTERVAL '1 hour');
        """
    )


def downgrade() -> None:
    op.execute("SELECT remove_continuous_aggregate_policy('pid_daily', if_exists => true);")
    op.execute("SELECT remove_continuous_aggregate_policy('pid_hourly', if_exists => true);")
    op.execute("DROP MATERIALIZED VIEW IF EXISTS pid_daily;")
    op.execute("DROP MATERIALIZED VIEW IF EXISTS pid_hourly;")

    op.drop_table("lookup_units")
    op.drop_table("settings")
    op.drop_table("vehicle_state")
    op.drop_index("ix_dtc_events_vehicle_seen", table_name="dtc_events")
    op.drop_table("dtc_events")
    op.drop_index("uq_trips_open_per_vehicle", table_name="trips")
    op.drop_index("ix_trips_vehicle_started", table_name="trips")
    op.drop_table("trips")
    op.drop_index("ix_pid_readings_vehicle_metric_time", table_name="pid_readings")
    op.drop_index("ix_pid_readings_vehicle_time", table_name="pid_readings")
    op.drop_table("pid_readings")
    op.drop_table("vehicles")
    op.drop_table("pid_profiles")
    # Leave pgcrypto + timescaledb extensions in place — they're cluster-wide
    # and likely shared with other databases.
