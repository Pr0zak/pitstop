"""Fillups CRUD + per-fillup recomputed MPG.

The "recomputed" MPG follows Fuelio's rule: MPG is undefined on partial
fills; partial volumes are rolled into the next full fill. We never trust
the source's ``mpg_reported`` for math — we expose it side-by-side for
comparison only.
"""

from __future__ import annotations

import logging
from datetime import datetime
from decimal import Decimal
from typing import Any
from uuid import UUID

import asyncpg
from fastapi import APIRouter, Depends, HTTPException, Query, Response
from fastapi import Path as FastAPIPath

from ..auth import require_ingest_token, require_query_token
from ..db.deps import get_pool
from ..schemas import FillupCreate, FillupOut, FillupUpdate
from ..services.fuel_state import derive_empty_pct, persist_estimate, reset_on_fillup

log = logging.getLogger(__name__)

router = APIRouter(prefix="/fillups", tags=["fillups"])


_FILLUP_COLS = (
    "id, vehicle_id, fuelio_guid, fillup_date, odo, fuel_volume, "
    "is_full, is_missed, price_total, price_per_unit, mpg_reported, "
    "lat, lon, city, station_id, tank_number, fuel_type, weather, notes, "
    "weather_temp_c, weather_humidity_pct, weather_precip_mm, "
    "weather_wind_kph, weather_code"
)


def _row_to_fillup(row: asyncpg.Record) -> dict[str, Any]:
    return {
        "id": row["id"],
        "vehicle_id": row["vehicle_id"],
        "fuelio_guid": row["fuelio_guid"],
        "fillup_date": row["fillup_date"],
        "odo": row["odo"],
        "fuel_volume": row["fuel_volume"],
        "is_full": row["is_full"],
        "is_missed": row["is_missed"],
        "price_total": row["price_total"],
        "price_per_unit": row["price_per_unit"],
        "mpg_reported": row["mpg_reported"],
        "lat": row["lat"],
        "lon": row["lon"],
        "city": row["city"],
        "station_id": row["station_id"],
        "tank_number": row["tank_number"],
        "fuel_type": row["fuel_type"],
        "weather": row["weather"],
        "notes": row["notes"],
        "weather_temp_c": row["weather_temp_c"],
        "weather_humidity_pct": row["weather_humidity_pct"],
        "weather_precip_mm": row["weather_precip_mm"],
        "weather_wind_kph": row["weather_wind_kph"],
        "weather_code": row["weather_code"],
    }


def compute_recomputed_mpg(rows: list[dict[str, Any]]) -> dict[UUID, float | None]:
    """Return per-fillup recomputed MPG.

    Fuelio rule: only full fills get an MPG number. Partial fills accumulate
    volume forward into the next full fill. The first chronological full fill
    after the very first record has MPG; the very first record has None.

    Args:
        rows: list of {id, fillup_date, odo, fuel_volume, is_full} dicts —
            one vehicle's fills.

    Mutually-distance-exclusive fills (``exclude_distance``) are not modelled
    here; the importer surfaces them but they're rare in practice.
    """
    # Sort by date asc.
    ordered = sorted(rows, key=lambda r: (r["fillup_date"], r["odo"]))
    out: dict[UUID, float | None] = {}
    last_full_odo: float | None = None
    pending_volume: float = 0.0
    for row in ordered:
        is_full = bool(row["is_full"])
        is_missed = bool(row.get("is_missed", False))
        odo = float(row["odo"])
        vol = float(row["fuel_volume"])
        if is_missed:
            # The user skipped recording the previous fillup, so the
            # distance-since-last-full is real but the volume-since-last-
            # full is missing. We can't compute a valid MPG here. Reset
            # the chain so the *next* fill has a clean baseline.
            out[row["id"]] = None
            last_full_odo = odo
            pending_volume = 0.0
            continue
        if not is_full:
            # Partial: roll volume forward; no MPG produced.
            pending_volume += vol
            out[row["id"]] = None
            continue
        # Full fillup.
        total_vol = pending_volume + vol
        if last_full_odo is None:
            # First full record we see — no prior odo to diff against.
            out[row["id"]] = None
        else:
            distance = odo - last_full_odo
            if distance > 0 and total_vol > 0:
                out[row["id"]] = round(distance / total_vol, 3)
            else:
                out[row["id"]] = None
        last_full_odo = odo
        pending_volume = 0.0
    return out


