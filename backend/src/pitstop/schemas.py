"""Pydantic v2 schemas for the pitstop REST API.

All models share ``model_config = ConfigDict(from_attributes=True)`` so they
can be constructed directly from asyncpg ``Record`` rows (which support both
attribute and item access).
"""

from __future__ import annotations

import re
from datetime import date, datetime
from decimal import Decimal
from typing import Any, Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_validator

# ---------------------------------------------------------------------------
# Vehicles
# ---------------------------------------------------------------------------


_SLUG_RE = re.compile(r"^[a-z0-9_-]+$")


def _slugify(s: str) -> str:
    """Best-effort slug from a free-text name. Lowercases, swaps non-allowed
    chars for ``-``, collapses runs, strips leading/trailing ``-``. Returns
    ``""`` for input that has no slug-able characters at all so the caller
    can decide whether to fall back further (e.g. random UUID prefix)."""
    import re

    out = re.sub(r"[^a-z0-9_-]+", "-", s.lower())
    out = re.sub(r"-{2,}", "-", out).strip("-")
    return out


class VehicleBase(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    slug: str
    name: str
    description: str | None = None
    make: str | None = None
    model: str | None = None
    year: int | None = None
    vin: str | None = None
    plate: str | None = None
    fuelio_guid: str | None = None
    dist_unit: int = 1
    fuel_unit: int = 1
    consumption_unit: int = 1
    tank_count: int = 1
    tank1_type: int | None = None
    tank2_type: int | None = None
    tank1_capacity: float | None = None
    tank2_capacity: float | None = None
    active: bool = True
    pid_profile_id: UUID | None = None

    @field_validator("slug")
    @classmethod
    def _slug_format(cls, v: str) -> str:
        if not _SLUG_RE.match(v):
            raise ValueError("slug must match [a-z0-9_-]+")
        return v


class VehicleCreate(VehicleBase):
    # On create only, slug is optional — we'll derive it from `name` if blank.
    slug: str | None = None  # type: ignore[assignment]

    @field_validator("slug")
    @classmethod
    def _slug_optional_format(cls, v: str | None) -> str | None:
        if v is None or v == "":
            return None
        if not _SLUG_RE.match(v):
            raise ValueError("slug must match [a-z0-9_-]+")
        return v

    def effective_slug(self) -> str:
        """Either the explicit slug, or one derived from ``name``."""
        if self.slug:
            return self.slug
        derived = _slugify(self.name)
        if not derived:
            raise ValueError(
                "could not derive slug from name; please supply slug explicitly"
            )
        return derived


class VehicleUpdate(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    slug: str | None = None
    name: str | None = None
    description: str | None = None
    make: str | None = None
    model: str | None = None
    year: int | None = None
    vin: str | None = None
    plate: str | None = None
    fuelio_guid: str | None = None
    dist_unit: int | None = None
    fuel_unit: int | None = None
    consumption_unit: int | None = None
    tank_count: int | None = None
    tank1_type: int | None = None
    tank2_type: int | None = None
    tank1_capacity: float | None = None
    tank2_capacity: float | None = None
    active: bool | None = None
    pid_profile_id: UUID | None = None
    purchase_price: float | None = None
    purchase_date: date | None = None
    epa_mpg_combined: float | None = None

    @field_validator("slug")
    @classmethod
    def _slug_format(cls, v: str | None) -> str | None:
        if v is None:
            return v
        if not _SLUG_RE.match(v):
            raise ValueError("slug must match [a-z0-9_-]+")
        return v


class ProfileBrief(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    name: str
    description: str | None = None


class VehicleOut(VehicleBase):
    id: UUID
    last_seen_at: datetime | None = None
    last_metric: str | None = None
    latest: dict[str, Any] = Field(default_factory=dict)
    pid_profile: ProfileBrief | None = None
    # Lifetime odometer reading (Task #100). Updated by trip_deriver
    # on each cycle from the freshest pid_readings odometer value;
    # also bumped on fillup save when the user provides an odo.
    latest_odo_km: float | None = None
    latest_odo_at: datetime | None = None
    # Lifetime cost-of-ownership inputs (Task #98).
    purchase_price: float | None = None
    purchase_date: date | None = None
    # EPA combined MPG sticker for "rated vs actual" overlay (Task #90).
    epa_mpg_combined: float | None = None


# ---------------------------------------------------------------------------
# PID profiles
# ---------------------------------------------------------------------------


class PidProfileCreate(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    name: str
    description: str | None = None
    profile: dict[str, Any]


class PidProfileUpdate(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    description: str | None = None
    profile: dict[str, Any]


class PidProfileBrief(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: UUID
    name: str
    description: str | None = None


class PidProfileOut(PidProfileBrief):
    profile: dict[str, Any]
    created_at: datetime
    updated_at: datetime


# ---------------------------------------------------------------------------
# Readings
# ---------------------------------------------------------------------------


class ReadingOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    time: datetime
    value_num: float | None
    value_text: str | None
    source: str


class AggregateOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    bucket: datetime
    avg: float | None
    min: float | None
    max: float | None
    count: int


# ---------------------------------------------------------------------------
# Trips
# ---------------------------------------------------------------------------


class TripOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: UUID
    vehicle_id: UUID
    started_at: datetime
    ended_at: datetime | None
    duration_s: int | None
    distance_km: float | None
    max_rpm: float | None
    max_speed_kph: float | None
    avg_speed_kph: float | None
    avg_coolant_c: float | None
    fuel_used_l: float | None
    dtc_count: int
    # Seconds the engine was on with vehicle speed < 1 m/s (Task #91).
    # Populated by compute_trip_stats; null for pre-migration rows
    # until the deriver re-runs on them.
    idle_s: int | None = None
    category: str | None
    notes: str | None
    # Per-trip weather observation captured at trip-open via the
    # Open-Meteo fetcher (Task #78). All nullable until the
    # backfiller catches up on historical rows.
    weather_temp_c: float | None = None
    weather_humidity_pct: int | None = None
    weather_precip_mm: float | None = None
    weather_wind_kph: float | None = None
    weather_code: int | None = None
    # Provenance (Task #116). 'phone_batch' for atomic uploads from
    # the phone, 'deriver' for legacy/WiCAN-only post-processing.
    # 'incomplete' is true when the phone sealed without an
    # engine_off event (phone died mid-drive) — frontend badges
    # these so the user knows the trip stats may be partial.
    source: str = "deriver"
    incomplete: bool = False


class TripUpdate(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    notes: str | None = None
    category: str | None = None


class TripSamplePoint(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    time: datetime
    metric: str
    value_num: float | None


class TripDetail(TripOut):
    samples: list[TripSamplePoint] = Field(default_factory=list)
    # Closest odometer reading within ±15 min of trip start / end.
    # Null when WiCAN didn't publish during the window. (Task #99)
    odo_start_km: float | None = None
    odo_end_km: float | None = None
    # DTCs that first fired during this trip's window. (Task #110)
    dtcs: list[dict[str, Any]] = Field(default_factory=list)


# ---------------------------------------------------------------------------
# DTCs
# ---------------------------------------------------------------------------


class DtcOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: UUID
    vehicle_id: UUID
    trip_id: UUID | None
    code: str
    description: str | None
    seen_at: datetime
    cleared_at: datetime | None


# ---------------------------------------------------------------------------
# Settings
# ---------------------------------------------------------------------------


class HASettings(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    enabled: bool = False
    url: str | None = None
    token_set: bool = False
    discovery_prefix: str = "homeassistant"
    per_pid_toggles: dict[str, bool] = Field(default_factory=dict)


class HASettingsUpdate(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    enabled: bool | None = None
    url: str | None = None
    token: str | None = None  # optional plaintext set; "" clears
    discovery_prefix: str | None = None
    per_pid_toggles: dict[str, bool] | None = None


class HomeLocation(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    lat: float | None = None
    lon: float | None = None


class SettingsOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    ha: HASettings = Field(default_factory=HASettings)
    home: HomeLocation = Field(default_factory=HomeLocation)
    disk_alert_pct: int = 70
    # Auto-purge thresholds. None = no auto-purge for that stream;
    # the manual /admin/purge endpoints still work either way.
    retention_readings_days: int | None = None
    retention_logs_days: int | None = None
    # Aggressive cutoff for level='debug' rows specifically — debug
    # dominates client_logs volume so we age it out faster than
    # warn/info/error (which follow retention_logs_days).
    retention_logs_debug_days: int | None = None


class SettingsUpdate(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    ha: HASettingsUpdate | None = None
    home: HomeLocation | None = None
    disk_alert_pct: int | None = None
    retention_readings_days: int | None = None
    retention_logs_days: int | None = None
    retention_logs_debug_days: int | None = None


# Forward-compat: AnalyticsWindow is referenced by Task #16 endpoints.
AnalyticsWindow = Literal["month", "3m", "year", "all"]


# ---------------------------------------------------------------------------
# Fillups
# ---------------------------------------------------------------------------


class FillupBase(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    vehicle_id: UUID
    fillup_date: datetime
    odo: float
    fuel_volume: float
    is_full: bool = True
    is_missed: bool = False
    price_total: Decimal | None = None
    price_per_unit: Decimal | None = None
    lat: float | None = None
    lon: float | None = None
    city: str | None = None
    station_id: int | None = None
    tank_number: int = 1
    fuel_type: int | None = None
    weather: str | None = None
    notes: str | None = None


class FillupCreate(FillupBase):
    pass


class FillupUpdate(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    fillup_date: datetime | None = None
    odo: float | None = None
    fuel_volume: float | None = None
    is_full: bool | None = None
    is_missed: bool | None = None
    price_total: Decimal | None = None
    price_per_unit: Decimal | None = None
    lat: float | None = None
    lon: float | None = None
    city: str | None = None
    station_id: int | None = None
    tank_number: int | None = None
    fuel_type: int | None = None
    weather: str | None = None
    notes: str | None = None


class FillupOut(FillupBase):
    id: UUID
    fuelio_guid: str | None = None
    mpg_reported: float | None = None
    mpg: float | None = None  # recomputed by API; None on partial fillups
    # Per-fillup weather observation (Task #78). NULL until the
    # backfiller / realtime-save fetches Open-Meteo. Distinct from
    # FillupBase.weather (Fuelio's free-text field).
    weather_temp_c: float | None = None
    weather_humidity_pct: int | None = None
    weather_precip_mm: float | None = None
    weather_wind_kph: float | None = None
    weather_code: int | None = None


# ---------------------------------------------------------------------------
# Expenses
# ---------------------------------------------------------------------------


class ExpenseBase(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    vehicle_id: UUID
    expense_date: date
    odo: float | None = None
    cost_type_id: int | None = None
    title: str | None = None
    notes: str | None = None
    cost: Decimal
    is_income: bool = False
    is_template: bool = False
    remind_odo: float | None = None
    remind_date: date | None = None
    repeat_odo: float | None = None
    repeat_months: float | None = None
    flag: int = 0


class ExpenseCreate(ExpenseBase):
    pass


class ExpenseUpdate(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    expense_date: date | None = None
    odo: float | None = None
    cost_type_id: int | None = None
    title: str | None = None
    notes: str | None = None
    cost: Decimal | None = None
    is_income: bool | None = None
    is_template: bool | None = None
    remind_odo: float | None = None
    remind_date: date | None = None
    repeat_odo: float | None = None
    repeat_months: float | None = None
    flag: int | None = None


class ExpenseOut(ExpenseBase):
    id: UUID
    fuelio_guid: str | None = None


class ExpenseCategoryOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: int
    name: str
    priority: int = 0
    color: str | None = None


# ---------------------------------------------------------------------------
# Maintenance
# ---------------------------------------------------------------------------


class ReminderOverdue(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    expense_id: UUID
    title: str | None
    category: str | None
    current_odo: float | None
    remind_odo: float | None
    remind_date: date | None
    miles_over: float | None
    days_over: int | None
    notes: str | None


class ReminderUpcoming(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    expense_id: UUID
    title: str | None
    category: str | None
    current_odo: float | None
    remind_odo: float | None
    remind_date: date | None
    miles_until: float | None
    days_until: int | None
    notes: str | None


class RemindersOut(BaseModel):
    overdue: list[ReminderOverdue]
    upcoming: list[ReminderUpcoming]


# ---------------------------------------------------------------------------
# Client logs (centralized log depot)
# ---------------------------------------------------------------------------


LogSource = Literal["phone", "web", "backend", "wican", "test"]
LogLevel = Literal["debug", "info", "warn", "error"]


class ClientLogEntry(BaseModel):
    """One inbound log line from a client. ``ts`` is optional — when present
    we store it as ``client_ts`` and let the server stamp ``ts`` itself so
    the tail view sees server-arrival order.

    Lenient validators on ``ts`` and ``vehicle_id`` coerce empty strings
    (which the Android Retrofit/kotlinx-serialization layer can produce
    when a field is "optional" but serialised regardless) to ``None``,
    so the client doesn't have to mint a real UUID just to hit the
    endpoint.
    """

    model_config = ConfigDict(from_attributes=True)

    ts: datetime | None = None
    # Defaults make the field optional on the wire — the Android client
    # sometimes omits these because kotlinx.serialization elides fields
    # equal to their declared default ("phone" for source, "info" for level).
    source: str = "phone"
    level: str = "info"
    message: str = Field(default="(empty)", max_length=4000)
    vehicle_id: UUID | None = None
    device_id: str | None = Field(default=None, max_length=128)
    context: dict[str, Any] | None = None

    @field_validator("ts", "vehicle_id", "device_id", mode="before")
    @classmethod
    def _empty_string_is_none(cls, v: Any) -> Any:
        if isinstance(v, str) and v.strip() == "":
            return None
        return v

    @field_validator("message", mode="before")
    @classmethod
    def _empty_message_placeholder(cls, v: Any) -> Any:
        if v is None or (isinstance(v, str) and v.strip() == ""):
            return "(empty)"
        if isinstance(v, str) and len(v) > 4000:
            return v[:4000]
        return v

    @field_validator("source", mode="before")
    @classmethod
    def _normalise_source(cls, v: Any) -> str:
        s = (str(v) if v is not None else "phone").strip().lower()
        if s in {"phone", "web", "backend", "wican", "test"}:
            return s
        return "phone"  # unknown source falls back rather than 422

    @field_validator("level", mode="before")
    @classmethod
    def _normalise_level(cls, v: Any) -> str:
        s = (str(v) if v is not None else "info").strip().lower()
        # Accept synonyms — Java's Level.WARNING, Python's WARNING, etc.
        m = {
            "warning": "warn", "warn": "warn",
            "err": "error", "error": "error",
            "info": "info",
            "debug": "debug", "trace": "debug", "verbose": "debug",
            "fatal": "error", "critical": "error",
        }
        return m.get(s, "info")

    def context_or_empty(self) -> dict[str, Any]:
        return self.context if self.context is not None else {}


class ClientLogIngest(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    entries: list[ClientLogEntry] = Field(min_length=1, max_length=500)


class ClientLogIngestResult(BaseModel):
    accepted: int


class ClientLogOut(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: UUID
    ts: datetime
    source: str
    level: str
    message: str
    vehicle_id: UUID | None = None
    device_id: str | None = None
    context: dict[str, Any] = Field(default_factory=dict)
    client_ts: datetime | None = None


class ClientLogDeleteResult(BaseModel):
    deleted: int
