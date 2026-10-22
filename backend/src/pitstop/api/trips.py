"""Trips endpoints — list/get/update/delete/merge + sampled detail."""

from __future__ import annotations

import logging
from datetime import datetime
from typing import Any
from uuid import UUID

import asyncpg
from fastapi import APIRouter, Depends, HTTPException, Query, Response
from fastapi import Path as FastAPIPath
from pydantic import BaseModel, Field

from ..auth import require_ingest_token, require_query_token
from ..db.deps import get_pool
from ..schemas import TripDetail, TripOut, TripUpdate
from ..tires import (
    TIRE_CORNERS,
    TIRE_METRICS,
    TPMS_LOW_DELTA_PSI,
    low_tire_corners,
    placard_meta,
    placard_verdict,
    valid_tire_value,
)

log = logging.getLogger(__name__)

router = APIRouter(prefix="/trips", tags=["trips"])


_TRIP_COLS = (
    "id, vehicle_id, started_at, ended_at, duration_s, distance_km, "
    "max_rpm, max_speed_kph, avg_speed_kph, avg_coolant_c, fuel_used_l, "
    "dtc_count, idle_s, category, notes, is_towing, gps_only, "
    "weather_temp_c, weather_humidity_pct, weather_precip_mm, "
    "weather_wind_kph, weather_code, "
    "source, incomplete"
)