async def _attach_recomputed_mpg(
    pool: asyncpg.Pool, rows: list[dict[str, Any]]
) -> None:
    """For each row, look up its vehicle's full fill chain and inject ``mpg``."""
    if not rows:
        return
    by_vehicle: dict[UUID, list[dict[str, Any]]] = {}
    for r in rows:
        by_vehicle.setdefault(r["vehicle_id"], []).append(r)
    async with pool.acquire() as conn:
        for vid in by_vehicle:
            chain = await conn.fetch(
                "SELECT id, fillup_date, odo, fuel_volume, is_full, is_missed "
                "FROM fillups WHERE vehicle_id = $1 ORDER BY fillup_date ASC",
                vid,
            )
            mpg_map = compute_recomputed_mpg([dict(r) for r in chain])
            for r in by_vehicle[vid]:
                r["mpg"] = mpg_map.get(r["id"])


@router.get(
    "",
    response_model=list[FillupOut],
    dependencies=[Depends(require_query_token)],
)
async def list_fillups(
    response: Response,
    vehicle_id: UUID | None = Query(default=None),
    from_: datetime | None = Query(default=None, alias="from"),
    to: datetime | None = Query(default=None),
    limit: int = Query(default=50, ge=1, le=1000),
    offset: int = Query(default=0, ge=0),
    pool: asyncpg.Pool = Depends(get_pool),
) -> list[dict[str, Any]]:
    where: list[str] = []
    args: list[Any] = []
    if vehicle_id is not None:
        args.append(vehicle_id)
        where.append(f"vehicle_id = ${len(args)}")
    if from_ is not None:
        args.append(from_)
        where.append(f"fillup_date >= ${len(args)}")
    if to is not None:
        args.append(to)
        where.append(f"fillup_date <= ${len(args)}")
    where_sql = (" WHERE " + " AND ".join(where)) if where else ""
    async with pool.acquire() as conn:
        total = await conn.fetchval(
            f"SELECT count(*) FROM fillups{where_sql}", *args
        )
        args.extend([limit, offset])
        rows = await conn.fetch(
            f"SELECT {_FILLUP_COLS} FROM fillups{where_sql} "
            f"ORDER BY fillup_date DESC "
            f"LIMIT ${len(args) - 1} OFFSET ${len(args)}",
            *args,
        )
    out = [_row_to_fillup(r) for r in rows]
    await _attach_recomputed_mpg(pool, out)
    response.headers["X-Total-Count"] = str(int(total or 0))
    return out


@router.get(
    "/{fillup_id}",
    response_model=FillupOut,
    dependencies=[Depends(require_query_token)],
)
async def get_fillup(
    fillup_id: UUID = FastAPIPath(...),
    pool: asyncpg.Pool = Depends(get_pool),
) -> dict[str, Any]:
    async with pool.acquire() as conn:
        row = await conn.fetchrow(
            f"SELECT {_FILLUP_COLS} FROM fillups WHERE id = $1", fillup_id
        )
    if row is None:
        raise HTTPException(status_code=404, detail="fillup not found")
    out = _row_to_fillup(row)
    await _attach_recomputed_mpg(pool, [out])
    return out


@router.post(
    "",
    response_model=FillupOut,
    status_code=201,
    dependencies=[Depends(require_ingest_token)],
)
async def create_fillup(
    body: FillupCreate,
    pool: asyncpg.Pool = Depends(get_pool),
) -> dict[str, Any]:
    async with pool.acquire() as conn:
        try:
            row = await conn.fetchrow(
                f"""
                INSERT INTO fillups (
                    vehicle_id, fillup_date, odo, fuel_volume,
                    is_full, is_missed, price_total, price_per_unit,
                    lat, lon, city, station_id, tank_number,
                    fuel_type, weather, notes
                ) VALUES (
                    $1, $2, $3, $4,
                    $5, $6, $7, $8,
                    $9, $10, $11, $12, $13,
                    $14, $15, $16
                )
                RETURNING {_FILLUP_COLS}
                """,
                body.vehicle_id, body.fillup_date, body.odo, body.fuel_volume,
                body.is_full, body.is_missed,
                _decimal(body.price_total), _decimal(body.price_per_unit),
                body.lat, body.lon, body.city, body.station_id, body.tank_number,
                body.fuel_type, body.weather, body.notes,
            )
        except asyncpg.ForeignKeyViolationError as exc:
            raise HTTPException(
                status_code=400, detail="vehicle does not exist"
            ) from exc
    assert row is not None
    out = _row_to_fillup(row)
    # Post-insert enrichment + side effects are best-effort. The fillup is
    # already committed, so a failure here must NOT surface as a 5xx — that
    # makes the client retry and create duplicate rows (the 2026-06-20
    # incident: a SQL bug in _maybe_calibrate_fuel_level 500'd every retry
    # AFTER the row committed → 4 identical fillups). The fuel-state workers
    # reconcile the estimate on their next pass regardless.
    try:
        await _attach_recomputed_mpg(pool, [out])
        if body.is_full:
            await _maybe_calibrate_fuel_level(pool, body.vehicle_id, body.fillup_date)
            await _maybe_calibrate_fuel_empty(pool, body)
        await _apply_fillup_to_fuel_estimate(pool, body)
    except Exception:
        log.exception("fillup post-insert enrichment failed id=%s", out.get("id"))
    return out


