"""Vehicles CRUD endpoints."""

from __future__ import annotations

import logging
from datetime import UTC, datetime
from typing import Any
from uuid import UUID

import asyncpg
from fastapi import APIRouter, Depends, HTTPException
from fastapi import Path as FastAPIPath

from ..auth import require_ingest_token, require_query_token
from ..db.deps import get_pool
from ..schemas import VehicleCreate, VehicleOut, VehicleUpdate
from ..services.fuel_state import CLUSTER_METRIC, SNAP_SOURCES, fuel_display

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
        v.fuel_level_empty_pct,
        v.tank_capacity_l,
        v.fuel_level_estimate_l,
        v.fuel_level_estimate_updated_at,
        v.purchase_price, v.purchase_date,
        v.epa_mpg_combined,
        v.odometer_offset_km,
        v.redline_rpm,
        v.tire_placard_front_kpa, v.tire_placard_rear_kpa,
        s.last_seen_at, s.last_metric, COALESCE(s.latest, '{}'::jsonb) AS latest,
        p.name AS profile_name, p.description AS profile_description
      FROM vehicles v
      LEFT JOIN vehicle_state s ON s.vehicle_id = v.id
      LEFT JOIN pid_profiles p  ON p.id = v.pid_profile_id
"""


def _normalize_latest(
    latest: dict[str, Any],
    calibration_pct: float,
    empty_pct: float | None = None,
) -> dict[str, Any]:
    """Apply per-vehicle fuel_level calibration to the JSONB ``latest`` map.

    Honda (and others) clip PID 0x2F below 100 even on a physically full
    tank, AND stop above 0 on an empty one. The fillup endpoint snapshots
    the full-tank ceiling into ``fuel_level_calibration_pct`` and solves
    the dry reading into ``fuel_level_empty_pct``; we map between them so
    the widget / hero card read 100 % full and 0 % dry. Raw value is
    preserved under ``value_num_raw`` for the debug view."""
    if calibration_pct <= 0 or calibration_pct >= 100:
        return latest
    entry = latest.get("fuel_level") if isinstance(latest, dict) else None
    if not isinstance(entry, dict):
        return latest
    raw = entry.get("value_num")
    if not isinstance(raw, (int, float)):
        return latest
    low = 0.0
    if empty_pct is not None and 0.0 <= float(empty_pct) < calibration_pct:
        low = float(empty_pct)
    normalized = min(
        100.0, max(0.0, (float(raw) - low) / (calibration_pct - low) * 100.0)
    )
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
        # PCM counter minus dash cluster, in km. Presentation-only — raw
        # pid_readings are never rewritten (0020 migration).
        "odometer_offset_km": (
            float(row["odometer_offset_km"])
            if row["odometer_offset_km"] is not None
            else None
        ),
        "redline_rpm": row["redline_rpm"],
        "tire_placard_front_kpa": (
            float(row["tire_placard_front_kpa"])
            if row["tire_placard_front_kpa"] is not None
            else None
        ),
        "tire_placard_rear_kpa": (
            float(row["tire_placard_rear_kpa"])
            if row["tire_placard_rear_kpa"] is not None
            else None
        ),
        "last_seen_at": row["last_seen_at"],
        "last_metric": row["last_metric"],
        "fuel_level_calibration_pct": (
            float(row["fuel_level_calibration_pct"])
            if row["fuel_level_calibration_pct"] is not None
            else 100.0
        ),
        "tank_capacity_l": (
            float(row["tank_capacity_l"])
            if row["tank_capacity_l"] is not None
            else None
        ),
        "fuel_level_estimate_l": (
            float(row["fuel_level_estimate_l"])
            if row["fuel_level_estimate_l"] is not None
            else None
        ),
        "fuel_level_estimate_updated_at": row["fuel_level_estimate_updated_at"],
        "fuel_level_empty_pct": (
            float(row["fuel_level_empty_pct"])
            if row["fuel_level_empty_pct"] is not None
            else None
        ),
        "latest": _normalize_latest(
            row["latest"] or {},
            float(row["fuel_level_calibration_pct"] or 100.0),
            (
                float(row["fuel_level_empty_pct"])
                if row["fuel_level_empty_pct"] is not None
                else None
            ),
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
        await _attach_fuel_display(conn, out)
    return out


# How far back to look for the cluster's last reading. Bounded so a vehicle
# that has never reported ``fuel_level_l`` doesn't walk every (compressed)
# chunk of its history on each GET; after a week parked the estimate —
# snapped to the cluster at the last park — says the same thing.
_CLUSTER_LOOKBACK = "7 days"


async def _attach_fuel_display(
    conn: asyncpg.Connection, vehicles: list[dict[str, Any]]
) -> None:
    """Add the cluster's fuel quantity and the shared display choice.

    ``fuel_cluster_l`` / ``fuel_cluster_at``: the newest ``fuel_level_l``
    (instrument-cluster litres, Honda DID 7029) captured by the PHONE
    (``SNAP_SOURCES``). The dongle's own rows are left out on purpose:
    firmware 6 can still land one stale snapshot after key-off, stamped at
    receipt, and nothing in a single row tells it apart from a live one —
    a "differs from the previous wican value" test does not either, since
    the replayed frame is from earlier in the same drive. Phone rows exist
    only when the ECU answered and carry its answer time (ADR-025).

    ``fuel_display_*``: see ``services.fuel_state.fuel_display``.
    """
    now = datetime.now(UTC)
    for v in vehicles:
        tank_l = v.get("tank_capacity_l")
        row = await conn.fetchrow(
            f"""
            SELECT time, value_num FROM pid_readings
             WHERE vehicle_id = $1
               AND metric = $2
               AND value_num IS NOT NULL
               AND source = ANY($3::text[])
               AND time > now() - interval '{_CLUSTER_LOOKBACK}'
             ORDER BY time DESC
             LIMIT 1
            """,
            v["id"], CLUSTER_METRIC, list(SNAP_SOURCES),
        )
        cluster_l: float | None = None
        cluster_at: datetime | None = None
        if row is not None:
            val = float(row["value_num"])
            # Outside 0..tank+5 % is a bad decode, not a level.
            if val >= 0.0 and (tank_l is None or val <= float(tank_l) * 1.05):
                cluster_l, cluster_at = val, row["time"]
        disp = fuel_display(
            cluster_l=cluster_l,
            cluster_at=cluster_at,
            estimate_l=v.get("fuel_level_estimate_l"),
            estimate_at=v.get("fuel_level_estimate_updated_at"),
            now=now,
        )
        v["fuel_cluster_l"] = cluster_l
        v["fuel_cluster_at"] = cluster_at
        v["fuel_display_l"] = disp.liters
        v["fuel_display_source"] = disp.source
        v["fuel_display_at"] = disp.at


_SMOOTH_MAX_SAMPLES = 80


async def _smooth_fuel_levels(
    conn: asyncpg.Connection, vehicles: list[dict[str, Any]]
) -> None:
    """Replace each vehicle's latest.fuel_level.value_num with a
    physically-grounded smoothed estimate.

    Real fuel can only *decrease* while the engine is running. Any rise
    during sampling is fuel slosh — the float arm moves with the fuel,
    not with the actual level. Honda Pilot's PID 0x2F bounces ±15-25 %
    in a single minute. We need a filter that's robust to those dips
    in both directions.

    Approach: take the **75th percentile** of recent fuel_level samples.
    The high end of the sample distribution sits at "level when not in
    a deep slosh dip", which is closest to truth (the float arm only
    DROPS into momentary dips, never rises above the actual level).
    The 75th percentile rather than the max gives a little robustness
    against the rare upward slosh too.

    Window: 60 minutes for the primary read, falling back to the last
    50 samples regardless of age when parked (WiCAN goes quiet with
    the CAN bus asleep)."""

    for v in vehicles:
        latest = v.get("latest")
        if not isinstance(latest, dict):
            continue
        entry = latest.get("fuel_level")
        if not isinstance(entry, dict) or entry.get("value_num") is None:
            continue
        rows = await conn.fetch(
            """
            SELECT value_num FROM pid_readings
             WHERE vehicle_id = $1
               AND metric = 'fuel_level'
               AND value_num IS NOT NULL
               AND time > now() - interval '60 minutes'
             ORDER BY time DESC
             LIMIT $2
            """,
            v["id"], _SMOOTH_MAX_SAMPLES,
        )
        if not rows or len(rows) < 3:
            # Parked / no recent samples — fall back to the most recent
            # 50 samples regardless of age.
            rows = await conn.fetch(
                """
                SELECT value_num FROM pid_readings
                 WHERE vehicle_id = $1
                   AND metric = 'fuel_level'
                   AND value_num IS NOT NULL
                 ORDER BY time DESC
                 LIMIT 50
                """,
                v["id"],
            )
        if not rows or len(rows) < 3:
            continue
        vals = sorted(float(r["value_num"]) for r in rows)
        # 75th percentile — robust to slosh dips below and the rare
        # slosh spike above, while tracking real consumption as the
        # whole distribution drifts down between fillups.
        idx = int(len(vals) * 0.75)
        if idx >= len(vals):
            idx = len(vals) - 1
        state = vals[idx]
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
        await _attach_fuel_display(conn, [out])
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
                    active, pid_profile_id, redline_rpm,
                    tire_placard_front_kpa, tire_placard_rear_kpa
                ) VALUES (
                    $1, $2, $3, $4, $5, $6,
                    $7, $8, $9,
                    $10, $11, $12,
                    $13, $14, $15,
                    $16, $17,
                    $18, $19, $20,
                    $21, $22
                )
                RETURNING id
                """,
                body.effective_slug(), body.name, body.description, body.make, body.model,
                body.year, body.vin, body.plate, body.fuelio_guid,
                body.dist_unit, body.fuel_unit, body.consumption_unit,
                body.tank_count, body.tank1_type, body.tank2_type,
                body.tank1_capacity, body.tank2_capacity,
                body.active, body.pid_profile_id, body.redline_rpm,
                body.tire_placard_front_kpa, body.tire_placard_rear_kpa,
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
        out = _row_to_vehicle(row)  # type: ignore[arg-type]
        await _attach_fuel_display(conn, [out])
    return out


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
            out = _row_to_vehicle(row)
            await _attach_fuel_display(conn, [out])
        return out

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
        out = _row_to_vehicle(row)  # type: ignore[arg-type]
        await _attach_fuel_display(conn, [out])
    return out


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
