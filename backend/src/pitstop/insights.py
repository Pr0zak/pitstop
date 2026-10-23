"""Insights: correlations that turn raw telemetry into plain-language findings.

Pure functions only, no DB. ``api/insights.py`` fetches the rows and hands
them here. Every builder returns one card in the same shape, so the web and
the phone render all of them with one component and never compute anything
themselves (ADR-013: the server is the source of truth).

Card shape::

    {
      "id": "cold_weather", "group": "fuel", "title": "Cold weather",
      "status": "good" | "watch" | "bad" | "info" | "learning",
      "headline": "one sentence a driver understands",
      "detail": "one or two supporting sentences" | None,
      "value": 12.0 | None, "value_unit": "% worse" | None,   # the big number
      "chart": None | {"kind": "bars", ...} | {"kind": "lines", ...}
                     | {"kind": "points", ...},
      "how": "how the number was worked out",
      "tip": "what to do about it" | None,
      "sample": "Based on 214 trips" | None,
      "progress": None | {"have": 2, "need": 7, "unit": "days"},
    }

Charts (all values already in display units):

* bars:   {"kind", "y_unit", "bars": [{"label", "value", "n", "highlight"}],
           "band": {"lo", "hi", "label"} | None, "ref": {"value", "label"} | None}
* lines:  {"kind", "x_type": "time", "y_unit", "series": [{"name",
           "points": [[epoch_ms, y], ...]}], "band" | None, "ref" | None}
* points: {"kind", "x_unit", "y_unit", "points": [[x, y], ...]} (a scatter)

Units are US (mpg, mph, degrees F, psi, gallons, dollars), like
/dashboard/summary. "learning" = not enough data yet; the card says how much
more it needs instead of showing a number that can't be trusted.
"""

from __future__ import annotations

import bisect
import statistics
from collections import defaultdict
from collections.abc import Iterable, Sequence
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any

KM_PER_MI = 1.609344
L_PER_GAL = 3.785411784
PSI_PER_KPA = 0.145038
ATM_KPA = 101.325
# J35Y6 in the 2019 Pilot. Only used for the airflow (volumetric-efficiency)
# trend, which compares the engine with itself over time, so a different
# engine only shifts the absolute level, never the trend.
DISPLACEMENT_L = 3.471
# Same density trip_stats uses to turn PID 0x9D grams into litres.
GASOLINE_G_PER_L = 749.9

GROUPS: list[dict[str, str]] = [
    {"id": "fuel", "title": "Fuel economy", "subtitle": "What moves your MPG"},
    {"id": "engine", "title": "Engine health", "subtitle": "Early warnings from the sensors"},
    {"id": "tires", "title": "Tires & brakes", "subtitle": "Pressure, wear and braking"},
    {"id": "cost", "title": "Cost", "subtitle": "What you pay and when"},
]

STATUS_RANK = {"bad": 0, "watch": 1, "good": 2, "info": 3, "learning": 4}


# ── inputs ─────────────────────────────────────────────────────────────


@dataclass(slots=True)
class Trip:
    start: float  # epoch s
    end: float
    duration_s: float
    distance_km: float | None
    fuel_l: float | None
    avg_kph: float | None
    idle_s: float | None = None
    temp_c: float | None = None
    wind_kph: float | None = None
    precip_mm: float | None = None
    towing: bool = False

    @property
    def miles(self) -> float:
        return (self.distance_km or 0.0) / KM_PER_MI

    @property
    def gallons(self) -> float:
        return (self.fuel_l or 0.0) / L_PER_GAL

    @property
    def mpg(self) -> float | None:
        """OBD-measured trip MPG, or None when the trip is too short or the
        figure is implausible (one MAF glitch dominates a tiny trip)."""
        if not self.fuel_l or self.fuel_l < 0.1 or not self.distance_km or self.distance_km < 1.0:
            return None
        v = self.miles / self.gallons
        return v if 3.0 <= v <= 60.0 else None


@dataclass(slots=True)
class Sample:
    """One 2-second bucket of the pivoted PID stream. None = not read."""

    t: float
    spd: float | None = None  # kph
    fr: float | None = None  # engine fuel rate (PID 0x9D), grams of fuel per second
    rpm: float | None = None
    maf: float | None = None  # g/s
    map: float | None = None  # manifold absolute pressure, kPa
    iat: float | None = None  # degrees C
    load: float | None = None  # %
    ect: float | None = None  # coolant, degrees C
    ltft1: float | None = None
    ltft2: float | None = None
    stft1: float | None = None
    stft2: float | None = None
    cyl: float | None = None  # active cylinders
    frp: float | None = None  # fuel rail pressure, kPa


# ── small helpers ──────────────────────────────────────────────────────


def _card(
    id: str,
    group: str,
    title: str,
    status: str,
    headline: str,
    *,
    detail: str | None = None,
    value: float | None = None,
    value_unit: str | None = None,
    chart: dict[str, Any] | None = None,
    how: str,
    tip: str | None = None,
    sample: str | None = None,
    progress: dict[str, Any] | None = None,
) -> dict[str, Any]:
    return {
        "id": id,
        "group": group,
        "title": title,
        "status": status,
        "headline": headline,
        "detail": detail,
        "value": value,
        "value_unit": value_unit,
        "chart": chart,
        "how": how,
        "tip": tip,
        "sample": sample,
        "progress": progress,
    }


def _learning(
    id: str,
    group: str,
    title: str,
    headline: str,
    *,
    how: str,
    have: float,
    need: float,
    unit: str,
    detail: str | None = None,
    chart: dict[str, Any] | None = None,
) -> dict[str, Any]:
    return _card(
        id,
        group,
        title,
        "learning",
        headline,
        detail=detail,
        how=how,
        chart=chart,
        progress={"have": round(have), "need": round(need), "unit": unit},
    )


def c_to_f(c: float) -> float:
    return c * 9 / 5 + 32


def _r(v: float | None, nd: int = 1) -> float | None:
    return None if v is None else round(v, nd)


def _pct(a: float, b: float) -> float:
    """a relative to b, in percent (a 15 vs b 20 -> -25)."""
    return (a - b) / b * 100 if b else 0.0


def _mpg_of(trips: Iterable[Trip]) -> tuple[float | None, int]:
    """Distance-weighted MPG over trips that have a valid MPG (total miles /
    total gallons, so one short trip can't swing it), and the trip count."""
    mi = gal = 0.0
    n = 0
    for t in trips:
        if t.mpg is None:
            continue
        mi += t.miles
        gal += t.gallons
        n += 1
    return (mi / gal if gal > 0 else None), n


def _bars(
    bars: list[dict[str, Any]], y_unit: str, *, band: dict | None = None, ref: dict | None = None
) -> dict[str, Any]:
    return {"kind": "bars", "y_unit": y_unit, "bars": bars, "band": band, "ref": ref}


def _lines(
    series: list[dict[str, Any]], y_unit: str, *, band: dict | None = None, ref: dict | None = None
) -> dict[str, Any]:
    return {
        "kind": "lines",
        "x_type": "time",
        "y_unit": y_unit,
        "series": series,
        "band": band,
        "ref": ref,
    }


def _bar(label: str, value: float | None, n: int = 0, highlight: bool = False) -> dict[str, Any]:
    return {"label": label, "value": _r(value, 1), "n": n, "highlight": highlight}


def slope_per_day(points: Sequence[tuple[float, float]]) -> float | None:
    """Least-squares slope of (epoch s, y) in y per day; None under 2 points."""
    if len(points) < 2:
        return None
    xs = [p[0] / 86400 for p in points]
    ys = [p[1] for p in points]
    mx, my = statistics.fmean(xs), statistics.fmean(ys)
    sxx = sum((x - mx) ** 2 for x in xs)
    if sxx == 0:
        return None
    return sum((x - mx) * (y - my) for x, y in zip(xs, ys, strict=True)) / sxx


def _day(t: float) -> str:
    return datetime.fromtimestamp(t, UTC).strftime("%Y-%m-%d")


def _day_ms(day: str) -> int:
    return int(datetime.strptime(day, "%Y-%m-%d").replace(tzinfo=UTC).timestamp() * 1000)


def _month(t: float) -> str:
    return datetime.fromtimestamp(t, UTC).strftime("%Y-%m")


def _month_label(key: str) -> str:
    return datetime.strptime(key, "%Y-%m").strftime("%b")