# Metrics the trip-detail chart may draw. Hoisted to one module constant
# because the sample query has TWO arms — the Timescale `time_bucket` path
# and the vanilla-Postgres fallback — and a list inlined in each drifts
# (the fallback previously carried no filter at all and returned whatever
# 500 rows sorted first, odometer and capability bitmaps included).
#
# Inclusion rule: a metric earns a slot only if it (a) actually varies over
# a drive on this fleet and (b) tells a story no other series already tells.
# Every entry costs up to _TRIP_SAMPLE_TARGET_POINTS rows of response body
# per trip, so near-duplicates and constants are refused — see
# _TRIP_SAMPLE_EXCLUDED below.
_TRIP_SAMPLE_METRICS: tuple[str, ...] = (
    # --- Core drive trace -------------------------------------------------
    "vehicle_speed",
    "engine_rpm",
    "coolant_temp",
    "throttle_position",
    "maf_air_flow",
    "manifold_pressure",
    "engine_load",
    "control_module_voltage",
    "fuel_level",
    "intake_air_temp",
    # --- Fuel + exhaust ---------------------------------------------------
    # engine_fuel_rate (g/s) is the ECU's own burn calculation, published
    # by a WiCAN *custom* PID 0x9D — trip_stats.FUEL_SOURCES already trusts
    # it first for the trip fuel integral, so charting it lets the MPG
    # number be read back off the timeline. g/s -> L/h = v * 3600 / 749.9.
    "engine_fuel_rate",
    # kg/h; custom PID 0x9E. Pairs with MAF/load to show engine breathing
    # (and is the one series that separates a loaded climb from a coast).
    "engine_exhaust_flow",
    # °C. Both banks, deliberately: they normally track within ~1 °C, so
    # the *divergence* is the diagnostic (a lazy or failing cat on one
    # bank). One bank alone would hide exactly the signal worth having.
    "catalyst_temp_b1",
    "catalyst_temp_b2",
    # Commanded equivalence ratio (~1.00 closed loop, dips rich under WOT
    # enrichment / DFCO). Needs 3 decimals to be readable at all.
    "commanded_afr_ratio",
    # Measured wide-range lambda. Kept alongside commanded_afr_ratio
    # because commanded-vs-actual is the fuel-control error — the pair is
    # the signal, either one alone is just a flat line near 1.0.
    "o2_s1_lambda",
    # kPa (~3500 on this fleet). Fuel-delivery diagnostic; kPa*0.145038=psi.
    "fuel_rail_pressure",
    # --- Distance ---------------------------------------------------------
    # km, absolute (~200 000 on this fleet), so it is the one series whose
    # RAW value would contradict the car: this PCM reads a fixed distance
    # above the dash cluster, which is what vehicles.odometer_offset_km
    # measures. get_trip() therefore subtracts the offset from every
    # odometer sample before it leaves the API -- see _apply_odo_offset.
    # Charting it raw would put the timeline ~50 km off the number on the
    # dash *and* off the Fillup form, which already corrects.
    #
    # Only the WiCAN publishes odometer (the phone bridge has no PID for
    # it), so on a cellular-only trip this series is simply absent and the
    # clients drop the toggle -- they filter to metrics that have data.
    "odometer",
    # --- Tires (TPMS) -----------------------------------------------------
    # kPa / °C per corner, Honda Mode 22 (WiCAN firmware-6 profile, or the
    # phone's optional Mode 22 polling during a drive). Four corners on
    # purpose: the story is the *spread* between them (a slow leak on one
    # wheel) and the warm-up curve (pressure climbs with tire temp), and
    # either needs all four on one axis. Cadence varies by source: the
    # phone's Mode 22 poll is ~30 s, the WiCAN profile was seen publishing
    # every ~2.5 s (2026-10-21), so a WiCAN-covered trip costs up to the
    # full per-metric budget like speed does. Sparse is safe under the
    # bucketing below: buckets are <= 30 s wide for any trip under ~4 h, so
    # each 30-s reading lands in its own bucket, and a bucket with no
    # reading emits no row, so a span with no tire data stays a gap (never a
    # zero).
    # Absent on most phone-only trips; clients hide the Tires chart then.
    "tire_pressure_fl",
    "tire_pressure_fr",
    "tire_pressure_rl",
    "tire_pressure_rr",
    "tire_temp_fl",
    "tire_temp_fr",
    "tire_temp_rl",
    "tire_temp_rr",
    # --- Braking ----------------------------------------------------------
    # bar, >= 0 (0 off the pedal, ~85-93 on a firm press, verified in Park
    # 2026-10-21). The one input no other series shows: speed falling can be
    # a coast, a climb or the brakes, and only this says which. 1 Hz, so it
    # costs a full metric budget on long trips like speed does. Gaps matter
    # here more than anywhere: carried forward across a dropout a single
    # press would read as a 10-minute brake hold, so the clients carry it a
    # few seconds at most.
    "brake_pressure",
    # --- Drivetrain (Honda Mode 22, WiCAN firmware-6 profile) -------------
    # °C, TCM. Warms far slower than coolant, so it is the series that says
    # whether the transmission was ever fully warm on a short hop.
    "atf_temp",
    # TCM gear code: 0 = P/N, 1..9 forward, 15 = R. Discrete -- see
    # _TRIP_SAMPLE_LAST_METRICS: an AVG over a shift reads as "gear 4.5".
    "gear_position",
    # 6 normally, 3 while Variable Cylinder Management has a bank off. The
    # eco story on a highway cruise; discrete, bucketed by last value.
    "active_cylinders",
    # Cumulative per-cylinder misfire counters (PCM DID 2663). Flat on a
    # healthy drive; a step on one cylinder is the diagnostic. Six series
    # because *which* cylinder matters (coil/plug swap tests).
    "misfire_count_1",
    "misfire_count_2",
    "misfire_count_3",
    "misfire_count_4",
    "misfire_count_5",
    "misfire_count_6",
    # --- Cluster (dash) readings ------------------------------------------
    # Litres, the dash's own damped reading (DID 7029) -- what the fuel snap
    # prefers since v0.1.257. Kept alongside the raw fuel_level % because the
    # gap between the two is the slosh the cluster filters out.
    "fuel_level_l",
    # °F as published (cluster DID 7028); clients convert. Outside air is
    # context for intake/coolant and for the cold-weather MPG dip.
    "ambient_air_temp_f",
    # --- Fuel trims -------------------------------------------------------
    # %, both banks short + long. Same reasoning as the catalyst pair: the
    # bank-to-bank *gap* is the diagnostic (vacuum leak or injector on one
    # side), so both banks or neither.
    "stft_b1",
    "stft_b2",
    "ltft_b1",
    "ltft_b2",
    # %, EVAP purge duty. Was on the denylist as "not drive telemetry"; the
    # clients now file it under an Emissions chip group, default hidden,
    # which is the diagnostics home that note asked for.
    "commanded_evap_purge",
)


