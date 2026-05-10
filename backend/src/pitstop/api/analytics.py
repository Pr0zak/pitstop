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
from fastapi import APIRouter, Depends, HTTPException, Query

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


# Distance bucket boundaries in km (5 buckets: <3.2, <16, <48, <161, ≥161 km
# = <2 mi, <10 mi, <30 mi, <100 mi, ≥100 mi). Matches the task description's
# bucketing — keeps the math fair when comparing 1-mi school runs against
# 90-mi commutes.
_TRIP_BUCKETS_KM = [3.2, 16, 48, 161]
_TRIP_BUCKET_LABELS = ["<2 mi", "2-10 mi", "10-30 mi", "30-100 mi", "100+ mi"]


def _bucket_index(km: float) -> int:
    for i, edge in enumerate(_TRIP_BUCKETS_KM):
        if km < edge:
            return i
    return len(_TRIP_BUCKETS_KM)


@router.get("/fuel-grade", dependencies=[Depends(require_query_token)])
async def fuel_grade_breakdown(
    vehicle_id: UUID = Query(...),
    pool: asyncpg.Pool = Depends(get_pool),
) -> dict[str, Any]:
    """Per-grade fuel comparison (Task #93). Groups fillups by
    `fuel_type` and reports recomputed MPG, mean price/unit, total
    volume, total cost, and fillup count per grade. Lets the user
    see whether premium is buying them anything.

    Recomputed MPG is the Fuelio-style chain over each grade's
    fillups *in isolation* — that's the apples-to-apples way to
    compare grades, not the global chain that mixes them.
    """
    async with pool.acquire() as conn:
        fills = await conn.fetch(
            """
            SELECT id, fillup_date, odo, fuel_volume, is_full,
                   fuel_type, price_total, price_per_unit
              FROM fillups
             WHERE vehicle_id = $1
               AND fuel_type IS NOT NULL
             ORDER BY fillup_date ASC
            """,
            vehicle_id,
        )

    if not fills:
        return {"grades": []}

    grades: dict[int, list[dict[str, Any]]] = defaultdict(list)
    for r in fills:
        grades[int(r["fuel_type"])].append(dict(r))

    out: list[dict[str, Any]] = []
    for grade, rows in grades.items():
        # Compute per-grade chain MPG
        chain = sorted(rows, key=lambda r: r["fillup_date"])
        mpg_map = compute_recomputed_mpg(chain)
        mpgs = [float(v) for v in mpg_map.values() if v is not None]
        avg_mpg = sum(mpgs) / len(mpgs) if mpgs else None

        prices = [
            float(r["price_per_unit"]) for r in rows
            if r["price_per_unit"] is not None
        ]
        avg_price = sum(prices) / len(prices) if prices else None
        total_volume = sum(
            float(r["fuel_volume"]) for r in rows if r["fuel_volume"] is not None
        )
        total_cost = sum(
            float(r["price_total"]) for r in rows if r["price_total"] is not None
        )
        out.append({
            "grade": grade,
            "fillup_count": len(rows),
            "avg_mpg": round(avg_mpg, 2) if avg_mpg is not None else None,
            "avg_price_per_unit": round(avg_price, 3) if avg_price is not None else None,
            "total_volume": round(total_volume, 2),
            "total_cost": round(total_cost, 2),
            "first_fillup": chain[0]["fillup_date"].isoformat(),
            "last_fillup": chain[-1]["fillup_date"].isoformat(),
        })
    out.sort(key=lambda g: g["fillup_count"], reverse=True)
    return {"grades": out}


