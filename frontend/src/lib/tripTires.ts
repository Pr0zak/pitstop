// Trip-detail tire chart data. Pure so vitest can pin the gap handling.
//
// The trip samples arrive long-form ({time, metric, value_num}); tires are
// sparse (~30 s from the phone's Mode 22 poll, ~2.5 s from the WiCAN) and
// often absent altogether. Each corner is carried forward between its own
// readings — pressures move slowly, so a held value is the honest picture —
// but only for `maxCarryS`. Past that the line breaks: a dropout must read as
// a gap, never as a flat line (and never as zero).
import { TIRE_CORNERS, type TireCorner } from "@/lib/vehicleSignals";

export type TireChartKind = "pressure" | "temp";

export interface LongSample {
  time: string;
  metric?: string;
  value_num?: number | null;
}

/** Longest a tire value is carried forward. 3× the phone's 30-s poll, so one
 *  missed poll doesn't break the line but a real dropout does. */
export const TIRE_MAX_CARRY_S = 90;

export function tireMetric(kind: TireChartKind, corner: TireCorner): string {
  return `tire_${kind}_${corner}`;
}

/** Sentinels the Honda TPMS sends for "no sensor": 0 kPa, −40 °C. */
export function validTireValue(kind: TireChartKind, v: number | null | undefined): v is number {
  if (v == null || !Number.isFinite(v)) return false;
  return kind === "pressure" ? v > 0 : v > -40;
}

/** True when any tire metric (pressure or temp) has a usable sample. */
export function hasTireData(samples: readonly LongSample[]): boolean {
  for (const s of samples) {
    const m = s.metric;
    if (!m || !m.startsWith("tire_")) continue;
    const kind: TireChartKind | null = m.startsWith("tire_pressure_")
      ? "pressure"
      : m.startsWith("tire_temp_")
        ? "temp"
        : null;
    if (kind && validTireValue(kind, s.value_num)) return true;
  }
  return false;
}

/**
 * Forward-fill `values` along `xs` (unix s, ascending), but only while the last
 * real value is younger than `maxCarryS`. Shared with the trip timeline lanes
 * for metrics where an unlimited carry would lie (brake pressure).
 */
export function fillForwardWithin(
  xs: readonly number[],
  values: readonly (number | null)[],
  maxCarryS: number,
): (number | null)[] {
  const out: (number | null)[] = new Array(values.length).fill(null);
  let last: number | null = null;
  let lastTs = -Infinity;
  for (let i = 0; i < values.length; i++) {
    const v = values[i];
    if (v != null) {
      last = v;
      lastTs = xs[i];
      out[i] = v;
    } else if (last != null && xs[i] - lastTs < maxCarryS) {
      out[i] = last;
    }
  }
  return out;
}

export interface TireChartData {
  /** Shared x column, unix seconds ascending. */
  x: number[];
  /** One column per corner in TIRE_CORNERS order, display units. */
  cols: Record<TireCorner, (number | null)[]>;
  /** Corners with at least one reading. */
  present: TireCorner[];
}

/**
 * Build the aligned columns for one kind. `transform` converts the stored
 * unit (kPa / °C) to the display unit. Returns null when no corner has data
 * of this kind.
 */
export function buildTireChart(
  samples: readonly LongSample[],
  kind: TireChartKind,
  transform: (v: number) => number,
  maxCarryS = TIRE_MAX_CARRY_S,
): TireChartData | null {
  const byCorner = new Map<TireCorner, Map<number, number>>();
  const metricCorner = new Map<string, TireCorner>(
    TIRE_CORNERS.map((c) => [tireMetric(kind, c), c]),
  );
  const tsSet = new Set<number>();
  for (const s of samples) {
    const c = s.metric ? metricCorner.get(s.metric) : undefined;
    if (!c || !validTireValue(kind, s.value_num)) continue;
    const ts = Math.round(Date.parse(s.time) / 1000);
    if (!Number.isFinite(ts)) continue;
    let m = byCorner.get(c);
    if (!m) byCorner.set(c, (m = new Map()));
    m.set(ts, s.value_num);
    tsSet.add(ts);
  }
  if (byCorner.size === 0) return null;

  // Union of every corner's timestamps, plus a break point wherever the whole
  // set goes quiet for longer than the carry: uPlot joins consecutive non-null
  // points however far apart they are, so a gap needs an explicit null row.
  const union = Array.from(tsSet).sort((a, b) => a - b);
  const x: number[] = [];
  for (let i = 0; i < union.length; i++) {
    x.push(union[i]);
    const next = union[i + 1];
    if (next != null && next - union[i] > maxCarryS) x.push(union[i] + maxCarryS);
  }

  const cols = {} as Record<TireCorner, (number | null)[]>;
  const present: TireCorner[] = [];
  for (const c of TIRE_CORNERS) {
    const m = byCorner.get(c);
    if (m) present.push(c);
    const raw = x.map((ts) => {
      const v = m?.get(ts);
      return v == null ? null : transform(v);
    });
    cols[c] = fillForwardWithin(x, raw, maxCarryS);
  }
  return { x, cols, present };
}

/** Summary rows for the per-trip Tires card (from TripDetail.tires). */
export interface TripTireCornerIn {
  corner: string;
  pressure_start_kpa?: number | null;
  pressure_end_kpa?: number | null;
  temp_start_c?: number | null;
  temp_end_c?: number | null;
}

/** Temp rise over the trip (°C delta), null unless both ends exist. */
export function tireWarmupC(c: TripTireCornerIn): number | null {
  if (c.temp_start_c == null || c.temp_end_c == null) return null;
  return c.temp_end_c - c.temp_start_c;
}
