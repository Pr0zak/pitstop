/**
 * Summer- vs winter-blend gasoline seasons, for reading MPG against the fuel.
 *
 * Summer-grade gasoline has a lower Reid vapour pressure (RVP); winter blend
 * is more volatile and carries a little less energy per gallon, which is part
 * of the usual cold-month MPG dip. The dates are the U.S. EPA federal rule:
 * 40 CFR 80.27 sets the volatility control period at June 1 – September 15
 * for RETAIL stations (terminals and refiners switch from May 1). Stations
 * sell down winter stock and start carrying transition / summer fuel from
 * roughly April 15 – May 1, and winter blend may be sold again from Sept 16.
 *
 * These are federal defaults only. Reformulated-gasoline (RFG) areas, low-RVP
 * state programs (e.g. California CARB, parts of the Midwest and South) and
 * EPA waivers can shift both the dates and the blends. Edit FUEL_SEASON below
 * to change the bands everywhere; nothing else hard-codes a date.
 *
 * Phone parity: the Android MPG chart has no equivalent yet. A port needs this
 * same table + classifyMonth/seasonalMpg (pure Kotlin) and a band draw step.
 */

/** Month is 1-based (6 = June). Each date is the first day of its season. */
export interface MonthDay {
  month: number;
  day: number;
}

export const FUEL_SEASON = {
  /** Stations begin the spring changeover (winter stock sold down). */
  transitionStart: { month: 4, day: 15 } as MonthDay,
  /** EPA retail summer volatility period begins (40 CFR 80.27). */
  summerStart: { month: 6, day: 1 } as MonthDay,
  /** First day winter blend may be sold again (period ends Sept 15). */
  winterStart: { month: 9, day: 16 } as MonthDay,
};

export type FuelSeason = "winter" | "transition" | "summer";

export interface SeasonBand {
  kind: "transition" | "summer";
  /** Local-midnight epoch ms, inclusive. */
  start: number;
  /** Local-midnight epoch ms, exclusive (the first day of the next season). */
  end: number;
}

function at(year: number, md: MonthDay): number {
  return new Date(year, md.month - 1, md.day).getTime();
}

/** Season of a calendar day (local time). */
export function fuelSeasonOn(d: Date): FuelSeason {
  const y = d.getFullYear();
  const t = new Date(y, d.getMonth(), d.getDate()).getTime();
  if (t >= at(y, FUEL_SEASON.summerStart) && t < at(y, FUEL_SEASON.winterStart)) return "summer";
  if (t >= at(y, FUEL_SEASON.transitionStart) && t < at(y, FUEL_SEASON.summerStart)) return "transition";
  return "winter";
}

/** Transition + summer bands for every year that overlaps [t0, t1] (epoch ms).
 *  Bands are not clipped; the chart clips to its plot area. */
export function fuelSeasonBands(t0: number, t1: number): SeasonBand[] {
  if (!(t1 >= t0)) return [];
  const out: SeasonBand[] = [];
  for (let y = new Date(t0).getFullYear(); y <= new Date(t1).getFullYear(); y++) {
    const tr = at(y, FUEL_SEASON.transitionStart);
    const su = at(y, FUEL_SEASON.summerStart);
    const wi = at(y, FUEL_SEASON.winterStart);
    if (su > t0 && tr < t1) out.push({ kind: "transition", start: tr, end: su });
    if (wi > t0 && su < t1) out.push({ kind: "summer", start: su, end: wi });
  }
  return out;
}

/**
 * Season a "YYYY-MM" month is counted under: whichever season covers the most
 * of its days. A tie goes to the season that was in force at the START of the
 * month — a tank holds a week or two of fuel and stations sell down old stock,
 * so the earlier blend is what most of that month's miles actually burned.
 * With the federal dates: Jun–Sep are summer (September splits 15/15 and goes
 * to summer by the tie rule), Apr–May are transition, Oct–Mar are winter.
 */
export function classifyMonth(period: string): FuelSeason | null {
  const m = /^(\d{4})-(\d{2})$/.exec(period);
  if (!m) return null;
  const y = Number(m[1]);
  const mo = Number(m[2]) - 1;
  const days = new Date(y, mo + 1, 0).getDate();
  const count: Record<FuelSeason, number> = { winter: 0, transition: 0, summer: 0 };
  for (let d = 1; d <= days; d++) count[fuelSeasonOn(new Date(y, mo, d))]++;
  const first = fuelSeasonOn(new Date(y, mo, 1));
  let best: FuelSeason = first;
  for (const s of ["winter", "transition", "summer"] as FuelSeason[]) {
    if (count[s] > count[best]) best = s;
  }
  return best;
}

export interface SeasonMpg {
  /** Fill-up-weighted mean MPG; null when the season has no months. */
  mpg: number | null;
  months: number;
  fills: number;
}

/**
 * Summer- vs winter-blend MPG from the same monthly, fill-up-based MPG the
 * "MPG over time" chart plots. Each month is classified whole by
 * classifyMonth — the API only returns monthly aggregates, so per-fill-up
 * dates aren't available here, and a month is also the chart's own unit.
 * Transition months (spring changeover) are left out of both sides: the pump
 * is selling a mix, so they would blur exactly the difference being measured.
 * Weighted by fill-up count, matching the chart's headline average.
 */
export function seasonalMpg(
  points: readonly { period: string; mpg: number; fills: number }[],
): { summer: SeasonMpg; winter: SeasonMpg } {
  const acc = {
    summer: { w: 0, sum: 0, months: 0 },
    winter: { w: 0, sum: 0, months: 0 },
  };
  for (const p of points) {
    if (!(p.mpg > 0)) continue;
    const s = classifyMonth(p.period);
    if (s !== "summer" && s !== "winter") continue;
    const w = p.fills > 0 ? p.fills : 1;
    acc[s].w += w;
    acc[s].sum += p.mpg * w;
    acc[s].months++;
  }
  const fin = (a: { w: number; sum: number; months: number }): SeasonMpg => ({
    mpg: a.w > 0 ? a.sum / a.w : null,
    months: a.months,
    fills: a.w,
  });
  return { summer: fin(acc.summer), winter: fin(acc.winter) };
}
