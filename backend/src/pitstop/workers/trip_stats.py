"""Trip statistics computation.

Pure-SQL close-time stats for a single trip window, shared by both trip
importers:

- ``workers/trip_deriver.py`` — the periodic batch derivation cron.
- ``api/drive_ingest.py`` — the phone's ``POST /drives`` batch upload.

Extracted from the retired streaming ``trip_detector`` (the streaming
``TripDetector`` class is gone — ``main.py`` sets it to ``None`` and the
periodic deriver is authoritative). The window math (haversine on
gps_points with a speed-integration fallback, mass-flow + fuel-level
fallback fuel estimation) is unchanged from what production already
trusted.
"""

from __future__ import annotations

import logging
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from datetime import datetime
from enum import Enum
from uuid import UUID

import asyncpg

log = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Fuel-used integration: mass-flow sources and their unit conventions
# ---------------------------------------------------------------------------
#
# Every source below is a mass flow in grams per second that we integrate over
# the trip window to get grams. What differs — and what a reader must never
# have to guess — is WHICH SUBSTANCE those grams are:
#
#   * a FUEL flow (OBD PID 0x9D, the ECU's own fuel calculation) is already
#     fuel, so it converts straight to liters by density;
#   * an AIR flow (MAF) is air, so it must first be divided by the
#     stoichiometric air:fuel ratio to become fuel.
#
# Getting that wrong is a 14.7x error, so the ratio is NOT written into any
# SQL. The queries return the bare integral ∫(g/s · dt) with zero conversion
# baked in, and ``liters_from_flow_integral`` is the single place any
# substance/density arithmetic happens — driven by the source's declared
# ``species``, which every source must state explicitly.

# Gasoline density: ~0.7499 kg/L, so g_fuel / 749.9 = L_fuel.
GASOLINE_DENSITY_G_PER_L = 749.9
# ~14.7 g of air burned per 1 g of fuel.
STOICH_AIR_PER_FUEL_G_PER_G = 14.7
# Below this a trip's integral is treated as "this source didn't really
# answer" and the next source (then the fuel-level delta) gets a turn.
MIN_CREDIBLE_FUEL_L = 0.05
# A source must also have SAMPLED at least this fraction of the window before
# its integral is believed. NOTE the denominator is the trip's OBD-ACTIVE
# seconds, not its wall-clock duration — see ``obd_active_seconds``.
#
# The magnitude floor alone is not enough, because these integrals UNDER-count
# silently: gaps > 60 s are dropped, so a source that only covered the first
# two minutes of a 23-minute drive still returns a small POSITIVE number that
# clears the floor and wins. That is not hypothetical — the WiCAN is WiFi-only
# and publishes `engine_fuel_rate` from the driveway, then goes silent the
# moment the car leaves the network, while the phone's BLE stream covers the
# rest. Without this gate a 12.65 km / 1397 s trip with 120 s of driveway
# coverage at ~0.35 g/s resolves to 0.056 L — i.e. ~530 MPG — and, because it
# clears MIN_CREDIBLE_FUEL_L, it also suppresses the fuel-level-delta fallback
# that would otherwise have produced a sane figure.
#
# When no source clears both gates we return None and let that fallback run:
# a well-smoothed tank-delta beats a confidently-wrong two-minute
# extrapolation.
MIN_COVERAGE_FRACTION = 0.5


async def obd_active_seconds(
    conn: asyncpg.Connection,
    vehicle_id: UUID,
    started_at: datetime,
    ended_at: datetime,
) -> float:
    """Seconds inside the trip window during which SOME OBD source was live.

    This — not ``ended_at - started_at`` — is the denominator the coverage gate
    divides by, because a trip's wall clock includes stretches during which no
    source could possibly have recorded anything:

    * **The dead tail.** A drive that ends with a BLE drop is sealed by the
      phone's BLE-lost watchdog, which stamps engine-off ~3 minutes after the
      last OBD frame (the OBD-quiet watchdog, 60 s). ``_build_intervals`` ends
      the interval at that event, so a 3-minute drive can carry a 3-minute
      silent tail.
    * **Mid-drive BLE flaps** shorter than the watchdog threshold. Every phone
      metric stops together, so the gap is not evidence against any one source.

    Charging that silence to the fuel source would reject a stream that
    genuinely covered every second the car was reporting, and the trip would
    fall through to the tank-delta fallback (or to no figure at all) — turning
    a correct answer into a blank. Measuring against activity keeps the gate
    aimed at what it was built for: one source covering a *fraction of the
    period other sources covered*, which is exactly the driveway-WiCAN case.

    Computed the same way the per-metric integrals are — ``SUM(dt)`` over the
    time-ordered rows with gaps > 60 s dropped — so numerator and denominator
    use identical conventions. All metrics are unioned, so this is the span
    from the first reading to the last, minus the dropped gaps.
    """
    covered = await conn.fetchval(
        """
        SELECT COALESCE(SUM(dt), 0)
        FROM (
            SELECT
                EXTRACT(EPOCH FROM (time - LAG(time) OVER (ORDER BY time)))
                    AS dt
            FROM pid_readings
            WHERE vehicle_id = $1
              AND time >= $2 AND time <= $3
              AND value_num IS NOT NULL
        ) s
        WHERE dt IS NOT NULL AND dt < 60
        """,
        vehicle_id,
        started_at,
        ended_at,
    )
    return float(covered or 0.0)


class FlowSpecies(Enum):
    """What substance a mass-flow metric actually measures."""

    #: Grams of FUEL per second — needs no air:fuel correction.
    FUEL = "fuel"
    #: Grams of AIR per second — must be divided by the stoich ratio.
    AIR = "air"


