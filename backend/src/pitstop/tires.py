"""TPMS rules shared by the trip detail and /analytics/tires.

Pure functions only, no DB. The web client implements the same low rule in
frontend/src/lib/vehicleSignals.ts (tpmsReadings / TPMS_LOW_DELTA_PSI), the
same slow-leak threshold (TIRE_SLOW_LEAK_PSI_PER_WEEK) and the same placard
rules (placardKpa / belowPlacardPsi / TIRE_UNDER_INFLATED_FRACTION /
TIRE_BELOW_SPEC_PSI) -- keep the numbers in step.
"""

from __future__ import annotations

from collections.abc import Iterable, Mapping
from datetime import UTC, date, datetime
from typing import Any

# Corner order every client lays out: front-left, front-right, rear-left,
# rear-right.
TIRE_CORNERS: tuple[str, ...] = ("fl", "fr", "rl", "rr")
TIRE_PRESSURE_METRICS: tuple[str, ...] = tuple(f"tire_pressure_{c}" for c in TIRE_CORNERS)
TIRE_TEMP_METRICS: tuple[str, ...] = tuple(f"tire_temp_{c}" for c in TIRE_CORNERS)
TIRE_METRICS: tuple[str, ...] = TIRE_PRESSURE_METRICS + TIRE_TEMP_METRICS

PSI_PER_KPA = 0.145038
KPA_PER_PSI = 1 / PSI_PER_KPA

# Low-tire rule: a tire is low when it reads at least 2 psi below the HIGHEST
# of the four, with >= 2 tires reporting. Relative, so it needs no door-
# placard pressure and can't fire on a uniform cold morning. 2 psi because
# on 2026-10-21 the dash flagged FR at a 2.5 psi spread. psi regardless of
# the display unit.
TPMS_LOW_DELTA_PSI = 2.0

# PROVISIONAL: a cold-pressure trend at or below this, resting on at least
# TIRE_SLOW_LEAK_MIN_SAMPLES cold readings, is flagged "possible slow leak".
# Picked so a tire losing ~2 psi a month gets noticed before the 2-psi spread
# rule trips; revisit once there are months of cold readings to look at.
# Ambient temperature moves cold pressure ~1 psi per 10 °F, so a cooling
# season alone can produce a downward slope on all four tires at once.
TIRE_SLOW_LEAK_PSI_PER_WEEK = -0.5
TIRE_SLOW_LEAK_MIN_SAMPLES = 4

# "Cold" = parked long enough for the tire to be back at ambient. Detected
# from the readings themselves (see analytics.tires): the first reading of a
# corner after >= TIRE_COLD_PARK_HOURS with no reading for that corner.
TIRE_COLD_PARK_HOURS = 3
# ...unless the tire is clearly still hot. The WiCAN only publishes on home
# WiFi, so the car coming HOME from an un-logged drive also produces a first
# reading after a long gap -- with warm tires. When the outside temp is
# known, a reading more than this many °C above it is not cold.
TIRE_COLD_MAX_ABOVE_AMBIENT_C = 8.0


def low_tire_corners(pressure_kpa: Mapping[str, float | None]) -> list[str]:
    """Corners >= TPMS_LOW_DELTA_PSI below the highest reporting tire.

    Mirrors tpmsReadings() on the web, including its 1e-6 slack so a spread
    of exactly 2.0 psi (after the kPa round-trip) counts as low.
    """
    known = [v for v in pressure_kpa.values() if v is not None]
    if len(known) < 2:
        return []
    high = max(known)
    threshold = TPMS_LOW_DELTA_PSI * KPA_PER_PSI
    return [
        c
        for c in TIRE_CORNERS
        if (p := pressure_kpa.get(c)) is not None and high - p >= threshold - 1e-6
    ]


# ── Door-placard baseline ─────────────────────────────────────────────
#
# vehicles.tire_placard_{front,rear}_kpa (0025) is the manufacturer's
# recommended COLD pressure. Two absolute rules ride on it, beside the
# relative spread rule above; both are off when no placard is set.

# "Under-inflated": at least 25 % below placard. This is the FMVSS 138 TPMS
# warning threshold Honda's own system uses -- the dash lights at ~26 psi
# on a 35-psi placard -- so the server's verdict matches the car's.
TIRE_UNDER_INFLATED_FRACTION = 0.25
# "Below spec": a softer note at >= 3 psi under placard. Judge it on a COLD
# reading (analytics) or a trip's start pressure; a warm tire gains ~2-4 psi,
# so a hot reading under placard by 3 is further under when cold.
TIRE_BELOW_SPEC_PSI = 3.0

