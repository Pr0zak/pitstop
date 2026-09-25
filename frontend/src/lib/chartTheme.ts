import type uPlot from "uplot";

/**
 * Token-based uPlot theme. UPlotChart merges this under every chart's own
 * options by default, so views only state what's specific to their chart
 * (scales, series meaning, axis labels) and never hard-code hex colours.
 *
 * uPlot draws to canvas, which can't resolve CSS custom properties, so the
 * tokens are read from :root once per build via getComputedStyle.
 */
export function cssVar(name: string, fallback: string): string {
  if (typeof document === "undefined") return fallback;
  const v = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  return v || fallback;
}

const PALETTE_FALLBACK = [
  "#60a5fa",
  "#4ade80",
  "#ffb020",
  "#c084fc",
  "#ff5b3a",
  "#2dd4bf",
  "#f472b6",
  "#a3e635",
];

/** The categorical series palette (--chart-1 … --chart-8). */
export function chartPalette(): string[] {
  return PALETTE_FALLBACK.map((fb, i) => cssVar(`--chart-${i + 1}`, fb));
}

/** Semantic colours for charts that need meaning rather than a slot. */
export function chartColors() {
  return {
    axis: cssVar("--chart-axis", "#6a6f78"),
    grid: cssVar("--chart-grid", "rgba(255,255,255,0.06)"),
    ink: cssVar("--c-ink2", "#9aa0aa"),
    accent: cssVar("--c-accent", "#ff5b3a"),
    success: cssVar("--c-success", "#4ade80"),
    warn: cssVar("--c-warn", "#ffb020"),
    danger: cssVar("--c-danger", "#ff3a2e"),
    info: cssVar("--c-info", "#60a5fa"),
  };
}

/** Translucent fill for an area under a series stroke. */
export function withAlpha(hex: string, alpha: number): string {
  const m = /^#([0-9a-f]{6})$/i.exec(hex.trim());
  if (!m) return hex;
  const n = parseInt(m[1], 16);
  return `rgba(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}, ${alpha})`;
}

const FONT = "11px Geist, -apple-system, system-ui, sans-serif";
const LABEL_FONT = "500 11px Geist, -apple-system, system-ui, sans-serif";

/** Merge theme defaults UNDER the caller's options (caller wins). */
export function applyChartTheme(opts: uPlot.Options): uPlot.Options {
  const c = chartColors();
  const palette = chartPalette();
  const axes = (opts.axes ?? [{}, {}]).map((a) => ({
    stroke: c.ink,
    font: FONT,
    labelFont: LABEL_FONT,
    ...a,
    grid: { stroke: c.grid, width: 1, ...(a.grid ?? {}) },
    ticks: { stroke: c.grid, width: 1, ...(a.ticks ?? {}) },
  }));
  let slot = 0;
  const series = (opts.series ?? []).map((s, i) => {
    if (i === 0) return s;
    const color = palette[slot++ % palette.length];
    return s.stroke == null ? { ...s, stroke: color } : s;
  });
  return { ...opts, axes, series };
}