@dataclass(frozen=True, slots=True)
class MassFlowFuelSource:
    """A ``pid_readings`` metric carrying a g/s mass flow, plus its species."""

    metric: str
    species: FlowSpecies

    @property
    def fuel_grams_per_flow_gram(self) -> float:
        """Grams of fuel represented by one gram of this source's flow."""
        if self.species is FlowSpecies.FUEL:
            return 1.0
        return 1.0 / STOICH_AIR_PER_FUEL_G_PER_G


# Tried in order; the first source that yields a credible figure wins.
#
# `engine_fuel_rate` is Mode 01 PID 0x9D and is preferred over any airflow
# source because it is the ECU's OWN fuel calculation: it already accounts
# for power enrichment and deceleration fuel cut-off, whereas integrating
# MAF assumes a perfect 14.7:1 forever and therefore over-reports on every
# lift-off. `maf_air_flow` is PID 0x10 (and the WiCAN's alias for it);
# `maf_sensor_a` is PID 0x66, added phone-side because 0x10 has never
# answered on the Pilot — every historical maf_air_flow row came from the
# WiCAN, so when that stopped publishing on 2026-07-25 fuel_used_l went to 0
# for every trip and the estimator fell back to a flat EPA decrement.
FUEL_SOURCES: tuple[MassFlowFuelSource, ...] = (
    MassFlowFuelSource("engine_fuel_rate", FlowSpecies.FUEL),
    MassFlowFuelSource("maf_air_flow", FlowSpecies.AIR),
    MassFlowFuelSource("maf_sensor_a", FlowSpecies.AIR),
)


def liters_from_flow_integral(
    flow_grams: float | None, source: MassFlowFuelSource
) -> float:
    """Convert ∫(g/s · dt) grams of `source`'s flow into liters of fuel.

    The ONLY place the air:fuel ratio and gasoline density are applied. A
    FUEL-species source skips the air:fuel step entirely (its grams are
    already fuel); an AIR-species source does not.
    """
    if flow_grams is None:
        return 0.0
    fuel_grams = float(flow_grams) * source.fuel_grams_per_flow_gram
    return fuel_grams / GASOLINE_DENSITY_G_PER_L


async def resolve_fuel_used_l(
    integrate_grams: Callable[[str], Awaitable[tuple[float | None, float]]],
    coverage_window_s: float,
) -> float | None:
    """Liters of fuel from the first ``FUEL_SOURCES`` entry that answers well.

    ``integrate_grams(metric)`` returns ``(grams, covered_s)`` for that metric
    over the trip window: the bare ∫(value_num · dt) in grams, and the number
    of seconds those samples actually spanned (the same ``SUM(dt)`` the
    integral was built from, so gaps > 60 s are excluded from both).

    ``coverage_window_s`` is the denominator the coverage gate judges against.
    It is deliberately NOT the trip's wall-clock duration: see
    ``obd_active_seconds`` for why, and ``MIN_COVERAGE_FRACTION`` for what the
    gate is protecting against.

    A source is believed only when it clears BOTH gates:

    * magnitude — at least ``MIN_CREDIBLE_FUEL_L``, so a stray sample or two
      doesn't win; and
    * coverage — it sampled at least ``MIN_COVERAGE_FRACTION`` of the window,
      so a source that only saw the first two minutes cannot stand in for the
      whole drive (see ``MIN_COVERAGE_FRACTION``).

    Sources are integrated SEPARATELY and the first qualifying one wins —
    never summed, which would double-count on a PCM that answers both a fuel
    rate and a MAF. Returns ``None`` when none qualifies, which hands the trip
    to the fuel-level-delta fallback in ``compute_trip_stats``.
    """
    for source in FUEL_SOURCES:
        grams, covered_s = await integrate_grams(source.metric)
        liters = liters_from_flow_integral(grams, source)
        if liters < MIN_CREDIBLE_FUEL_L:
            continue
        # A zero/unknown-length window can't be judged on coverage; fall back
        # to the magnitude gate alone rather than rejecting everything.
        if (
            coverage_window_s > 0
            and covered_s < MIN_COVERAGE_FRACTION * coverage_window_s
        ):
            log.info(
                "trip_stats: ignoring %s — covered %.0fs of %.0fs of OBD "
                "activity (%.1f%%), would have reported %.3f L",
                source.metric, covered_s, coverage_window_s,
                100.0 * covered_s / coverage_window_s, liters,
            )
            continue
        return liters
    return None


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

    # Fuel used: integrate a g/s mass flow across the trip window. See
    # FUEL_SOURCES above for the source order and the fuel-vs-air unit
    # conventions — this query deliberately carries NO unit constants, it
    # returns the bare integral in grams of whatever the metric measures
    # and `resolve_fuel_used_l` does every conversion.
    async def _integrate_grams(metric: str) -> tuple[float | None, float]:
        row = await conn.fetchrow(
            """
            SELECT COALESCE(SUM(value_num * dt), 0) AS flow_grams,
                   COALESCE(SUM(dt), 0)             AS covered_s
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
            metric,
        )
        if row is None:
            return None, 0.0
        return row["flow_grams"], float(row["covered_s"] or 0.0)

    # Denominator for the coverage gate: the seconds the car was actually
    # reporting, not the trip's wall clock. See `obd_active_seconds`.
    coverage_window_s = await obd_active_seconds(
        conn, vehicle_id, started_at, ended_at
    )
    fuel_used_l: float | int | None = await resolve_fuel_used_l(
        _integrate_grams, coverage_window_s
    )
    if fuel_used_l is None:
        fuel_used_l = 0

    # Fallback: derive fuel_used from the fuel_level delta when no mass-flow
    # source answered. Honda Pilot's poll list doesn't include PID 0x10 (MAF)
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
    if (fuel_used_l is None) or (float(fuel_used_l) < MIN_CREDIBLE_FUEL_L):
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
