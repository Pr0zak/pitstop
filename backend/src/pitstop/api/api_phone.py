"""Phone-shaped aliases at ``/api/...``.

The Android app's PitstopApi was authored against ``api/fillups`` and
``api/logs`` paths but the rest of the backend mounts routers at the
top-level (``/fillups``, ``/logs``). This file adds thin slug-based
aliases so the existing APK keeps working without a re-release. The
aliases:

  POST /api/fillups          → resolves vehicle_slug, calls /fillups
  POST /api/logs             → already mounted at /logs (alias here)

The phone payload uses convenience names + units (gallons, miles,
total_price as float, vehicle_slug). The aliases convert to the
backend's canonical shape (vehicle_id, fuel_volume in the vehicle's
fuel_unit, price_total as Decimal) and delegate to the same SQL
insert path so the recompute / observability stays unified.
"""

from __future__ import annotations

import logging
from datetime import datetime, timezone
from decimal import Decimal
from typing import Any

import asyncpg
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel

from ..auth import require_ingest_token
from ..db.deps import get_pool
from ..schemas import FillupCreate
from .fillups import _apply_fillup_to_fuel_estimate, _maybe_calibrate_fuel_level

log = logging.getLogger(__name__)

router = APIRouter(prefix="/phone", tags=["phone"])


# Conversion constants. The phone always sends US units (gal + mi).
# Storage matches the vehicle's fuel_unit / dist_unit columns.
_GAL_TO_L = 3.785411784
_MI_TO_KM = 1.609344


class PhoneFillupRequest(BaseModel):
    """Payload from the Android app's PitstopApi.FillupRequest."""

    vehicle_slug: str
    ts: datetime
    gallons: float
    total_price: float
    odometer_mi: float | None = None
    partial: bool = False
    lat: float | None = None
    lon: float | None = None
    station_name: str | None = None
    notes: str | None = None


class PhoneFillupResponse(BaseModel):
    id: str


@router.post(
    "/fillups",
    response_model=PhoneFillupResponse,
    status_code=201,
    dependencies=[Depends(require_ingest_token)],
)
async def post_fillup_phone(
    body: PhoneFillupRequest,
    pool: asyncpg.Pool = Depends(get_pool),
) -> dict[str, Any]:
    """Phone-shaped fillup ingest.

    Resolves vehicle_slug → vehicle_id, converts US gal → vehicle's
    fuel_unit (gal-as-stored or litres), miles → vehicle's dist_unit,
    derives price_per_unit, and inserts. Returns the new row id.
    """
    async with pool.acquire() as conn:
        veh = await conn.fetchrow(
            "SELECT id, fuel_unit, dist_unit FROM vehicles WHERE slug = $1",
            body.vehicle_slug,
        )
        if veh is None:
            raise HTTPException(
                status_code=404, detail=f"vehicle slug '{body.vehicle_slug}' not found"
            )

        vehicle_id = veh["id"]
        fuel_unit = veh["fuel_unit"] or 1  # Fuelio: 0=l, 1=us_gal, 2=uk_gal
        dist_unit = veh["dist_unit"] or 1  # Fuelio: 0=km, 1=mi

        # Convert phone-side gal → stored unit. fuel_unit=1 (US gal)
        # passes through; fuel_unit=0 (litres) multiplies by 3.785;
        # 2 (UK gal) treated as US gal (close enough; users rarely
        # mix UK + US fuel input on the same vehicle in this dataset).
        fuel_volume = body.gallons
        if fuel_unit == 0:
            fuel_volume = body.gallons * _GAL_TO_L

        # Convert phone-side mi → stored unit. dist_unit=1 (mi)
        # passes through; 0 (km) multiplies by 1.609.
        odo: float | None = body.odometer_mi
        if odo is not None and dist_unit == 0:
            odo = body.odometer_mi * _MI_TO_KM

        if odo is None:
            # Fillups need an odometer reading — derive from the
            # vehicle's most recent fillup as a fallback so a phone
            # entry without OBD odo (eg. on an empty test vehicle)
            # doesn't 400.
            odo_fallback = await conn.fetchval(
                "SELECT odo FROM fillups WHERE vehicle_id = $1 "
                "ORDER BY fillup_date DESC LIMIT 1",
                vehicle_id,
            )
            odo = float(odo_fallback) if odo_fallback is not None else 0.0

        price_total = Decimal(str(round(body.total_price, 2)))
        price_per_unit: Decimal | None = None
        if fuel_volume > 0:
            price_per_unit = (price_total / Decimal(str(fuel_volume))).quantize(
                Decimal("0.0001")
            )

        # is_full = !partial. is_missed left at default false; the
        # phone bridge doesn't expose that yet.
        is_full = not body.partial

        try:
            new_id = await conn.fetchval(
                """
                INSERT INTO fillups (
                    vehicle_id, fillup_date, odo, fuel_volume,
                    is_full, is_missed, price_total, price_per_unit,
                    lat, lon, city, notes
                ) VALUES (
                    $1, $2, $3, $4,
                    $5, false, $6, $7,
                    $8, $9, $10, $11
                )
                RETURNING id
                """,
                vehicle_id,
                body.ts.astimezone(timezone.utc) if body.ts.tzinfo else body.ts,
                odo,
                fuel_volume,
                is_full,
                price_total,
                price_per_unit,
                body.lat,
                body.lon,
                body.station_name,
                body.notes,
            )
        except asyncpg.ForeignKeyViolationError as exc:
            raise HTTPException(
                status_code=400, detail="vehicle does not exist"
            ) from exc

    # Route the fillup through the same fuel-estimate + calibration helpers
    # that POST /fillups uses, so a pump-side phone quick-add resets the
    # hybrid estimate exactly like a web entry (ADR-013 parity). Build a
    # FillupCreate from the already-converted stored-unit values.
    fill = FillupCreate(
        vehicle_id=vehicle_id,
        fillup_date=(
            body.ts.astimezone(timezone.utc) if body.ts.tzinfo else body.ts
        ),
        odo=odo,
        fuel_volume=fuel_volume,
        is_full=is_full,
        price_total=price_total,
        price_per_unit=price_per_unit,
        lat=body.lat,
        lon=body.lon,
        city=body.station_name,
        notes=body.notes,
    )
    # Best-effort: the fillup row is already committed above, so a failure in
    # calibration/estimate must NOT 500 — otherwise the phone retries and we
    # get duplicate fillups (the 2026-06-20 incident). Workers reconcile.
    try:
        if is_full:
            await _maybe_calibrate_fuel_level(pool, vehicle_id, fill.fillup_date)
        await _apply_fillup_to_fuel_estimate(pool, fill)
    except Exception:
        log.exception("phone fillup post-insert enrichment failed id=%s", new_id)

    log.info(
        "phone fillup ingested vehicle_slug=%s id=%s gallons=%.3f total=$%s",
        body.vehicle_slug,
        new_id,
        body.gallons,
        price_total,
    )
    return {"id": str(new_id)}
