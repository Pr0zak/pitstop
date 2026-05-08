"""SQLAlchemy ORM models for pitstop.

Models cover Phase A core schema (vehicles, profiles, readings, trips, DTCs,
state, settings, lookups) and the Fuelio import schema (fillups, expenses,
pictures, expense + trip categories).

DB-side concerns that don't fit ORM cleanly (TimescaleDB extension,
hypertable creation, continuous aggregates, refresh policies, seed data)
are handled in the Alembic migrations directly via op.execute().
"""

from __future__ import annotations

from datetime import date, datetime
from decimal import Decimal
from uuid import UUID

from sqlalchemy import (
    BigInteger,
    Boolean,
    CheckConstraint,
    Date,
    DateTime,
    Float,
    ForeignKey,
    Index,
    Integer,
    Numeric,
    SmallInteger,
    String,
    Text,
    UniqueConstraint,
    func,
    text,
)
from sqlalchemy.dialects.postgresql import JSONB, UUID as PG_UUID
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


class Base(DeclarativeBase):
    pass


# ---------------------------------------------------------------------------
# Reference / lookup tables
# ---------------------------------------------------------------------------


class LookupUnit(Base):
    """Unit codes used by Fuelio (and surfaced in pitstop UI).

    `kind` partitions the table: dist | fuel | consumption | tank.
    `id` mirrors Fuelio's own integer code where applicable.
    """

    __tablename__ = "lookup_units"

    id: Mapped[int] = mapped_column(SmallInteger, primary_key=True)
    kind: Mapped[str] = mapped_column(Text, nullable=False)
    code: Mapped[str] = mapped_column(Text, nullable=False)
    label: Mapped[str] = mapped_column(Text, nullable=False)


class ExpenseCategory(Base):
    """Cost / expense categories. Seeded with Fuelio's nine defaults."""

    __tablename__ = "expense_categories"

    id: Mapped[int] = mapped_column(SmallInteger, primary_key=True)
    name: Mapped[str] = mapped_column(Text, nullable=False, unique=True)
    priority: Mapped[int] = mapped_column(SmallInteger, server_default=text("0"))
    color: Mapped[str | None] = mapped_column(Text, nullable=True)
    fuelio_guid: Mapped[str | None] = mapped_column(Text, unique=True, nullable=True)
    lastupdated_ms: Mapped[int | None] = mapped_column(BigInteger, nullable=True)


class TripCategory(Base):
    """Trip categories from Fuelio (Private / Work). Imported but not yet surfaced."""

    __tablename__ = "trip_categories"

    id: Mapped[int] = mapped_column(SmallInteger, primary_key=True)
    name: Mapped[str] = mapped_column(Text, nullable=False)
    fuelio_guid: Mapped[str | None] = mapped_column(Text, unique=True, nullable=True)
    lastupdated_ms: Mapped[int | None] = mapped_column(BigInteger, nullable=True)


# ---------------------------------------------------------------------------
# PID profiles + vehicles
# ---------------------------------------------------------------------------


class PidProfile(Base):
    """WiCAN AutoPID profile. Stored 1:1 as JSONB (ADR-005)."""

    __tablename__ = "pid_profiles"

    id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        primary_key=True,
        server_default=text("gen_random_uuid()"),
    )
    name: Mapped[str] = mapped_column(Text, nullable=False, unique=True)
    description: Mapped[str | None] = mapped_column(Text, nullable=True)
    profile: Mapped[dict] = mapped_column(JSONB, nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )


