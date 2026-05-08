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
        s.last_seen_at, s.last_metric, COALESCE(s.latest, '{}'::jsonb) AS latest,
        p.name AS profile_name, p.description AS profile_description
      FROM vehicles v
      LEFT JOIN vehicle_state s ON s.vehicle_id = v.id
      LEFT JOIN pid_profiles p  ON p.id = v.pid_profile_id
"""


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
        "last_seen_at": row["last_seen_at"],
        "last_metric": row["last_metric"],
        "latest": row["latest"] or {},
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
    return [_row_to_vehicle(r) for r in rows]


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
    return _row_to_vehicle(row)


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
