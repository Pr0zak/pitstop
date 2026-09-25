"""Time-series readings endpoints (raw + continuous-aggregate rollups)."""

from __future__ import annotations

import logging
from datetime import UTC, datetime, timedelta
from typing import Any, Literal
from uuid import UUID

import asyncpg
from fastapi import APIRouter, Depends, HTTPException, Query

from ..auth import require_query_token
from ..db.deps import get_pool
from ..schemas import AggregateOut, LatestReadingOut, ReadingOut

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


# How far back /readings/latest looks. Bounded so the query only touches the
# last few chunks of the hypertable (7-day chunks, compressed after 7 days)
# and a vehicle that has been parked for a season returns [] rather than a
# months-old snapshot presented as "last reading".
LATEST_LOOKBACK_DAYS = 30

# Loose index scan ("skip scan") over (vehicle_id, metric, time DESC):
#   1. the recursive CTE walks the DISTINCT metric values one index probe at a
#      time (ORDER BY metric LIMIT 1, then metric > previous) instead of
#      reading every row in the window, and
#   2. the LATERAL join fetches the newest row per metric with ORDER BY time
#      DESC LIMIT 1, which ChunkAppend satisfies from the newest chunk and
#      stops.
# Measured read-only against the production hypertable (~720k rows / 52
# metrics in the 30-day window, 4 of 6 chunks compressed): ~45 ms, vs ~170 ms
# for the plain `SELECT DISTINCT ON (metric) ... ORDER BY metric, time DESC`,
# which has to decompress and merge every row of the window.
_LATEST_SQL = """
WITH RECURSIVE m(metric) AS (
    (
        SELECT metric FROM pid_readings
        WHERE vehicle_id = $1 AND time > $2
        ORDER BY metric
        LIMIT 1
    )
    UNION ALL
    SELECT (
        SELECT p.metric FROM pid_readings p
        WHERE p.vehicle_id = $1 AND p.time > $2 AND p.metric > m.metric
        ORDER BY p.metric
        LIMIT 1
    )
    FROM m
    WHERE m.metric IS NOT NULL
)
SELECT m.metric, r.time, r.value_num, r.value_text, r.source
FROM m
CROSS JOIN LATERAL (
    SELECT time, value_num, value_text, source
    FROM pid_readings p
    WHERE p.vehicle_id = $1 AND p.metric = m.metric AND p.time > $2
    ORDER BY p.time DESC
    LIMIT 1
) r
WHERE m.metric IS NOT NULL
ORDER BY m.metric
"""


@router.get(
    "/latest",
    response_model=list[LatestReadingOut],
    dependencies=[Depends(require_query_token)],
)
async def latest_readings(
    vehicle_id: UUID = Query(...),
    pool: asyncpg.Pool = Depends(get_pool),
) -> list[dict[str, Any]]:
    """Newest reading per metric for one vehicle, within the last 30 days.

    Seeds the web Live view while the car is parked: the WebSocket only
    carries frames that arrive after the page opens, so without this every
    tile reads "—" until the next drive. ``value`` is the numeric value when
    there is one, otherwise the text value. Metrics with no reading in the
    window are simply absent.
    """
    since = datetime.now(UTC) - timedelta(days=LATEST_LOOKBACK_DAYS)
    async with pool.acquire() as conn:
        rows = await conn.fetch(_LATEST_SQL, vehicle_id, since)
    return [
        {
            "metric": r["metric"],
            "value": r["value_num"] if r["value_num"] is not None else r["value_text"],
            "time": r["time"],
            "source": r["source"],
        }
        for r in rows
    ]
