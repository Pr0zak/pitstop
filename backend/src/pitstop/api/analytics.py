"""Fuel + cost analytics.

All time-grouping is done in Python over chronologically-sorted fillups —
the dataset is small (hundreds of rows over years) and recomputed MPG must
respect partial-fill rollups. Pushing this into SQL would lose the rollup
semantics.
"""

from __future__ import annotations

import logging
from collections import defaultdict
from datetime import UTC, date, datetime, timedelta
from typing import Any, Literal
from uuid import UUID

import asyncpg
from fastapi import APIRouter, Depends, Query

from ..auth import require_query_token
from ..db.deps import get_pool
from .fillups import compute_recomputed_mpg

log = logging.getLogger(__name__)

router = APIRouter(prefix="/analytics", tags=["analytics"])


AnalyticsWindow = Literal["month", "3m", "year", "all"]


def _period_key(d: datetime | date, window: AnalyticsWindow) -> str:
    if isinstance(d, datetime):
        d = d.date()
    if window == "month":
        return d.strftime("%Y-%m")
    if window == "3m":
        # Group into calendar quarters.
        q = (d.month - 1) // 3 + 1
        return f"{d.year}-Q{q}"
    if window == "year":
        return f"{d.year}"
    # "all" — everything in one bucket.
    return "all"


def _window_cutoff(window: AnalyticsWindow) -> datetime | None:
    """Filter cutoff applied client-side via ?from on real callers; we don't
    enforce a cutoff in the analytics SQL because window controls grouping."""
    if window == "all":
        return None
    today = datetime.now(tz=None)
    if window == "month":
        return today - timedelta(days=31)
    if window == "3m":
        return today - timedelta(days=92)
    if window == "year":
        return today - timedelta(days=365)
    return None


@router.get("/mpg", dependencies=[Depends(require_query_token)])
async def mpg_series(
    vehicle_id: UUID = Query(...),
    window: AnalyticsWindow = Query(default="year"),
    pool: asyncpg.Pool = Depends(get_pool),
) -> dict[str, Any]:
    async with pool.acquire() as conn:
        rows = await conn.fetch(
            "SELECT id, fillup_date, odo, fuel_volume, is_full "
            "FROM fillups WHERE vehicle_id = $1 ORDER BY fillup_date ASC",
            vehicle_id,
        )
    chain = [dict(r) for r in rows]
    mpg_map = compute_recomputed_mpg(chain)

    by_period: dict[str, list[float]] = defaultdict(list)
    for row in chain:
        mpg = mpg_map.get(row["id"])
        if mpg is None:
            continue
        key = _period_key(row["fillup_date"], window)
        by_period[key].append(mpg)
    points = [
        {
            "period": k,
            "mpg": round(sum(v) / len(v), 3) if v else None,
            "fillup_count": len(v),
        }
        for k, v in sorted(by_period.items())
    ]
    return {"points": points}