# Metrics bucketed by MAX instead of AVG. A brake press lasts a second or
# two; averaged into a 3-8 s bucket a 90 bar stab reads as 30 bar and a
# light tap disappears. Max keeps every press at its real height.
_TRIP_SAMPLE_PEAK_METRICS: tuple[str, ...] = ("brake_pressure",)

# Discrete / counter metrics bucketed by their LAST value in the bucket.
# AVG invents states that never existed (gear 4.5, 4.5 cylinders, a
# fractional misfire count); MAX would turn a P/N->R->P/N move into a
# bucket of "R" (15) and hide a VCM drop. Last is a true sample of the
# signal. Written as array_agg(... ORDER BY time DESC)[1] rather than
# Timescale's last() so the vanilla-Postgres fallback arm runs it too.
_TRIP_SAMPLE_LAST_METRICS: tuple[str, ...] = (
    "gear_position",
    "active_cylinders",
    "misfire_count_1",
    "misfire_count_2",
    "misfire_count_3",
    "misfire_count_4",
    "misfire_count_5",
    "misfire_count_6",
)

# Metrics that are live in pid_readings but deliberately NOT charted, kept
# here as a documented denylist so they don't get re-added by inspection of
# "what's flowing". Asserted disjoint from _TRIP_SAMPLE_METRICS in
# tests/test_trip_samples.py.
_TRIP_SAMPLE_EXCLUDED: tuple[str, ...] = (
    # Never lands under this canonical name: WiCAN's decoder for Mode 01
    # PID 0x24's voltage field is invariant (a flat 2.000 V across cold
    # start, idle, WOT and DFCO), so wican_aliases.py refuses to alias it.
    # o2_s1_lambda above is that sensor's real output.
    "o2_s1_voltage",
    # Constant 0.00 over every row this PCM has ever answered — the Pilot
    # exposes no EGR command. A flat zero line reads as "EGR failed".
    "commanded_egr",
    # Post-cat O2 pair. Cat-efficiency diagnostics rather than drive
    # telemetry, four series for one narrow story, and o2_s2_stft carries
    # the 99.0 % "sensor not used" sentinel rather than a trim value.
    "o2_s2_voltage",
    "o2_s2_stft",
    "o2_s6_voltage",
    "o2_s6_stft",
    # Tracks coolant temp and RPM almost exactly (it is largely a function
    # of both) — no independent signal, just a fourth warm-up curve.
    "friction_torque_pct",
    # Dash odometer in miles (cluster DID 7022). The same number the
    # offset-corrected `odometer` series already shows, at a 30 s cadence;
    # within one trip it is a ramp that the distance stat already states.
    "odometer_dash",
    # Seconds since engine start: a straight line with slope 1 over every
    # drive. The x axis already is this.
    "run_time_since_start",
    # Near-duplicates of throttle_position: the same pedal/plate seen
    # through three raw sensor channels, each offset by a fixed bias.
    # Three extra series to redraw a line already on the chart.
    "throttle_pos_b",
    "throttle_pos_d",
    "throttle_pos_e",
)

# Nominal per-metric sample budget for the trip chart. Total response rows
# are bounded by len(_TRIP_SAMPLE_METRICS) * this.
_TRIP_SAMPLE_TARGET_POINTS = 500