FRONT_CORNERS: tuple[str, ...] = ("fl", "fr")
REAR_CORNERS: tuple[str, ...] = ("rl", "rr")


def placard_kpa(
    corner: str, front_kpa: float | None, rear_kpa: float | None
) -> float | None:
    """The placard pressure that applies to ``corner`` (fl/fr front, rl/rr
    rear), or None when that axle has none / the corner is unknown."""
    if corner in FRONT_CORNERS:
        return front_kpa
    if corner in REAR_CORNERS:
        return rear_kpa
    return None


def below_placard_psi(
    pressure_kpa: float | None, placard: float | None
) -> float | None:
    """psi UNDER the placard: positive = below, negative = above. None when
    either side is unknown. Clients render the delta as ``-below``."""
    if pressure_kpa is None or placard is None or placard <= 0:
        return None
    return (placard - pressure_kpa) * PSI_PER_KPA


def is_under_inflated(pressure_kpa: float | None, placard: float | None) -> bool:
    """At or below placard x (1 - TIRE_UNDER_INFLATED_FRACTION)."""
    if pressure_kpa is None or placard is None or placard <= 0:
        return False
    return pressure_kpa <= placard * (1 - TIRE_UNDER_INFLATED_FRACTION) + 1e-6


def is_below_spec(pressure_kpa: float | None, placard: float | None) -> bool:
    """At least TIRE_BELOW_SPEC_PSI under placard (1e-6 slack as the spread
    rule, so exactly 3.0 psi after the kPa round-trip counts)."""
    below = below_placard_psi(pressure_kpa, placard)
    return below is not None and below >= TIRE_BELOW_SPEC_PSI - 1e-6


def placard_verdict(
    corner: str,
    pressure_kpa: float | None,
    front_kpa: float | None,
    rear_kpa: float | None,
) -> dict[str, Any]:
    """Per-corner placard fields every tire payload carries."""
    plc = placard_kpa(corner, front_kpa, rear_kpa)
    below = below_placard_psi(pressure_kpa, plc)
    return {
        "placard_kpa": plc,
        "below_placard_psi": None if below is None else round(below, 1),
        "under_inflated": is_under_inflated(pressure_kpa, plc),
        "below_spec": is_below_spec(pressure_kpa, plc),
    }


def placard_meta(front_kpa: float | None, rear_kpa: float | None) -> dict[str, Any]:
    """Top-level placard fields + the thresholds, for both tire payloads."""
    return {
        "placard_front_kpa": front_kpa,
        "placard_rear_kpa": rear_kpa,
        "under_inflated_fraction": TIRE_UNDER_INFLATED_FRACTION,
        "below_spec_psi": TIRE_BELOW_SPEC_PSI,
    }


# Physically plausible tire temperatures; outside this the signed byte is
# noise or a sensor that hasn't reported (e.g. 0x80 = -128).
TIRE_TEMP_MIN_C = -50.0
TIRE_TEMP_MAX_C = 120.0


def valid_tire_value(metric: str, value: float | None) -> bool:
    """Drop TPMS sentinels rather than summarise them. A 0 kPa pressure is a
    sensor that didn't answer (a genuinely flat tire would have been flagged
    long before). Tire temp is a plain signed byte on this car (DID 6001,
    -128..127 °C, no offset), so there is no single sentinel value: keep
    anything a tire can physically read and drop the byte's extremes."""
    if value is None:
        return False
    if metric.startswith("tire_pressure_"):
        return value > 0
    if metric.startswith("tire_temp_"):
        return TIRE_TEMP_MIN_C <= value <= TIRE_TEMP_MAX_C
    return True


def f_to_c(f: float) -> float:
    return (f - 32) * 5 / 9


def is_cold_reading(temp_c: float | None, ambient_f: float | None) -> bool:
    """Heat guard for a cold-candidate reading (already past the >= 3 h gap
    test). Without both temps there is nothing to check against, so the gap
    alone decides."""
    if temp_c is None or ambient_f is None:
        return True
    return temp_c - f_to_c(ambient_f) <= TIRE_COLD_MAX_ABOVE_AMBIENT_C


def slope_psi_per_week(
    points: Iterable[tuple[datetime, float]],
) -> tuple[float | None, int]:
    """Ordinary least-squares slope of (time, kPa) as psi per week.

    Returns (slope, n). slope is None under TIRE_SLOW_LEAK_MIN_SAMPLES points
    or when every point shares one timestamp (no time span to fit).
    """
    pts = list(points)
    n = len(pts)
    if n < TIRE_SLOW_LEAK_MIN_SAMPLES:
        return None, n
    t0 = min(t for t, _ in pts)
    xs = [(t - t0).total_seconds() / (7 * 86400) for t, _ in pts]
    ys = [v * PSI_PER_KPA for _, v in pts]
    mx = sum(xs) / n
    my = sum(ys) / n
    sxx = sum((x - mx) ** 2 for x in xs)
    if sxx <= 0:
        return None, n
    sxy = sum((x - mx) * (y - my) for x, y in zip(xs, ys, strict=True))
    return sxy / sxx, n


