"""Vehicles CRUD endpoints."""

from __future__ import annotations

import logging
from typing import Any
from uuid import UUID

import asyncpg
from fastapi import APIRouter, Depends, HTTPException
from fastapi import Path as FastAPIPath

from ..auth import require_ingest_token, require_query_token
from ..db.deps import get_pool
from ..schemas import VehicleCreate, VehicleOut, VehicleUpdate

log = logging.getLogger(__name__)

router = APIRouter(prefix="/vehicles", tags=["vehicles"])


_VEHICLE_SELECT = """
    SELECT
        v.id, v.slug, v.name, v.description,
        v.make, v.model, v.year, v.vin, v.plate, v.fuelio_guid,
        v.dist_unit, v.fuel_unit, v.consumption_unit,
        v.tank_count, v.tank1_type, v.tank2_type,
        v.tank1_capacity, v.tank2_capacity,
        v.active, v.pid_profile_id,
        v.latest_odo_km, v.latest_odo_at,
        v.fuel_level_calibration_pct,
        v.purchase_price, v.purchase_date,
        v.epa_mpg_combined,
        s.last_seen_at, s.last_metric, COALESCE(s.latest, '{}'::jsonb) AS latest,
        p.name AS profile_name, p.description AS profile_description
      FROM vehicles v
      LEFT JOIN vehicle_state s ON s.vehicle_id = v.id
      LEFT JOIN pid_profiles p  ON p.id = v.pid_profile_id
"""


def _normalize_latest(
    latest: dict[str, Any], calibration_pct: float
) -> dict[str, Any]:
    """Apply per-vehicle fuel_level calibration to the JSONB ``latest`` map.

    Honda (and others) clip PID 0x2F below 100 even on a physically full
    tank. The fillup endpoint snapshots the highest reading near each
    is_full=true fillup into ``vehicles.fuel_level_calibration_pct``; we
    divide here so widget / hero card render 100 % on a full tank.
    Raw value is preserved under ``value_num_raw`` for the debug view."""
    if calibration_pct <= 0 or calibration_pct >= 100:
        return latest
    entry = latest.get("fuel_level") if isinstance(latest, dict) else None
    if not isinstance(entry, dict):
        return latest
    raw = entry.get("value_num")
    if not isinstance(raw, (int, float)):
        return latest
    normalized = min(100.0, float(raw) / calibration_pct * 100.0)
    out = dict(latest)
    out["fuel_level"] = {**entry, "value_num": normalized, "value_num_raw": float(raw)}
    return out


def _row_to_vehicle(row: asyncpg.Record) -> dict[str, Any]:
    """Re-shape a vehicle row into the VehicleOut JSON shape."""
    out: dict[str, Any] = {
        "id": row["id"],
        "slug": row["slug"],
        "name": row["name"],
        "description": row["description"],
        "make": row["make"],
        "model": row["model"],
        "year": row["year"],
        "vin": row["vin"],
        "plate": row["plate"],
        "fuelio_guid": row["fuelio_guid"],
        "dist_unit": row["dist_unit"],
        "fuel_unit": row["fuel_unit"],
        "consumption_unit": row["consumption_unit"],
        "tank_count": row["tank_count"],
        "tank1_type": row["tank1_type"],
        "tank2_type": row["tank2_type"],
        "tank1_capacity": row["tank1_capacity"],
        "tank2_capacity": row["tank2_capacity"],
        "active": row["active"],
        "pid_profile_id": row["pid_profile_id"],
        "latest_odo_km": row["latest_odo_km"],
        "latest_odo_at": row["latest_odo_at"],
        "purchase_price": (
            float(row["purchase_price"]) if row["purchase_price"] is not None else None
        ),
        "purchase_date": row["purchase_date"],
        "epa_mpg_combined": (
            float(row["epa_mpg_combined"]) if row["epa_mpg_combined"] is not None else None
        ),
        "last_seen_at": row["last_seen_at"],
        "last_metric": row["last_metric"],
        "fuel_level_calibration_pct": (
            float(row["fuel_level_calibration_pct"])
            if row["fuel_level_calibration_pct"] is not None
            else 100.0
        ),
        "latest": _normalize_latest(
            row["latest"] or {},
            float(row["fuel_level_calibration_pct"] or 100.0),
        ),
        "pid_profile": (
            {"name": row["profile_name"], "description": row["profile_description"]}
            if row["profile_name"] is not None
            else None
        ),
    }
    return out


