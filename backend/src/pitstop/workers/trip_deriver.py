"""Periodic trip derivation worker.

Replaces the streaming :mod:`trip_detector` with a batch derivator that
rebuilds the ``trips`` table from the underlying ``pid_readings`` /
``engine_events`` / ``gps_points`` data on a 5-minute cycle. Looking
at the full data window makes boundary decisions robust against the
noisy real-time signals (brief WiCAN LWT blips, late GPS fixes,
out-of-order phone-buffer drains) that fooled the streaming detector.

Algorithm per vehicle:
    1. Pull all ``vehicle_speed > 0`` readings + ``engine_state``
       events + GPS points in the watermark window.
    2. Sort, then walk forward building activity intervals: contiguous
       windows where some signal says "the car is doing things". Gaps
       under ``MERGE_GAP_S`` (default 5 min) are absorbed — covers stops
       at lights, brief LWT keepalive blips mid-drive, etc.
    3. Drop intervals shorter than ``MIN_DURATION_S`` or whose distance
       falls below ``cfg.trip_min_distance_km``.
    4. Compute stats from :func:`compute_trip_stats` (haversine on
       gps_points, falls back to vehicle_speed integration).
    5. Upsert into ``trips`` keyed on a deterministic
       ``(vehicle_id, started_at)`` UUID v5 so re-running this worker
       is idempotent. ``category`` and ``notes`` are preserved on
       conflict — only stat columns get overwritten.

The watermark is tracked in-memory: the worker remembers the latest
event time it saw last cycle and starts the next cycle a configurable
``LOOKBACK_S`` earlier (5 minutes) to catch stragglers from offline
buffer drains.
"""

from __future__ import annotations

import asyncio
import logging
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from typing import TYPE_CHECKING

import asyncpg

if TYPE_CHECKING:
    from ..config import Settings

# Reuse compute_trip_stats from the streaming detector — same window
# math (haversine on gps_points, speed integration fallback) we already
# trust in production.
from .trip_detector import compute_trip_stats

log = logging.getLogger(__name__)


CYCLE_INTERVAL_S = 5 * 60          # 5 minutes
LOOKBACK_S = 5 * 60                 # tail-walk back 5 min on each cycle
# Bridge any signal-gap shorter than this within an activity interval.
MERGE_GAP_S = 5 * 60                # 5 minutes
# Discard intervals shorter than this regardless of distance.
MIN_DURATION_S = 30
# Deterministic UUID namespace for trip ids — ensures the same vehicle
# + started_at pair always maps to the same row across restarts.
_TRIP_NS = uuid.UUID("8d0e5c41-4f23-4d1e-9c42-9f6b6b3e2a01")


@dataclass(slots=True)
class _Interval:
    started_at: datetime
    ended_at: datetime


def _trip_id_for(vehicle_id: uuid.UUID, started_at: datetime) -> uuid.UUID:
    seed = f"{vehicle_id}|{started_at.isoformat()}"
    return uuid.uuid5(_TRIP_NS, seed)


@dataclass(slots=True)
class _Sample:
    time: datetime
    kind: str  # "activity" | "on" | "off"