async def _apply_fillup_to_fuel_estimate(
    pool: asyncpg.Pool, body: FillupCreate
) -> None:
    """Update the hybrid fuel-level estimate after a fillup is logged.

    is_full=true snaps estimate to tank capacity. Partial fillups add the
    pumped liters to the prior estimate. See services/fuel_state.py for
    the full state-machine documentation.
    """
    async with pool.acquire() as conn:
        v = await conn.fetchrow(
            """
            SELECT tank_capacity_l, fuel_level_estimate_l, fuel_unit
              FROM vehicles WHERE id = $1
            """,
            body.vehicle_id,
        )
        if v is None or v["tank_capacity_l"] is None:
            return  # vehicle pre-migration or missing capacity; skip
        # Convert pumped volume to liters. fuel_unit convention in this
        # codebase: 0=liters, 1=US gallons. The estimator math is L-only.
        pumped_l: float | None = None
        if body.fuel_volume:
            raw = float(body.fuel_volume)
            pumped_l = raw * 3.78541 if v["fuel_unit"] == 1 else raw
        update = reset_on_fillup(
            is_full=bool(body.is_full),
            pumped_liters=pumped_l,
            current_estimate_l=(
                float(v["fuel_level_estimate_l"])
                if v["fuel_level_estimate_l"] is not None
                else None
            ),
            tank_capacity_l=float(v["tank_capacity_l"]),
            when=body.fillup_date,
        )
        if update.confidence == "LOW" and update.reason == "fillup_skipped_no_liters":
            return  # nothing meaningful to persist
        await persist_estimate(conn, body.vehicle_id, update)
        log.info(
            "fuel-estimate reset vehicle=%s liters=%.2f reason=%s",
            body.vehicle_id, update.liters, update.reason,
        )


async def _maybe_calibrate_fuel_level(
    pool: asyncpg.Pool, vehicle_id: UUID, fillup_date: datetime
) -> None:
    """When a full-tank fillup is logged, capture the freshest fuel_level
    raw reading from within +/-30 min as the vehicle's calibration ceiling.

    Honda's PID 0x2F caps below 100 even on a physically full tank — the
    sensor's float-arm stop is below the actual fill line. By snapshotting
    the post-fillup reading as the per-vehicle reference, the UI can
    normalize all subsequent readings against it (so a "full" tank reads
    100 %). The window straddles fillup_date because the user may file
    the fillup either before or after the OBD sees the bump.
    """
    async with pool.acquire() as conn:
        raw = await conn.fetchval(
            """
            SELECT value_num FROM pid_readings
             WHERE vehicle_id = $1
               AND metric = 'fuel_level'
               AND time BETWEEN $2::timestamptz - interval '30 min'
                            AND $2::timestamptz + interval '30 min'
               AND value_num IS NOT NULL
             ORDER BY value_num DESC
             LIMIT 1
            """,
            vehicle_id, fillup_date,
        )
        if raw is None:
            return
        # Only ratchet the ceiling upward, and only when the new reading
        # is meaningfully higher (>1 % above the current setting) so we
        # don't drift down on a partial fill mistakenly marked full.
        await conn.execute(
            """
            UPDATE vehicles
               SET fuel_level_calibration_pct = $2
             WHERE id = $1
               AND $2 > COALESCE(fuel_level_calibration_pct, 100) + 1.0
               AND $2 <= 100
            """,
            vehicle_id, float(raw),
        )