@router.get("/cost-of-ownership", dependencies=[Depends(require_query_token)])
async def cost_of_ownership(
    vehicle_id: UUID = Query(...),
    pool: asyncpg.Pool = Depends(get_pool),
) -> dict[str, Any]:
    """Lifetime $/mile breakdown (Task #98). Sums fillups + expenses
    + optional purchase price; divides by lifetime miles. The honest
    "what is this car costing me?" number.

    Returns components separately so the UI can render a stacked
    bar / breakdown without re-fetching. `cost_per_mi` is null when
    we don't yet have a meaningful odometer (latest_odo_km - the
    odometer at purchase isn't computable).
    """
    async with pool.acquire() as conn:
        v = await conn.fetchrow(
            """
            SELECT id, purchase_price, purchase_date, latest_odo_km, dist_unit
              FROM vehicles
             WHERE id = $1
            """,
            vehicle_id,
        )
        if v is None:
            raise HTTPException(status_code=404, detail="vehicle not found")
        fuel_total = await conn.fetchval(
            "SELECT COALESCE(SUM(price_total), 0) FROM fillups WHERE vehicle_id = $1",
            vehicle_id,
        )
        # Maintenance = sum of expenses where is_income is not true.
        maint_total = await conn.fetchval(
            """
            SELECT COALESCE(SUM(cost), 0)
              FROM expenses
             WHERE vehicle_id = $1
               AND COALESCE(is_income, false) = false
               AND COALESCE(is_template, false) = false
            """,
            vehicle_id,
        )

    purchase_price = (
        float(v["purchase_price"]) if v["purchase_price"] is not None else None
    )
    fuel_total_f = float(fuel_total or 0)
    maint_total_f = float(maint_total or 0)
    total = (purchase_price or 0) + fuel_total_f + maint_total_f

    # Lifetime miles: prefer latest_odo_km; null when no odometer
    # data yet. We can't subtract a "purchase odometer" because it's
    # not stored — caveat documented in the response.
    latest_odo_km = float(v["latest_odo_km"]) if v["latest_odo_km"] is not None else None
    lifetime_mi = latest_odo_km / 1.609344 if latest_odo_km is not None else None

    cost_per_mi = (
        round(total / lifetime_mi, 3)
        if lifetime_mi and lifetime_mi > 0
        else None
    )

    return {
        "purchase_price": purchase_price,
        "purchase_date": v["purchase_date"].isoformat() if v["purchase_date"] else None,
        "fuel_total": round(fuel_total_f, 2),
        "maintenance_total": round(maint_total_f, 2),
        "total": round(total, 2),
        "lifetime_mi": round(lifetime_mi, 1) if lifetime_mi else None,
        "cost_per_mi": cost_per_mi,
    }