async def _activity_samples(
    conn: asyncpg.Connection,
    vehicle_id: uuid.UUID,
    since: datetime,
) -> list[_Sample]:
    """Return sorted activity samples + engine on/off events.

    Activity sources (kind="activity"):
      - vehicle_speed > 0 readings (driving)
      - GPS points faster than 1 m/s (moving)

    Hard boundaries (kind="off"):
      - Bridge engine_state=off — phone OBD STOPPED-frame detector,
        accurate to seconds. Always trusted.
    Soft signals (kind="activity"):
      - WiCAN LWT off — broker keepalive timeout, can blip false on
        WiFi interruption. We DON'T treat it as a hard boundary here;
        instead it's coerced to "activity" so the merge-gap logic
        still groups across the blip. If the engine truly is off, a
        bridge "off" or 5-min activity gap will close the interval.
      - All "on" events (regardless of source) extend/open intervals.
    """
    rows = await conn.fetch(
        """
        SELECT time, 'activity' AS kind FROM pid_readings
         WHERE vehicle_id = $1 AND time >= $2
           AND metric = 'vehicle_speed' AND value_num IS NOT NULL
           AND value_num > 0
        UNION ALL
        SELECT time, 'activity' AS kind FROM gps_points
         WHERE vehicle_id = $1 AND time >= $2
           AND speed_mps IS NOT NULL AND speed_mps > 1.0
        UNION ALL
        SELECT time,
               CASE
                   WHEN state = 'off' AND source = 'bridge' THEN 'off'
                   WHEN state = 'on' THEN 'on'
                   ELSE 'activity'
               END AS kind
          FROM engine_events
         WHERE vehicle_id = $1 AND time >= $2
        ORDER BY 1
        """,
        vehicle_id, since,
    )
    return [_Sample(time=r["time"], kind=r["kind"]) for r in rows]


def _build_intervals(samples: list[_Sample]) -> list[_Interval]:
    """Walk the samples and produce activity intervals.

    Rules:
      - "on" or "activity" extends or opens an interval.
      - "off" hard-closes the current interval (engine actually
        stopped — no merge across this).
      - A signal-gap > MERGE_GAP_S between consecutive activity samples
        also closes the current interval (catches drives where the
        engine_off event was lost / never published).
    """
    out: list[_Interval] = []
    start: datetime | None = None
    last: datetime | None = None
    for s in samples:
        if s.kind == "off":
            if start is not None and last is not None:
                # Use the off-event time if it's after `last`; otherwise
                # cap at last activity (off can fire late after silence).
                end = max(last, s.time)
                out.append(_Interval(start, end))
            start = None
            last = None
            continue
        # "on" or "activity"
        if start is None:
            start = s.time
            last = s.time
            continue
        if (s.time - last).total_seconds() > MERGE_GAP_S:  # type: ignore[operator]
            out.append(_Interval(start, last))  # type: ignore[arg-type]
            start = s.time
        last = s.time
    if start is not None and last is not None:
        out.append(_Interval(start, last))
    return out


