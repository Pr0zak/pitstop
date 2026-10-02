/**
 * Pure maths behind three insight charts (mirrored on Android):
 *
 *   A. MPG over time — monthly MPG + 3-month rolling median + a
 *      fillup-count-weighted average over the chosen span.
 *   B. What you paid vs the market — each fillup's $/gal against the EIA
 *      US weekly average for the week containing / preceding it.
 *   D. Fuel spend, this year vs last — running total by day of year.
 *
 * Everything here is unit-agnostic: callers pass canonical values (US mpg,
 * $/US gal, US gal, dollars) and convert for display afterwards. No DOM,
 * no stores — so it unit-tests cleanly.
 */

const DAY_MS = 86_400_000;

export function median(values: readonly number[]): number {
  if (values.length === 0) return NaN;
  const s = [...values].sort((a, b) => a - b);
  const m = s.length >> 1;
  return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2;
}

/** Trailing rolling median over `window` points (fewer at the start). */
export function rollingMedian(values: readonly number[], window = 3): number[] {
  return values.map((_, i) => median(values.slice(Math.max(0, i - window + 1), i + 1)));
}

/**
 * Axis ticks at 1/2/5 × 10^n steps inside [lo, hi], at most ~`n` intervals.
 * Never 2.5 — a "nice" step reads as a round number on the axis.
 */
export function niceTicks(lo: number, hi: number, n = 4): number[] {
  const span = hi - lo || 1;
  const raw = span / n;
  const mag = 10 ** Math.floor(Math.log10(raw));
  const step = [1, 2, 5, 10].map((m) => m * mag).find((s) => span / s <= n) ?? 10 * mag;
  const out: number[] = [];
  for (let v = Math.ceil(lo / step) * step; v <= hi + 1e-9; v += step) out.push(+v.toFixed(6));
  return out;
}

/** [min, max] padded by `f` of the span on both sides. */
export function padRange(values: readonly number[], f = 0.08): [number, number] {
  const lo = Math.min(...values);
  const hi = Math.max(...values);
  const s = hi - lo || 1;
  return [lo - s * f, hi + s * f];
}