@router.get("/anomalies", dependencies=[Depends(require_query_token)])
async def anomalies(
    vehicle_id: UUID = Query(...),
    pool: asyncpg.Pool = Depends(get_pool),
) -> dict[str, Any]:
    """Compute the headline-worthy anomalies for a vehicle. v1 covers
    only "MPG below personal baseline at this temperature" — other
    types (cost/mile drift, station price shock, DTC spike) plug in
    later as additional checkers (Task #86).

    Returns at most one anomaly per type, sorted by severity. The
    frontend keeps a localStorage dismiss-cooldown so a user who
    explicitly waved off "MPG dropped" doesn't get nagged by the
    same one for a week.
    """
    out: list[dict[str, Any]] = []

    async with pool.acquire() as conn:
        # Build temp-bucketed MPG baseline from all fillups that have
        # weather + recomputed MPG. Only chains long enough to get
        # decent coverage land here (skip vehicles with < 8 weather-
        # tagged fillups — too noisy).
        fills = await conn.fetch(
            """
            SELECT id, fillup_date, odo, fuel_volume, is_full,
                   weather_temp_c
              FROM fillups
             WHERE vehicle_id = $1
             ORDER BY fillup_date ASC
            """,
            vehicle_id,
        )
        v = await conn.fetchrow(
            "SELECT dist_unit, fuel_unit FROM vehicles WHERE id = $1",
            vehicle_id,
        )

    if v is None or len(fills) < 8:
        return {"anomalies": out}
    chain = [dict(r) for r in fills]
    mpg_map = compute_recomputed_mpg(chain)
    weathered: list[tuple[float, float, datetime]] = []  # (temp_c, mpg, date)
    for r in chain:
        m = mpg_map.get(r["id"])
        if m is None or r["weather_temp_c"] is None:
            continue
        weathered.append((float(r["weather_temp_c"]), float(m), r["fillup_date"]))

    if len(weathered) < 8:
        return {"anomalies": out}

    # Bucket by 10°F (≈5.6°C). Mean per bucket.
    buckets: dict[int, list[float]] = defaultdict(list)
    for tc, mpg, _ in weathered:
        tf = tc * 9 / 5 + 32
        bucket_idx = int(tf // 10)
        buckets[bucket_idx].append(mpg)
    bucket_mean: dict[int, float] = {
        i: sum(v) / len(v) for i, v in buckets.items() if len(v) >= 2
    }

    # Look at the most recent 3 fillups with weather. Compute
    # mean deviation from bucket baseline.
    recent = weathered[-3:]
    if len(recent) < 3:
        return {"anomalies": out}
    deviations: list[tuple[float, float, int]] = []  # (mpg, expected, bucket)
    for tc, mpg, _ in recent:
        tf = tc * 9 / 5 + 32
        bucket_idx = int(tf // 10)
        expected = bucket_mean.get(bucket_idx)
        if expected is None:
            continue
        deviations.append((mpg, expected, bucket_idx))
    if len(deviations) < 2:
        return {"anomalies": out}

    avg_actual = sum(d[0] for d in deviations) / len(deviations)
    avg_expected = sum(d[1] for d in deviations) / len(deviations)
    pct = (avg_actual - avg_expected) / avg_expected * 100
    if pct < -7:
        # Bucket the warning by its dominant temperature band
        bucket_idx = max((d[2] for d in deviations), key=[d[2] for d in deviations].count)
        bucket_lo = bucket_idx * 10
        bucket_hi = bucket_lo + 10
        out.append({
            "type": "mpg_below_baseline",
            "severity": "warn" if pct > -12 else "danger",
            "headline": (
                f"MPG is {abs(pct):.1f}% below your baseline at "
                f"{bucket_lo}–{bucket_hi}°F"
            ),
            "detail": (
                f"Last {len(deviations)} fillups: {avg_actual:.1f} mpg "
                f"vs {avg_expected:.1f} expected for this temperature."
            ),
            "deep_link": "/analytics",
            # Fingerprint lets the frontend store a dismiss cooldown
            # without re-nagging on every page load.
            "fingerprint": f"mpg-bucket-{bucket_idx}",
        })

    return {"anomalies": out}


@router.get("/odometer", dependencies=[Depends(require_query_token)])
async def odometer_history(
    vehicle_id: UUID = Query(...),
    window: Literal["year", "3y", "all"] = Query(default="all"),
    pool: asyncpg.Pool = Depends(get_pool),
) -> dict[str, Any]:
    """Deduped odometer line over time (Task #101). Pulls from
    `pid_readings` where metric='odometer' and only emits a sample
    when the value changes from the prior point — long runs of
    identical readings between km transitions collapse to a single
    edge. Augmented with fillup `odo` values converted to km when
    the fillup pre-dates the WiCAN coverage window. Capped to ~500
    points by uniform downsampling so a 5-year history stays fast.
    """
    # Pull pid_readings odometer history
    if window == "year":
        cutoff = datetime.now(UTC) - timedelta(days=365)
    elif window == "3y":
        cutoff = datetime.now(UTC) - timedelta(days=3 * 365)
    else:
        cutoff = datetime.fromtimestamp(0, UTC)

    async with pool.acquire() as conn:
        pid_rows = await conn.fetch(
            """
            SELECT time, value_num
              FROM pid_readings
             WHERE vehicle_id = $1
               AND metric = 'odometer'
               AND value_num IS NOT NULL
               AND time >= $2
             ORDER BY time ASC
            """,
            vehicle_id, cutoff,
        )
        # Fillup odometers — Fuelio-style stored in either km or mi
        # depending on vehicle.dist_unit. Read the unit once and
        # normalise to km here.
        v = await conn.fetchrow(
            "SELECT dist_unit FROM vehicles WHERE id = $1", vehicle_id,
        )
        fill_rows = await conn.fetch(
            """
            SELECT fillup_date, odo
              FROM fillups
             WHERE vehicle_id = $1
               AND odo IS NOT NULL
               AND fillup_date >= $2
             ORDER BY fillup_date ASC
            """,
            vehicle_id, cutoff,
        )

    dist_unit = int(v["dist_unit"]) if v and v["dist_unit"] is not None else 1
    mi_to_km = 1.609344
    fillup_points: list[tuple[datetime, float]] = []
    for r in fill_rows:
        odo_native = float(r["odo"])
        odo_km = odo_native * mi_to_km if dist_unit == 1 else odo_native
        fillup_points.append((r["fillup_date"], odo_km))

    pid_points: list[tuple[datetime, float]] = [
        (r["time"], float(r["value_num"])) for r in pid_rows
    ]

    # Merge: prefer pid_readings inside the WiCAN-covered window;
    # take fillups for the prefix the live data doesn't reach.
    merged: list[tuple[datetime, float]] = []
    if pid_points:
        first_pid_t = pid_points[0][0]
        for ts, odo in fillup_points:
            if ts < first_pid_t:
                merged.append((ts, odo))
        merged.extend(pid_points)
    else:
        merged = fillup_points

    # Dedupe consecutive identical readings — keep the first sample
    # of each plateau, plus the last seen reading so the chart tail
    # always reflects the freshest value.
    deduped: list[tuple[datetime, float]] = []
    for ts, odo in merged:
        if not deduped or deduped[-1][1] != odo:
            deduped.append((ts, odo))
    if merged and (not deduped or deduped[-1][0] != merged[-1][0]):
        deduped.append(merged[-1])

    # Cap to ~500 points by uniform downsampling.
    cap = 500
    if len(deduped) > cap:
        step = len(deduped) / cap
        deduped = [deduped[int(i * step)] for i in range(cap)] + [deduped[-1]]

    points = [
        {"time": ts.isoformat(), "odo_km": round(odo, 3)}
        for ts, odo in deduped
    ]

    # Summary: total Δ over the window + miles/day mean
    summary: dict[str, Any] = {"window": window, "n_points": len(points)}
    if len(deduped) >= 2:
        first_ts, first_odo = deduped[0]
        last_ts, last_odo = deduped[-1]
        delta_km = last_odo - first_odo
        days = max((last_ts - first_ts).total_seconds() / 86400.0, 1)
        summary["delta_km"] = round(delta_km, 1)
        summary["delta_mi"] = round(delta_km / mi_to_km, 1)
        summary["miles_per_day"] = round((delta_km / mi_to_km) / days, 2)
        summary["current_km"] = round(last_odo, 1)
        summary["current_mi"] = round(last_odo / mi_to_km, 1)
    return {"points": points, "summary": summary}


@router.get("/trip-baseline", dependencies=[Depends(require_query_token)])
async def trip_baseline(
    vehicle_id: UUID = Query(...),
    distance_km: float = Query(..., gt=0),
    pool: asyncpg.Pool = Depends(get_pool),
) -> dict[str, Any]:
    """Average MPG / speed / duration for trips in the same distance
    bucket as `distance_km`. Powers the "this trip vs your average"
    panel on TripDetail (Task #112).

    Returns `null` averages when the bucket has < 5 samples — the UI
    hides the panel in that case to avoid drawing conclusions from a
    single trip.
    """
    bucket_i = _bucket_index(distance_km)
    lo = _TRIP_BUCKETS_KM[bucket_i - 1] if bucket_i > 0 else 0.0
    hi = _TRIP_BUCKETS_KM[bucket_i] if bucket_i < len(_TRIP_BUCKETS_KM) else None
    where_hi = "AND distance_km < $3" if hi is not None else ""

    args: list[Any] = [vehicle_id, lo]
    if hi is not None:
        args.append(hi)

    async with pool.acquire() as conn:
        row = await conn.fetchrow(
            f"""
            SELECT
                count(*) AS n,
                avg(distance_km) AS avg_km,
                avg(duration_s) AS avg_duration_s,
                avg(avg_speed_kph) AS avg_speed_kph,
                avg(max_speed_kph) AS avg_max_speed_kph,
                -- Recomputed MPG: km/mi ÷ L/gal, only for trips with
                -- meaningful fuel data
                avg(
                  CASE WHEN fuel_used_l > 0.4
                       THEN (distance_km / 1.609344) / (fuel_used_l / 3.785411784)
                  END
                ) AS avg_mpg
              FROM trips
             WHERE vehicle_id = $1
               AND ended_at IS NOT NULL
               AND distance_km IS NOT NULL
               AND distance_km >= $2
               {where_hi}
            """,
            *args,
        )

    n = int(row["n"] or 0)
    sufficient = n >= 5
    return {
        "bucket_label": _TRIP_BUCKET_LABELS[bucket_i],
        "sample_size": n,
        "sufficient": sufficient,
        "avg_distance_km": float(row["avg_km"]) if sufficient and row["avg_km"] else None,
        "avg_duration_s": int(row["avg_duration_s"]) if sufficient and row["avg_duration_s"] else None,
        "avg_speed_kph": float(row["avg_speed_kph"]) if sufficient and row["avg_speed_kph"] else None,
        "avg_max_speed_kph": float(row["avg_max_speed_kph"]) if sufficient and row["avg_max_speed_kph"] else None,
        "avg_mpg": float(row["avg_mpg"]) if sufficient and row["avg_mpg"] else None,
    }
