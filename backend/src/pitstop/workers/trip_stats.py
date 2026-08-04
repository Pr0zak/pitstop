"""Trip statistics computation.

Pure-SQL close-time stats for a single trip window, shared by both trip
importers:

- ``workers/trip_deriver.py`` — the periodic batch derivation cron.
- ``api/drive_ingest.py`` — the phone's ``POST /drives`` batch upload.

Extracted from the retired streaming ``trip_detector`` (the streaming
``TripDetector`` class is gone — ``main.py`` sets it to ``None`` and the
periodic deriver is authoritative). The window math (haversine on
gps_points with a speed-integration fallback, MAF + fuel-level fallback
fuel estimation) is unchanged from what production already trusted.
"""

from __future__ import annotations

import logging
from datetime import datetime
from uuid import UUID

import asyncpg

log = logging.getLogger(__name__)


async def compute_trip_stats(
    conn: asyncpg.Connection,
    trip_id: UUID,
    vehicle_id: UUID,
    started_at: datetime,
    ended_at: datetime,
) -> dict[str, float | int | None]:
    """Compute close-time stats from pid_readings + dtc_events."""
    duration_s = max(int((ended_at - started_at).total_seconds()), 0)

    # Aggregate the well-known metrics in one pass.
    agg = await conn.fetchrow(
        """
        SELECT
            max(CASE WHEN metric = 'engine_rpm'      THEN value_num END) AS max_rpm,
            max(CASE WHEN metric = 'vehicle_speed'   THEN value_num END) AS max_speed_kph,
            avg(CASE WHEN metric = 'vehicle_speed'   THEN value_num END) AS avg_speed_kph,
            -- Exclude WiCAN decoder-garbage coolant (the -37 C artifact,
            -- byte 0x03) so it can't drag a trip's avg; real operating
            -- coolant sits well within [-20, 150].
            avg(CASE WHEN metric = 'coolant_temp'
                      AND value_num BETWEEN -20 AND 150 THEN value_num END) AS avg_coolant_c
        FROM pid_readings
        WHERE vehicle_id = $1 AND time >= $2 AND time <= $3
        """,
        vehicle_id,
        started_at,
        ended_at,
    )

    # Distance: compute BOTH the GPS-haversine sum and the vehicle-speed
    # integration, then take the larger.
    #
    #   1) GPS haversine sum from gps_points — great-circle over 6371 km
    #      (no PostGIS shipped). Accurate when fixes are dense.
    #   2) Vehicle-speed integration (kph * dt) from OBD.
    #
    # Both queries only sum segments whose neighbours are < 60 s apart, so
    # BOTH UNDER-count when their source is sparse — never over-count. GPS
    # goes sparse on cold-start / poor-sky drives (a handful of fixes over
    # tens of minutes leaves >60 s gaps that all get dropped, collapsing a
    # 12 km drive to <1 km), while OBD speed goes sparse when the ECU stops
    # answering. Preferring GPS whenever it returned anything non-zero (the
    # old behaviour) therefore silently under-reported sparse-GPS drives by
    # ~20x. Taking the max lets whichever method captured more of the real
    # segments win; the per-method sanity filters (GPS < 250 km/h step, OBD
    # dt < 60 s) keep either from over-counting.
    gps_km = await conn.fetchval(
        """
        WITH pairs AS (
            SELECT
                lat, lon,
                LAG(lat) OVER (ORDER BY time) AS lag_lat,
                LAG(lon) OVER (ORDER BY time) AS lag_lon,
                EXTRACT(EPOCH FROM (time - LAG(time) OVER (ORDER BY time)))
                    AS dt
            FROM gps_points
            WHERE vehicle_id = $1
              AND time >= $2 AND time <= $3
        ),
        steps AS (
            SELECT
                dt,
                2 * 6371.0 * asin(sqrt(
                    pow(sin(radians(lat - lag_lat) / 2), 2) +
                    cos(radians(lag_lat)) * cos(radians(lat)) *
                    pow(sin(radians(lon - lag_lon) / 2), 2)
                )) AS step_km
            FROM pairs
            WHERE lag_lat IS NOT NULL AND dt IS NOT NULL AND dt < 60
        )
        -- Drop pairs that imply > 250 km/h apparent velocity. Those are
        -- network-provider noise spikes (alternating real / stale
        -- coords) that exploded historical trip distances. Real
        -- driving never produces a single step over the threshold.
        SELECT COALESCE(SUM(step_km), 0)
        FROM steps
        WHERE step_km / NULLIF(dt, 0) * 3600 < 250
        """,
        vehicle_id,
        started_at,
        ended_at,
    )
    speed_km = await conn.fetchval(
        """
        SELECT COALESCE(SUM(value_num * dt) / 3600.0, 0)
        FROM (
            SELECT
                value_num,
                EXTRACT(EPOCH FROM (time - LAG(time) OVER (ORDER BY time)))
                    AS dt
            FROM pid_readings
            WHERE vehicle_id = $1
              AND metric = 'vehicle_speed'
              AND time >= $2 AND time <= $3
        ) s
        WHERE dt IS NOT NULL AND dt < 60
        """,
        vehicle_id,
        started_at,
        ended_at,
    )
    distance_km = max(float(gps_km or 0.0), float(speed_km or 0.0)) or None

    # Fuel used: integrate maf_air_flow (g/s) / 14.7 (stoich) / 749.9 (g/L)
    # across the trip duration. ~14.7 g air per 1 g fuel; gasoline density
    # ~0.7499 kg/L, so g_fuel / 749.9 = L_fuel.
    # Two airflow sources, tried in order. `maf_air_flow` is Mode 01 PID
    # 0x10 (and the WiCAN's alias for it); `maf_sensor_a` is PID 0x66,
    # added phone-side because 0x10 has never answered on the Pilot —
    # every historical maf_air_flow row came from the WiCAN, so when that
    # stopped publishing on 2026-07-25 fuel_used_l went to 0 for every
    # trip and the estimator fell back to a flat EPA decrement.
    #
    # They are integrated SEPARATELY and the first non-trivial result
    # wins. Summing them would double-count on a PCM that answers both.
    fuel_used_l = None
    for airflow_metric in ("maf_air_flow", "maf_sensor_a"):
        candidate = await conn.fetchval(
            """
            SELECT COALESCE(SUM(value_num * dt) / 14.7 / 749.9, 0)
            FROM (
                SELECT
                    value_num,
                    EXTRACT(EPOCH FROM (time - LAG(time) OVER (ORDER BY time)))
                        AS dt
                FROM pid_readings
                WHERE vehicle_id = $1
                  AND metric = $4
                  AND time >= $2 AND time <= $3
            ) s
            WHERE dt IS NOT NULL AND dt < 60
            """,
            vehicle_id,
            started_at,
            ended_at,
            airflow_metric,
        )
        if candidate is not None and float(candidate) >= 0.05:
            fuel_used_l = candidate
            break
    if fuel_used_l is None:
        fuel_used_l = 0

    # Fallback: derive fuel_used from the fuel_level delta when MAF isn't
    # available. Honda Pilot's poll list doesn't include PID 0x10 (MAF)
    # reliably, so MAF-integration produces zero and MPG was always blank
    # for these trips.
    #
    # De-slosh the delta (FUEL-DECREMENT-NULL): the raw 0x2F sensor bounces
    # ±15-25 % from fuel slosh, so a single first-vs-single last sample
    # produced garbage — 0 L (rounds out) or a huge spike that tripped the
    # 0.40 L/km sanity cap below and went NULL. Instead diff the P75 of the
    # first ~2 min of samples against the P75 of the last ~2 min, mirroring
    # the smoothing in api/vehicles._smooth_fuel_levels (the high end of the
    # distribution is closest to truth — the float arm only DROPS into
    # momentary dips, never rises above the actual level). raw_pct is
    # normalized against the per-vehicle calibration ceiling (Honda 0x2F
    # caps below 100 on a full tank) so the delta maps to physical gallons
    # consistently with the gauge UI.
    if (fuel_used_l is None) or (float(fuel_used_l) < 0.05):
        fuel_used_l = await conn.fetchval(
            """
            WITH cfg AS (
                SELECT
                    COALESCE(tank1_capacity, 0)::float          AS cap_gal,
                    NULLIF(fuel_level_calibration_pct, 0)::float AS cal_pct
                  FROM vehicles WHERE id = $1
            ),
            samples AS (
                SELECT time, value_num
                  FROM pid_readings
                 WHERE vehicle_id = $1
                   AND metric = 'fuel_level'
                   AND value_num IS NOT NULL
                   AND time BETWEEN $2 AND $3
            ),
            first_pct AS (
                -- P75 of the first ~2 min of the trip.
                SELECT percentile_cont(0.75) WITHIN GROUP (ORDER BY value_num)
                       AS pct
                  FROM samples
                 WHERE time <= $2 + interval '2 min'
            ),
            last_pct AS (
                -- P75 of the last ~2 min of the trip.
                SELECT percentile_cont(0.75) WITHIN GROUP (ORDER BY value_num)
                       AS pct
                  FROM samples
                 WHERE time >= $3 - interval '2 min'
            )
            SELECT CASE
                WHEN cfg.cap_gal > 0
                 AND cfg.cal_pct IS NOT NULL
                 AND first_pct.pct IS NOT NULL
                 AND last_pct.pct IS NOT NULL
                 AND (first_pct.pct - last_pct.pct) > 0.5
                THEN ((first_pct.pct - last_pct.pct)
                       / cfg.cal_pct * cfg.cap_gal * 3.78541)
                ELSE 0
            END
            FROM cfg, first_pct, last_pct
            """,
            vehicle_id,
            started_at,
            ended_at,
        )

    # Sanity cap: raw fuel_level can slosh 20+ pct on a short trip,
    # producing fallback estimates that imply sub-1-MPG consumption.
    # Discard rather than poison /analytics/* with the bogus value;
    # NULL distinguishes "unknown" from a genuine zero. Threshold:
    # 0.40 L/km ≈ 5.9 MPG, looser than any plausible real-world figure
    # on a moving trip (worst-case towing-uphill gasoline still beats
    # ~6 MPG). Kept as a backstop behind the de-sloshed delta above.
    if (
        fuel_used_l is not None
        and distance_km is not None
        and float(distance_km) > 0.5
        and float(fuel_used_l) / float(distance_km) > 0.40
    ):
        fuel_used_l = None

    dtc_count = await conn.fetchval(
        """
        SELECT count(*)
          FROM dtc_events
         WHERE vehicle_id = $1
           AND seen_at >= $2 AND seen_at <= $3
        """,
        vehicle_id,
        started_at,
        ended_at,
    )

    # Idle seconds (Task #91). Count distinct seconds where
    # vehicle_speed < 1 m/s (≈ 3.6 kph). The bridge only publishes
    # vehicle_speed while the engine is responding to OBD requests,
    # so any low-speed sample inside the trip window is engine-on +
    # vehicle-stopped — exactly what we want.
    idle_s = await conn.fetchval(
        """
        SELECT count(DISTINCT date_trunc('second', time))
          FROM pid_readings
         WHERE vehicle_id = $1
           AND metric = 'vehicle_speed'
           AND value_num IS NOT NULL
           AND value_num < 1
           AND time >= $2 AND time <= $3
        """,
        vehicle_id,
        started_at,
        ended_at,
    )

    def _f(v: object) -> float | None:
        if v is None:
            return None
        return float(v)

    return {
        "duration_s": duration_s,
        "max_rpm": _f(agg["max_rpm"]) if agg else None,
        "max_speed_kph": _f(agg["max_speed_kph"]) if agg else None,
        "avg_speed_kph": _f(agg["avg_speed_kph"]) if agg else None,
        "avg_coolant_c": _f(agg["avg_coolant_c"]) if agg else None,
        "distance_km": _f(distance_km),
        "fuel_used_l": _f(fuel_used_l),
        "dtc_count": int(dtc_count or 0),
        "idle_s": int(idle_s or 0) if idle_s is not None else None,
    }
