"""Pure unit tests for the per-vehicle odometer offset (0020 migration).

``odometer_offset_km`` is the user-measured (PCM counter − dash cluster)
difference in km. It is a presentation/prefill correction only — nothing
server-side rewrites stored readings — so the only backend surface worth
testing is the plumbing: the row -> dict mapping, the VehicleOut shape,
and the VehicleUpdate PATCH contract.

No DB: ``_row_to_vehicle`` only subscripts its argument, so a plain dict
stands in for the asyncpg Record (same trick as test_mpg_recompute).
"""

from __future__ import annotations

from decimal import Decimal
from typing import Any
from uuid import uuid4

from pitstop.api.vehicles import _row_to_vehicle
from pitstop.schemas import VehicleOut, VehicleUpdate


def _row(**overrides: Any) -> dict[str, Any]:
    """A complete vehicles-JOIN row as ``_VEHICLE_SELECT`` returns it."""
    row: dict[str, Any] = {
        "id": uuid4(),
        "slug": "test-pilot",
        "name": "Test Pilot",
        "description": None,
        "make": "Honda",
        "model": "Pilot",
        "year": 2019,
        "vin": None,
        "plate": None,
        "fuelio_guid": None,
        "dist_unit": 1,
        "fuel_unit": 1,
        "consumption_unit": 1,
        "tank_count": 1,
        "tank1_type": None,
        "tank2_type": None,
        "tank1_capacity": None,
        "tank2_capacity": None,
        "active": True,
        "pid_profile_id": None,
        "latest_odo_km": 200000.0,
        "latest_odo_at": None,
        "purchase_price": None,
        "purchase_date": None,
        "epa_mpg_combined": None,
        "odometer_offset_km": None,
        "last_seen_at": None,
        "last_metric": None,
        "fuel_level_calibration_pct": None,
        "tank_capacity_l": None,
        "fuel_level_estimate_l": None,
        "fuel_level_estimate_updated_at": None,
        "fuel_level_empty_pct": None,
        "latest": {},
        "profile_name": None,
        "profile_description": None,
    }
    row.update(overrides)
    return row


# ---------------------------------------------------------------------------
# row -> dict mapping
# ---------------------------------------------------------------------------


def test_row_to_vehicle_passes_offset_through() -> None:
    # PCM reads ~51 km above the dash on this vehicle.
    out = _row_to_vehicle(_row(odometer_offset_km=51.5))  # type: ignore[arg-type]
    assert out["odometer_offset_km"] == 51.5


def test_row_to_vehicle_offset_null_stays_none() -> None:
    """NULL means "not calibrated" — distinct from a measured 0.0."""
    out = _row_to_vehicle(_row(odometer_offset_km=None))  # type: ignore[arg-type]
    assert out["odometer_offset_km"] is None


def test_row_to_vehicle_offset_zero_is_not_none() -> None:
    out = _row_to_vehicle(_row(odometer_offset_km=0.0))  # type: ignore[arg-type]
    assert out["odometer_offset_km"] == 0.0
    assert out["odometer_offset_km"] is not None


def test_row_to_vehicle_offset_negative() -> None:
    """A dash that reads ahead of the PCM is a legal (negative) offset."""
    out = _row_to_vehicle(_row(odometer_offset_km=-12.25))  # type: ignore[arg-type]
    assert out["odometer_offset_km"] == -12.25


def test_row_to_vehicle_offset_coerced_to_float() -> None:
    out = _row_to_vehicle(_row(odometer_offset_km=Decimal("51.5")))  # type: ignore[arg-type]
    assert isinstance(out["odometer_offset_km"], float)
    assert out["odometer_offset_km"] == 51.5


def test_row_to_vehicle_does_not_adjust_stored_odometer() -> None:
    """The offset is presentation-only — latest_odo_km stays raw PCM."""
    out = _row_to_vehicle(  # type: ignore[arg-type]
        _row(latest_odo_km=200000.0, odometer_offset_km=51.5)
    )
    assert out["latest_odo_km"] == 200000.0


# ---------------------------------------------------------------------------
# VehicleOut serialisation
# ---------------------------------------------------------------------------


def test_vehicle_out_serialises_offset() -> None:
    model = VehicleOut.model_validate(
        _row_to_vehicle(_row(odometer_offset_km=51.5))  # type: ignore[arg-type]
    )
    assert model.odometer_offset_km == 51.5
    assert model.model_dump()["odometer_offset_km"] == 51.5


def test_vehicle_out_offset_defaults_to_none() -> None:
    model = VehicleOut(id=uuid4(), slug="test-pilot", name="Test Pilot")
    assert model.odometer_offset_km is None
    assert "odometer_offset_km" in model.model_dump()


# ---------------------------------------------------------------------------
# VehicleUpdate — the PATCH allow-list is the schema's field set, and the
# endpoint builds its SET clause from model_dump(exclude_unset=True).
# ---------------------------------------------------------------------------


def test_vehicle_update_accepts_offset() -> None:
    body = VehicleUpdate.model_validate({"odometer_offset_km": 51.5})
    assert body.odometer_offset_km == 51.5


def test_vehicle_update_offset_is_patchable() -> None:
    fields = VehicleUpdate.model_validate(
        {"odometer_offset_km": 51.5}
    ).model_dump(exclude_unset=True)
    assert fields == {"odometer_offset_km": 51.5}


def test_vehicle_update_offset_omitted_when_unset() -> None:
    fields = VehicleUpdate.model_validate({"name": "Renamed"}).model_dump(
        exclude_unset=True
    )
    assert "odometer_offset_km" not in fields


def test_vehicle_update_offset_explicit_null_clears() -> None:
    """Sending an explicit null must reach the UPDATE as a NULL write."""
    fields = VehicleUpdate.model_validate(
        {"odometer_offset_km": None}
    ).model_dump(exclude_unset=True)
    assert fields == {"odometer_offset_km": None}


def test_vehicle_update_offset_accepts_int_and_negative() -> None:
    assert VehicleUpdate.model_validate(
        {"odometer_offset_km": 51}
    ).odometer_offset_km == 51.0
    assert VehicleUpdate.model_validate(
        {"odometer_offset_km": -12.25}
    ).odometer_offset_km == -12.25
