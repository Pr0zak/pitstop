"""Pydantic v2 schemas for the pitstop REST API.

All models share ``model_config = ConfigDict(from_attributes=True)`` so they
can be constructed directly from asyncpg ``Record`` rows (which support both
attribute and item access).
"""

from __future__ import annotations

import re
from datetime import datetime
from typing import Any, Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, field_validator

# ---------------------------------------------------------------------------
# Vehicles
# ---------------------------------------------------------------------------


_SLUG_RE = re.compile(r"^[a-z0-9_-]+$")


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
    pass


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
    category: str | None
    notes: str | None


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


class SettingsUpdate(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    ha: HASettingsUpdate | None = None
    home: HomeLocation | None = None
    disk_alert_pct: int | None = None


# Forward-compat: AnalyticsWindow is referenced by Task #16 endpoints.
AnalyticsWindow = Literal["month", "3m", "year", "all"]