def samples_by_trip(trips: Sequence[Trip], samples: Sequence[Sample]) -> dict[int, list[Sample]]:
    """Index of trip -> its samples (samples sorted by time)."""
    order = sorted(range(len(trips)), key=lambda i: trips[i].start)
    starts = [trips[i].start for i in order]
    out: dict[int, list[Sample]] = defaultdict(list)
    for s in samples:
        k = bisect.bisect_right(starts, s.t) - 1
        if k < 0:
            continue
        i = order[k]
        if s.t <= trips[i].end:
            out[i].append(s)
    return out


# ── fuel economy ───────────────────────────────────────────────────────

_TEMP_BINS = [
    ("Below 32°F", -999, 32),
    ("32–50°F", 32, 50),
    ("50–70°F", 50, 70),
    ("70–85°F", 70, 85),
    ("Above 85°F", 85, 999),
]


def _temp_phrase(label: str) -> str:
    """'Below 32°F' -> 'days below 32 °F'; '50–70°F' -> '50–70 °F days'."""
    t = label.replace("°F", " °F")
    if t.startswith(("Below", "Above")):
        return "days " + t[0].lower() + t[1:]
    return t + " days"


def cold_weather(trips: Sequence[Trip]) -> dict[str, Any]:
    how = (
        "Each trip's MPG comes from the engine's own fuel-flow reading. Trips are "
        "grouped by the outside temperature at the time, and each bar is total miles "
        "divided by total gallons for that group."
    )
    title = "Outside temperature"
    usable = [t for t in trips if t.mpg is not None and t.temp_c is not None]
    if len(usable) < 15:
        return _learning(
            "cold_weather",
            "fuel",
            title,
            "Needs more trips with fuel data to compare temperatures.",
            how=how,
            have=len(usable),
            need=15,
            unit="trips",
        )
    bars = []
    for label, lo, hi in _TEMP_BINS:
        group = [t for t in usable if lo <= c_to_f(t.temp_c) < hi]  # type: ignore[arg-type]
        mpg, n = _mpg_of(group)
        if n >= 8 and mpg is not None:
            bars.append({"label": label, "mpg": mpg, "n": n})
    if len(bars) < 2:
        return _learning(
            "cold_weather",
            "fuel",
            title,
            "All your trips so far were in similar weather, so there is nothing to compare yet.",
            how=how,
            have=len(bars),
            need=2,
            unit="temperature ranges",
        )
    best = max(bars, key=lambda b: b["mpg"])
    worst = min(bars, key=lambda b: b["mpg"])
    drop = _pct(worst["mpg"], best["mpg"])
    if abs(drop) < 4:
        headline = "Outside temperature barely changes your fuel economy."
    else:
        headline = (
            f"Your MPG is best on {_temp_phrase(best['label'])} ({best['mpg']:.1f}) and "
            f"{abs(drop):.0f}% lower on {_temp_phrase(worst['label'])} ({worst['mpg']:.1f})."
        )
    return _card(
        "cold_weather",
        "fuel",
        title,
        "info",
        headline,
        value=_r(abs(drop), 0),
        value_unit="% swing",
        chart=_bars([_bar(b["label"], b["mpg"], b["n"], b is best) for b in bars], "mpg"),
        how=how,
        detail=(
            "Cold weather hurts twice: the engine runs rich until it warms up, and "
            "winter-blend gas holds slightly less energy. Hot days cost A/C."
        ),
        tip="In winter, combine short errands into one trip so the engine stays warm.",
        sample=f"Based on {sum(b['n'] for b in bars)} trips",
    )


_DUR_BINS = [
    ("Under 5 min", 0, 5),
    ("5–10 min", 5, 10),
    ("10–20 min", 10, 20),
    ("20–40 min", 20, 40),
    ("Over 40 min", 40, 10_000),
]


def short_trips(trips: Sequence[Trip]) -> dict[str, Any]:
    how = (
        "Trips are grouped by how long the engine ran. Each bar is total miles "
        "divided by total gallons for trips of that length."
    )
    title = "Short trips"
    usable = [t for t in trips if t.mpg is not None]
    if len(usable) < 15:
        return _learning(
            "short_trips",
            "fuel",
            title,
            "Needs more trips with fuel data to compare trip lengths.",
            how=how,
            have=len(usable),
            need=15,
            unit="trips",
        )
    bars = []
    for label, lo, hi in _DUR_BINS:
        mpg, n = _mpg_of(t for t in usable if lo <= t.duration_s / 60 < hi)
        if n >= 3 and mpg is not None:
            bars.append((label, mpg, n))
    short_mpg, n_short = _mpg_of(t for t in usable if t.duration_s < 600)
    long_mpg, n_long = _mpg_of(t for t in usable if t.duration_s >= 1200)
    share = sum(1 for t in trips if t.duration_s < 600) / len(trips) * 100 if trips else 0
    best = max(bars, key=lambda b: b[1]) if bars else None
    chart = _bars(
        [_bar(lbl, m, n, best is not None and lbl == best[0]) for lbl, m, n in bars], "mpg"
    )
    if short_mpg is None or long_mpg is None or n_short < 3 or n_long < 3:
        headline = f"{share:.0f}% of your trips are under 10 minutes."
        value = None
    else:
        drop = _pct(short_mpg, long_mpg)
        value = _r(abs(drop), 0)
        headline = (
            f"Trips under 10 minutes average {short_mpg:.1f} mpg, "
            f"{abs(drop):.0f}% {'worse' if drop < 0 else 'better'} than trips over 20 minutes "
            f"({long_mpg:.1f})."
        )
    return _card(
        "short_trips",
        "fuel",
        title,
        "watch" if share >= 50 else "info",
        headline,
        value=value,
        value_unit="% worse" if value is not None else None,
        detail=f"{share:.0f}% of all your trips are under 10 minutes. A cold engine burns "
        "extra fuel for its first few miles.",
        chart=chart,
        how=how,
        tip="Chain errands together: one 20-minute loop beats three 7-minute trips.",
        sample=f"Based on {len(usable)} trips",
    )


def idle_rate_lph(samples: Sequence[Sample]) -> float | None:
    """Median fuel flow while stopped with the engine running (L/h)."""
    vals = [
        s.fr
        for s in samples
        if s.fr is not None
        and s.fr > 0
        and s.spd is not None
        and s.spd < 1
        and s.rpm is not None
        and s.rpm > 400
    ]
    return statistics.median(vals) * 3600 / GASOLINE_G_PER_L if len(vals) >= 30 else None


def idle_cost(
    trips: Sequence[Trip], samples: Sequence[Sample], price_per_gal: float | None
) -> dict[str, Any]:
    how = (
        "Idle time is how long the engine ran while the car stood still, summed over "
        "your trips. The fuel it burned comes from the engine's measured fuel flow "
        "while stopped, and the cost uses your latest fill-up price."
    )
    title = "Idling"
    idle_trips = [t for t in trips if t.idle_s is not None]
    rate = idle_rate_lph(samples)
    if not idle_trips or rate is None:
        return _learning(
            "idle_cost",
            "fuel",
            title,
            "Needs fuel-flow readings while stopped to price your idle time.",
            how=how,
            have=0,
            need=1,
            unit="drives with fuel data",
        )
    idle_h = sum(t.idle_s or 0 for t in idle_trips) / 3600
    run_h = sum(t.duration_s for t in idle_trips) / 3600
    share = idle_h / run_h * 100 if run_h else 0
    gal_h = rate / L_PER_GAL
    gal = idle_h * gal_h
    cost = gal * price_per_gal if price_per_gal else None
    months: dict[str, float] = defaultdict(float)
    for t in idle_trips:
        months[_month(t.start)] += (t.idle_s or 0) / 3600 * gal_h * (price_per_gal or 1)
    keys = sorted(months)[-12:]
    unit = "$" if price_per_gal else "gal"
    chart = _bars([_bar(_month_label(k), months[k]) for k in keys], unit)
    money = f"${cost:,.0f} ({gal:.1f} gal)" if cost is not None else f"{gal:.1f} gal"
    return _card(
        "idle_cost",
        "fuel",
        title,
        "watch" if share >= 25 else "info",
        f"Idling burned {money} over this period.",
        value=_r(cost if cost is not None else gal, 0),
        value_unit="$" if cost is not None else "gal",
        detail=(
            f"The engine idled {idle_h:.1f} hours, {share:.0f}% of its running time, "
            f"burning about {gal_h:.2f} gal an hour while stopped."
        ),
        chart=chart,
        how=how,
        tip="Idle-stop saves fuel at lights; for waits over a minute, shutting off is cheaper than idling.",
        sample=f"Based on {len(idle_trips)} trips",
    )