async def _maybe_calibrate_fuel_empty(
    pool: asyncpg.Pool, body: FillupCreate
) -> None:
    """Calibrate the LOW end of the sender curve from a fill-to-full.

    A full fillup states exactly how much fuel was in the tank before the
    pump: ``tank_capacity - pumped``. Pairing that with the raw sender
    reading taken just BEFORE the fillup gives a second point on the
    curve (the full-tank ceiling from 0016 is the first), and the line
    through them yields the reading the sender shows when dry.

    Only deep fills calibrate. A splash-and-dash tells us almost nothing
    about the bottom of the curve, and a small measurement error there
    swings the extrapolated intercept wildly — so require the tank to
    have been at most MAX_PRE_FILL_FRACTION full beforehand.
    """
    # Pre-fill level must be at or below this fraction of tank for the
    # observation to constrain the low end usefully.
    MAX_PRE_FILL_FRACTION = 0.40

    async with pool.acquire() as conn:
        v = await conn.fetchrow(
            """
            SELECT tank_capacity_l, fuel_unit,
                   COALESCE(fuel_level_calibration_pct, 100.0) AS cal_pct
              FROM vehicles WHERE id = $1
            """,
            body.vehicle_id,
        )
        if v is None or v["tank_capacity_l"] is None or not body.fuel_volume:
            return
        tank_l = float(v["tank_capacity_l"])
        raw_vol = float(body.fuel_volume)
        pumped_l = raw_vol * 3.78541 if v["fuel_unit"] == 1 else raw_vol
        remaining_l = tank_l - pumped_l
        if remaining_l < 0 or remaining_l > tank_l * MAX_PRE_FILL_FRACTION:
            return

        # Freshest sensor reading strictly BEFORE the fillup. Deliberately
        # not the ±30 min window _maybe_calibrate_fuel_level uses — that
        # one wants the post-fill bump, this one wants the pre-fill level.
        sensor_before = await conn.fetchval(
            """
            SELECT value_num FROM pid_readings
             WHERE vehicle_id = $1
               AND metric = 'fuel_level'
               AND time BETWEEN $2::timestamptz - interval '2 hours'
                            AND $2::timestamptz
               AND value_num IS NOT NULL
             ORDER BY time DESC
             LIMIT 1
            """,
            body.vehicle_id, body.fillup_date,
        )
        if sensor_before is None:
            return

        empty_pct = derive_empty_pct(
            sensor_pct_before=float(sensor_before),
            liters_remaining_before=remaining_l,
            tank_capacity_l=tank_l,
            calibration_pct=float(v["cal_pct"]),
        )
        if empty_pct is None:
            return
        await conn.execute(
            "UPDATE vehicles SET fuel_level_empty_pct = $2 WHERE id = $1",
            body.vehicle_id, empty_pct,
        )
        log.info(
            "fuel empty-point calibrated vehicle=%s sensor_before=%.2f%% "
            "remaining=%.2fL -> empty_pct=%.2f%%",
            body.vehicle_id, float(sensor_before), remaining_l, empty_pct,
        )


@router.patch(
    "/{fillup_id}",
    response_model=FillupOut,
    dependencies=[Depends(require_ingest_token)],
)
async def update_fillup(
    body: FillupUpdate,
    fillup_id: UUID = FastAPIPath(...),
    pool: asyncpg.Pool = Depends(get_pool),
) -> dict[str, Any]:
    fields = body.model_dump(exclude_unset=True)
    if not fields:
        return await get_fillup(fillup_id, pool)
    set_parts: list[str] = []
    values: list[Any] = []
    for i, (k, v) in enumerate(fields.items(), start=2):
        set_parts.append(f"{k} = ${i}")
        if k in {"price_total", "price_per_unit"} and isinstance(v, (int, float)):
            v = Decimal(str(v))
        values.append(v)
    sql = (
        f"UPDATE fillups SET {', '.join(set_parts)} WHERE id = $1 "
        f"RETURNING {_FILLUP_COLS}"
    )
    async with pool.acquire() as conn:
        row = await conn.fetchrow(sql, fillup_id, *values)
    if row is None:
        raise HTTPException(status_code=404, detail="fillup not found")
    out = _row_to_fillup(row)
    await _attach_recomputed_mpg(pool, [out])
    return out


@router.delete(
    "/{fillup_id}",
    status_code=204,
    dependencies=[Depends(require_ingest_token)],
)
async def delete_fillup(
    fillup_id: UUID = FastAPIPath(...),
    pool: asyncpg.Pool = Depends(get_pool),
) -> None:
    async with pool.acquire() as conn:
        result = await conn.execute("DELETE FROM fillups WHERE id = $1", fillup_id)
    if result.endswith(" 0"):
        raise HTTPException(status_code=404, detail="fillup not found")


def _decimal(v: Decimal | int | float | None) -> Decimal | None:
    if v is None:
        return None
    if isinstance(v, Decimal):
        return v
    return Decimal(str(v))
