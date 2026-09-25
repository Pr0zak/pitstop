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


@router.get(
    "/timeline",
    dependencies=[Depends(require_query_token)],
)
async def dtcs_timeline(
    vehicle_id: UUID = Query(...),
    days: int = Query(default=365, ge=1, le=3650),
    pool: asyncpg.Pool = Depends(get_pool),
) -> dict[str, Any]:
    """Group DTC events by code over the last `days` for a horizontal
    timeline (Task #88). Returns one row per code with the list of
    seen-at timestamps and the most-recent description / cleared_at.

    Surfaces recurring codes vs one-time blips at a glance — much
    harder to see in the existing flat /dtcs list.
    """
    # Each event is attributed to the trip (same vehicle) whose
    # [started_at, ended_at] window contains seen_at, so the clients can
    # link an occurrence to its drive. Resolved by time rather than read
    # from dtc_events.trip_id: that column is stamped at ingest and goes
    # stale when the deriver re-keys or a merge folds the trip away. An
    # open trip (ended_at NULL) only matches at its start instant, the
    # same convention the 0022 backfill uses. Should two trips ever
    # overlap, the later-started one wins.
    async with pool.acquire() as conn:
        rows = await conn.fetch(
            """
            SELECT
                d.code,
                count(*)                                           AS n,
                min(d.seen_at)                                     AS first_seen,
                max(d.seen_at)                                     AS last_seen,
                array_agg(d.seen_at ORDER BY d.seen_at)            AS seen_ats,
                array_agg(d.id ORDER BY d.seen_at)                 AS event_ids,
                array_agg(t.id ORDER BY d.seen_at)                 AS trip_ids,
                array_agg(t.distance_km ORDER BY d.seen_at)        AS trip_distances,
                (array_agg(d.description ORDER BY d.seen_at DESC))[1] AS description,
                bool_or(d.cleared_at IS NULL)                      AS has_active
              FROM dtc_events d
              LEFT JOIN LATERAL (
                  SELECT tr.id, tr.distance_km
                    FROM trips tr
                   WHERE tr.vehicle_id = d.vehicle_id
                     AND tr.started_at <= d.seen_at
                     AND COALESCE(tr.ended_at, tr.started_at) >= d.seen_at
                   ORDER BY tr.started_at DESC
                   LIMIT 1
              ) t ON true
             WHERE d.vehicle_id = $1
               AND d.seen_at >= now() - make_interval(days => $2)
             GROUP BY d.code
             ORDER BY last_seen DESC
            """,
            vehicle_id, days,
        )
    codes = [
        {
            "code": r["code"],
            "description": r["description"],
            "count": int(r["n"]),
            "first_seen": r["first_seen"],
            "last_seen": r["last_seen"],
            "active": bool(r["has_active"]),
            "events": [
                {
                    "id": str(eid),
                    "seen_at": ts,
                    "trip_id": str(tid) if tid is not None else None,
                    "trip_distance_km": (
                        float(dist) if dist is not None else None
                    ),
                }
                for eid, ts, tid, dist in zip(
                    r["event_ids"],
                    r["seen_ats"],
                    r["trip_ids"],
                    r["trip_distances"],
                    strict=True,
                )
            ],
        }
        for r in rows
    ]
    return {"codes": codes, "window_days": days}


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
