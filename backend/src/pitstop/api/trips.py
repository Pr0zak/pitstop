"""Trips endpoints — list/get/update/delete + sampled detail."""

from __future__ import annotations

import logging
from datetime import datetime
from typing import Any
from uuid import UUID

import asyncpg
from fastapi import APIRouter, Depends, HTTPException, Query, Response
from fastapi import Path as FastAPIPath

from ..auth import require_ingest_token, require_query_token
from ..db.deps import get_pool
from ..schemas import TripDetail, TripOut, TripUpdate

log = logging.getLogger(__name__)

router = APIRouter(prefix="/trips", tags=["trips"])


_TRIP_COLS = (
    "id, vehicle_id, started_at, ended_at, duration_s, distance_km, "
    "max_rpm, max_speed_kph, avg_speed_kph, avg_coolant_c, fuel_used_l, "
    "dtc_count, category, notes, "
    "weather_temp_c, weather_humidity_pct, weather_precip_mm, "
    "weather_wind_kph, weather_code"
)


def _row_to_trip(row: asyncpg.Record) -> dict[str, Any]:
    return {
        "id": row["id"],
        "vehicle_id": row["vehicle_id"],
        "started_at": row["started_at"],
        "ended_at": row["ended_at"],
        "duration_s": row["duration_s"],
        "distance_km": row["distance_km"],
        "max_rpm": row["max_rpm"],
        "max_speed_kph": row["max_speed_kph"],
        "avg_speed_kph": row["avg_speed_kph"],
        "avg_coolant_c": row["avg_coolant_c"],
        "fuel_used_l": row["fuel_used_l"],
        "dtc_count": row["dtc_count"],
        "category": row["category"],
        "notes": row["notes"],
        "weather_temp_c": row["weather_temp_c"],
        "weather_humidity_pct": row["weather_humidity_pct"],
        "weather_precip_mm": row["weather_precip_mm"],
        "weather_wind_kph": row["weather_wind_kph"],
        "weather_code": row["weather_code"],
    }


