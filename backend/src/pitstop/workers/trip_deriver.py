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
# Look back 24h on every cycle. Anything shorter and a sliding
# window misses bridge engine_on events that are older than the
# window edge — the algorithm then falls back to "first activity
# sample" as the trip start, which produces a NEW trip row (with
# a different deterministic UUID) on every subsequent cycle. 24h
# of pid_readings + engine_events is a few thousand rows for an
# active vehicle, well under 100ms per cycle.
LOOKBACK_S = 24 * 3_600
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

    Hard boundaries (kind="off" / kind="on"):
      - Bridge engine_state=on / =off — phone OBD-frame detector,
        accurate to seconds. Always trusted as authoritative.
    Soft signals (kind="activity"):
      - WiCAN LWT on / off — driven by broker keepalive + retained
        publishes. Both sides can fire spuriously: a `wican_lwt off`
        on WiFi blip, or a retained `wican_lwt on` re-delivered when
        the *backend* reconnects to the broker (which the deriver
        previously treated as a real key-on event, opening trips
        with timestamps from minutes earlier than the actual drive).
        Coerce both to "activity" so they groups across blips and
        only the bridge events draw hard boundaries.
    """
    rows = await conn.fetch(
        """
        -- Any successful OBD frame counts as activity. The bridge
        -- only writes pid_readings rows when the WiCAN gets a
        -- successful response from the ECU; STOPPED errors don't
        -- produce a row. So presence of a value_num IS NOT NULL
        -- row for any of these metrics = "the ECU was responding
        -- at this instant" = drive ongoing.
        --
        -- Handles Honda i-stop correctly: during auto-idle-stop,
        -- engine_rpm drops to 0 but the ECU keeps responding
        -- (vehicle_speed=0, coolant_temp=87, etc. all come back
        -- with valid value_num). Activity continues, no split.
        -- True engine-off → WiCAN STOPPED → no rows → activity-
        -- gap eventually closes the interval.
        SELECT time, 'activity' AS kind FROM pid_readings
         WHERE vehicle_id = $1 AND time >= $2
           AND metric IN ('vehicle_speed', 'engine_rpm',
                          'coolant_temp', 'control_module_voltage')
           AND value_num IS NOT NULL
        UNION ALL
        SELECT time, 'activity' AS kind FROM gps_points
         WHERE vehicle_id = $1 AND time >= $2
           AND speed_mps IS NOT NULL AND speed_mps > 1.0
        UNION ALL
        SELECT time,
               CASE
                   WHEN source = 'bridge' AND state = 'off' THEN 'off'
                   WHEN source = 'bridge' AND state = 'on' THEN 'on'
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
      - "off" (bridge-source only) hard-closes the current interval.
      - "on" (bridge-source only) is an authoritative trip-start —
        ALWAYS resets the interval start to its own time, even if
        earlier activity was already opening one. Earlier "activity"
        samples within the merge window are treated as pre-departure
        noise (retained MQTT, GPS chatter, brief WiCAN wake-up
        before key-on) and discarded by the reset.
      - "activity" extends an existing interval or opens one when
        none is active. A signal-gap > MERGE_GAP_S between activity
        samples closes the current interval (catches drives where
        engine_off was lost or never published).
    """
    out: list[_Interval] = []
    start: datetime | None = None
    last: datetime | None = None
    for s in samples:
        if s.kind == "off":
            if start is not None and last is not None:
                end = max(last, s.time)
                out.append(_Interval(start, end))
            start = None
            last = None
            continue
        if s.kind == "on":
            # Authoritative new trip starts here. Close any pending
            # interval (as a separate trip) and reset.
            if start is not None and last is not None and last < s.time:
                out.append(_Interval(start, last))
            start = s.time
            last = s.time
            continue
        # activity
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


async def _refresh_latest_odo(
    conn: asyncpg.Connection,
    vehicle_id: uuid.UUID,
) -> None:
    """Pull the freshest odometer reading from pid_readings and update
    vehicles.latest_odo_km if it's newer than the stored value.

    Uses latest-by-time (not max-by-value). The original max(value_num)
    pattern let a single glitched CAN frame poison the field permanently
    — observed 2026-06-03 with a burst of 10,496,563 km samples from a
    pre-retain-fix WiCAN cycle that lifted the Pilot's "lifetime
    odometer" by 85× and never relaxed.

    Sanity-rejects implausibly-large values (no consumer car odometer
    crosses 1,000,000 km / ~620k mi). Caps don't filter the underlying
    pid_readings rows (those land via the ingest path), only what we
    promote to vehicles.latest_odo_km.
    """
    row = await conn.fetchrow(
        """
        SELECT time AS t, value_num AS odo
          FROM pid_readings
         WHERE vehicle_id = $1
           AND metric = 'odometer'
           AND value_num IS NOT NULL
           AND value_num > 0
           AND value_num < 1000000
         ORDER BY time DESC
         LIMIT 1
        """,
        vehicle_id,
    )
    if row is None or row["t"] is None or row["odo"] is None:
        return
    await conn.execute(
        """
        UPDATE vehicles
           SET latest_odo_km = $2,
               latest_odo_at = $3
         WHERE id = $1
           AND ($3 > latest_odo_at OR latest_odo_at IS NULL OR latest_odo_km IS NULL)
        """,
        vehicle_id, float(row["odo"]), row["t"],
    )


async def _derive_for_vehicle(
    conn: asyncpg.Connection,
    vehicle_id: uuid.UUID,
    since: datetime,
    cfg: "Settings",
) -> int:
    """Build + upsert trips for one vehicle since `since`. Returns the
    number of trip rows touched (added + updated).
    """
    # Refresh odometer first — cheap, makes Overview's "miles since
    # last fill" stat fresh on every deriver cycle (5 min).
    await _refresh_latest_odo(conn, vehicle_id)
    samples = await _activity_samples(conn, vehicle_id, since)
    if not samples:
        return 0

    # Skip ranges that already have a trip we mustn't touch:
    #   - phone-sourced trips (#119) — the phone owns drive boundaries
    #     for any vehicle with a bridge; this deriver only fills in
    #     legacy gaps and WiCAN-only driveway sessions.
    #   - manual_merge trips (MERGE-1) — the user explicitly combined
    #     two drives via POST /trips/{id}/merge; re-deriving the gap
    #     would just split them back apart.
    skip_ranges = await conn.fetch(
        """
        SELECT started_at, ended_at
          FROM trips
         WHERE vehicle_id = $1
           AND source IN ('phone_batch', 'manual_merge')
           AND ended_at >= $2
         ORDER BY started_at
        """,
        vehicle_id, since,
    )

    def _in_skip_range(t: datetime) -> bool:
        # Linear scan — skip_ranges is small (10s of rows in 24h
        # lookback even at peak driving).
        for r in skip_ranges:
            if r["started_at"] <= t <= r["ended_at"]:
                return True
        return False

    if skip_ranges:
        samples = [s for s in samples if not _in_skip_range(s.time)]
        if not samples:
            log.info(
                "deriver: all samples in skip ranges, skipping",
                extra={
                    "vehicle_id": str(vehicle_id),
                    "skip_ranges": len(skip_ranges),
                },
            )
            return 0

    intervals = _build_intervals(samples)

    # Self-cleanup: delete deriver trips whose started_at falls
    # strictly inside one of the new intervals (but isn't itself an
    # interval start). These are stale outputs from earlier deriver
    # runs that split a single drive into multiple trips — typically
    # caused by an idle-stop split before the engine_rpm > 0 fix.
    # The new run merged them into one longer trip; the shorter
    # leftover would otherwise show as a duplicate in the trips list.
    new_starts = {iv.started_at for iv in intervals}
    stale_purged = 0
    for iv in intervals:
        rows = await conn.fetch(
            """
            DELETE FROM trips
             WHERE vehicle_id = $1
               AND source = 'deriver'
               AND started_at >  $2
               AND ended_at   <= $3
            RETURNING id, started_at
            """,
            vehicle_id, iv.started_at, iv.ended_at,
        )
        for r in rows:
            if r["started_at"] in new_starts:
                continue  # shouldn't happen given the strict > but defensive
            stale_purged += 1
            log.info(
                "deriver: purged stale trip merged into longer interval",
                extra={
                    "vehicle_id": str(vehicle_id),
                    "purged_id": str(r["id"]),
                    "purged_started": r["started_at"].isoformat(),
                    "absorbed_into_started": iv.started_at.isoformat(),
                    "absorbed_into_ended": iv.ended_at.isoformat(),
                },
            )
    if stale_purged > 0:
        log.info(
            "deriver: self-cleanup removed %d stale trip(s)",
            stale_purged,
            extra={"vehicle_id": str(vehicle_id)},
        )

    touched = 0
    for iv in intervals:
        # Defense in depth — if an interval *overlaps* a protected
        # range (phone-sourced or manually-merged trip), drop it. The
        # phone may have closed slightly past our last sample, or a
        # manual_merge may have absorbed a gap the deriver still wants
        # to slot a trip into.
        if any(
            r["started_at"] < iv.ended_at and r["ended_at"] > iv.started_at
            for r in skip_ranges
        ):
            log.warning(
                "deriver: interval overlaps protected trip, skipping",
                extra={
                    "vehicle_id": str(vehicle_id),
                    "interval_started": iv.started_at.isoformat(),
                    "interval_ended": iv.ended_at.isoformat(),
                },
            )
            continue
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
                avg_coolant_c, fuel_used_l, dtc_count, idle_s,
                weather_temp_c, weather_humidity_pct,
                weather_precip_mm, weather_wind_kph, weather_code,
                source
            ) VALUES (
                $1, $2, $3, $4, $5,
                $6, $7, $8, $9,
                $10, $11, $12, $13,
                $14, $15, $16, $17, $18,
                'deriver'
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
                idle_s = EXCLUDED.idle_s,
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
            stats["fuel_used_l"], stats["dtc_count"], stats.get("idle_s"),
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
            since = datetime.now(UTC) - timedelta(seconds=LOOKBACK_S)
            summary = await derive_window(pool, cfg, since=since)
            touched = sum(summary.values())
            if touched:
                log.info("trip deriver cycle: %s trips touched %s", touched, summary)
        except asyncio.CancelledError:
            raise
        except Exception as exc:  # noqa: BLE001
            log.error("trip deriver cycle failed: %s", exc)
        await asyncio.sleep(CYCLE_INTERVAL_S)
