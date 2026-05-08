"""DTC (diagnostic trouble code) endpoints."""

from __future__ import annotations

import logging
from typing import Any
from uuid import UUID

import asyncpg
from fastapi import APIRouter, Depends, HTTPException, Query
from fastapi import Path as FastAPIPath

from ..auth import require_ingest_token, require_query_token
from ..db.deps import get_pool
from ..schemas import DtcOut

log = logging.getLogger(__name__)

router = APIRouter(prefix="/dtcs", tags=["dtcs"])


@router.get(
    "",
    response_model=list[DtcOut],
    dependencies=[Depends(require_query_token)],
)
async def list_dtcs(
    vehicle_id: UUID | None = Query(default=None),
    active_only: bool = Query(default=False),
    pool: asyncpg.Pool = Depends(get_pool),
) -> list[dict[str, Any]]:
    where: list[str] = []
    args: list[Any] = []
    if vehicle_id is not None:
        args.append(vehicle_id)
        where.append(f"vehicle_id = ${len(args)}")
    if active_only:
        where.append("cleared_at IS NULL")
    sql = (
        "SELECT id, vehicle_id, trip_id, code, description, seen_at, cleared_at "
        "FROM dtc_events"
    )
    if where:
        sql += " WHERE " + " AND ".join(where)
    sql += " ORDER BY seen_at DESC LIMIT 1000"
    async with pool.acquire() as conn:
        rows = await conn.fetch(sql, *args)
    return [
        {
            "id": r["id"],
            "vehicle_id": r["vehicle_id"],
            "trip_id": r["trip_id"],
            "code": r["code"],
            "description": r["description"],
            "seen_at": r["seen_at"],
            "cleared_at": r["cleared_at"],
        }
        for r in rows
    ]


@router.post(
    "/clear/{dtc_id}",
    response_model=DtcOut,
    dependencies=[Depends(require_ingest_token)],
)
async def clear_dtc(
    dtc_id: UUID = FastAPIPath(...),
    pool: asyncpg.Pool = Depends(get_pool),
) -> dict[str, Any]:
    async with pool.acquire() as conn:
        row = await conn.fetchrow(
            "UPDATE dtc_events SET cleared_at = now() WHERE id = $1 "
            "RETURNING id, vehicle_id, trip_id, code, description, "
            "         seen_at, cleared_at",
            dtc_id,
        )
    if row is None:
        raise HTTPException(status_code=404, detail="dtc not found")
    return {
        "id": row["id"],
        "vehicle_id": row["vehicle_id"],
        "trip_id": row["trip_id"],
        "code": row["code"],
        "description": row["description"],
        "seen_at": row["seen_at"],
        "cleared_at": row["cleared_at"],
    }
