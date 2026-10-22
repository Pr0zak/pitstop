// Pure shaping for the Analytics "Tires" section (GET /analytics/tires).
// Pressure always in psi; temperatures through the caller's converter.
import type { TireAnalytics } from "@/api/endpoints";
import { TIRE_CORNERS, kpaToPsi, type TireCorner } from "@/lib/vehicleSignals";

const MON = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

/** "2026-10-21" (a UTC day from the API) → epoch ms at 12:00 UTC, so the
 *  point sits mid-day whatever the viewer's zone. */
export function dayToMs(day: string): number {
  const [y, m, d] = day.split("-").map(Number);
  return Date.UTC(y, m - 1, d, 12);
}
export function dayLabel(day: string): string {
  const [, m, d] = day.split("-").map(Number);
  return `${MON[m - 1]} ${d}`;
}

/** At most `max` evenly spaced day ticks, always including the last day. */
export function dayTicks(days: readonly string[], max = 6): { v: number; label: string }[] {
  if (days.length === 0) return [];
  const step = Math.max(1, Math.ceil(days.length / max));
  const out: { v: number; label: string }[] = [];
  for (let i = days.length - 1; i >= 0; i -= step) {
    out.unshift({ v: dayToMs(days[i]), label: dayLabel(days[i]) });
  }
  return out;
}

export interface TireDailyChart {
  x: number[];
  days: string[];
  /** Per corner, display values (null = no data that day). */
  corners: Record<TireCorner, (number | null)[]>;
  /** Outside temp (display unit), null where unknown. */
  outside: (number | null)[];
  hasCorner: boolean;
  hasOutside: boolean;
}

/** Daily cold pressure (psi) per corner + outside temp. */
export function coldPressureChart(
  a: TireAnalytics,
  fToDisplay: (f: number) => number,
): TireDailyChart {
  return dailyChart(a, (d) => (d.cold_pressure_kpa != null ? kpaToPsi(d.cold_pressure_kpa) : null), fToDisplay);
}

/** Daily max tire temp per corner + outside temp, both in the display unit. */
export function tireTempChart(
  a: TireAnalytics,
  cToDisplay: (c: number) => number,
  fToDisplay: (f: number) => number,
): TireDailyChart {
  return dailyChart(a, (d) => (d.temp_max_c != null ? cToDisplay(d.temp_max_c) : null), fToDisplay);
}

function dailyChart(
  a: TireAnalytics,
  pick: (d: TireAnalytics["corners"][number]["daily"][number]) => number | null,
  fToDisplay: (f: number) => number,
): TireDailyChart {
  const days = a.days;
  const corners = {} as Record<TireCorner, (number | null)[]>;
  let hasCorner = false;
  for (const c of TIRE_CORNERS) {
    const hist = a.corners.find((h) => h.corner === c);
    const byDay = new Map((hist?.daily ?? []).map((d) => [d.day, pick(d)]));
    corners[c] = days.map((d) => byDay.get(d) ?? null);
    if (corners[c].some((v) => v != null)) hasCorner = true;
  }
  const ambByDay = new Map(a.ambient.map((x) => [x.day, x.ambient_mean_f]));
  const outside = days.map((d) => {
    const f = ambByDay.get(d);
    return f != null ? fToDisplay(f) : null;
  });
  return {
    x: days.map(dayToMs),
    days: [...days],
    corners,
    outside,
    hasCorner,
    hasOutside: outside.some((v) => v != null),
  };
}

/** Indices of days where any corner has a value (the hoverable points). */
export function populatedDays(chart: TireDailyChart): number[] {
  const out: number[] = [];
  for (let i = 0; i < chart.x.length; i++) {
    if (TIRE_CORNERS.some((c) => chart.corners[c][i] != null)) out.push(i);
  }
  return out;
}