async def _derive_for_vehicle(
    conn: asyncpg.Connection,
    vehicle_id: uuid.UUID,
    since: datetime,
    cfg: "Settings",
) -> int:
    """Build + upsert trips for one vehicle since `since`. Returns the
    number of trip rows touched (added + updated).
    """
    samples = await _activity_samples(conn, vehicle_id, since)
    if not samples:
        return 0
    intervals = _build_intervals(samples)
    touched = 0
    for iv in intervals:
        duration = (iv.ended_at - iv.started_at).total_seconds()
        if duration < MIN_DURATION_S:
            continue
        stats = await compute_trip_stats(
            conn, _trip_id_for(vehicle_id, iv.started_at),
            vehicle_id, iv.started_at, iv.ended_at,
        )
        distance_km = stats.get("distance_km")
        if distance_km is None or float(distance_km) < cfg.trip_min_distance_km:
            continue
        # Realtime weather hook (Task #78): grab the first GPS point
        # in the trip window and fetch the obs at trip start. Best-
        # effort — service.weather.fetch returns EMPTY_OBS on any
        # error so the trip still gets upserted with NULL weather
        # columns. Backfill worker fills the gap on the next cycle.
        from ..services.weather import fetch as fetch_weather
        gps_anchor = await conn.fetchrow(
            """
            SELECT lat, lon FROM gps_points
             WHERE vehicle_id = $1 AND time >= $2 AND time <= $3
             ORDER BY time ASC LIMIT 1
            """,
            vehicle_id, iv.started_at, iv.ended_at,
        )
        weather = None
        if gps_anchor is not None:
            weather = await fetch_weather(
                float(gps_anchor["lat"]),
                float(gps_anchor["lon"]),
                iv.started_at,
            )
        trip_id = _trip_id_for(vehicle_id, iv.started_at)
        # UPSERT: if the row already exists (same deterministic id),
        # update only the stat columns. Preserve user-set category +
        # notes — the user might have edited them since last cycle.
        # Weather columns: only overwrite when we have a fresh obs;
        # COALESCE keeps prior values intact if today's fetch failed
        # (so the backfill worker doesn't lose a previously-fetched
        # value just because the realtime path momentarily errored).
        wtemp = weather.temp_c if weather else None
        whum = weather.humidity_pct if weather else None
        wprecip = weather.precip_mm if weather else None
        wwind = weather.wind_kph if weather else None
        wcode = weather.weather_code if weather else None
        await conn.execute(
            """
            INSERT INTO trips (
                id, vehicle_id, started_at, ended_at, duration_s,
                distance_km, max_rpm, max_speed_kph, avg_speed_kph,
                avg_coolant_c, fuel_used_l, dtc_count,
                weather_temp_c, weather_humidity_pct,
                weather_precip_mm, weather_wind_kph, weather_code
            ) VALUES (
                $1, $2, $3, $4, $5,
                $6, $7, $8, $9,
                $10, $11, $12,
                $13, $14, $15, $16, $17
            )
            ON CONFLICT (id) DO UPDATE SET
                ended_at = EXCLUDED.ended_at,
                duration_s = EXCLUDED.duration_s,
                distance_km = EXCLUDED.distance_km,
                max_rpm = EXCLUDED.max_rpm,
                max_speed_kph = EXCLUDED.max_speed_kph,
                avg_speed_kph = EXCLUDED.avg_speed_kph,
                avg_coolant_c = EXCLUDED.avg_coolant_c,
                fuel_used_l = EXCLUDED.fuel_used_l,
                dtc_count = EXCLUDED.dtc_count,
                weather_temp_c = COALESCE(EXCLUDED.weather_temp_c, trips.weather_temp_c),
                weather_humidity_pct = COALESCE(EXCLUDED.weather_humidity_pct, trips.weather_humidity_pct),
                weather_precip_mm = COALESCE(EXCLUDED.weather_precip_mm, trips.weather_precip_mm),
                weather_wind_kph = COALESCE(EXCLUDED.weather_wind_kph, trips.weather_wind_kph),
                weather_code = COALESCE(EXCLUDED.weather_code, trips.weather_code)
            """,
            trip_id, vehicle_id, iv.started_at, iv.ended_at,
            stats["duration_s"], stats["distance_km"],
            stats["max_rpm"], stats["max_speed_kph"],
            stats["avg_speed_kph"], stats["avg_coolant_c"],
            stats["fuel_used_l"], stats["dtc_count"],
            wtemp, whum, wprecip, wwind, wcode,
        )
        touched += 1
    return touched


async def derive_window(
    pool: asyncpg.Pool,
    cfg: "Settings",
    *,
    since: datetime,
) -> dict[str, int]:
    """Run the derivation once for every vehicle. Used by the cron
    cycle and exposed via /admin/trips/reprocess."""
    summary: dict[str, int] = {}
    async with pool.acquire() as conn:
        vrows = await conn.fetch("SELECT id, slug FROM vehicles")
        for v in vrows:
            n = await _derive_for_vehicle(conn, v["id"], since, cfg)
            summary[v["slug"]] = n
    return summary


async def run(pool: asyncpg.Pool, cfg: "Settings") -> None:
    log.info("trip deriver started (cycle every %s s)", CYCLE_INTERVAL_S)
    while True:
        try:
            since = datetime.now(UTC) - timedelta(seconds=LOOKBACK_S * 12)
            # On every cycle look back 1h by default (12 × LOOKBACK_S).
            # That's plenty for late buffer drains without re-scanning
            # the whole history. The first cycle after a process restart
            # picks up everything in the last hour; older trips are
            # whatever the last persistent run derived.
            summary = await derive_window(pool, cfg, since=since)
            touched = sum(summary.values())
            if touched:
                log.info("trip deriver cycle: %s trips touched %s", touched, summary)
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001
            log.error("trip deriver cycle failed: %s", exc)
        await asyncio.sleep(CYCLE_INTERVAL_S)