def _bucket_seconds(
    duration_s: int, target_points: int = _TRIP_SAMPLE_TARGET_POINTS
) -> int:
    """Bucket width (whole seconds) keeping a trip at or under
    ``target_points`` samples *per metric*.

    Ceiling division, not floor. ``duration_s // 500`` under-rounds: a 999 s
    trip lands on 1 s buckets and yields 999 points per metric — twice the
    nominal budget, on every series at once. Harmless at 10 series, less so
    now that the list is longer, so the budget is made to actually hold.
    """
    if duration_s <= 0 or target_points <= 0:
        return 1
    return max(-(-duration_s // target_points), 1)


def _tire_summary(
    readings: list[tuple[datetime, str, float | None]],
    *,
    placard_front_kpa: float | None = None,
    placard_rear_kpa: float | None = None,
) -> dict[str, Any] | None:
    """Per-corner TPMS summary from raw (time, metric, value) rows inside a
    trip window. Returns None when no tire metric has a usable reading, so
    the clients can hide the whole section rather than show four dashes.

    Placard verdicts (below_placard_psi / under_inflated / below_spec) are
    judged on the START pressure: the placard is a cold figure and the start
    of a drive is the coldest the tire is during it.
    """
    by_metric: dict[str, list[tuple[datetime, float]]] = {}
    for t, metric, value in readings:
        if metric not in TIRE_METRICS or not valid_tire_value(metric, value):
            continue
        by_metric.setdefault(metric, []).append((t, float(value)))  # type: ignore[arg-type]
    if not by_metric:
        return None
    for seq in by_metric.values():
        seq.sort(key=lambda tv: tv[0])

    corners: list[dict[str, Any]] = []
    end_kpa: dict[str, float | None] = {}
    for c in TIRE_CORNERS:
        pres = [v for _, v in by_metric.get(f"tire_pressure_{c}", [])]
        temp = [v for _, v in by_metric.get(f"tire_temp_{c}", [])]
        end_kpa[c] = pres[-1] if pres else None
        corners.append(
            {
                "corner": c,
                "pressure_start_kpa": pres[0] if pres else None,
                "pressure_end_kpa": pres[-1] if pres else None,
                "pressure_min_kpa": min(pres) if pres else None,
                "pressure_max_kpa": max(pres) if pres else None,
                "temp_start_c": temp[0] if temp else None,
                "temp_end_c": temp[-1] if temp else None,
                **placard_verdict(
                    c, pres[0] if pres else None,
                    placard_front_kpa, placard_rear_kpa,
                ),
            }
        )
    return {
        "corners": corners,
        "low_corners": low_tire_corners(end_kpa),
        "low_delta_psi": TPMS_LOW_DELTA_PSI,
        **placard_meta(placard_front_kpa, placard_rear_kpa),
    }


# Braking stats (brake_pressure, bar). A press starts on a rising edge above
# _BRAKE_ON_BAR -- above sensor noise at rest (0 bar) and far below a light
# stop (~15-30 bar). Two presses separated by less than _BRAKE_DEBOUNCE_S of
# release are one press (a pumped pedal or a single noisy sample dipping
# under the threshold).
_BRAKE_ON_BAR = 5.0
_BRAKE_DEBOUNCE_S = 1.0
# PROVISIONAL: a press peaking at or above this is a "hard brake". Chosen
# from the 85-93 bar firm presses measured in Park on 2026-10-21, not from
# real deceleration; recalibrate against vehicle_speed dv/dt (~0.4 g) once
# drives with brake data exist.
_HARD_BRAKE_BAR = 60.0
# A sample spacing beyond this is a dropout, not a held pedal: time across
# it is not counted as braking and it ends any press in progress.
_BRAKE_MAX_GAP_S = 5.0


def _brake_summary(
    readings: list[tuple[datetime, float | None]],
) -> dict[str, Any]:
    """brake_events / hard_brake_events / max_brake_bar / braking_s from raw
    (time, bar) rows. All four are None when the trip has no brake_pressure
    sample -- "not measured" must never read as "never braked"."""
    pts = sorted(
        ((t, float(v)) for t, v in readings if v is not None and v >= 0),
        key=lambda tv: tv[0],
    )
    if not pts:
        return {
            "brake_events": None,
            "hard_brake_events": None,
            "max_brake_bar": None,
            "braking_s": None,
        }

    events = 0
    hard = 0
    braking_s = 0.0
    in_press = False
    peak = 0.0
    # Time of the first below-threshold sample after a press; None while the
    # pedal is held. The press only ends once the pedal has stayed released
    # for _BRAKE_DEBOUNCE_S, so a pumped pedal is one event with one peak.
    release_t: datetime | None = None
    prev_t: datetime | None = None
    prev_on = False

    def finish() -> None:
        nonlocal in_press, hard, release_t
        if in_press and peak >= _HARD_BRAKE_BAR:
            hard += 1
        in_press = False
        release_t = None

    for t, v in pts:
        if prev_t is not None:
            gap = (t - prev_t).total_seconds()
            if gap > _BRAKE_MAX_GAP_S:
                # Dropout: close whatever was in progress, count no time.
                finish()
                prev_on = False
            elif prev_on:
                braking_s += gap
        on = v > _BRAKE_ON_BAR
        if on:
            if (
                in_press
                and release_t is not None
                and (t - release_t).total_seconds() >= _BRAKE_DEBOUNCE_S
            ):
                finish()
            if not in_press:
                events += 1
                in_press = True
                peak = v
            else:
                peak = max(peak, v)
            release_t = None
        elif in_press:
            if release_t is None:
                release_t = t
            elif (t - release_t).total_seconds() >= _BRAKE_DEBOUNCE_S:
                finish()
        prev_on = on
        prev_t = t
    finish()

    return {
        "brake_events": events,
        "hard_brake_events": hard,
        "max_brake_bar": max(v for _, v in pts),
        "braking_s": round(braking_s, 1),
    }


def _row_to_trip(row: asyncpg.Record) -> dict[str, Any]:
    return {
        "id": row["id"],
        "vehicle_id": row["vehicle_id"],
        "started_at": row["started_at"],
        "ended_at": row["ended_at"],
        "duration_s": row["duration_s"],
        "is_towing": bool(row["is_towing"]),
        "gps_only": bool(row["gps_only"]),
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


# `sort` values for GET /trips -> ORDER BY column. Every non-default order
# is descending with NULLs last (a trip without a distance/fuel figure is
# not "the longest"), tie-broken by recency so paging is stable. Column
# names come only from this allowlist — never from the request.
_TRIP_SORTS: dict[str, str] = {
    "recent": "started_at",
    "distance": "distance_km",
    "duration": "duration_s",
    "top_speed": "max_speed_kph",
    "max_rpm": "max_rpm",
    "fuel": "fuel_used_l",
    # The web's pre-overhaul client-side sort called this "speed".
    "speed": "max_speed_kph",
}

# Trip-provenance chips shared by web + phone. Values are the trips.source
# column values the web already filters on (TripsView.vue), plus short
# aliases; "other" is everything that is neither a phone upload nor a
# manual merge (i.e. the deriver today).
_TRIP_SOURCE_FILTERS: dict[str, str | None] = {
    "all": None,
    "phone_batch": "source = 'phone_batch'",
    "phone": "source = 'phone_batch'",
    "manual_merge": "source = 'manual_merge'",
    "merged": "source = 'manual_merge'",
    "other": "source NOT IN ('phone_batch', 'manual_merge')",
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
    sort: str = Query(default="recent"),
    source: str | None = Query(default=None),
    towing: bool | None = Query(default=None),
    pool: asyncpg.Pool = Depends(get_pool),
) -> list[dict[str, Any]]:
    sort_col = _TRIP_SORTS.get(sort)
    if sort_col is None:
        raise HTTPException(
            status_code=422,
            detail=f"sort must be one of {sorted(_TRIP_SORTS)}",
        )
    if source is not None and source not in _TRIP_SOURCE_FILTERS:
        raise HTTPException(
            status_code=422,
            detail=f"source must be one of {sorted(_TRIP_SOURCE_FILTERS)}",
        )
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
    source_clause = _TRIP_SOURCE_FILTERS.get(source) if source is not None else None
    if source_clause is not None:
        where.append(source_clause)
    # towing=false is "no filter", not "exclude towing" — the chip is a
    # show-only-towing toggle on both clients.
    if towing:
        where.append("is_towing")
    where_sql = (" WHERE " + " AND ".join(where)) if where else ""
    order_sql = f"{sort_col} DESC NULLS LAST"
    if sort_col != "started_at":
        order_sql += ", started_at DESC"

    async with pool.acquire() as conn:
        total = await conn.fetchval(
            f"SELECT count(*) FROM trips{where_sql}", *args
        )
        args.extend([limit, offset])
        rows = await conn.fetch(
            f"SELECT {_TRIP_COLS} FROM trips{where_sql} "
            f"ORDER BY {order_sql} "
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

        # Per-vehicle display corrections, both applied server-side so every
        # client agrees with the dash without re-deriving the policy:
        #   fuel_level_calibration_pct -> normalizes fuel_level to a real 100%
        #   odometer_offset_km         -> PCM reads high vs the cluster
        veh = await conn.fetchrow(
            "SELECT fuel_level_calibration_pct, odometer_offset_km, "
            "tire_placard_front_kpa, tire_placard_rear_kpa "
            "  FROM vehicles WHERE id = $1",
            row["vehicle_id"],
        )

        # Sample readings: bucket the trip into <= _TRIP_SAMPLE_TARGET_POINTS
        # points per metric for every metric in _TRIP_SAMPLE_METRICS.
        started = row["started_at"]
        ended = row["ended_at"] or started
        duration_s = max(int((ended - started).total_seconds()), 1)
        bucket_s = _bucket_seconds(duration_s)
        metrics = list(_TRIP_SAMPLE_METRICS)
        # Use time_bucket on the chunk; fall back to plain-SQL epoch
        # bucketing if Timescale is absent (dev/test on stock Postgres).
        try:
            sample_rows = await conn.fetch(
                """
                SELECT time_bucket(make_interval(secs => $4), time) AS time,
                       metric,
                       CASE WHEN metric = ANY($6::text[])
                            THEN max(value_num)
                            WHEN metric = ANY($7::text[])
                            THEN (array_agg(value_num ORDER BY time DESC))[1]
                            ELSE avg(value_num)
                       END AS value_num
                  FROM pid_readings
                 WHERE vehicle_id = $1
                   AND time >= $2 AND time <= $3
                   AND metric = ANY($5::text[])
                   AND value_num IS NOT NULL
                 GROUP BY 1, 2
                 ORDER BY 1 ASC
                """,
                row["vehicle_id"], started, ended, bucket_s, metrics,
                list(_TRIP_SAMPLE_PEAK_METRICS),
                list(_TRIP_SAMPLE_LAST_METRICS),
            )
        except asyncpg.UndefinedFunctionError:
            # Same shape, same allowlist, same bucket width — floor the epoch
            # to a bucket boundary by hand. The old fallback filtered no
            # metrics and took `LIMIT 500` off the front of the trip, which
            # both leaked untargeted metrics (odometer, capability bitmaps)
            # and truncated the chart to its first few seconds. GROUP BY
            # bounds the row count here exactly as it does above.
            sample_rows = await conn.fetch(
                """
                SELECT to_timestamp(
                           floor(extract(epoch FROM time) / $4::int)::bigint
                           * $4::int
                       ) AS time,
                       metric,
                       CASE WHEN metric = ANY($6::text[])
                            THEN max(value_num)
                            WHEN metric = ANY($7::text[])
                            THEN (array_agg(value_num ORDER BY time DESC))[1]
                            ELSE avg(value_num)
                       END AS value_num
                  FROM pid_readings
                 WHERE vehicle_id = $1
                   AND time >= $2 AND time <= $3
                   AND metric = ANY($5::text[])
                   AND value_num IS NOT NULL
                 GROUP BY 1, 2
                 ORDER BY 1 ASC
                """,
                row["vehicle_id"], started, ended, bucket_s, metrics,
                list(_TRIP_SAMPLE_PEAK_METRICS),
                list(_TRIP_SAMPLE_LAST_METRICS),
            )

    out = _row_to_trip(row)

    # The measured (PCM - dash cluster) delta, in km. Applied to every
    # odometer value this endpoint returns -- the chart series AND the
    # start/end stats below -- so an odometer read anywhere in the app
    # matches the number on the dash. Mirrors what FillupModal.vue does
    # to the live odometer when pre-filling a fillup.
    odo_offset = (
        float(veh["odometer_offset_km"])
        if veh is not None and veh["odometer_offset_km"] is not None
        else 0.0
    )

    def _apply_odo_offset(metric: str, value: float | None) -> float | None:
        if value is None or metric != "odometer":
            return value
        return value - odo_offset

    out["samples"] = [
        {
            "time": r["time"],
            "metric": r["metric"],
            "value_num": _apply_odo_offset(
                r["metric"],
                float(r["value_num"]) if r["value_num"] is not None else None,
            ),
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
               AND time BETWEEN $2::timestamptz - interval '15 minutes'
                            AND $2::timestamptz + interval '15 minutes'
             ORDER BY abs(extract(epoch FROM (time - $2::timestamptz)))
             LIMIT 1
            """,
            row["vehicle_id"], started,
        )
        odo_end = await conn.fetchval(
            """
            SELECT value_num FROM pid_readings
             WHERE vehicle_id = $1 AND metric = 'odometer'
               AND time BETWEEN $2::timestamptz - interval '15 minutes'
                            AND $2::timestamptz + interval '15 minutes'
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
        # TPMS summary. Raw rows rather than the bucketed samples: the
        # first/last reading is the start/end pressure, and bucket averaging
        # would blur exactly the boundary values the summary reports. Cheap
        # -- ~30 s cadence, 8 metrics.
        tire_rows = await conn.fetch(
            """
            SELECT time, metric, value_num FROM pid_readings
             WHERE vehicle_id = $1
               AND time >= $2 AND time <= $3
               AND metric = ANY($4::text[])
               AND value_num IS NOT NULL
             ORDER BY time ASC
            """,
            row["vehicle_id"], started, ended, list(TIRE_METRICS),
        )
        # Raw 1 Hz brake pressure for the braking stats -- edge counting
        # needs every sample, not bucket averages (an average smears a
        # 1-second stab under the threshold).
        brake_rows = await conn.fetch(
            """
            SELECT time, value_num FROM pid_readings
             WHERE vehicle_id = $1
               AND time >= $2 AND time <= $3
               AND metric = 'brake_pressure'
               AND value_num IS NOT NULL
             ORDER BY time ASC
            """,
            row["vehicle_id"], started, ended,
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
    # Offset-corrected, same as the odometer chart series above -- these two
    # numbers and that line are the same quantity and must not disagree.
    out["odo_start_km"] = (
        float(odo_start) - odo_offset if odo_start is not None else None
    )
    out["odo_end_km"] = float(odo_end) - odo_offset if odo_end is not None else None
    # Normalize fuel boundaries against the per-vehicle calibration so
    # the displayed start/end matches the gauge (Honda 0x2F caps below
    # 100 raw on a full tank — see v0.1.159 calibration capture).
    veh_cal = veh["fuel_level_calibration_pct"] if veh is not None else None
    cal = float(veh_cal) if veh_cal is not None and float(veh_cal) > 0 else 100.0
    def _norm(raw: Any) -> float | None:
        if raw is None:
            return None
        return min(100.0, float(raw) / cal * 100.0)
    out["fuel_level_start_pct"] = _norm(fuel_start_raw)
    out["fuel_level_end_pct"] = _norm(fuel_end_raw)
    def _kpa(col: str) -> float | None:
        if veh is None or veh[col] is None:
            return None
        return float(veh[col])

    out["tires"] = _tire_summary(
        [(r["time"], r["metric"], r["value_num"]) for r in tire_rows],
        placard_front_kpa=_kpa("tire_placard_front_kpa"),
        placard_rear_kpa=_kpa("tire_placard_rear_kpa"),
    )
    out.update(
        _brake_summary([(r["time"], r["value_num"]) for r in brake_rows])
    )
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
    async with pool.acquire() as conn, conn.transaction():
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