# Air added: a corner reading at least this much above its lowest reading
# in the previous TIRE_AIR_UP_MAX_GAP_S. Driving warms a tire by well under
# 1 psi a minute, while a hose adds 2-4 psi per 30-second sensor update
# (2026-10-23: FR 29.0 -> 32.5 -> 34.8 -> 36.4 psi parked at a pump; RR
# went 32.9 -> 34.7 -> 36.3, under 2 psi per step).
TIRE_AIR_UP_PSI = 2.0
TIRE_AIR_UP_MAX_GAP_S = 180
# Air added with the car off shows up as a cold reading this much above the
# previous one, both as read and after both are corrected to one temperature
# (gas law). Both, because sensor temperature is noisy: on 2026-10-23 RR read
# the same 30 psi at 10 °C as the day before at 24 °C, a corrected "rise" of
# 2.2 psi with no air added.
TIRE_REF_C = 20.0
ATM_KPA = 101.325


def compensate_kpa(kpa: float, temp_c: float | None, to_c: float = TIRE_REF_C) -> float:
    """Gauge kPa the tire would read at [to_c], by the gas law on absolute
    pressure. Without the tire's temperature the reading is returned as is."""
    if temp_c is None:
        return kpa
    return (kpa + ATM_KPA) * (to_c + 273.15) / (temp_c + 273.15) - ATM_KPA


def median(values: Iterable[float]) -> float | None:
    vs = sorted(values)
    if not vs:
        return None
    mid = len(vs) // 2
    return vs[mid] if len(vs) % 2 else (vs[mid - 1] + vs[mid]) / 2


def _r(v: float | None, nd: int = 1) -> float | None:
    return None if v is None else round(float(v), nd)


