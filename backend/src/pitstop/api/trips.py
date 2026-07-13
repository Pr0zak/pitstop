"""Trips endpoints — list/get/update/delete/merge + sampled detail."""

from __future__ import annotations

import logging
from datetime import datetime
from typing import Any
from uuid import UUID

import asyncpg
from fastapi import APIRouter, Body, Depends, HTTPException, Query, Response
from fastapi import Path as FastAPIPath
from pydantic import BaseModel, Field

from ..auth import require_ingest_token, require_query_token
from ..db.deps import get_pool
from ..schemas import TripDetail, TripOut, TripUpdate

log = logging.getLogger(__name__)

router = APIRouter(prefix="/trips", tags=["trips"])


_TRIP_COLS = (
    "id, vehicle_id, started_at, ended_at, duration_s, distance_km, "
    "max_rpm, max_speed_kph, avg_speed_kph, avg_coolant_c, fuel_used_l, "
    "dtc_count, idle_s, category, notes, "
    "weather_temp_c, weather_humidity_pct, weather_precip_mm, "
    "weather_wind_kph, weather_code, "
    "source, incomplete"
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
        "idle_s": row["idle_s"],
        "category": row["category"],
        "notes": row["notes"],
        "weather_temp_c": row["weather_temp_c"],
        "weather_humidity_pct": row["weather_humidity_pct"],
        "weather_precip_mm": row["weather_precip_mm"],
        "weather_wind_kph": row["weather_wind_kph"],
        "weather_code": row["weather_code"],
        "source": row["source"],
        "incomplete": row["incomplete"],
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
                                  'intake_air_temp')
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
               AND time BETWEEN $2::timestamptz - interval '15 minutes' AND $2::timestamptz + interval '15 minutes'
             ORDER BY abs(extract(epoch FROM (time - $2::timestamptz)))
             LIMIT 1
            """,
            row["vehicle_id"], started,
        )
        odo_end = await conn.fetchval(
            """
            SELECT value_num FROM pid_readings
             WHERE vehicle_id = $1 AND metric = 'odometer'
               AND time BETWEEN $2::timestamptz - interval '15 minutes' AND $2::timestamptz + interval '15 minutes'
             ORDER BY abs(extract(epoch FROM (time - $2::timestamptz)))
             LIMIT 1
            """,
            row["vehicle_id"], ended,
        )
        # Fuel level at trip boundaries — settled value (not the bouncy
        # slosh samples). Take the LAST reading within the first 60 s
        # for start (sensor has settled by the time engine is steady)
        # and the LAST reading of the trip for end.
        fuel_start_raw = await conn.fetchval(
            """
            SELECT value_num FROM pid_readings
             WHERE vehicle_id = $1 AND metric = 'fuel_level'
               AND time BETWEEN $2::timestamptz AND $2::timestamptz + interval '60 seconds'
             ORDER BY time DESC LIMIT 1
            """,
            row["vehicle_id"], started,
        )
        fuel_end_raw = await conn.fetchval(
            """
            SELECT value_num FROM pid_readings
             WHERE vehicle_id = $1 AND metric = 'fuel_level'
               AND time BETWEEN $2::timestamptz - interval '60 seconds' AND $2::timestamptz
             ORDER BY time DESC LIMIT 1
            """,
            row["vehicle_id"], ended,
        )
        # Calibration ceiling so the start/end values displayed match
        # the hero card (normalized so 100 % == full tank).
        veh_cal = await conn.fetchval(
            "SELECT fuel_level_calibration_pct FROM vehicles WHERE id = $1",
            row["vehicle_id"],
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
    # Normalize fuel boundaries against the per-vehicle calibration so
    # the displayed start/end matches the gauge (Honda 0x2F caps below
    # 100 raw on a full tank — see v0.1.159 calibration capture).
    cal = float(veh_cal) if veh_cal is not None and float(veh_cal) > 0 else 100.0
    def _norm(raw: Any) -> float | None:
        if raw is None:
            return None
        return min(100.0, float(raw) / cal * 100.0)
    out["fuel_level_start_pct"] = _norm(fuel_start_raw)
    out["fuel_level_end_pct"] = _norm(fuel_end_raw)
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


class TripMergeRequest(BaseModel):
    """Trips to fold into the path-parameter trip.

    `other_trip_ids` merges N trips at once (MERGE-N) — the common case
    for a long drive the phone fragmented into many legs across BLE
    dropouts. `other_trip_id` is the legacy single-trip field, still
    accepted so older phone/web clients keep working; it's coerced into
    the list below. At least one of the two must be supplied.
    """

    other_trip_ids: list[UUID] = Field(
        default_factory=list,
        description="Trips to merge into the path-parameter trip (MERGE-N).",
    )
    other_trip_id: UUID | None = Field(
        default=None,
        description="Legacy single-trip field, folded into other_trip_ids.",
    )

    def target_ids(self, trip_id: UUID) -> list[UUID]:
        """Distinct set of every trip in the merge, path trip included,
        order-preserved (path trip first). Raises 400 if fewer than two
        distinct trips end up in the set."""
        seen: dict[UUID, None] = {trip_id: None}
        for tid in [*self.other_trip_ids, *( [self.other_trip_id] if self.other_trip_id else [])]:
            seen.setdefault(tid, None)
        ids = list(seen)
        if len(ids) < 2:
            raise HTTPException(
                status_code=400,
                detail="merge needs at least two distinct trips",
            )
        return ids


@router.post(
    "/{trip_id}/merge",
    response_model=TripOut,
    dependencies=[Depends(require_ingest_token)],
)
async def merge_trips(
    body: TripMergeRequest,
    trip_id: UUID = FastAPIPath(...),
    pool: asyncpg.Pool = Depends(get_pool),
) -> dict[str, Any]:
    """Manually combine two or more trips into one (MERGE-N). The
    earliest trip (by started_at) absorbs every other; the rest are
    deleted. Stat columns are recombined across all legs: distance_km /
    fuel_used_l / dtc_count / idle_s sum, max_rpm / max_speed_kph take
    the max, and avg_speed_kph / avg_coolant_c are re-weighted by each
    leg's duration. ended_at moves to the latest leg's ended_at;
    duration_s becomes the elapsed time from the first start to the last
    end (all gaps included).

    `source` is set to `manual_merge` so trip_deriver.py treats the
    combined range as off-limits and doesn't re-split it on the next
    cycle. The kept row's `category` / `notes` are preserved (the UPDATE
    only touches stat columns).

    All trips must belong to the same vehicle. Trips already on a
    `manual_merge` row can be merged again (chains are allowed).
    """
    target_ids = body.target_ids(trip_id)
    async with pool.acquire() as conn:
        async with conn.transaction():
            rows = await conn.fetch(
                f"SELECT {_TRIP_COLS} FROM trips WHERE id = ANY($1::uuid[]) FOR UPDATE",
                target_ids,
            )
            if len(rows) != len(target_ids):
                raise HTTPException(status_code=404, detail="one or more trips not found")
            vehicle_ids = {r["vehicle_id"] for r in rows}
            if len(vehicle_ids) != 1:
                raise HTTPException(status_code=400, detail="trips belong to different vehicles")
            # Order by started_at — earliest leg absorbs the rest.
            legs = sorted(rows, key=lambda r: r["started_at"])
            kept = legs[0]
            new_started = kept["started_at"]
            new_ended = max((r["ended_at"] or r["started_at"]) for r in legs)
            new_duration_s = int((new_ended - new_started).total_seconds())

            def _sum(col: str):
                vals = [r[col] for r in legs if r[col] is not None]
                return sum(vals) if vals else None

            def _max(col: str):
                vals = [r[col] for r in legs if r[col] is not None]
                return max(vals) if vals else None

            def _wavg(col: str):
                # Duration-weighted mean over legs with both a value and
                # a positive duration. Falls back to a plain mean of the
                # values if no leg carries a usable duration.
                num = den = 0.0
                plain: list[float] = []
                for r in legs:
                    v = r[col]
                    if v is None:
                        continue
                    plain.append(float(v))
                    d = r["duration_s"] or 0
                    if d > 0:
                        num += float(v) * d
                        den += d
                if den > 0:
                    return num / den
                if plain:
                    return sum(plain) / len(plain)
                return None

            distance_km = _sum("distance_km")
            fuel_used_l = _sum("fuel_used_l")
            dtc_count = sum((r["dtc_count"] or 0) for r in legs)
            idle_s = _sum("idle_s")
            max_rpm = _max("max_rpm")
            max_speed_kph = _max("max_speed_kph")
            avg_speed_kph = _wavg("avg_speed_kph")
            avg_coolant_c = _wavg("avg_coolant_c")
            # Delete every absorbed leg, then update the kept row.
            drop_ids = [r["id"] for r in legs if r["id"] != kept["id"]]
            await conn.execute("DELETE FROM trips WHERE id = ANY($1::uuid[])", drop_ids)
            updated = await conn.fetchrow(
                f"""
                UPDATE trips SET
                    ended_at = $2,
                    duration_s = $3,
                    distance_km = $4,
                    max_rpm = $5,
                    max_speed_kph = $6,
                    avg_speed_kph = $7,
                    avg_coolant_c = $8,
                    fuel_used_l = $9,
                    dtc_count = $10,
                    idle_s = $11,
                    source = 'manual_merge'
                  WHERE id = $1
                RETURNING {_TRIP_COLS}
                """,
                kept["id"], new_ended, new_duration_s,
                distance_km, max_rpm, max_speed_kph,
                avg_speed_kph, avg_coolant_c, fuel_used_l,
                dtc_count, idle_s,
            )
            log.info(
                "trips merged",
                extra={
                    "kept_id": str(kept["id"]),
                    "dropped_ids": [str(i) for i in drop_ids],
                    "leg_count": len(legs),
                    "vehicle_id": str(kept["vehicle_id"]),
                    "new_started_at": new_started.isoformat(),
                    "new_ended_at": new_ended.isoformat(),
                    "new_duration_s": new_duration_s,
                },
            )
    if updated is None:
        raise HTTPException(status_code=500, detail="merge produced no row")
    return _row_to_trip(updated)