@router.get(
    "",
    response_model=list[TripOut],
    dependencies=[Depends(require_query_token)],
)
async def list_trips(
    response: Response,
    vehicle_id: UUID | None = Query(default=None),
    from_: datetime | None = Query(default=None, alias="from"),
    to: datetime | None = Query(default=None),
    limit: int = Query(default=50, ge=1, le=500),
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
        where.append(f"started_at >= ${len(args)}")
    if to is not None:
        args.append(to)
        where.append(f"started_at <= ${len(args)}")
    where_sql = (" WHERE " + " AND ".join(where)) if where else ""

    async with pool.acquire() as conn:
        total = await conn.fetchval(
            f"SELECT count(*) FROM trips{where_sql}", *args
        )
        args.extend([limit, offset])
        rows = await conn.fetch(
            f"SELECT {_TRIP_COLS} FROM trips{where_sql} "
            f"ORDER BY started_at DESC NULLS LAST "
            f"LIMIT ${len(args) - 1} OFFSET ${len(args)}",
            *args,
        )
    response.headers["X-Total-Count"] = str(int(total or 0))
    return [_row_to_trip(r) for r in rows]


@router.get(
    "/{trip_id}",
    response_model=TripDetail,
    dependencies=[Depends(require_query_token)],
)
async def get_trip(
    trip_id: UUID = FastAPIPath(...),
    pool: asyncpg.Pool = Depends(get_pool),
) -> dict[str, Any]:
    async with pool.acquire() as conn:
        row = await conn.fetchrow(
            f"SELECT {_TRIP_COLS} FROM trips WHERE id = $1", trip_id
        )
        if row is None:
            raise HTTPException(status_code=404, detail="trip not found")

        # Sample readings: bucket the trip into ~500 points of speed + rpm.
        started = row["started_at"]
        ended = row["ended_at"] or started
        duration_s = max(int((ended - started).total_seconds()), 1)
        bucket_s = max(duration_s // 500, 1)
        # Use time_bucket on the chunk; fall back to LIMIT if Timescale absent.
        try:
            sample_rows = await conn.fetch(
                """
                SELECT time_bucket(make_interval(secs => $4), time) AS time,
                       metric,
                       avg(value_num) AS value_num
                  FROM pid_readings
                 WHERE vehicle_id = $1
                   AND time >= $2 AND time <= $3
                   AND metric IN ('vehicle_speed', 'engine_rpm', 'coolant_temp',
                                  'throttle_position', 'maf_air_flow',
                                  'manifold_pressure', 'engine_load',
                                  'control_module_voltage', 'fuel_level',
                                  'intake_air_temp', 'engine_oil_temp',
                                  'atf_temp_f')
                   AND value_num IS NOT NULL
                 GROUP BY 1, 2
                 ORDER BY 1 ASC
                """,
                row["vehicle_id"], started, ended, bucket_s,
            )
        except asyncpg.UndefinedFunctionError:
            sample_rows = await conn.fetch(
                """
                SELECT time, metric, value_num
                  FROM pid_readings
                 WHERE vehicle_id = $1
                   AND time >= $2 AND time <= $3
                   AND value_num IS NOT NULL
                 ORDER BY time ASC
                 LIMIT 500
                """,
                row["vehicle_id"], started, ended,
            )

    out = _row_to_trip(row)
    out["samples"] = [
        {
            "time": r["time"],
            "metric": r["metric"],
            "value_num": float(r["value_num"]) if r["value_num"] is not None else None,
        }
        for r in sample_rows
    ]

    # Odometer at trip start + end (Task #99). Pull the closest
    # pid_readings odometer value within ±15 minutes of each
    # boundary; null when WiCAN didn't publish in that window.
    async with pool.acquire() as conn:
        odo_start = await conn.fetchval(
            """
            SELECT value_num FROM pid_readings
             WHERE vehicle_id = $1 AND metric = 'odometer'
               AND time BETWEEN $2 - interval '15 minutes' AND $2 + interval '15 minutes'
             ORDER BY abs(extract(epoch FROM (time - $2)))
             LIMIT 1
            """,
            row["vehicle_id"], started,
        )
        odo_end = await conn.fetchval(
            """
            SELECT value_num FROM pid_readings
             WHERE vehicle_id = $1 AND metric = 'odometer'
               AND time BETWEEN $2 - interval '15 minutes' AND $2 + interval '15 minutes'
             ORDER BY abs(extract(epoch FROM (time - $2)))
             LIMIT 1
            """,
            row["vehicle_id"], ended,
        )
        # DTCs that fired during the trip window (Task #110).
        dtc_rows = await conn.fetch(
            """
            SELECT id, code, seen_at, description
              FROM dtc_events
             WHERE vehicle_id = $1
               AND seen_at BETWEEN $2 AND $3
             ORDER BY seen_at ASC
            """,
            row["vehicle_id"], started, ended,
        )
    out["odo_start_km"] = float(odo_start) if odo_start is not None else None
    out["odo_end_km"] = float(odo_end) if odo_end is not None else None
    out["dtcs"] = [
        {
            "id": str(d["id"]),
            "code": d["code"],
            "seen_at": d["seen_at"],
            "description": d["description"],
        }
        for d in dtc_rows
    ]
    return out


@router.get(
    "/{trip_id}/route",
    dependencies=[Depends(require_query_token)],
)
async def get_trip_route(
    trip_id: UUID = FastAPIPath(...),
    pool: asyncpg.Pool = Depends(get_pool),
) -> dict[str, Any]:
    """Return the GPS polyline + per-point metadata for a trip.

    Used by the frontend trip detail page to render a MapLibre line.
    Empty array when the trip predates gps_points or the bridge had
    no GPS during the trip; the page should fall back to a no-map view.
    """
    async with pool.acquire() as conn:
        row = await conn.fetchrow(
            "SELECT vehicle_id, started_at, ended_at FROM trips WHERE id = $1",
            trip_id,
        )
        if row is None:
            raise HTTPException(status_code=404, detail="trip not found")
        ended = row["ended_at"] or row["started_at"]
        points = await conn.fetch(
            """
            SELECT time, lat, lon, alt_m, speed_mps, heading_deg, accuracy_m
              FROM gps_points
             WHERE vehicle_id = $1 AND time >= $2 AND time <= $3
             ORDER BY time ASC
            """,
            row["vehicle_id"], row["started_at"], ended,
        )
    return {
        "trip_id": trip_id,
        "points": [
            {
                "t": p["time"],
                "lat": float(p["lat"]),
                "lon": float(p["lon"]),
                "alt_m": float(p["alt_m"]) if p["alt_m"] is not None else None,
                "speed_mps": float(p["speed_mps"]) if p["speed_mps"] is not None else None,
                "heading_deg": float(p["heading_deg"]) if p["heading_deg"] is not None else None,
                "accuracy_m": float(p["accuracy_m"]) if p["accuracy_m"] is not None else None,
            }
            for p in points
        ],
    }


@router.patch(
    "/{trip_id}",
    response_model=TripOut,
    dependencies=[Depends(require_ingest_token)],
)
async def update_trip(
    body: TripUpdate,
    trip_id: UUID = FastAPIPath(...),
    pool: asyncpg.Pool = Depends(get_pool),
) -> dict[str, Any]:
    fields = body.model_dump(exclude_unset=True)
    if not fields:
        async with pool.acquire() as conn:
            row = await conn.fetchrow(
                f"SELECT {_TRIP_COLS} FROM trips WHERE id = $1", trip_id
            )
        if row is None:
            raise HTTPException(status_code=404, detail="trip not found")
        return _row_to_trip(row)

    set_parts: list[str] = []
    values: list[Any] = []
    for i, (k, v) in enumerate(fields.items(), start=2):
        set_parts.append(f"{k} = ${i}")
        values.append(v)
    sql = (
        f"UPDATE trips SET {', '.join(set_parts)} WHERE id = $1 "
        f"RETURNING {_TRIP_COLS}"
    )
    async with pool.acquire() as conn:
        row = await conn.fetchrow(sql, trip_id, *values)
    if row is None:
        raise HTTPException(status_code=404, detail="trip not found")
    return _row_to_trip(row)


@router.delete(
    "/{trip_id}",
    status_code=204,
    dependencies=[Depends(require_ingest_token)],
)
async def delete_trip(
    trip_id: UUID = FastAPIPath(...),
    pool: asyncpg.Pool = Depends(get_pool),
) -> None:
    async with pool.acquire() as conn:
        result = await conn.execute("DELETE FROM trips WHERE id = $1", trip_id)
    if result.endswith(" 0"):
        raise HTTPException(status_code=404, detail="trip not found")