@router.get("/cost-per-mi", dependencies=[Depends(require_query_token)])
async def cost_per_mi(
    vehicle_id: UUID = Query(...),
    window: AnalyticsWindow = Query(default="year"),
    pool: asyncpg.Pool = Depends(get_pool),
) -> dict[str, Any]:
    async with pool.acquire() as conn:
        fills = await conn.fetch(
            "SELECT fillup_date, odo, fuel_volume, is_full, "
            "       price_total, price_per_unit "
            "FROM fillups WHERE vehicle_id = $1 ORDER BY fillup_date ASC",
            vehicle_id,
        )
        exps = await conn.fetch(
            "SELECT expense_date, cost FROM expenses "
            "WHERE vehicle_id = $1 AND is_income = false",
            vehicle_id,
        )
    rows = [dict(r) for r in fills]
    # Compute per-period: distance between full-fill checkpoints, and the cost
    # of fuel + service in that period.
    fuel_cost_by_period: dict[str, float] = defaultdict(float)
    distance_by_period: dict[str, float] = defaultdict(float)
    last_full_odo: float | None = None
    last_full_period: str | None = None
    for row in rows:
        period = _period_key(row["fillup_date"], window)
        # Fuel cost: prefer price_total else price_per_unit * volume.
        cost: float | None = None
        if row.get("price_total") is not None:
            cost = float(row["price_total"])
        elif row.get("price_per_unit") is not None:
            cost = float(row["price_per_unit"]) * float(row["fuel_volume"])
        if cost is not None:
            fuel_cost_by_period[period] += cost
        # Distance: only between full fills; assign to the later full's period.
        if row["is_full"]:
            if last_full_odo is not None:
                distance = float(row["odo"]) - last_full_odo
                if distance > 0:
                    # Split distance proportionally would be more accurate;
                    # simpler to attribute to the later period.
                    distance_by_period[period] += distance
            last_full_odo = float(row["odo"])
            last_full_period = period  # noqa: F841 (kept for readability)
    service_cost_by_period: dict[str, float] = defaultdict(float)
    for ex in exps:
        period = _period_key(ex["expense_date"], window)
        service_cost_by_period[period] += float(ex["cost"])

    periods = sorted(
        set(fuel_cost_by_period) | set(service_cost_by_period) | set(distance_by_period)
    )
    points = []
    for p in periods:
        miles = distance_by_period.get(p, 0.0)
        total = fuel_cost_by_period.get(p, 0.0) + service_cost_by_period.get(p, 0.0)
        cpm: float | None = round(total / miles, 4) if miles > 0 else None
        points.append(
            {
                "period": p,
                "cost_per_mi": cpm,
                "miles": round(miles, 2),
                "total_cost": round(total, 2),
            }
        )
    return {"points": points}


@router.get("/monthly-spend", dependencies=[Depends(require_query_token)])
async def monthly_spend(
    vehicle_id: UUID = Query(...),
    months: int = Query(default=12, ge=1, le=120),
    pool: asyncpg.Pool = Depends(get_pool),
) -> dict[str, Any]:
    async with pool.acquire() as conn:
        fuel = await conn.fetch(
            """
            SELECT to_char(fillup_date, 'YYYY-MM') AS month,
                   sum(COALESCE(price_total,
                                price_per_unit * fuel_volume,
                                0)) AS total
              FROM fillups
             WHERE vehicle_id = $1
             GROUP BY 1
             ORDER BY 1 DESC
             LIMIT $2
            """,
            vehicle_id,
            months,
        )
        service = await conn.fetch(
            """
            SELECT to_char(expense_date, 'YYYY-MM') AS month,
                   COALESCE(sum(cost), 0) AS total
              FROM expenses
             WHERE vehicle_id = $1 AND is_income = false
             GROUP BY 1
             ORDER BY 1 DESC
             LIMIT $2
            """,
            vehicle_id,
            months,
        )
    fuel_map: dict[str, float] = {r["month"]: float(r["total"] or 0) for r in fuel}
    service_map: dict[str, float] = {
        r["month"]: float(r["total"] or 0) for r in service
    }
    keys = sorted(set(fuel_map) | set(service_map), reverse=True)[:months]
    out = []
    for k in sorted(keys):
        f = fuel_map.get(k, 0.0)
        s = service_map.get(k, 0.0)
        out.append(
            {
                "month": k,
                "fuel": round(f, 2),
                "service": round(s, 2),
                "total": round(f + s, 2),
            }
        )
    return {"months": out}


@router.get("/stations", dependencies=[Depends(require_query_token)])
async def stations(
    vehicle_id: UUID | None = Query(default=None),
    pool: asyncpg.Pool = Depends(get_pool),
) -> list[dict[str, Any]]:
    """Cluster fillups by station_id, falling back to rounded lat/lon."""
    where: list[str] = []
    args: list[Any] = []
    if vehicle_id is not None:
        args.append(vehicle_id)
        where.append(f"vehicle_id = ${len(args)}")
    where_sql = (" WHERE " + " AND ".join(where)) if where else ""
    async with pool.acquire() as conn:
        rows = await conn.fetch(
            f"""
            SELECT station_id, lat, lon, city, fuel_volume,
                   COALESCE(price_total, price_per_unit * fuel_volume) AS cost,
                   fillup_date
              FROM fillups
              {where_sql}
            """,
            *args,
        )

    clusters: dict[str, dict[str, Any]] = {}
    for r in rows:
        if r["station_id"] is not None:
            key = f"sid:{r['station_id']}"
        elif r["lat"] is not None and r["lon"] is not None:
            key = f"ll:{round(float(r['lat']), 3)}:{round(float(r['lon']), 3)}"
        else:
            key = "unknown"
        c = clusters.setdefault(
            key,
            {
                "cluster_id": key,
                "lat": float(r["lat"]) if r["lat"] is not None else None,
                "lon": float(r["lon"]) if r["lon"] is not None else None,
                "name": r["city"],
                "fillup_count": 0,
                "total_volume": 0.0,
                "total_cost": 0.0,
                "last_visit": None,
            },
        )
        c["fillup_count"] += 1
        c["total_volume"] += float(r["fuel_volume"] or 0)
        c["total_cost"] += float(r["cost"] or 0)
        if c["last_visit"] is None or r["fillup_date"] > c["last_visit"]:
            c["last_visit"] = r["fillup_date"]

    return sorted(
        clusters.values(), key=lambda x: x["fillup_count"], reverse=True
    )


