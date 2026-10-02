import type uPlot from "uplot";
import { niceTicks } from "@/lib/fuelInsights";
import { cssVar, withAlpha } from "@/lib/chartTheme";

/**
 * Shared uPlot option pieces for the insight charts (MPG over time, paid vs
 * market, spend this year vs last). x is plain epoch-ms / day numbers (not a
 * uPlot time scale) so the tick placement is exactly what the caller asks for.
 */

export function insightColors() {
  return {
    accent: cssVar("--c-accent", "#ff5b3a"),
    compare: cssVar("--c-compare", "#4d8ae0"),
    surface: cssVar("--c-bg2", "#14171d"),
    ink: cssVar("--c-ink2", "#9aa0aa"),
  };
}

/** Fixed-range scales: x [x0, x1], y [y0, y1]. */
export function fixedScales(x: [number, number], y: [number, number]): uPlot.Scales {
  return {
    x: { time: false, range: () => x },
    y: { range: () => y },
  };
}

/** x axis with caller-chosen ticks and no vertical grid; y axis on 1/2/5 steps. */
export function insightAxes(
  xTicks: { v: number; label: string }[],
  yFmt: (v: number) => string,
): uPlot.Axis[] {
  const byV = new Map(xTicks.map((t) => [t.v, t.label]));
  return [
    {
      splits: () => xTicks.map((t) => t.v),
      values: (_u, splits) => splits.map((v) => byV.get(v) ?? ""),
      grid: { show: false },
      ticks: { show: false },
      size: 28,
    },
    {
      splits: (_u, _i, min, max) => niceTicks(min, max, 4),
      values: (_u, splits) => splits.map(yFmt),
      ticks: { show: false },
      size: 48,
    },
  ];
}

/**
 * A series drawn only as round dots. `radius` is the visible fill radius;
 * the 2 px surface-coloured ring is added outside it (uPlot centres the
 * stroke on the edge, so size = 2·radius + stroke).
 */
export function dotSeries(label: string, color: string, radius: number, opacity = 1): uPlot.Series {
  const c = insightColors();
  return {
    label,
    stroke: color,
    paths: () => null,
    points: {
      show: true,
      size: radius * 2 + 2,
      width: 2,
      stroke: c.surface,
      fill: opacity < 1 ? withAlpha(color, opacity) : color,
    },
  };
}

/** A line series (optionally with a light area fill). */
export function lineSeries(
  label: string,
  color: string,
  opts: { fill?: boolean; paths?: uPlot.Series.PathBuilder; width?: number } = {},
): uPlot.Series {
  return {
    label,
    stroke: color,
    width: opts.width ?? 2,
    spanGaps: true,
    points: { show: false },
    ...(opts.fill ? { fill: withAlpha(color, 0.12) } : {}),
    ...(opts.paths ? { paths: opts.paths } : {}),
  };
}

/** The emphasised end point of a line: one dot, data null except the last. */
export function endDotSeries(color: string): uPlot.Series {
  return dotSeries("", color, 4.5);
}

const MON = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
export const MONTH_ABBR = MON;

/** Jan 1 of each year strictly inside (t0, t1], labelled "2024". */
export function yearTicks(t0: number, t1: number): { v: number; label: string }[] {
  const out: { v: number; label: string }[] = [];
  for (let y = new Date(t0).getFullYear() + 1; y <= new Date(t1).getFullYear(); y++) {
    out.push({ v: new Date(y, 0, 1).getTime(), label: String(y) });
  }
  return out;
}

/** 1st of every `every`-th month in (t0, t1]; January carries the year ("Jan ’26"). */
export function monthTicks(t0: number, t1: number, every: number): { v: number; label: string }[] {
  const out: { v: number; label: string }[] = [];
  const d = new Date(t0);
  d.setDate(1);
  d.setHours(0, 0, 0, 0);
  d.setMonth(d.getMonth() + 1);
  while (d.getTime() <= t1) {
    if (d.getMonth() % every === 0) {
      const m = d.getMonth();
      out.push({
        v: d.getTime(),
        label: `${MON[m]}${m === 0 ? " ’" + String(d.getFullYear()).slice(2) : ""}`,
      });
    }
    d.setMonth(d.getMonth() + 1);
  }
  return out;
}

/**
 * Explicit step-after vertices for a running total: each point is preceded
 * by a vertex at (x − ε, previous y), so a plain linear path draws the
 * staircase — the same vertices the phone chart draws, and two series
 * with different fillup days can share one aligned x axis.
 */
export function stepVertices(pts: readonly { x: number; y: number }[], eps = 1e-3): { x: number; y: number }[] {
  return pts.flatMap((p, i) => (i ? [{ x: p.x - eps, y: pts[i - 1].y }, p] : [p]));
}