/** Local-time "YYYY-MM-DD" of an ISO timestamp (or a bare date string). */
export function localDay(iso: string): string {
  if (/^\d{4}-\d{2}-\d{2}$/.test(iso)) return iso;
  const d = new Date(iso);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

/** Local-noon epoch ms of a "YYYY-MM-DD" day — DST-safe for day maths. */
export function dayMs(day: string): number {
  const [y, m, d] = day.split("-").map(Number);
  return new Date(y, m - 1, d, 12).getTime();
}

// ─── A. MPG over time ─────────────────────────────────────────────────────

export interface MonthlyMpgIn {
  period: string; // "YYYY-MM"
  mpg: number | null;
  fillup_count?: number;
}
export interface MonthlyMpgPoint {
  period: string;
  /** Mid-month local noon, epoch ms. */
  t: number;
  mpg: number;
  fills: number;
  /** 3-month trailing median ending at this month. */
  median: number;
}
export interface MpgSpan {
  points: MonthlyMpgPoint[];
  /** Fillup-count-weighted mean of the monthly MPG over the span. */
  average: number | null;
}

/** `spanMonths` = null for all history. Null/≤0 MPG months are dropped. */
export function mpgOverSpan(raw: readonly MonthlyMpgIn[], spanMonths: number | null): MpgSpan {
  const all = raw
    .filter((p) => p.mpg != null && p.mpg > 0)
    .map((p) => {
      const [y, m] = p.period.split("-").map(Number);
      return {
        period: p.period,
        t: new Date(y, m - 1, 15, 12).getTime(),
        mpg: p.mpg as number,
        fills: p.fillup_count ?? 1,
      };
    })
    .sort((a, b) => a.t - b.t);
  if (all.length === 0) return { points: [], average: null };
  const lastT = all[all.length - 1].t;
  // Exactly `spanMonths` calendar months ending with the newest one (the
  // phone uses the same rule), so "12 mo" is 12 points, not 13.
  const lastD = new Date(lastT);
  const cut = spanMonths == null
    ? -Infinity
    : +new Date(lastD.getFullYear(), lastD.getMonth() - (spanMonths - 1), 1);
  const inSpan = all.filter((p) => p.t >= cut);
  const med = rollingMedian(inSpan.map((p) => p.mpg), 3);
  const points = inSpan.map((p, i) => ({ ...p, median: med[i] }));
  const w = points.reduce((s, p) => s + p.fills, 0);
  const average = w > 0 ? points.reduce((s, p) => s + p.mpg * p.fills, 0) / w : null;
  return { points, average };
}

// ─── B. Paid vs market ────────────────────────────────────────────────────

export interface EiaIn {
  week_of: string; // "YYYY-MM-DD"
  price: number; // $/US gal
}
export interface MarketFillIn {
  day: string; // local "YYYY-MM-DD"
  /** $/US gal. */
  ppg: number;
  /** US gal, null when unknown. */
  gallons: number | null;
}
export interface MarketFill extends MarketFillIn {
  t: number;
  /** EIA price for the week containing / preceding the fillup. */
  eia: number;
  /** ppg − eia, $/gal. Negative = paid below the market. */
  diff: number;
}
export interface MarketComparison {
  /** EIA weekly series, oldest first, t = local-noon ms. */
  eia: { t: number; day: string; price: number }[];
  fills: MarketFill[];
  /** Mean of `diff` across fills, $/gal. */
  meanDiff: number | null;
  gallons: number;
  /** |meanDiff| × total gallons — saved when meanDiff < 0, extra cost otherwise. */
  dollars: number | null;
}

/**
 * Price lookup over an EIA weekly series (any order): the week starting on
 * or before `day`, or null for a day more than 3 days before the first
 * week (outside the series). A day in the first week's 3-day lead-in maps
 * to the first week.
 */
export function eiaLookup(eiaRaw: readonly EiaIn[]): (day: string) => number | null {
  const eia = [...eiaRaw]
    .filter((p) => Number.isFinite(p.price))
    .map((p) => ({ t: dayMs(p.week_of), price: p.price }))
    .sort((a, b) => a.t - b.t);
  if (eia.length === 0) return () => null;
  const t0 = eia[0].t - 3 * DAY_MS;
  return (day: string) => {
    const t = dayMs(day);
    if (t < t0) return null;
    let best = eia[0];
    for (const p of eia) {
      if (p.t > t) break;
      best = p;
    }
    return best.price;
  };
}

/**
 * Fillups are clipped to the EIA window (3 days before the first week).
 * Each one is matched to the latest EIA week starting on or before its day.
 */
export function compareToMarket(
  eiaRaw: readonly EiaIn[],
  fillsRaw: readonly MarketFillIn[],
): MarketComparison {
  const eia = [...eiaRaw]
    .filter((p) => Number.isFinite(p.price))
    .map((p) => ({ t: dayMs(p.week_of), day: p.week_of, price: p.price }))
    .sort((a, b) => a.t - b.t);
  if (eia.length === 0) return { eia, fills: [], meanDiff: null, gallons: 0, dollars: null };
  const lookup = eiaLookup(eiaRaw);
  const fills = fillsRaw
    .filter((f) => Number.isFinite(f.ppg) && f.ppg > 0)
    .map((f) => ({ ...f, t: dayMs(f.day), eia: lookup(f.day) }))
    .filter((f): f is typeof f & { eia: number } => f.eia != null)
    .sort((a, b) => a.t - b.t)
    .map((f) => ({ ...f, diff: f.ppg - f.eia }));
  if (fills.length === 0) return { eia, fills, meanDiff: null, gallons: 0, dollars: null };
  const meanDiff = fills.reduce((s, f) => s + f.diff, 0) / fills.length;
  const gallons = fills.reduce((s, f) => s + (f.gallons ?? 0), 0);
  return { eia, fills, meanDiff, gallons, dollars: Math.abs(meanDiff) * gallons };
}

// ─── D. Spend, this year vs last ─────────────────────────────────────────

export interface SpendFillIn {
  day: string; // local "YYYY-MM-DD"
  cost: number | null;
}
export interface SpendStep {
  /** Days since Jan 1 (0 = Jan 1). */
  doy: number;
  /** Running total after this fillup. */
  total: number;
  day: string | null;
}

/** Day-of-year offset (0-based) of a local "YYYY-MM-DD". */
export function dayOfYear(day: string): number {
  const y = Number(day.slice(0, 4));
  return Math.round((dayMs(day) - new Date(y, 0, 1, 12).getTime()) / DAY_MS);
}

/** Running total for one calendar year, starting with a (0, 0) anchor. */
export function cumulativeSpend(fills: readonly SpendFillIn[], year: number): SpendStep[] {
  const prefix = `${year}-`;
  const rows = fills
    .filter((f) => f.day.startsWith(prefix) && f.cost != null && Number.isFinite(f.cost))
    .sort((a, b) => (a.day < b.day ? -1 : a.day > b.day ? 1 : 0));
  const out: SpendStep[] = [{ doy: 0, total: 0, day: null }];
  let s = 0;
  for (const f of rows) {
    s += f.cost as number;
    out.push({ doy: dayOfYear(f.day), total: s, day: f.day });
  }
  return out;
}

/** Running total of `steps` as of day-of-year `doy` (last step at or before). */
export function totalAt(steps: readonly SpendStep[], doy: number): number {
  let v = 0;
  for (const p of steps) if (p.doy <= doy) v = p.total;
  return v;
}

export interface SpendComparison {
  year: number;
  current: SpendStep[];
  previous: SpendStep[];
  /** This year's total so far. */
  total: number;
  /** Last year's total by the day of this year's latest fillup. */
  previousAtSameDate: number;
  /** total − previousAtSameDate. */
  delta: number;
}

export function compareSpend(fills: readonly SpendFillIn[], year: number): SpendComparison {
  const current = cumulativeSpend(fills, year);
  const previous = cumulativeSpend(fills, year - 1);
  const last = current[current.length - 1];
  const previousAtSameDate = totalAt(previous, last.doy);
  return {
    year,
    current,
    previous,
    total: last.total,
    previousAtSameDate,
    delta: last.total - previousAtSameDate,
  };
}

/** Day-of-year offsets of Jan/Apr/Jul/Oct 1 in `year`. */
export function quarterTicks(year: number): { doy: number; label: string }[] {
  const MON = ["Jan", "Apr", "Jul", "Oct"];
  return [0, 3, 6, 9].map((m, i) => ({
    doy: Math.round((new Date(year, m, 1, 12).getTime() - new Date(year, 0, 1, 12).getTime()) / DAY_MS),
    label: MON[i],
  }));
}

// ─── Fillups grouped by calendar month ────────────────────────────────────

export interface MonthGroupIn {
  day: string; // local "YYYY-MM-DD"
  cost: number | null;
  /** In the vehicle's source volume unit. */
  volume: number | null;
}
export interface MonthGroup<T> {
  key: string; // "YYYY-MM"
  items: T[];
  fills: number;
  volume: number;
  total: number;
  /** total ÷ the biggest month's total in this list, 0..1. */
  barFrac: number;
}

/** Group rows by month, keeping the incoming order (rows and months). */
export function groupByMonth<T>(rows: readonly T[], pick: (r: T) => MonthGroupIn): MonthGroup<T>[] {
  const by = new Map<string, MonthGroup<T>>();
  for (const r of rows) {
    const p = pick(r);
    const key = p.day.slice(0, 7);
    let g = by.get(key);
    if (!g) {
      g = { key, items: [], fills: 0, volume: 0, total: 0, barFrac: 0 };
      by.set(key, g);
    }
    g.items.push(r);
    g.fills += 1;
    g.volume += p.volume ?? 0;
    g.total += p.cost ?? 0;
  }
  const groups = [...by.values()];
  const max = Math.max(0, ...groups.map((g) => g.total));
  for (const g of groups) g.barFrac = max > 0 ? g.total / max : 0;
  return groups;
}

// ─── Battery, resting voltage ─────────────────────────────────────────────

export type BatteryBand = "good" | "fair" | "low";
/** Good ≥ 12.4 V, Fair 12.0–12.4 V, Low < 12.0 V. */
export function batteryBand(v: number): BatteryBand {
  return v >= 12.4 ? "good" : v >= 12.0 ? "fair" : "low";
}
export interface BatterySummary {
  latest: number;
  band: BatteryBand;
  days: number;
  /** The band most days fall in (ties go to the worse band). */
  mostly: BatteryBand;
}
export function batterySummary(daily: readonly number[]): BatterySummary | null {
  const vs = daily.filter((v) => Number.isFinite(v));
  if (vs.length === 0) return null;
  const n: Record<BatteryBand, number> = { good: 0, fair: 0, low: 0 };
  for (const v of vs) n[batteryBand(v)] += 1;
  const order: BatteryBand[] = ["low", "fair", "good"];
  const mostly = order.reduce((a, b) => (n[b] > n[a] ? b : a));
  const latest = vs[vs.length - 1];
  return { latest, band: batteryBand(latest), days: vs.length, mostly };
}