@router.get(
    "/station-prices",
    dependencies=[Depends(require_query_token)],
)
async def station_prices(
    vehicle_id: UUID | None = Query(default=None),
    limit_per_station: int = Query(default=5, ge=1, le=50),
    pool: asyncpg.Pool = Depends(get_pool),
) -> list[dict[str, Any]]:
    """Per-station price history derived from the user's own fillups.

    Useful for "is QuikTrip on 87th still cheaper than the Phillips
    66 on State Line?" comparisons without leaving pitstop. For each
    cluster (station_id when present, rounded lat/lon when not):

      cluster_id            same key shape as /analytics/stations
      name                  city / station label
      lat, lon              first non-null lat/lon in the cluster
      fillup_count          all rows in the cluster
      latest_price          most-recent price_per_unit (USD/unit)
      latest_date           timestamp of that fill
      avg_price             mean of last `limit_per_station` prices
      delta_pct             latest vs the rest-of-recent avg, +/-
      recent                array of {date, price_per_unit, fuel_volume}
                            for the last N fills, newest first

    All prices come straight from the fillups table (already in the
    user's locale's $/gal or $/L). No external lookups; this is the
    "personal price intelligence" layer of the gas-price plan.
    """
    where: list[str] = ["price_per_unit IS NOT NULL"]
    args: list[Any] = []
    if vehicle_id is not None:
        args.append(vehicle_id)
        where.append(f"vehicle_id = ${len(args)}")
    where_sql = " WHERE " + " AND ".join(where)
    async with pool.acquire() as conn:
        rows = await conn.fetch(
            f"""
            SELECT station_id, lat, lon, city, fuel_volume,
                   price_per_unit, fillup_date
              FROM fillups
              {where_sql}
              ORDER BY fillup_date DESC
            """,
            *args,
        )

    # Bucket by station, keep up to `limit_per_station * 2` rows per
    # cluster so we can compute a stable avg and surface the most
    # recent N for the trend visualization.
    buckets: dict[str, dict[str, Any]] = {}
    keep_per = limit_per_station * 2
    for r in rows:
        if r["station_id"] is not None:
            key = f"sid:{r['station_id']}"
        elif r["lat"] is not None and r["lon"] is not None:
            key = f"ll:{round(float(r['lat']), 3)}:{round(float(r['lon']), 3)}"
        else:
            continue  # skip unknowns — no useful station label
        b = buckets.setdefault(
            key,
            {
                "cluster_id": key,
                "name": r["city"],
                "lat": float(r["lat"]) if r["lat"] is not None else None,
                "lon": float(r["lon"]) if r["lon"] is not None else None,
                "fillup_count": 0,
                "rows": [],
            },
        )
        b["fillup_count"] += 1
        if len(b["rows"]) < keep_per:
            b["rows"].append(
                {
                    "date": r["fillup_date"].isoformat(),
                    "price_per_unit": float(r["price_per_unit"]),
                    "fuel_volume": float(r["fuel_volume"] or 0),
                }
            )
        # First non-null name wins for stations with mixed labels.
        if not b["name"] and r["city"]:
            b["name"] = r["city"]

    out = []
    for b in buckets.values():
        recent = b["rows"][:limit_per_station]
        if not recent:
            continue
        latest = recent[0]
        avg_window = recent[1:] if len(recent) > 1 else recent
        avg_price = sum(x["price_per_unit"] for x in avg_window) / len(avg_window)
        delta_pct = (
            ((latest["price_per_unit"] - avg_price) / avg_price * 100.0)
            if avg_price > 0
            else None
        )
        out.append(
            {
                "cluster_id": b["cluster_id"],
                "name": b["name"],
                "lat": b["lat"],
                "lon": b["lon"],
                "fillup_count": b["fillup_count"],
                "latest_price": latest["price_per_unit"],
                "latest_date": latest["date"],
                "avg_price": round(avg_price, 4),
                "delta_pct": round(delta_pct, 2) if delta_pct is not None else None,
                "recent": recent,
            }
        )
    return sorted(out, key=lambda x: x["latest_date"], reverse=True)


