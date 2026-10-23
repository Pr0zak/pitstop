"""Per-trip and per-day feature extraction for Insights. Pure, no DB.

workers/insight_features.py runs these once per trip / per UTC day and stores
the result as JSONB (migration 0026); /analytics/insights only ever reads the
stored documents. Everything here is either an exact sum (so trips/days add up
without loss) or a [median, count] pair (combined later as a count-weighted
mean of medians -- close enough for a trend, and robust to glitches).

Bump FEATURE_VERSION whenever a document's meaning changes; the worker then
recomputes every row whose raw readings still exist.
"""

from __future__ import annotations

import statistics
from collections import defaultdict
from collections.abc import Sequence
from typing import Any

from .insights import (
    KM_PER_MI,
    PSI_PER_KPA,
    Sample,
    compensated_psi,
    hard_events,
    volumetric_efficiency,
)

FEATURE_VERSION = 1

# Fahrenheit ATF bins, same edges as insights._ATF_BINS.
ATF_EDGES_F = (140, 175, 200, 220)
SPEED_BANDS = (("City", 0, 56), ("Suburban", 56, 88), ("Highway", 88, 999))


def _med(vals: Sequence[float]) -> list[float] | None:
    return [round(statistics.median(vals), 3), len(vals)] if vals else None


# ── per trip ───────────────────────────────────────────────────────────


def trip_features(samples: Sequence[Sample], tires: dict[str, Sequence[float]]) -> dict[str, Any]:
    """samples: the trip's 2-second pivot, time-sorted. tires: corner ->
    pressure readings (kPa) seen during the trip."""
    acc, brk = hard_events(samples)
    bins: dict[int, list[float]] = defaultdict(lambda: [0.0, 0.0, 0])
    cyl_bands: dict[str, list[int]] = {b[0]: [0, 0] for b in SPEED_BANDS}
    for s in samples:
        if s.spd is not None and s.spd >= 8 and s.fr is not None:
            mph = s.spd / KM_PER_MI
            b = bins[min(int(mph // 5) * 5, 80)]
            b[0] += mph
            b[1] += s.fr
            b[2] += 1
        if s.cyl is not None and s.spd is not None and s.spd >= 8:
            band = next(n for n, lo, hi in SPEED_BANDS if lo <= s.spd < hi)
            cyl_bands[band][0] += 1
            cyl_bands[band][1] += int(s.cyl <= 3.5)
    ect = [s for s in samples if s.ect is not None]
    warmup = None
    if len(ect) >= 10 and ect[0].ect < 40:  # type: ignore[operator]
        hot = next((s for s in ect if s.ect >= 80), None)  # type: ignore[operator]
        if hot is not None and hot.t - ect[0].t < 3600:
            warmup = round((hot.t - ect[0].t) / 60, 2)
    cruise = [s.ect for s in ect if s.t - ect[0].t > 900 and s.spd is not None and s.spd > 60]
    return {
        "n": len(samples),
        "hard_acc": acc,
        "hard_brk": brk,
        "speed_bins": {str(k): [round(v[0], 2), round(v[1], 3), v[2]] for k, v in bins.items()},
        "cyl": cyl_bands if any(v[0] for v in cyl_bands.values()) else None,
        "warmup_min": warmup,
        "cruise_ect": _med(cruise),  # type: ignore[arg-type]
        "tire_kpa": {c: [round(statistics.fmean(v), 2), len(v)] for c, v in tires.items() if v},
    }


# ── per day ────────────────────────────────────────────────────────────


def day_features(
    samples: Sequence[Sample],
    *,
    rest_v: Sequence[float],
    run_v: Sequence[float],
    misfire_max: dict[int, float],
    atf_c: Sequence[float],
    tires: dict[str, Sequence[tuple[float, float, float]]],
    gps_pairs: tuple[float, float, int] | None,
) -> dict[str, Any]:
    """One UTC day. tires: corner -> [(epoch s, kPa, tire °C)];
    gps_pairs: (sum OBD kph, sum GPS kph, n) over steady >30 mph buckets."""
    trims: dict[str, list[float]] = defaultdict(list)
    ve: list[float] = []
    frp: list[float] = []
    idle_fr: list[float] = []
    for s in samples:
        if (
            s.fr is not None
            and s.fr > 0
            and s.spd is not None
            and s.spd < 1
            and s.rpm
            and s.rpm > 400
        ):
            idle_fr.append(s.fr)
        if s.frp is not None and s.load is not None and s.load >= 60 and s.frp > 0:
            frp.append(s.frp * PSI_PER_KPA)
        if (
            s.rpm is not None
            and 2000 <= s.rpm <= 3500
            and s.map is not None
            and s.map >= 70
            and not (s.cyl is not None and s.cyl <= 3.5)
        ):
            v = volumetric_efficiency(s)
            if v is not None and 30 < v < 130:
                ve.append(v)
        if s.ect is None or s.ect < 70 or s.spd is None:
            continue
        if s.spd < 2 and s.rpm is not None and s.rpm < 1000:
            mode = "idle"
        elif s.spd >= 40 and s.load is not None and 30 <= s.load <= 80:
            mode = "cruise"
        else:
            continue
        for bank, lt, st in ((1, s.ltft1, s.stft1), (2, s.ltft2, s.stft2)):
            if lt is not None and st is not None and -40 < lt < 40 and -40 < st < 40:
                trims[f"{bank}{mode}"].append(lt + st)
    atf_f = [c * 9 / 5 + 32 for c in atf_c]
    atf = None
    if atf_f:
        counts = [0] * (len(ATF_EDGES_F) + 1)
        for f in atf_f:
            counts[sum(1 for e in ATF_EDGES_F if f >= e)] += 1
        atf = {"max": round(max(atf_f), 1), "bins": counts}
    hourly: dict[str, list[list[float]]] = {}
    for c, rows in tires.items():
        per: dict[int, list[float]] = defaultdict(list)
        for t, p, tc in rows:
            if 100 < p < 450 and -40 < tc < 120:
                per[int(t // 3600)].append(compensated_psi(p, tc))
        if per:
            hourly[c] = [[h * 3600, round(statistics.median(v), 2)] for h, v in sorted(per.items())]
    return {
        "trim": {k: _med(v) for k, v in trims.items()},
        "ve": _med(ve),
        "frp_psi": _med(frp) if len(frp) >= 5 else None,
        "idle_gs": _med(idle_fr),
        "rest_v": _med([v for v in rest_v if 10.5 < v < 13.0]),
        "run_v": _med([v for v in run_v if v > 11]),
        "misfire": {str(k): v for k, v in misfire_max.items()},
        "atf": atf,
        "tires": hourly,
        "gps": list(gps_pairs) if gps_pairs and gps_pairs[2] else None,
    }