class Vehicle(Base):
    """A vehicle in the user's garage. UUID PK; fuelio_guid for sync (ADR-003)."""

    __tablename__ = "vehicles"
    __table_args__ = (
        CheckConstraint("slug ~ '^[a-z0-9_-]+$'", name="vehicles_slug_format"),
    )

    id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        primary_key=True,
        server_default=text("gen_random_uuid()"),
    )
    slug: Mapped[str] = mapped_column(Text, nullable=False, unique=True)
    name: Mapped[str] = mapped_column(Text, nullable=False)
    description: Mapped[str | None] = mapped_column(Text, nullable=True)
    make: Mapped[str | None] = mapped_column(Text, nullable=True)
    model: Mapped[str | None] = mapped_column(Text, nullable=True)
    year: Mapped[int | None] = mapped_column(Integer, nullable=True)
    vin: Mapped[str | None] = mapped_column(Text, nullable=True)
    plate: Mapped[str | None] = mapped_column(Text, nullable=True)
    fuelio_guid: Mapped[str | None] = mapped_column(Text, unique=True, nullable=True)
    dist_unit: Mapped[int] = mapped_column(SmallInteger, server_default=text("1"))
    fuel_unit: Mapped[int] = mapped_column(SmallInteger, server_default=text("1"))
    consumption_unit: Mapped[int] = mapped_column(
        SmallInteger, server_default=text("1")
    )
    tank_count: Mapped[int] = mapped_column(SmallInteger, server_default=text("1"))
    tank1_type: Mapped[int | None] = mapped_column(SmallInteger, nullable=True)
    tank2_type: Mapped[int | None] = mapped_column(SmallInteger, nullable=True)
    tank1_capacity: Mapped[float | None] = mapped_column(Float, nullable=True)
    tank2_capacity: Mapped[float | None] = mapped_column(Float, nullable=True)
    active: Mapped[bool] = mapped_column(Boolean, server_default=text("true"))
    pid_profile_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("pid_profiles.id", ondelete="SET NULL"),
        nullable=True,
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )
    lastupdated_ms: Mapped[int | None] = mapped_column(BigInteger, nullable=True)


# ---------------------------------------------------------------------------
# Time-series + trips + DTCs + state
# ---------------------------------------------------------------------------


class PidReading(Base):
    """Raw PID reading (TimescaleDB hypertable; created in migration)."""

    __tablename__ = "pid_readings"
    __table_args__ = (
        Index("ix_pid_readings_vehicle_time", "vehicle_id", text("time DESC")),
        Index(
            "ix_pid_readings_vehicle_metric_time",
            "vehicle_id",
            "metric",
            text("time DESC"),
        ),
    )

    time: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, primary_key=True
    )
    vehicle_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("vehicles.id", ondelete="CASCADE"),
        nullable=False,
        primary_key=True,
    )
    metric: Mapped[str] = mapped_column(Text, nullable=False, primary_key=True)
    value_num: Mapped[float | None] = mapped_column(Float, nullable=True)
    value_text: Mapped[str | None] = mapped_column(Text, nullable=True)
    source: Mapped[str] = mapped_column(
        Text, nullable=False, server_default=text("'wican'")
    )


class Trip(Base):
    """One trip = silence-detected window of telemetry. ended_at NULL = open."""

    __tablename__ = "trips"
    __table_args__ = (
        Index("ix_trips_vehicle_started", "vehicle_id", text("started_at DESC")),
        Index(
            "uq_trips_open_per_vehicle",
            "vehicle_id",
            unique=True,
            postgresql_where=text("ended_at IS NULL"),
        ),
    )

    id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        primary_key=True,
        server_default=text("gen_random_uuid()"),
    )
    vehicle_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("vehicles.id", ondelete="CASCADE"),
        nullable=False,
    )
    started_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False
    )
    ended_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
    duration_s: Mapped[int | None] = mapped_column(Integer, nullable=True)
    distance_km: Mapped[float | None] = mapped_column(Float, nullable=True)
    max_rpm: Mapped[float | None] = mapped_column(Float, nullable=True)
    max_speed_kph: Mapped[float | None] = mapped_column(Float, nullable=True)
    avg_speed_kph: Mapped[float | None] = mapped_column(Float, nullable=True)
    avg_coolant_c: Mapped[float | None] = mapped_column(Float, nullable=True)
    fuel_used_l: Mapped[float | None] = mapped_column(Float, nullable=True)
    dtc_count: Mapped[int] = mapped_column(Integer, server_default=text("0"))
    category: Mapped[str | None] = mapped_column(Text, nullable=True)
    notes: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )


class DtcEvent(Base):
    """Diagnostic Trouble Code observation. cleared_at NULL = still active."""

    __tablename__ = "dtc_events"
    __table_args__ = (
        Index("ix_dtc_events_vehicle_seen", "vehicle_id", text("seen_at DESC")),
    )

    id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        primary_key=True,
        server_default=text("gen_random_uuid()"),
    )
    vehicle_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("vehicles.id", ondelete="CASCADE"),
        nullable=False,
    )
    trip_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("trips.id", ondelete="SET NULL"),
        nullable=True,
    )
    code: Mapped[str] = mapped_column(Text, nullable=False)
    description: Mapped[str | None] = mapped_column(Text, nullable=True)
    seen_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )
    cleared_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )


class VehicleState(Base):
    """Per-vehicle latest snapshot. One row per vehicle."""

    __tablename__ = "vehicle_state"

    vehicle_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("vehicles.id", ondelete="CASCADE"),
        primary_key=True,
    )
    last_seen_at: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
    last_metric: Mapped[str | None] = mapped_column(Text, nullable=True)
    latest: Mapped[dict] = mapped_column(JSONB, server_default=text("'{}'::jsonb"))
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )


# ---------------------------------------------------------------------------
# Settings (singleton)
# ---------------------------------------------------------------------------


class Settings(Base):
    """Singleton row (id=1) holding global app settings."""

    __tablename__ = "settings"
    __table_args__ = (
        CheckConstraint("id = 1", name="settings_singleton"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True, server_default=text("1"))
    ha_enabled: Mapped[bool] = mapped_column(Boolean, server_default=text("false"))
    ha_url: Mapped[str | None] = mapped_column(Text, nullable=True)
    ha_token: Mapped[str | None] = mapped_column(Text, nullable=True)
    ha_discovery_prefix: Mapped[str] = mapped_column(
        Text, server_default=text("'homeassistant'")
    )
    ha_per_pid_toggles: Mapped[dict] = mapped_column(
        JSONB, server_default=text("'{}'::jsonb")
    )
    home_lat: Mapped[float | None] = mapped_column(Float, nullable=True)
    home_lon: Mapped[float | None] = mapped_column(Float, nullable=True)
    disk_alert_pct: Mapped[int] = mapped_column(Integer, server_default=text("70"))
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )


# ---------------------------------------------------------------------------
# Fuel + expenses (Task #14)
# ---------------------------------------------------------------------------


class Fillup(Base):
    """One Fuelio fillup entry. fuelio_guid is the upsert key."""

    __tablename__ = "fillups"
    __table_args__ = (
        Index("ix_fillups_vehicle_date", "vehicle_id", text("fillup_date DESC")),
    )

    id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        primary_key=True,
        server_default=text("gen_random_uuid()"),
    )
    vehicle_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("vehicles.id", ondelete="CASCADE"),
        nullable=False,
    )
    fuelio_guid: Mapped[str | None] = mapped_column(Text, unique=True, nullable=True)
    fillup_date: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False
    )
    odo: Mapped[float] = mapped_column(Float, nullable=False)
    fuel_volume: Mapped[float] = mapped_column(Float, nullable=False)
    is_full: Mapped[bool] = mapped_column(Boolean, server_default=text("true"))
    is_missed: Mapped[bool] = mapped_column(Boolean, server_default=text("false"))
    price_total: Mapped[Decimal | None] = mapped_column(
        Numeric(10, 2), nullable=True
    )
    price_per_unit: Mapped[Decimal | None] = mapped_column(
        Numeric(10, 4), nullable=True
    )
    mpg_reported: Mapped[float | None] = mapped_column(Float, nullable=True)
    lat: Mapped[float | None] = mapped_column(Float, nullable=True)
    lon: Mapped[float | None] = mapped_column(Float, nullable=True)
    city: Mapped[str | None] = mapped_column(Text, nullable=True)
    station_id: Mapped[int | None] = mapped_column(Integer, nullable=True)
    tank_number: Mapped[int] = mapped_column(SmallInteger, server_default=text("1"))
    fuel_type: Mapped[int | None] = mapped_column(SmallInteger, nullable=True)
    weather: Mapped[str | None] = mapped_column(Text, nullable=True)
    tank_calc: Mapped[float | None] = mapped_column(Float, nullable=True)
    exclude_distance: Mapped[bool] = mapped_column(
        Boolean, server_default=text("false")
    )
    notes: Mapped[str | None] = mapped_column(Text, nullable=True)
    lastupdated_ms: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )


class Expense(Base):
    """One Fuelio cost / expense / income entry, with optional reminder fields."""

    __tablename__ = "expenses"
    __table_args__ = (
        Index("ix_expenses_vehicle_date", "vehicle_id", text("expense_date DESC")),
    )

    id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        primary_key=True,
        server_default=text("gen_random_uuid()"),
    )
    vehicle_id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("vehicles.id", ondelete="CASCADE"),
        nullable=False,
    )
    fuelio_guid: Mapped[str | None] = mapped_column(Text, unique=True, nullable=True)
    expense_date: Mapped[date] = mapped_column(Date, nullable=False)
    odo: Mapped[float | None] = mapped_column(Float, nullable=True)
    cost_type_id: Mapped[int | None] = mapped_column(
        SmallInteger,
        ForeignKey("expense_categories.id"),
        nullable=True,
    )
    title: Mapped[str | None] = mapped_column(Text, nullable=True)
    notes: Mapped[str | None] = mapped_column(Text, nullable=True)
    cost: Mapped[Decimal] = mapped_column(Numeric(10, 2), nullable=False)
    is_income: Mapped[bool] = mapped_column(Boolean, server_default=text("false"))
    is_template: Mapped[bool] = mapped_column(Boolean, server_default=text("false"))
    remind_odo: Mapped[float | None] = mapped_column(Float, nullable=True)
    remind_date: Mapped[date | None] = mapped_column(Date, nullable=True)
    repeat_odo: Mapped[float | None] = mapped_column(Float, nullable=True)
    repeat_months: Mapped[float | None] = mapped_column(Float, nullable=True)
    flag: Mapped[int] = mapped_column(SmallInteger, server_default=text("0"))
    lastupdated_ms: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )


class Picture(Base):
    """Photo metadata only — bytes never imported. Optional vehicle link."""

    __tablename__ = "pictures"

    id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        primary_key=True,
        server_default=text("gen_random_uuid()"),
    )
    vehicle_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("vehicles.id", ondelete="SET NULL"),
        nullable=True,
    )
    fuelio_guid: Mapped[str | None] = mapped_column(Text, unique=True, nullable=True)
    filename: Mapped[str] = mapped_column(Text, nullable=False)
    note: Mapped[str | None] = mapped_column(Text, nullable=True)
    kind: Mapped[int | None] = mapped_column(SmallInteger, nullable=True)
    target_id: Mapped[str | None] = mapped_column(Text, nullable=True)
    lastupdated_ms: Mapped[int | None] = mapped_column(BigInteger, nullable=True)


# ---------------------------------------------------------------------------
# Client logs (centralized log depot)
# ---------------------------------------------------------------------------


class ClientLog(Base):
    """Centralized log entry from phone / web / wican / backend.

    Not a hypertable: volume is expected to be low (warn/error from the
    backend, occasional client diagnostics) and DELETEs for retention pruning
    should stay cheap. ``ts`` is the server-arrival timestamp; ``client_ts``
    preserves the timestamp the client reported (may differ — e.g. queued
    while the phone was offline).
    """

    __tablename__ = "client_logs"
    __table_args__ = (
        CheckConstraint(
            "source IN ('phone', 'web', 'backend', 'wican', 'test')",
            name="client_logs_source_check",
        ),
        CheckConstraint(
            "level IN ('debug', 'info', 'warn', 'error')",
            name="client_logs_level_check",
        ),
        Index("ix_client_logs_ts", text("ts DESC")),
        Index(
            "ix_client_logs_source_level_ts",
            "source",
            "level",
            text("ts DESC"),
        ),
        Index("ix_client_logs_vehicle_ts", "vehicle_id", text("ts DESC")),
    )

    id: Mapped[UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        primary_key=True,
        server_default=text("gen_random_uuid()"),
    )
    ts: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )
    source: Mapped[str] = mapped_column(Text, nullable=False)
    level: Mapped[str] = mapped_column(Text, nullable=False)
    message: Mapped[str] = mapped_column(Text, nullable=False)
    vehicle_id: Mapped[UUID | None] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("vehicles.id", ondelete="SET NULL"),
        nullable=True,
    )
    device_id: Mapped[str | None] = mapped_column(Text, nullable=True)
    context: Mapped[dict] = mapped_column(JSONB, server_default=text("'{}'::jsonb"))
    client_ts: Mapped[datetime | None] = mapped_column(
        DateTime(timezone=True), nullable=True
    )