def best_speed(samples: Sequence[Sample]) -> dict[str, Any]:
    how = (
        "Every two seconds of driving is filed by road speed. For each 5-mph band, "
        "fuel economy is the average speed divided by the average fuel flow, so "
        "time spent coasting with the fuel cut off counts in your favour."
    )
    title = "Most efficient speed"
    bins: dict[int, list[float]] = defaultdict(lambda: [0.0, 0.0, 0])  # mph sum, gal/h sum, n
    for s in samples:
        if s.spd is None or s.fr is None or s.spd < 8:
            continue
        mph = s.spd / KM_PER_MI
        b = min(int(mph // 5) * 5, 80)
        acc = bins[b]
        acc[0] += mph
        acc[1] += s.fr * 3600 / GASOLINE_G_PER_L / L_PER_GAL
        acc[2] += 1
    rows = [(b, acc[0] / acc[1]) for b, acc in sorted(bins.items()) if acc[2] >= 50 and acc[1] > 0]
    if len(rows) < 4:
        return _learning(
            "best_speed",
            "fuel",
            title,
            "Needs more driving with fuel-flow readings across a range of speeds.",
            how=how,
            have=len(rows),
            need=4,
            unit="speed bands",
        )
    # Best band among speeds a driver can actually hold (25 mph and up).
    cruise = [r for r in rows if r[0] >= 25] or rows
    best = max(cruise, key=lambda r: r[1])

    def label(b: int) -> str:
        return "80+" if b >= 80 else f"{b}–{b + 5}"

    fast = next((r for r in rows if r[0] == 70), rows[-1])
    headline = f"Your Pilot is most efficient at {label(best[0])} mph ({best[1]:.0f} mpg)."
    detail = None
    if fast[0] > best[0]:
        detail = (
            f"At {label(fast[0])} mph it gets {fast[1]:.0f} mpg, "
            f"{abs(_pct(fast[1], best[1])):.0f}% less. Air drag grows with the square of speed."
        )
    return _card(
        "best_speed",
        "fuel",
        title,
        "info",
        headline,
        value=_r(best[1], 0),
        value_unit="mpg",
        detail=detail,
        chart=_bars([_bar(label(b), m, 0, b == best[0]) for b, m in rows], "mpg"),
        how=how,
        tip="On the highway, 65 instead of 75 mph is the cheapest fuel saving there is.",
    )


def wind_rain(trips: Sequence[Trip]) -> dict[str, Any]:
    how = (
        "Trips are split by the weather recorded for them: calm (wind under 9 mph) vs "
        "windy (16 mph and up), and dry vs wet (any rain). Bars are total miles "
        "divided by total gallons."
    )
    title = "Wind & rain"
    usable = [t for t in trips if t.mpg is not None]
    calm = [t for t in usable if t.wind_kph is not None and t.wind_kph < 15]
    windy = [t for t in usable if t.wind_kph is not None and t.wind_kph >= 25]
    dry = [t for t in usable if t.precip_mm is not None and t.precip_mm == 0]
    wet = [t for t in usable if t.precip_mm is not None and t.precip_mm > 0.2]
    bars: list[dict[str, Any]] = []
    parts: list[str] = []
    for a_lbl, a, b_lbl, b, what in (
        ("Calm", calm, "Windy", windy, "strong wind"),
        ("Dry", dry, "Rain", wet, "rain"),
    ):
        am, an = _mpg_of(a)
        bm, bn = _mpg_of(b)
        if an >= 5 and bn >= 5 and am and bm:
            bars += [_bar(a_lbl, am, an), _bar(b_lbl, bm, bn, True)]
            d = _pct(bm, am)
            parts.append(
                f"{what} cost {abs(d):.0f}%" if d < -2 else f"{what} made no real difference"
            )
    if not bars:
        return _learning(
            "wind_rain",
            "fuel",
            title,
            "Needs at least 5 windy or rainy trips with fuel data.",
            how=how,
            have=max(len(windy), len(wet)),
            need=5,
            unit="trips",
        )
    headline = "In your driving, " + " and ".join(parts) + "."
    return _card(
        "wind_rain",
        "fuel",
        title,
        "info",
        headline[0].upper() + headline[1:],
        chart=_bars(bars, "mpg"),
        how=how,
        detail="Wet roads add rolling resistance; a headwind adds drag. Both are worst at highway speed.",
        sample=f"Based on {len(usable)} trips",
    )


def towing(trips: Sequence[Trip]) -> dict[str, Any]:
    how = "Trips you marked as towing are compared with all other trips, by total miles divided by total gallons."
    title = "Towing"
    tow = [t for t in trips if t.towing]
    norm = [t for t in trips if not t.towing]
    tm, tn = _mpg_of(tow)
    nm, nn = _mpg_of(norm)
    if tn < 3 or not tm or not nm:
        return _learning(
            "towing",
            "fuel",
            title,
            "No towing trips to compare yet. Mark a trip as towing in its details.",
            how=how,
            have=tn,
            need=3,
            unit="towing trips",
        )
    d = _pct(tm, nm)
    return _card(
        "towing",
        "fuel",
        title,
        "info",
        f"Towing drops you to {tm:.1f} mpg, {abs(d):.0f}% below your usual {nm:.1f}.",
        value=_r(abs(d), 0),
        value_unit="% worse",
        chart=_bars([_bar("Normal", nm, nn), _bar("Towing", tm, tn, True)], "mpg"),
        how=how,
        tip="When towing, keep to 60–65 mph; drag on a trailer rises fast above that.",
        sample=f"{tn} towing trips",
    )


# 0.25 g in kph per second. The stream is 2-second buckets, which smooths a
# real 0.3 g event to roughly this.
HARD_EVENT_KPH_S = 8.8


def hard_events(samples: Sequence[Sample]) -> tuple[int, int]:
    """(hard accelerations, hard brakes) in a time-sorted sample run, at most
    one event per 6 s so one long stop isn't counted three times."""
    acc = brk = 0
    last_a = last_b = -1e18
    prev: Sample | None = None
    for s in samples:
        if s.spd is None:
            continue
        if prev is not None and prev.spd is not None:
            dt = s.t - prev.t
            if 0 < dt <= 4:
                rate = (s.spd - prev.spd) / dt
                if rate >= HARD_EVENT_KPH_S and s.t - last_a > 6:
                    acc += 1
                    last_a = s.t
                elif rate <= -HARD_EVENT_KPH_S and s.t - last_b > 6:
                    brk += 1
                    last_b = s.t
        prev = s
    return acc, brk


def driving_style(trips: Sequence[Trip], by_trip: dict[int, list[Sample]]) -> dict[str, Any]:
    how = (
        "For each trip we count hard starts and hard stops (speed changing faster than "
        "about a quarter of a g) per 10 miles. Trips are ranked by that and split into "
        "three equal groups; bars are each group's total miles divided by total gallons."
    )
    title = "Driving style"
    rows = []
    for i, t in enumerate(trips):
        ss = by_trip.get(i)
        if t.mpg is None or t.miles < 2 or not ss or len(ss) < 60:
            continue
        a, b = hard_events(ss)
        rows.append(((a + b) / t.miles * 10, t))
    if len(rows) < 15:
        return _learning(
            "driving_style",
            "fuel",
            title,
            "Needs more trips with speed and fuel data to rank your driving.",
            how=how,
            have=len(rows),
            need=15,
            unit="trips",
        )
    rows.sort(key=lambda r: r[0])
    k = len(rows) // 3
    groups = [
        ("Gentle", rows[:k]),
        ("Normal", rows[k : len(rows) - k]),
        ("Hard", rows[len(rows) - k :]),
    ]
    bars = []
    stats = []
    for label, grp in groups:
        m, n = _mpg_of(t for _, t in grp)
        rate = statistics.fmean(r for r, _ in grp)
        stats.append((label, m, rate))
        bars.append(_bar(label, m, n, label == "Gentle"))
    g, h = stats[0][1], stats[2][1]
    if not g or not h:
        return _learning(
            "driving_style",
            "fuel",
            title,
            "Not enough fuel data in each group yet.",
            how=how,
            have=len(rows),
            need=15,
            unit="trips",
        )
    d = _pct(h, g)
    return _card(
        "driving_style",
        "fuel",
        title,
        "info",
        f"Your gentlest trips average {g:.1f} mpg; your hardest average {h:.1f}, {abs(d):.0f}% {'less' if d < 0 else 'more'}.",
        value=_r(abs(d), 0),
        value_unit="% gap",
        detail=(
            f"Gentle trips have about {stats[0][2]:.1f} hard starts or stops per 10 mi; "
            f"hard ones about {stats[2][2]:.1f}."
        ),
        chart=_bars(bars, "mpg"),
        how=how,
        tip="Look further ahead and lift off early. Coasting to a red light uses no fuel at all.",
        sample=f"Based on {len(rows)} trips",
    )


def cylinder_mode(trips: Sequence[Trip], by_trip: dict[int, list[Sample]]) -> dict[str, Any]:
    how = (
        "The engine reports how many cylinders are firing. While the car is moving, we "
        "measure how much of the time it ran on 3 instead of 6, by road type."
    )
    title = "3-cylinder cruising"
    moving = [
        s
        for ss in by_trip.values()
        for s in ss
        if s.cyl is not None and s.spd is not None and s.spd >= 8
    ]
    if len(moving) < 300:
        return _learning(
            "cylinder_mode",
            "fuel",
            title,
            "Collecting cylinder readings. Needs about 10 more minutes of driving.",
            how=how,
            have=len(moving) * 2 // 60,
            need=10,
            unit="minutes",
        )
    share = sum(1 for s in moving if s.cyl <= 3.5) / len(moving) * 100  # type: ignore[operator]
    bands = [("City", 0, 56), ("Suburban", 56, 88), ("Highway", 88, 999)]
    bars = []
    for label, lo, hi in bands:
        grp = [s for s in moving if lo <= s.spd < hi]  # type: ignore[operator]
        if len(grp) >= 30:
            bars.append(_bar(label, sum(1 for s in grp if s.cyl <= 3.5) / len(grp) * 100, len(grp)))  # type: ignore[operator]
    # Per-trip share vs MPG once there are enough trips.
    pairs = []
    for i, t in enumerate(trips):
        grp = [
            s for s in by_trip.get(i, []) if s.cyl is not None and s.spd is not None and s.spd >= 8
        ]
        if t.mpg is not None and len(grp) >= 60:
            pairs.append((sum(1 for s in grp if s.cyl <= 3.5) / len(grp), t))  # type: ignore[operator]
    detail = (
        "Honda's Variable Cylinder Management shuts off one bank at light, steady load "
        "to save fuel."
    )
    if len(pairs) >= 8:
        pairs.sort(key=lambda p: p[0])
        half = len(pairs) // 2
        lo_m, _ = _mpg_of(t for _, t in pairs[:half])
        hi_m, _ = _mpg_of(t for _, t in pairs[half:])
        if lo_m and hi_m:
            detail += (
                f" Trips with more 3-cylinder time averaged {hi_m:.1f} mpg vs "
                f"{lo_m:.1f} for trips with less."
            )
    else:
        detail += (
            f" The MPG comparison unlocks after 8 trips with cylinder data ({len(pairs)} so far)."
        )
    return _card(
        "cylinder_mode",
        "fuel",
        title,
        "info",
        f"The engine ran on 3 cylinders {share:.0f}% of the time while moving.",
        value=_r(share, 0),
        value_unit="% of driving",
        detail=detail,
        chart=_bars(bars, "%"),
        how=how,
        tip="Gentle, steady throttle keeps it in 3-cylinder mode longer.",
        sample=f"{len(moving) * 2 / 3600:.1f} hours of driving",
    )


def tire_pressure_mpg(trips: Sequence[Trip], trip_psi_below: dict[int, float]) -> dict[str, Any]:
    """trip_psi_below: trip index -> average psi below the door placard
    during that trip (negative = above placard)."""
    how = (
        "For each trip we take the average of the four tire pressures against the door "
        "placard, then compare MPG on trips with softer tires with trips with firmer ones."
    )
    title = "Tire pressure vs MPG"
    pairs = [
        (trip_psi_below[i], t)
        for i, t in enumerate(trips)
        if i in trip_psi_below and t.mpg is not None
    ]
    if len(pairs) < 10:
        return _learning(
            "tire_pressure_mpg",
            "fuel",
            title,
            "Collecting tire readings on drives to compare against fuel economy.",
            how=how,
            have=len(pairs),
            need=10,
            unit="trips",
        )
    pairs.sort(key=lambda p: p[0])
    half = len(pairs) // 2
    firm, soft = pairs[:half], pairs[half:]
    fm, fn = _mpg_of(t for _, t in firm)
    sm, sn = _mpg_of(t for _, t in soft)
    spread = statistics.fmean(p for p, _ in soft) - statistics.fmean(p for p, _ in firm)
    if not fm or not sm or spread < 1:
        return _card(
            "tire_pressure_mpg",
            "fuel",
            title,
            "info",
            "Your tire pressure has stayed steady, so there's no MPG difference to show.",
            how=how,
            sample=f"Based on {len(pairs)} trips",
        )
    d = _pct(sm, fm)
    return _card(
        "tire_pressure_mpg",
        "fuel",
        title,
        "info",
        f"With tires {spread:.1f} psi softer you averaged {sm:.1f} mpg vs {fm:.1f}, {abs(d):.0f}% {'less' if d < 0 else 'more'}.",
        value=_r(abs(d), 0),
        value_unit="% gap",
        chart=_bars([_bar("Firmer", fm, fn), _bar("Softer", sm, sn, True)], "mpg"),
        how=how,
        tip="Check pressure monthly with the tires cold; the door sticker is the target.",
        sample=f"Based on {len(pairs)} trips",
    )


# ── engine health ──────────────────────────────────────────────────────


def fuel_trim_split(samples: Sequence[Sample]) -> dict[str, Any]:
    how = (
        "Fuel trim is how much fuel the computer adds (+) or removes (−) to hit the "
        "right mixture. We add the short- and long-term trims for each side of the V6 "
        "and compare idle (stopped, warm) with steady cruising. Within ±5% is healthy."
    )
    title = "Fuel mixture"
    buckets: dict[str, list[float]] = defaultdict(list)
    for s in samples:
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
                buckets[f"{bank}{mode}"].append(lt + st)
    keys = ["1idle", "1cruise", "2idle", "2cruise"]
    if any(len(buckets[k]) < 50 for k in keys):
        return _learning(
            "fuel_trim_split",
            "engine",
            title,
            "Needs more warm-engine idle and cruise readings.",
            how=how,
            have=min(len(buckets[k]) for k in keys),
            need=50,
            unit="readings",
        )
    v = {k: statistics.median(buckets[k]) for k in keys}
    worst = max(abs(x) for x in v.values())
    status = "good" if worst <= 5 else "watch" if worst <= 10 else "bad"
    idle = max(v["1idle"], v["2idle"])
    cruise = max(v["1cruise"], v["2cruise"])
    if idle > 5 and idle > cruise + 4:
        headline = (
            "Extra fuel is being added mostly at idle, a classic sign of an air (vacuum) leak."
        )
        tip = "Have the intake hoses, PCV valve and intake gaskets checked for leaks."
    elif cruise > 5 and cruise > idle + 4:
        headline = "Extra fuel is being added mostly under load, which points to fuel delivery or a dirty airflow sensor."
        tip = "A fuel-pressure check and a MAF sensor cleaning are the cheap first steps."
    elif min(v.values()) < -5:
        headline = "The engine is pulling fuel out, meaning it's running a little rich."
        tip = "Common causes: a leaking injector, a sticking purge valve, or a sensor reading high."
    elif worst > 5:
        headline = "Fuel correction is a little high but has no clear pattern yet."
        tip = "Keep an eye on it; if it climbs past 10% a check-engine light usually follows."
    else:
        headline = "Both sides of the engine need very little fuel correction. Healthy."
        tip = None
    labels = {
        "1idle": "Left idle",
        "1cruise": "Left cruise",
        "2idle": "Right idle",
        "2cruise": "Right cruise",
    }
    return _card(
        "fuel_trim_split",
        "engine",
        title,
        status,
        headline,
        value=_r(worst, 1),
        value_unit="% max correction",
        detail="Bank 1 (left) and bank 2 (right) are the two sides of the V6.",
        chart=_bars(
            [_bar(labels[k], v[k], len(buckets[k]), abs(v[k]) == worst) for k in keys],
            "%",
            band={"lo": -5, "hi": 5, "label": "Healthy ±5%"},
        ),
        how=how,
        tip=tip,
        sample=f"Last {_span_days(samples)} days",
    )


def _span_days(samples: Sequence[Sample]) -> int:
    return max(1, round((samples[-1].t - samples[0].t) / 86400)) if samples else 0


def bank_balance(daily: dict[str, dict[str, float]]) -> dict[str, Any]:
    """daily: metric -> {YYYY-MM-DD: avg}. Uses ltft_b1/ltft_b2 and
    catalyst_temp_b1/catalyst_temp_b2."""
    how = (
        "We compare the two sides (banks) of the V6: their long-term fuel trims and "
        "their catalytic-converter temperatures, averaged per day. On a healthy engine "
        "both sides track each other."
    )
    title = "Left vs right bank"
    b1, b2 = daily.get("ltft_b1", {}), daily.get("ltft_b2", {})
    days = sorted(set(b1) & set(b2))
    if len(days) < 5:
        return _learning(
            "bank_balance",
            "engine",
            title,
            "Needs a few more days of fuel-trim data.",
            how=how,
            have=len(days),
            need=5,
            unit="days",
        )
    recent = days[-14:]
    diff = statistics.fmean(b1[d] - b2[d] for d in recent)
    c1, c2 = daily.get("catalyst_temp_b1", {}), daily.get("catalyst_temp_b2", {})
    cdays = sorted(set(c1) & set(c2))[-14:]
    cat_diff_f = statistics.fmean(c1[d] - c2[d] for d in cdays) * 9 / 5 if cdays else None
    status = "good"
    if abs(diff) > 8 or (cat_diff_f is not None and abs(cat_diff_f) > 150):
        status = "bad"
    elif abs(diff) > 4 or (cat_diff_f is not None and abs(cat_diff_f) > 90):
        status = "watch"
    side = "left" if diff > 0 else "right"
    if status == "good":
        headline = "Both sides of the engine are in step."
    else:
        headline = (
            f"The {side} side is getting {abs(diff):.1f}% more fuel correction than the other."
        )
    detail = f"Fuel trims differ by {abs(diff):.1f}%"
    if cat_diff_f is not None:
        detail += f" and the two catalytic converters by {abs(cat_diff_f):.0f} °F"
    detail += " (last 14 days)."
    return _card(
        "bank_balance",
        "engine",
        title,
        status,
        headline,
        detail=detail,
        value=_r(abs(diff), 1),
        value_unit="% apart",
        chart=_lines(
            [
                {"name": "Left (bank 1)", "points": [[_day_ms(d), round(b1[d], 1)] for d in days]},
                {"name": "Right (bank 2)", "points": [[_day_ms(d), round(b2[d], 1)] for d in days]},
            ],
            "%",
            band={"lo": -5, "hi": 5, "label": "Healthy ±5%"},
        ),
        how=how,
        tip=None
        if status == "good"
        else "A one-sided difference usually means an injector, an O2 sensor or an intake leak on that side.",
        sample=f"{len(days)} days",
    )


def volumetric_efficiency(s: Sample) -> float | None:
    """Measured airflow / what a 3.5 L engine would draw at this rpm,
    manifold pressure and intake temperature, in %."""
    if s.maf is None or s.rpm is None or s.map is None or s.iat is None or s.rpm <= 0:
        return None
    rho = s.map * 1000 / (287.05 * (s.iat + 273.15))  # kg/m^3
    theo_g_s = s.rpm / 120 * DISPLACEMENT_L / 1000 * rho * 1000
    return s.maf / theo_g_s * 100 if theo_g_s > 0 else None


def _baseline_trend(
    daily: dict[str, float], *, base_days: int = 30, recent_days: int = 14
) -> tuple[float, float, float] | None:
    """(baseline, recent, change %) from a {day: value} series: median of the
    first base_days of data vs the last recent_days."""
    days = sorted(daily)
    if len(days) < 10:
        return None
    t0 = datetime.strptime(days[0], "%Y-%m-%d")
    base = [daily[d] for d in days if (datetime.strptime(d, "%Y-%m-%d") - t0).days < base_days]
    recent = [daily[d] for d in days[-recent_days:]]
    b, r = statistics.median(base), statistics.median(recent)
    return b, r, _pct(r, b)


def airflow(samples: Sequence[Sample]) -> dict[str, Any]:
    how = (
        "At moderate load (2,000–3,500 rpm, firm throttle, all 6 cylinders) we compare "
        "the air the engine actually drew in with what it should draw at that speed and "
        "pressure. That ratio should stay constant; a slow fall means a clogging air "
        "filter, a dirty airflow sensor or carbon build-up."
    )
    title = "Engine breathing"
    per_day: dict[str, list[float]] = defaultdict(list)
    for s in samples:
        if s.rpm is None or not (2000 <= s.rpm <= 3500) or s.map is None or s.map < 70:
            continue
        if s.cyl is not None and s.cyl <= 3.5:
            continue
        ve = volumetric_efficiency(s)
        if ve is not None and 30 < ve < 130:
            per_day[_day(s.t)].append(ve)
    daily = {d: statistics.median(v) for d, v in per_day.items() if len(v) >= 10}
    trend = _baseline_trend(daily)
    if trend is None:
        return _learning(
            "airflow",
            "engine",
            title,
            "Needs more days with moderate-load driving.",
            how=how,
            have=len(daily),
            need=10,
            unit="days",
        )
    base, recent, change = trend
    status = "good" if change > -6 else "watch" if change > -10 else "bad"
    headline = (
        "Airflow is steady: the engine breathes as well as when tracking began."
        if abs(change) < 3
        else f"Airflow is {abs(change):.0f}% {'lower' if change < 0 else 'higher'} than when tracking began."
    )
    days = sorted(daily)
    return _card(
        "airflow",
        "engine",
        title,
        status,
        headline,
        value=_r(recent, 0),
        value_unit="% efficiency",
        detail=f"Breathing efficiency was {base:.0f}% at first and is {recent:.0f}% now.",
        chart=_lines(
            [{"name": "Efficiency", "points": [[_day_ms(d), round(daily[d], 1)] for d in days]}],
            "%",
            ref={"value": round(base, 1), "label": "Starting level"},
        ),
        how=how,
        tip=None if status == "good" else "Start with a new engine air filter; it's a cheap fix.",
        sample=f"{len(days)} days",
    )


def fuel_pressure(samples: Sequence[Sample]) -> dict[str, Any]:
    how = (
        "The direct-injection pump raises fuel to thousands of psi under load. We take "
        "the daily median rail pressure at 60% engine load or more and compare recent "
        "days with the first month. A weakening pump shows as a slow fall."
    )
    title = "Fuel pump"
    per_day: dict[str, list[float]] = defaultdict(list)
    for s in samples:
        if s.frp is not None and s.load is not None and s.load >= 60 and s.frp > 0:
            per_day[_day(s.t)].append(s.frp * PSI_PER_KPA)
    daily = {d: statistics.median(v) for d, v in per_day.items() if len(v) >= 5}
    trend = _baseline_trend(daily)
    if trend is None:
        return _learning(
            "fuel_pressure",
            "engine",
            title,
            "Needs more days with heavy-load driving.",
            how=how,
            have=len(daily),
            need=10,
            unit="days",
        )
    base, recent, change = trend
    status = "good" if change > -10 else "watch" if change > -20 else "bad"
    days = sorted(daily)
    return _card(
        "fuel_pressure",
        "engine",
        title,
        status,
        (
            f"Fuel pressure under load holds at {recent:,.0f} psi, steady since tracking began."
            if abs(change) < 5
            else f"Fuel pressure under load is {abs(change):.0f}% {'lower' if change < 0 else 'higher'} than at first ({recent:,.0f} psi)."
        ),
        value=_r(recent, 0),
        value_unit="psi",
        chart=_lines(
            [{"name": "Rail pressure", "points": [[_day_ms(d), round(daily[d])] for d in days]}],
            "psi",
            ref={"value": round(base), "label": "Starting level"},
        ),
        how=how,
        tip=None
        if status == "good"
        else "Low rail pressure causes hesitation under load; a shop can test the high-pressure pump.",
        sample=f"{len(days)} days",
    )


def _soc_label(v: float) -> str:
    if v >= 12.6:
        return "fully charged"
    if v >= 12.4:
        return "about 75% charged"
    if v >= 12.2:
        return "about half charged"
    return "low"


def battery(
    rest: Sequence[tuple[float, float]], samples: Sequence[Sample], running_v: Sequence[float]
) -> dict[str, Any]:
    """rest: (epoch s, volts) readings with the engine off (< 13 V)."""
    how = (
        "Resting voltage is read while the car is parked. 12.6 V is a full battery, "
        "12.4 V about 75%, 12.2 V about half. We take the median reading each day; a "
        "slow slide over months means the battery is ageing."
    )
    title = "Battery"
    per_vals: dict[str, list[float]] = defaultdict(list)
    for t, v in rest:
        if 10.5 < v < 13.0:
            per_vals[_day(t)].append(v)
    per_day = {d: statistics.median(v) for d, v in per_vals.items()}
    if len(per_day) < 5:
        return _learning(
            "battery",
            "engine",
            title,
            "Needs a few more days of parked voltage readings.",
            how=how,
            have=len(per_day),
            need=5,
            unit="days",
        )
    days = sorted(per_day)
    recent = statistics.median(per_day[d] for d in days[-14:])
    status = "good" if recent >= 12.4 else "watch" if recent >= 12.0 else "bad"
    slope = slope_per_day([(_day_ms(d) / 1000, per_day[d]) for d in days])
    detail = None
    if slope is not None and len(days) >= 30:
        per_month = slope * 30
        detail = f"Trend: {per_month:+.2f} V a month." + (
            " A steady fall like this usually means the battery is wearing out."
            if per_month < -0.05
            else ""
        )
    if running_v:
        rv = statistics.median(running_v)
        detail = (detail + " " if detail else "") + (
            f"While driving the system runs at {rv:.1f} V (Honda varies charging to save fuel; 12.5–14.8 V is normal)."
        )
    return _card(
        "battery",
        "engine",
        title,
        status,
        f"Your battery rests at {recent:.2f} V, {_soc_label(recent)}.",
        value=_r(recent, 2),
        value_unit="V",
        detail=detail,
        chart=_lines(
            [
                {
                    "name": "Resting voltage",
                    "points": [[_day_ms(d), round(per_day[d], 2)] for d in days],
                }
            ],
            "V",
            band={"lo": 12.4, "hi": 12.8, "label": "Healthy"},
        ),
        how=how,
        tip=None
        if status == "good"
        else "Short trips don't fully recharge it; a long drive or a trickle charger helps. Most batteries last 4–6 years.",
        sample=f"{len(days)} days",
    )


def warmup(trips: Sequence[Trip], by_trip: dict[int, list[Sample]]) -> dict[str, Any]:
    how = (
        "On every cold start (coolant under 104 °F) we time how long the engine takes "
        "to reach 176 °F, and plot that against the outside temperature. We also take "
        "the coolant temperature during steady driving once warm."
    )
    title = "Warm-up & cooling"
    pts: list[list[float]] = []
    mins: list[float] = []
    cruise: list[float] = []
    for i, t in enumerate(trips):
        ss = [s for s in by_trip.get(i, []) if s.ect is not None]
        if len(ss) < 10:
            continue
        if ss[0].ect < 40:  # type: ignore[operator]
            hot = next((s for s in ss if s.ect >= 80), None)  # type: ignore[operator]
            if hot is not None:
                m = (hot.t - ss[0].t) / 60
                if m < 60:
                    mins.append(m)
                    if t.temp_c is not None:
                        pts.append([round(c_to_f(t.temp_c)), round(m, 1)])
        cruise += [s.ect for s in ss if s.t - ss[0].t > 900 and s.spd is not None and s.spd > 60]  # type: ignore[misc]
    if len(mins) < 5 or len(cruise) < 100:
        return _learning(
            "warmup",
            "engine",
            title,
            "Needs a few more cold starts to time the warm-up.",
            how=how,
            have=len(mins),
            need=5,
            unit="cold starts",
        )
    med = statistics.median(mins)
    run_f = c_to_f(statistics.median(cruise))
    if run_f < 176:
        status, headline = (
            "watch",
            (
                f"Once warm the engine only runs at {run_f:.0f} °F. "
                "A thermostat stuck open is the usual cause."
            ),
        )
        tip = "A stuck-open thermostat costs MPG and heater output; it's an inexpensive part."
    elif run_f > 220:
        status, headline = "bad", f"The engine runs hot at {run_f:.0f} °F during steady driving."
        tip = "Check the coolant level and radiator fans soon."
    else:
        status, headline = (
            "good",
            (
                f"The engine warms up in about {med:.0f} minutes and runs at "
                f"{run_f:.0f} °F once warm. Normal."
            ),
        )
        tip = None
    return _card(
        "warmup",
        "engine",
        title,
        status,
        headline,
        value=_r(med, 0),
        value_unit="min warm-up",
        detail="Colder mornings take longer, which is why short winter trips cost so much fuel.",
        chart={"kind": "points", "x_unit": "°F outside", "y_unit": "min to warm", "points": pts}
        if len(pts) >= 3
        else None,
        how=how,
        tip=tip,
        sample=f"{len(mins)} cold starts",
    )


def misfires(daily_max: dict[int, dict[str, float]]) -> dict[str, Any]:
    """daily_max: cylinder (1-6) -> {day: max counter that day}."""
    how = (
        "The engine counts misfires for each cylinder. We add up each day's count. A "
        "few scattered misfires are normal; one cylinder piling them up points to its "
        "spark plug, ignition coil or injector."
    )
    title = "Misfires"
    days = {d for per in daily_max.values() for d in per}
    if len(days) < 3:
        return _learning(
            "misfires",
            "engine",
            title,
            "Collecting misfire counts. Needs a few more days of driving.",
            how=how,
            have=len(days),
            need=3,
            unit="days",
        )
    totals = {c: sum(daily_max.get(c, {}).values()) for c in range(1, 7)}
    total = sum(totals.values())
    worst = max(totals, key=lambda c: totals[c])
    others = statistics.fmean(v for c, v in totals.items() if c != worst)
    if total == 0:
        status, headline = "good", "No misfires recorded on any cylinder."
    elif totals[worst] >= 20 and totals[worst] > 4 * max(others, 1):
        status, headline = (
            "watch",
            f"Cylinder {worst} has {totals[worst]:.0f} misfires, far more than the others.",
        )
    else:
        status, headline = (
            "good",
            f"{total:.0f} misfires in total, spread across cylinders. Normal.",
        )
    return _card(
        "misfires",
        "engine",
        title,
        status,
        headline,
        value=total,
        value_unit="misfires",
        chart=_bars(
            [_bar(f"Cyl {c}", totals[c], 0, status == "watch" and c == worst) for c in range(1, 7)],
            "count",
        ),
        how=how,
        tip="Swap that cylinder's coil with a neighbour's: if the misfires follow, it's the coil."
        if status == "watch"
        else None,
        sample=f"{len(days)} days",
    )


_ATF_BINS = [
    ("Under 140°F", -999, 140),
    ("140–175°F", 140, 175),
    ("175–200°F", 175, 200),
    ("200–220°F", 200, 220),
    ("Over 220°F", 220, 999),
]


def transmission(atf_c: Sequence[float]) -> dict[str, Any]:
    how = (
        "We look at every transmission-fluid temperature reading and how the time "
        "splits across temperature ranges. Fluid life halves for roughly every 20 °F "
        "above 175 °F."
    )
    title = "Transmission fluid"
    if len(atf_c) < 100:
        return _learning(
            "transmission",
            "engine",
            title,
            "Needs more transmission temperature readings.",
            how=how,
            have=len(atf_c),
            need=100,
            unit="readings",
        )
    f = [c_to_f(c) for c in atf_c]
    peak = max(f)
    hot = sum(1 for v in f if v >= 220) / len(f) * 100
    bars = [
        _bar(lbl, sum(1 for v in f if lo <= v < hi) / len(f) * 100, 0, lo >= 200)
        for lbl, lo, hi in _ATF_BINS
    ]
    status = "bad" if hot > 5 else "watch" if peak >= 230 else "good"
    headline = (
        f"Transmission fluid stays cool: it peaked at {peak:.0f} °F."
        if status == "good"
        else f"Transmission fluid reached {peak:.0f} °F and spent {hot:.0f}% of the time above 220 °F."
    )
    return _card(
        "transmission",
        "engine",
        title,
        status,
        headline,
        value=_r(peak, 0),
        value_unit="°F peak",
        chart=_bars(bars, "% of time"),
        how=how,
        tip=None
        if status == "good"
        else "Heavy towing or long climbs heat the fluid; change it sooner if this keeps happening.",
        sample=f"{len(f)} readings",
    )


# ── tires & brakes ─────────────────────────────────────────────────────

TIRE_REF_C = 20.0  # 68 °F, the temperature pressures are corrected to


def compensated_psi(pressure_kpa: float, tire_c: float) -> float:
    """Gauge pressure the tire would read at 68 °F (gas law on absolute
    pressure), in psi. Separates a real leak from cold weather."""
    abs_kpa = (pressure_kpa + ATM_KPA) * (TIRE_REF_C + 273.15) / (tire_c + 273.15)
    return (abs_kpa - ATM_KPA) * PSI_PER_KPA


# A rise of this much (temperature-corrected) between consecutive hourly
# medians is air being added, not weather: the correction already removes
# the warm-up from driving.
TIRE_AIR_ADDED_PSI = 2.0
_CORNER_NAMES = {"fl": "Front left", "fr": "Front right", "rl": "Rear left", "rr": "Rear right"}
_CORNER_SHORT = {"fl": "FL", "fr": "FR", "rl": "RL", "rr": "RR"}


def _hourly_compensated(rows: Sequence[tuple[float, float, float]]) -> list[tuple[float, float]]:
    per: dict[int, list[float]] = defaultdict(list)
    for t, p, tc in rows:
        if p is not None and tc is not None and 100 < p < 450 and -40 < tc < 120:
            per[int(t // 3600)].append(compensated_psi(p, tc))
    return [(h * 3600.0, statistics.median(v)) for h, v in sorted(per.items())]


def _days_txt(days: float) -> str:
    n = max(1, round(days))
    return f"{n} day{'s' if n != 1 else ''} of readings"


def leak_segments(
    hourly: Sequence[tuple[float, float]],
) -> tuple[list[dict[str, Any]], list[float]]:
    """Split a corner's corrected hourly series at air-ups. Returns
    (segments, air_added_times); each segment with >= 12 h span and >= 3
    points carries ``psi_per_day`` (least squares)."""
    segs: list[list[tuple[float, float]]] = [[]]
    airups: list[float] = []
    for i, (t, v) in enumerate(hourly):
        if i and v - hourly[i - 1][1] >= TIRE_AIR_ADDED_PSI:
            segs.append([])
            airups.append(t)
        segs[-1].append((t, v))
    out = []
    for seg in segs:
        if len(seg) >= 3 and seg[-1][0] - seg[0][0] >= 12 * 3600:
            out.append({"start": seg[0][0], "end": seg[-1][0], "psi_per_day": slope_per_day(seg)})
    return out, airups


def _rate_txt(per_day: float) -> str:
    per_day = abs(per_day)
    return f"{per_day:.1f} psi a day" if per_day >= 1 else f"{per_day * 7:.1f} psi a week"


def tire_leak(
    readings: dict[str, Sequence[tuple[float, float, float]]],
    placard_kpa: float | None,
    leak_psi_week: float,
) -> dict[str, Any]:
    """readings: corner -> [(epoch s, pressure kPa, tire temp C)]."""
    how = (
        "Tire pressure drops about 1 psi for every 10 °F colder, so a raw reading can't "
        "tell a leak from a cold morning. We correct every reading to what it would be at "
        "68 °F using the tire's own temperature sensor, then measure how fast each tire "
        "loses pressure between the times air was added."
    )
    title = "Slow-leak check"
    hourly = {c: _hourly_compensated(rows) for c, rows in readings.items()}
    hourly = {c: h for c, h in hourly.items() if h}
    if not hourly:
        return _learning(
            "tire_leak",
            "tires",
            title,
            "Waiting for tire pressure readings.",
            how=how,
            have=0,
            need=7,
            unit="days",
        )
    cutoff = max(h[-1][0] for h in hourly.values()) - 30 * 86400
    series = [
        {
            "name": _CORNER_SHORT.get(c, c),
            "points": [[int(t * 1000), round(v, 1)] for t, v in h if t >= cutoff],
        }
        for c, h in sorted(
            hourly.items(),
            key=lambda kv: list(_CORNER_NAMES).index(kv[0]) if kv[0] in _CORNER_NAMES else 9,
        )
    ]
    ref = (
        {"value": round(placard_kpa * PSI_PER_KPA, 1), "label": "Door placard"}
        if placard_kpa
        else None
    )
    chart = _lines(series, "psi at 68°F", ref=ref)
    now_txt = ", ".join(f"{_CORNER_SHORT.get(c, c)} {h[-1][1]:.1f}" for c, h in hourly.items())
    detail_now = f"Corrected to 68 °F, latest: {now_txt} psi."
    # Worst leak per corner: its most recent measurable segment, plus whether
    # air has been added since.
    leaks = []
    any_seg = False
    for c, h in hourly.items():
        segs, airups = leak_segments([p for p in h if p[0] >= cutoff])
        if not segs:
            continue
        any_seg = True
        last = segs[-1]
        rate = last["psi_per_day"]
        if rate is not None and rate * 7 <= leak_psi_week:
            topped = [a for a in airups if a > last["end"]]
            leaks.append((rate, c, topped[-1] if topped else None))
    span_days = (
        max(h[-1][0] for h in hourly.values()) - min(h[0][0] for h in hourly.values())
    ) / 86400
    if leaks:
        rate, c, topped = min(leaks)
        status = "bad" if rate <= -1.0 or rate * 7 <= 2 * leak_psi_week else "watch"
        if topped is not None:
            when = datetime.fromtimestamp(topped, UTC).strftime("%b %-d")
            headline = (
                f"{_CORNER_NAMES[c]} was losing {_rate_txt(rate)} (temperature-corrected) "
                f"before air was added on {when}. Watch it for a repeat."
            )
        else:
            headline = (
                f"{_CORNER_NAMES[c]} is losing {_rate_txt(rate)} even after correcting "
                "for temperature: likely a slow leak."
            )
        return _card(
            "tire_leak",
            "tires",
            title,
            status,
            headline,
            value=_r(rate, 1) if rate <= -1 else _r(rate * 7, 1),
            value_unit="psi/day" if rate <= -1 else "psi/week",
            detail=detail_now,
            chart=chart,
            how=how,
            tip="Check that tire for a nail or a leaking valve stem; a shop can usually plug it.",
            sample=_days_txt(span_days),
        )
    if not any_seg or span_days < 7:
        return _learning(
            "tire_leak",
            "tires",
            title,
            "No leak so far. Needs a week of readings to rule out a slow one.",
            how=how,
            have=span_days,
            need=7,
            unit="days",
            detail=detail_now,
            chart=chart,
        )
    return _card(
        "tire_leak",
        "tires",
        title,
        "good",
        "No slow leaks: corrected for temperature, all four tires are holding pressure.",
        detail=detail_now,
        chart=chart,
        how=how,
        sample=_days_txt(span_days),
    )


def speedometer(monthly: dict[str, tuple[float, int]]) -> dict[str, Any]:
    """monthly: YYYY-MM -> (OBD speed / GPS speed ratio, paired samples)."""
    how = (
        "We compare the speed the car reports (from wheel rotation) with GPS speed at "
        "steady speeds over 30 mph, month by month. As tread wears the tires get "
        "smaller, so the car's reading creeps up against GPS: new-to-bald is about "
        "2–3%. The dash needle adds its own built-in margin on top of this reading."
    )
    title = "Wheel speed & tread"
    good = {m: r for m, (r, n) in monthly.items() if n >= 200}
    if len(good) < 1:
        return _learning(
            "speedometer",
            "tires",
            title,
            "Needs more highway driving with GPS.",
            how=how,
            have=0,
            need=1,
            unit="months",
        )
    months = sorted(good)
    now = good[months[-1]]
    err = (now - 1) * 100
    headline = (
        f"The car's wheel-speed reading is {abs(err):.1f}% {'above' if err > 0 else 'below'} GPS"
    )
    detail = None
    if len(months) >= 3:
        drift = (now - good[months[0]]) * 100
        if abs(drift) < 0.3:
            headline += f", unchanged since {_month_label(months[0])}."
            detail = "No measurable tread wear over this period."
        else:
            headline += f", {abs(drift):.1f} points {'higher' if drift > 0 else 'lower'} than in {_month_label(months[0])}."
            detail = (
                "A rising gap is consistent with tread wearing down."
                if drift > 0
                else "A falling gap usually means new or larger tires."
            )
    else:
        headline += "."
    return _card(
        "speedometer",
        "tires",
        title,
        "info",
        headline,
        value=_r(err, 1),
        value_unit="% vs GPS",
        detail=detail,
        chart=_lines(
            [
                {
                    "name": "Car vs GPS",
                    "points": [[_day_ms(m + "-15"), round((good[m] - 1) * 100, 2)] for m in months],
                }
            ],
            "%",
            ref={"value": 0, "label": "GPS"},
        ),
        how=how,
        tip="Logged miles come from the same wheel sensor, so they share this offset.",
        sample=f"{len(months)} months",
    )


def braking(
    trips: Sequence[Trip],
    by_trip: dict[int, list[Sample]],
    last_brake_service: tuple[str, float] | None,
    odo_mi: float | None,
) -> dict[str, Any]:
    how = (
        "From the speed stream we count hard stops (slowing faster than about a quarter "
        "of a g) per 100 miles, month by month. Hard braking and stop-and-go driving "
        "are what wear pads."
    )
    title = "Braking"
    months: dict[str, list[float]] = defaultdict(lambda: [0.0, 0.0])
    total_b = 0
    total_mi = 0.0
    for i, t in enumerate(trips):
        ss = by_trip.get(i)
        if not ss or len(ss) < 30 or t.miles < 1:
            continue
        _, b = hard_events(ss)
        months[_month(t.start)][0] += b
        months[_month(t.start)][1] += t.miles
        total_b += b
        total_mi += t.miles
    if total_mi < 50:
        return _learning(
            "braking",
            "tires",
            title,
            "Needs more miles with speed data.",
            how=how,
            have=total_mi,
            need=50,
            unit="miles",
        )
    rate = total_b / total_mi * 100
    keys = [k for k in sorted(months) if months[k][1] >= 20][-12:]
    detail = "No brake job on record."
    if last_brake_service is not None:
        date_s, at_mi = last_brake_service
        detail = f"Last brake service: {date_s}"
        if odo_mi is not None and at_mi:
            detail += f", {odo_mi - at_mi:,.0f} mi ago"
        detail += "."
    return _card(
        "braking",
        "tires",
        title,
        "info",
        f"You brake hard about {rate:.1f} times per 100 miles.",
        value=_r(rate, 1),
        value_unit="per 100 mi",
        detail=detail,
        chart=_bars(
            [_bar(_month_label(k), months[k][0] / months[k][1] * 100) for k in keys], "per 100 mi"
        ),
        how=how,
        tip='Log brake jobs as a Service expense with "brake" in the title to track miles on the pads.',
        sample=f"{total_mi:,.0f} miles",
    )


# ── cost ───────────────────────────────────────────────────────────────


@dataclass(slots=True)
class Fill:
    t: float
    gallons: float
    price: float  # $/gal
    market: float | None  # US average $/gal that week


def price_vs_market(fills: Sequence[Fill]) -> dict[str, Any]:
    how = (
        "Each fill-up's price per gallon is compared with the US average for regular "
        "that week (EIA). The total multiplies each difference by the gallons you bought."
    )
    title = "Price vs US average"
    rows = [f for f in fills if f.market]
    if len(rows) < 3:
        return _learning(
            "price_vs_market",
            "cost",
            title,
            "Needs a few fill-ups with prices.",
            how=how,
            have=len(rows),
            need=3,
            unit="fill-ups",
        )
    diff = statistics.fmean(f.price - f.market for f in rows)  # type: ignore[operator]
    total = sum((f.price - f.market) * f.gallons for f in rows)  # type: ignore[operator]
    cents = abs(diff) * 100
    if abs(diff) < 0.02:
        headline = "You pay about the US average for gas."
    elif diff < 0:
        headline = (
            f"You paid {cents:.0f}¢/gal less than the US average, about ${abs(total):,.0f} saved."
        )
    else:
        headline = f"You paid {cents:.0f}¢/gal more than the US average, about ${total:,.0f} extra."
    return _card(
        "price_vs_market",
        "cost",
        title,
        "good" if diff <= 0 else "info",
        headline,
        value=_r(diff * 100, 0),
        value_unit="¢/gal vs average",
        detail="Local prices differ from the national average, so read this as a trend rather than a verdict.",
        chart=_lines(
            [
                {
                    "name": "You paid",
                    "points": [[int(f.t * 1000), round(f.price, 2)] for f in rows],
                },
                {
                    "name": "US average",
                    "points": [[int(f.t * 1000), round(f.market, 2)] for f in rows],
                },
            ],  # type: ignore[arg-type]
            "$/gal",
        ),
        how=how,
        sample=f"{len(rows)} fill-ups",
    )


_WEEKDAYS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]
_WEEKDAY_NAMES = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]


def fill_day(fills: Sequence[Fill], tz: Any) -> dict[str, Any]:
    how = (
        "Each fill-up's price is compared with that week's US average, which removes "
        "the ups and downs of the market. What's left is grouped by weekday: below zero "
        "means you paid less than usual on that day."
    )
    title = "Best day to fill up"
    rows = [f for f in fills if f.market]
    if len(rows) < 15:
        return _learning(
            "fill_day",
            "cost",
            title,
            "Needs more fill-ups to compare weekdays.",
            how=how,
            have=len(rows),
            need=15,
            unit="fill-ups",
        )
    overall = statistics.fmean(f.price - f.market for f in rows)  # type: ignore[operator]
    by: dict[int, list[float]] = defaultdict(list)
    for f in rows:
        by[datetime.fromtimestamp(f.t, tz).weekday()].append(f.price - f.market - overall)  # type: ignore[operator]
    days = [(d, statistics.fmean(v) * 100, len(v)) for d, v in sorted(by.items()) if len(v) >= 2]
    if len(days) < 3:
        return _learning(
            "fill_day",
            "cost",
            title,
            "Your fill-ups cluster on too few days to compare.",
            how=how,
            have=len(days),
            need=3,
            unit="weekdays",
        )
    best = min(days, key=lambda r: r[1])
    worst = max(days, key=lambda r: r[1])
    spread = worst[1] - best[1]
    headline = (
        f"{_WEEKDAY_NAMES[best[0]]} has been your cheapest day, {abs(best[1]):.0f}¢/gal below your usual."
        if spread >= 3
        else "No weekday is reliably cheaper for you."
    )
    return _card(
        "fill_day",
        "cost",
        title,
        "info",
        headline,
        value=_r(spread, 0),
        value_unit="¢ spread",
        detail=f"{_WEEKDAY_NAMES[worst[0]]} has been the priciest ({worst[1]:+.0f}¢)."
        if spread >= 3
        else None,
        chart=_bars([_bar(_WEEKDAYS[d], c, n, d == best[0]) for d, c, n in days], "¢ vs usual"),
        how=how,
        sample=f"{len(rows)} fill-ups",
    )


def cost_per_mile(trips: Sequence[Trip], price_per_gal: float | None) -> dict[str, Any]:
    how = (
        "Each trip type's fuel economy (total miles / total gallons) is converted to "
        "fuel cost per mile at your latest fill-up price. City = average under 35 mph, "
        "Highway = average 55 mph and up."
    )
    title = "Fuel cost per mile"
    if not price_per_gal:
        return _learning(
            "cost_per_mile",
            "cost",
            title,
            "Needs a fill-up with a price.",
            how=how,
            have=0,
            need=1,
            unit="fill-ups",
        )
    usable = [t for t in trips if t.mpg is not None and t.avg_kph is not None]
    groups = [
        ("Short (<10 min)", [t for t in usable if t.duration_s < 600]),
        ("City", [t for t in usable if t.duration_s >= 600 and t.avg_kph < 56]),  # type: ignore[operator]
        ("Suburban", [t for t in usable if t.duration_s >= 600 and 56 <= t.avg_kph < 88]),  # type: ignore[operator]
        ("Highway", [t for t in usable if t.duration_s >= 600 and t.avg_kph >= 88]),
    ]  # type: ignore[operator]
    rows = []
    for label, grp in groups:
        m, n = _mpg_of(grp)
        if m and n >= 3:
            rows.append((label, price_per_gal / m * 100, n))
    if len(rows) < 2:
        return _learning(
            "cost_per_mile",
            "cost",
            title,
            "Needs more trips of different kinds.",
            how=how,
            have=len(rows),
            need=2,
            unit="trip types",
        )
    cheap = min(rows, key=lambda r: r[1])
    dear = max(rows, key=lambda r: r[1])
    return _card(
        "cost_per_mile",
        "cost",
        title,
        "info",
        f"A {dear[0].split(' ')[0].lower()} mile costs {dear[1]:.0f}¢ in fuel; a {cheap[0].lower()} mile costs {cheap[1]:.0f}¢.",
        value=_r(dear[1] - cheap[1], 0),
        value_unit="¢/mi gap",
        detail=f"At ${price_per_gal:.2f}/gal. 1,000 {dear[0].split(' ')[0].lower()} miles cost ${dear[1] * 10:,.0f}; "
        f"1,000 {cheap[0].lower()} miles ${cheap[1] * 10:,.0f}.",
        chart=_bars([_bar(lbl, c, n, lbl == cheap[0]) for lbl, c, n in rows], "¢/mi"),
        how=how,
        sample=f"Based on {len(usable)} trips",
    )


def order_cards(cards: list[dict[str, Any]]) -> dict[str, Any]:
    """Wrap cards with groups and the 'needs attention' list (bad, then watch)."""
    attention = sorted(
        (c for c in cards if c["status"] in ("bad", "watch")),
        key=lambda c: STATUS_RANK[c["status"]],
    )
    return {"groups": GROUPS, "attention": [c["id"] for c in attention], "insights": cards}