@router.get(
    "",
    response_model=list[VehicleOut],
    dependencies=[Depends(require_query_token)],
)
async def list_vehicles(pool: asyncpg.Pool = Depends(get_pool)) -> list[dict[str, Any]]:
    async with pool.acquire() as conn:
        rows = await conn.fetch(_VEHICLE_SELECT + " ORDER BY v.name ASC")
        out = [_row_to_vehicle(r) for r in rows]
        await _smooth_fuel_levels(conn, out)
    return out


async def _smooth_fuel_levels(
    conn: asyncpg.Connection, vehicles: list[dict[str, Any]]
) -> None:
    """Replace each vehicle's latest.fuel_level.value_num with a
    physically-grounded smoothed estimate.

    Real fuel can only *decrease* while the engine is running, capped
    at the engine's worst-case consumption rate. Any rise during
    driving is fuel slosh in the tank — the float arm moves with the
    fuel, not with the actual level. Honda Pilot's PID 0x2F bounces
    ±15-25 % from this in a single minute. The factory dashboard gauge
    runs a comparable filter (heavy IIR + slew-rate limit + fillup
    detector) which is why the needle stays calm while OBD scanners
    bounce visibly.

    Algorithm: replay the last N samples through a state machine —
      - drop: rate-limited (max SLEW_PCT_PER_MIN raw % per minute)
      - rise (small, < FILLUP_THRESHOLD raw %): rejected as slosh
      - rise (large, >= FILLUP_THRESHOLD raw %): accepted only if the
        previous sample was >= FILLUP_QUIET_MIN minutes ago (engine
        off in between = fillup), else rejected.
    The output is the state at the most recent sample, normalized
    against the per-vehicle calibration ceiling for display."""
    SLEW_PCT_PER_MIN = 0.5
    FILLUP_THRESHOLD = 5.0
    FILLUP_QUIET_MIN = 5.0
    MAX_SAMPLES = 400

    for v in vehicles:
        latest = v.get("latest")
        if not isinstance(latest, dict):
            continue
        entry = latest.get("fuel_level")
        if not isinstance(entry, dict) or entry.get("value_num") is None:
            continue
        samples = await conn.fetch(
            """
            SELECT time, value_num FROM pid_readings
             WHERE vehicle_id = $1
               AND metric = 'fuel_level'
               AND value_num IS NOT NULL
             ORDER BY time DESC
             LIMIT $2
            """,
            v["id"], MAX_SAMPLES,
        )
        if not samples:
            continue
        # SQL returned newest-first; replay oldest-first.
        samples = list(reversed(samples))
        ENGINE_OFF_GAP_MIN = 15.0
        state = float(samples[0]["value_num"])
        last_t = samples[0]["time"]
        for s in samples[1:]:
            t = s["time"]
            val = float(s["value_num"])
            dt_min = max(0.0, (t - last_t).total_seconds() / 60.0)
            last_t = t
            diff = val - state
            if dt_min >= ENGINE_OFF_GAP_MIN:
                # Engine-off period (WiCAN sleeps with the CAN bus).
                # Three cases on resume:
                #   - val >= state + FILLUP_THRESHOLD → fillup; re-anchor up.
                #   - val <= state → real consumption + maybe slosh; accept down.
                #   - val ~= state (small rise) → slosh on restart; keep.
                if diff >= FILLUP_THRESHOLD:
                    state = val
                elif val < state:
                    state = val
                # else: small rise after restart, slosh, keep state.
            elif diff >= FILLUP_THRESHOLD and dt_min >= FILLUP_QUIET_MIN:
                # Mid-gap fillup (rare — phone bridge sometimes goes
                # quiet briefly during a fillup). Still requires the
                # FILLUP_QUIET_MIN guard so a single bouncing sample
                # while driving never re-anchors upward.
                state = val
            elif diff > 0:
                # Small rise during active sampling — pure slosh.
                pass
            else:
                # Decrease. Slew-limited so a momentary dip can't
                # yank the displayed value down by 15+ % in a minute.
                max_drop = SLEW_PCT_PER_MIN * dt_min
                state = max(val, state - max_drop)
        # state is now the OEM-style smoothed raw value.
        cal = float(v.get("fuel_level_calibration_pct") or 100.0)
        smoothed = (
            min(100.0, state / cal * 100.0)
            if 0 < cal <= 100
            else state
        )
        new_entry = dict(entry)
        new_entry["value_num"] = smoothed
        new_entry["value_num_raw"] = state
        latest = dict(latest)
        latest["fuel_level"] = new_entry
        v["latest"] = latest


@router.get(
    "/{vehicle_id}",
    response_model=VehicleOut,
    dependencies=[Depends(require_query_token)],
)
async def get_vehicle(
    vehicle_id: UUID = FastAPIPath(...),
    pool: asyncpg.Pool = Depends(get_pool),
) -> dict[str, Any]:
    async with pool.acquire() as conn:
        row = await conn.fetchrow(_VEHICLE_SELECT + " WHERE v.id = $1", vehicle_id)
        if row is None:
            raise HTTPException(status_code=404, detail="vehicle not found")
        out = _row_to_vehicle(row)
        await _smooth_fuel_levels(conn, [out])
    return out


