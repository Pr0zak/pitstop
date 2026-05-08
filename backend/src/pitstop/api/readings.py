"""Time-series readings endpoints (raw + continuous-aggregate rollups)."""

from __future__ import annotations

import logging
from datetime import datetime
from typing import Any, Literal
from uuid import UUID

import asyncpg
from fastapi import APIRouter, Depends, HTTPException, Query

from ..auth import require_query_token
from ..db.deps import get_pool
from ..schemas import AggregateOut, ReadingOut

log = logging.getLogger(__name__)

router = APIRouter(prefix="/readings", tags=["readings"])


@router.get(
    "",
    response_model=list[ReadingOut],
    dependencies=[Depends(require_query_token)],
)
async def list_readings(
    vehicle_id: UUID = Query(...),
    metric: str = Query(...),
    from_: datetime | None = Query(default=None, alias="from"),
    to: datetime | None = Query(default=None),
    limit: int = Query(default=1000, ge=1, le=10000),
    pool: asyncpg.Pool = Depends(get_pool),
) -> list[dict[str, Any]]:
    """Return raw pid_readings for one (vehicle, metric) ordered DESC."""
    where = ["vehicle_id = $1", "metric = $2"]
    args: list[Any] = [vehicle_id, metric]
    if from_ is not None:
        where.append(f"time >= ${len(args) + 1}")
        args.append(from_)
    if to is not None:
        where.append(f"time <= ${len(args) + 1}")
        args.append(to)
    sql = (
        "SELECT time, value_num, value_text, source FROM pid_readings WHERE "
        + " AND ".join(where)
        + f" ORDER BY time DESC LIMIT ${len(args) + 1}"
    )
    args.append(limit)
    async with pool.acquire() as conn:
        rows = await conn.fetch(sql, *args)
    return [
        {
            "time": r["time"],
            "value_num": r["value_num"],
            "value_text": r["value_text"],
            "source": r["source"],
        }
        for r in rows
    ]


@router.get(
    "/aggregate",
    response_model=list[AggregateOut],
    dependencies=[Depends(require_query_token)],
)
async def aggregate_readings(
    vehicle_id: UUID = Query(...),
    metric: str = Query(...),
    from_: datetime | None = Query(default=None, alias="from"),
    to: datetime | None = Query(default=None),
    bucket: Literal["hour", "day"] = Query(default="hour"),
    pool: asyncpg.Pool = Depends(get_pool),
) -> list[dict[str, Any]]:
    view = "pid_hourly" if bucket == "hour" else "pid_daily"
    where = ["vehicle_id = $1", "metric = $2"]
    args: list[Any] = [vehicle_id, metric]
    if from_ is not None:
        where.append(f"bucket >= ${len(args) + 1}")
        args.append(from_)
    if to is not None:
        where.append(f"bucket <= ${len(args) + 1}")
        args.append(to)
    sql = (
        f"SELECT bucket, avg_value AS avg, min_value AS min, "
        f"       max_value AS max, n AS count "
        f"FROM {view} WHERE " + " AND ".join(where)
        + " ORDER BY bucket ASC"
    )
    try:
        async with pool.acquire() as conn:
            rows = await conn.fetch(sql, *args)
    except asyncpg.UndefinedTableError as exc:
        # Continuous aggregate doesn't exist (e.g. test DB without timescale).
        raise HTTPException(
            status_code=503, detail="continuous aggregates unavailable"
        ) from exc
    return [
        {
            "bucket": r["bucket"],
            "avg": float(r["avg"]) if r["avg"] is not None else None,
            "min": float(r["min"]) if r["min"] is not None else None,
            "max": float(r["max"]) if r["max"] is not None else None,
            "count": int(r["count"]),
        }
        for r in rows
    ]