def assemble_tire_analytics(
    days: list[date],
    daily: Mapping[tuple[date, str], tuple[float | None, float | None, float | None]],
    cold_candidates: Iterable[
        tuple[datetime, str, float, float | None, float | None]
    ],
    *,
    placard_front_kpa: float | None = None,
    placard_rear_kpa: float | None = None,
    air_ups: Mapping[str, Iterable[datetime]] | None = None,
    latest: Mapping[str, tuple[datetime, float, float | None, float | None]] | None = None,
) -> dict[str, Any]:
    """Build the /analytics/tires body from pre-aggregated inputs.

    days            every UTC day in the window, ascending
    daily           (day, metric) -> (mean, min, max) for the 8 tire metrics
                    and ambient_air_temp_f (missing key = no data that day)
    cold_candidates (time, corner, kPa, temp °C | None, ambient °F | None):
                    each corner's first reading after a >= TIRE_COLD_PARK_HOURS
                    gap; the heat guard is applied here.
    placard_*_kpa   door-placard cold pressure; each corner's placard verdict
                    is judged on its current cold pressure.
    air_ups         corner -> times air was seen going in (a fast rise, see
                    TIRE_AIR_UP_PSI). Cold readings before the last one no
                    longer describe the tire: the trend restarts there.
    latest          corner -> (time, kPa, tire °C, outside °F) of the newest
                    reading. After an air-up with no cold reading since, the
                    current cold pressure is this reading corrected to the
                    outside temperature, flagged current_cold_estimated.
    """
    cold_by_corner: dict[str, list[dict[str, Any]]] = {c: [] for c in TIRE_CORNERS}
    for t, corner, kpa, temp_c, ambient_f in sorted(cold_candidates, key=lambda r: r[0]):
        if corner not in cold_by_corner or kpa is None or kpa <= 0:
            continue
        if not is_cold_reading(temp_c, ambient_f):
            continue
        cold_by_corner[corner].append(
            {"time": t, "pressure_kpa": float(kpa), "temp_c": _r(temp_c)}
        )

    has_data = False
    corners_out: list[dict[str, Any]] = []
    current_cold: dict[str, float | None] = {}
    for c in TIRE_CORNERS:
        all_colds = cold_by_corner[c]
        # Fills: seen live (a fast rise), or with the car off (a cold reading
        # well above the previous one at the same temperature).
        fills = sorted((air_ups or {}).get(c, ()))
        for prev, cur in zip(all_colds, all_colds[1:], strict=False):
            rise = compensate_kpa(cur["pressure_kpa"], cur["temp_c"]) - compensate_kpa(
                prev["pressure_kpa"], prev["temp_c"]
            )
            raw_rise = cur["pressure_kpa"] - prev["pressure_kpa"]
            if min(rise, raw_rise) * PSI_PER_KPA >= TIRE_AIR_UP_PSI:
                fills.append(cur["time"])
        fills.sort()
        last_fill = fills[-1] if fills else None
        fill_days = {f.astimezone(UTC).date() for f in fills}
        # Trend on the cold readings since the last fill only, so adding air
        # never reads as a rising trend.
        colds = [cr for cr in all_colds if last_fill is None or cr["time"] >= last_fill]
        # First cold reading of each day is that day's cold pressure.
        cold_day: dict[date, float] = {}
        for cr in all_colds:
            cold_day.setdefault(cr["time"].astimezone(UTC).date(), cr["pressure_kpa"])
        daily_out: list[dict[str, Any]] = []
        warmups: list[float] = []
        for d in days:
            p_mean, p_min, p_max = daily.get((d, f"tire_pressure_{c}"), (None, None, None))
            t_mean, _t_min, t_max = daily.get((d, f"tire_temp_{c}"), (None, None, None))
            cold = cold_day.get(d)
            warm = None
            # A fill day's max is the new pressure, not warm-up.
            if cold is not None and p_max is not None and d not in fill_days:
                warm = max(0.0, (float(p_max) - cold) * PSI_PER_KPA)
                warmups.append(warm)
            if p_mean is not None or t_mean is not None:
                has_data = True
            daily_out.append(
                {
                    "day": d.isoformat(),
                    "pressure_min_kpa": _r(p_min),
                    "pressure_mean_kpa": _r(p_mean),
                    "pressure_max_kpa": _r(p_max),
                    "temp_mean_c": _r(t_mean),
                    "temp_max_c": _r(t_max),
                    "cold_pressure_kpa": _r(cold),
                    "warmup_psi": _r(warm, 2),
                }
            )
        slope, n = slope_psi_per_week((cr["time"], cr["pressure_kpa"]) for cr in colds)
        last = all_colds[-1] if all_colds else None
        current_cold[c] = last["pressure_kpa"] if last else None
        current_at = last["time"] if last else None
        estimated = False
        if last_fill is not None and (last is None or last["time"] < last_fill):
            # The cold reading predates the fill. Estimate cold from the
            # newest reading; unknown temperatures leave it unknown rather
            # than passing a hot reading off as cold.
            current_cold[c], current_at = None, None
            lt = (latest or {}).get(c)
            if lt is not None and lt[0] >= last_fill and lt[2] is not None and lt[3] is not None:
                current_cold[c] = compensate_kpa(lt[1], lt[2], f_to_c(lt[3]))
                current_at = lt[0]
                estimated = True
        if all_colds:
            has_data = True
        corners_out.append(
            {
                "corner": c,
                "daily": daily_out,
                "cold_readings": [
                    {
                        "time": cr["time"].isoformat(),
                        "pressure_kpa": _r(cr["pressure_kpa"]),
                        "temp_c": cr["temp_c"],
                    }
                    for cr in all_colds
                ],
                "current_cold_kpa": _r(current_cold[c]),
                "current_cold_at": current_at.isoformat() if current_at else None,
                "current_cold_estimated": estimated,
                "aired_up_at": last_fill.isoformat() if last_fill else None,
                "slope_psi_per_week": _r(slope, 3),
                "slope_samples": n,
                "possible_slow_leak": (
                    slope is not None
                    and n >= TIRE_SLOW_LEAK_MIN_SAMPLES
                    and slope <= TIRE_SLOW_LEAK_PSI_PER_WEEK
                ),
                "warmup_median_psi": _r(median(warmups), 2),
                **placard_verdict(
                    c, current_cold[c], placard_front_kpa, placard_rear_kpa
                ),
            }
        )

    ambient = []
    for d in days:
        a_mean = daily.get((d, "ambient_air_temp_f"), (None, None, None))[0]
        ambient.append({"day": d.isoformat(), "ambient_mean_f": _r(a_mean)})

    return {
        "has_data": has_data,
        "days": [d.isoformat() for d in days],
        "ambient": ambient,
        "corners": corners_out,
        "low_corners": low_tire_corners(current_cold),
        "low_delta_psi": TPMS_LOW_DELTA_PSI,
        "slow_leak_psi_per_week": TIRE_SLOW_LEAK_PSI_PER_WEEK,
        "slow_leak_min_samples": TIRE_SLOW_LEAK_MIN_SAMPLES,
        "cold_park_hours": TIRE_COLD_PARK_HOURS,
        **placard_meta(placard_front_kpa, placard_rear_kpa),
    }
