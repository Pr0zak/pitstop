"""TPMS rules shared by the trip detail and /analytics/tires.

Pure functions only, no DB. The web client implements the same low rule in
frontend/src/lib/vehicleSignals.ts (tpmsReadings / TPMS_LOW_DELTA_PSI) and
the same slow-leak threshold (TIRE_SLOW_LEAK_PSI_PER_WEEK) -- keep the
numbers in step.
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
) -> dict[str, Any]:
    """Build the /analytics/tires body from pre-aggregated inputs.

    days            every UTC day in the window, ascending
    daily           (day, metric) -> (mean, min, max) for the 8 tire metrics
                    and ambient_air_temp_f (missing key = no data that day)
    cold_candidates (time, corner, kPa, temp °C | None, ambient °F | None):
                    each corner's first reading after a >= TIRE_COLD_PARK_HOURS
                    gap; the heat guard is applied here.
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
        colds = cold_by_corner[c]
        # First cold reading of each day is that day's cold pressure.
        cold_day: dict[date, float] = {}
        for cr in colds:
            cold_day.setdefault(cr["time"].astimezone(UTC).date(), cr["pressure_kpa"])
        daily_out: list[dict[str, Any]] = []
        warmups: list[float] = []
        for d in days:
            p_mean, p_min, p_max = daily.get((d, f"tire_pressure_{c}"), (None, None, None))
            t_mean, _t_min, t_max = daily.get((d, f"tire_temp_{c}"), (None, None, None))
            cold = cold_day.get(d)
            warm = None
            if cold is not None and p_max is not None:
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
        last = colds[-1] if colds else None
        current_cold[c] = last["pressure_kpa"] if last else None
        if colds:
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
                    for cr in colds
                ],
                "current_cold_kpa": _r(current_cold[c]),
                "current_cold_at": last["time"].isoformat() if last else None,
                "slope_psi_per_week": _r(slope, 3),
                "slope_samples": n,
                "possible_slow_leak": (
                    slope is not None
                    and n >= TIRE_SLOW_LEAK_MIN_SAMPLES
                    and slope <= TIRE_SLOW_LEAK_PSI_PER_WEEK
                ),
                "warmup_median_psi": _r(median(warmups), 2),
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
    }