@router.post(
    "",
    response_model=VehicleOut,
    status_code=201,
    dependencies=[Depends(require_ingest_token)],
)
async def create_vehicle(
    body: VehicleCreate,
    pool: asyncpg.Pool = Depends(get_pool),
) -> dict[str, Any]:
    async with pool.acquire() as conn, conn.transaction():
        try:
            vid = await conn.fetchval(
                """
                INSERT INTO vehicles (
                    slug, name, description, make, model, year,
                    vin, plate, fuelio_guid,
                    dist_unit, fuel_unit, consumption_unit,
                    tank_count, tank1_type, tank2_type,
                    tank1_capacity, tank2_capacity,
                    active, pid_profile_id
                ) VALUES (
                    $1, $2, $3, $4, $5, $6,
                    $7, $8, $9,
                    $10, $11, $12,
                    $13, $14, $15,
                    $16, $17,
                    $18, $19
                )
                RETURNING id
                """,
                body.effective_slug(), body.name, body.description, body.make, body.model,
                body.year, body.vin, body.plate, body.fuelio_guid,
                body.dist_unit, body.fuel_unit, body.consumption_unit,
                body.tank_count, body.tank1_type, body.tank2_type,
                body.tank1_capacity, body.tank2_capacity,
                body.active, body.pid_profile_id,
            )
        except asyncpg.UniqueViolationError as exc:
            raise HTTPException(
                status_code=409, detail=f"unique constraint: {exc!s}"
            ) from exc
        except asyncpg.CheckViolationError as exc:
            raise HTTPException(
                status_code=400, detail=f"check violation: {exc!s}"
            ) from exc
        await conn.execute(
            "INSERT INTO vehicle_state (vehicle_id) VALUES ($1) "
            "ON CONFLICT (vehicle_id) DO NOTHING",
            vid,
        )
        row = await conn.fetchrow(_VEHICLE_SELECT + " WHERE v.id = $1", vid)
    return _row_to_vehicle(row)  # type: ignore[arg-type]


@router.patch(
    "/{vehicle_id}",
    response_model=VehicleOut,
    dependencies=[Depends(require_ingest_token)],
)
async def update_vehicle(
    body: VehicleUpdate,
    vehicle_id: UUID = FastAPIPath(...),
    pool: asyncpg.Pool = Depends(get_pool),
) -> dict[str, Any]:
    fields = body.model_dump(exclude_unset=True)
    if not fields:
        # No-op update; just return the current row.
        async with pool.acquire() as conn:
            row = await conn.fetchrow(
                _VEHICLE_SELECT + " WHERE v.id = $1", vehicle_id
            )
        if row is None:
            raise HTTPException(status_code=404, detail="vehicle not found")
        return _row_to_vehicle(row)

    set_parts: list[str] = []
    values: list[Any] = []
    for i, (k, v) in enumerate(fields.items(), start=2):
        set_parts.append(f"{k} = ${i}")
        values.append(v)
    sql = f"UPDATE vehicles SET {', '.join(set_parts)} WHERE id = $1 RETURNING id"
    async with pool.acquire() as conn:
        try:
            updated = await conn.fetchval(sql, vehicle_id, *values)
        except asyncpg.UniqueViolationError as exc:
            raise HTTPException(
                status_code=409, detail=f"unique constraint: {exc!s}"
            ) from exc
        except asyncpg.CheckViolationError as exc:
            raise HTTPException(
                status_code=400, detail=f"check violation: {exc!s}"
            ) from exc
        if updated is None:
            raise HTTPException(status_code=404, detail="vehicle not found")
        row = await conn.fetchrow(_VEHICLE_SELECT + " WHERE v.id = $1", vehicle_id)
    return _row_to_vehicle(row)  # type: ignore[arg-type]


@router.delete(
    "/{vehicle_id}",
    status_code=204,
    dependencies=[Depends(require_ingest_token)],
)
async def delete_vehicle(
    vehicle_id: UUID = FastAPIPath(...),
    pool: asyncpg.Pool = Depends(get_pool),
) -> None:
    async with pool.acquire() as conn:
        result = await conn.execute("DELETE FROM vehicles WHERE id = $1", vehicle_id)
    # asyncpg returns "DELETE 1" or "DELETE 0".
    if result.endswith(" 0"):
        raise HTTPException(status_code=404, detail="vehicle not found")
