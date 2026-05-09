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

log = logging.getLogger(__name__)

router = APIRouter(prefix="/fillups", tags=["fillups"])


_FILLUP_COLS = (
    "id, vehicle_id, fuelio_guid, fillup_date, odo, fuel_volume, "
    "is_full, is_missed, price_total, price_per_unit, mpg_reported, "
    "lat, lon, city, station_id, tank_number, fuel_type, weather, notes"
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
    await _attach_recomputed_mpg(pool, [out])
    return out


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