@router.get(
    "/eia-weekly",
    dependencies=[Depends(require_query_token)],
)
async def eia_weekly(
    region: str = Query(default="us"),
    weeks: int = Query(default=52, ge=1, le=520),
    pool: asyncpg.Pool = Depends(get_pool),
) -> dict[str, Any]:
    """EIA weekly retail-gasoline averages for a region.

    Returns the last `weeks` Mondays of regular-grade $/gal pulled by
    workers/eia_fetcher. Region codes: us, east_coast, new_england,
    central_atlantic, lower_atlantic, midwest, gulf_coast, rocky_mtn,
    west_coast, california.

    Used by the frontend Overview hero card for "vs region avg" delta
    and (optionally) overlaid on the $/gal trend chart.
    """
    async with pool.acquire() as conn:
        rows = await conn.fetch(
            """
            SELECT week_of, price_usd_per_gal
              FROM fuel_market_weekly
             WHERE region = $1 AND grade = 'regular'
             ORDER BY week_of DESC
             LIMIT $2
            """,
            region,
            weeks,
        )
    return {
        "region": region,
        "points": [
            {"week_of": r["week_of"].isoformat(), "price": float(r["price_usd_per_gal"])}
            for r in rows
        ],
    }


@router.get("/mpg-overlay", dependencies=[Depends(require_query_token)])
async def mpg_overlay(
    vehicle_id: UUID = Query(...),
    from_: datetime | None = Query(default=None, alias="from"),
    to: datetime | None = Query(default=None),
    pool: asyncpg.Pool = Depends(get_pool),
) -> dict[str, Any]:
    """OBD-derived MPG (per trip) vs fillup-pump MPG (per full fill)."""
    if from_ is None:
        from_ = datetime.now(UTC) - timedelta(days=365)
    if to is None:
        to = datetime.now(UTC)

    async with pool.acquire() as conn:
        trips = await conn.fetch(
            """
            SELECT ended_at, distance_km, fuel_used_l
              FROM trips
             WHERE vehicle_id = $1
               AND ended_at IS NOT NULL
               AND ended_at >= $2 AND ended_at <= $3
               AND distance_km > 0 AND fuel_used_l > 0
             ORDER BY ended_at ASC
            """,
            vehicle_id,
            from_,
            to,
        )
        fills = await conn.fetch(
            """
            SELECT id, fillup_date, odo, fuel_volume, is_full
              FROM fillups
             WHERE vehicle_id = $1
             ORDER BY fillup_date ASC
            """,
            vehicle_id,
        )

    # OBD: km / L → mi/gal
    km_per_mi = 1.609344
    l_per_gal = 3.785411784
    obd_series = []
    for t in trips:
        km = float(t["distance_km"])
        liters = float(t["fuel_used_l"])
        if liters <= 0:
            continue
        mpg = (km / km_per_mi) / (liters / l_per_gal)
        obd_series.append({"time": t["ended_at"].isoformat(), "mpg": round(mpg, 3)})

    chain = [dict(r) for r in fills]
    mpg_map = compute_recomputed_mpg(chain)
    fillup_series = []
    for r in chain:
        if from_ and r["fillup_date"] < from_:
            continue
        if to and r["fillup_date"] > to:
            continue
        m = mpg_map.get(r["id"])
        if m is None:
            continue
        fillup_series.append(
            {"time": r["fillup_date"].isoformat(), "mpg": round(float(m), 3)}
        )

    return {"obd_mpg": obd_series, "fillup_mpg": fillup_series}
