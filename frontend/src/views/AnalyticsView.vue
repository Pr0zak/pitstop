<script setup lang="ts">
import { computed } from "vue";
import { RouterLink } from "vue-router";
import StateCard from "@/components/StateCard.vue";
import WindowChips from "@/components/WindowChips.vue";
import { useQueryParam } from "@/composables/useQueryParam";
import { chartPalette, withAlpha } from "@/lib/chartTheme";
import { useVehiclesStore } from "@/stores/vehicles";
import { useAsync } from "@/composables/useAsync";
import * as api from "@/api/endpoints";
import type uPlot from "uplot";
import UPlotChart from "@/components/charts/UPlotChart.vue";
import InsightChart from "@/components/charts/InsightChart.vue";
import TireAnalyticsSection from "@/components/TireAnalyticsSection.vue";
import { mpgOverSpan, niceTicks, padRange } from "@/lib/fuelInsights";
import { FUEL_SEASON, classifyMonth, fuelSeasonBands, seasonalMpg } from "@/lib/fuelSeasons";
import {
  MONTH_ABBR,
  dotSeries,
  endDotSeries,
  fixedScales,
  fuelSeasonPlugin,
  insightAxes,
  insightColors,
  lineSeries,
  monthTicks,
  yearTicks,
} from "@/lib/insightChartOpts";
import type { AnalyticsWindow } from "@/api/types";
import {
  fmtMpg,
  fmtMoney,
  fmtInt,
  fmtDistance,
  fmtPricePerVolume,
  nf,
  convEconomyMpg,
  convDistance,
  convTempC,
  economyUnitLabel,
  distUnitLabel,
  tempUnitLabel,
  speedUnitLabel,
  vehicleVolUnit,
} from "@/composables/useFormat";

const vehicles = useVehiclesStore();
const vehicleId = computed(() => vehicles.selectedVehicleId);
// Shared window chips, synced to ?window=. The ids are the old ones plus
// "2y"/"3y" so saved links keep working ("year" is now labelled "1 year").
// Page-local type: nothing on this page sends it to /analytics/mpg or
// /cost-per-mi (whose backend Literal is AnalyticsWindow); every request
// below is translated to days / months / a from= timestamp first.
type PageWindow = AnalyticsWindow | "2y" | "3y";
const WINDOW_OPTIONS = [
  { value: "month" as const, label: "30 days" },
  { value: "3m" as const, label: "3 months" },
  { value: "year" as const, label: "1 year" },
  { value: "2y" as const, label: "2 years" },
  { value: "3y" as const, label: "3 years" },
  { value: "all" as const, label: "All time" },
];
const window = useQueryParam<PageWindow>("window", "all", ["month", "3m", "year", "2y", "3y", "all"]);
/** The selected window in days; null = all history. */
const WINDOW_DAYS: Record<PageWindow, number | null> = {
  month: 30,
  "3m": 90,
  year: 365,
  "2y": 730,
  "3y": 1095,
  all: null,
};
const windowDays = computed(() => WINDOW_DAYS[window.value]);
/** Chip label of the current window, shown as the tag on charts that follow it. */
const windowLabel = computed(
  () => WINDOW_OPTIONS.find((o) => o.value === window.value)?.label ?? "All time",
);
const PAL = chartPalette();
const DAY_MS = 86_400_000;

// Time range for aggregate calls
const fromIso = computed(() => {
  const d = windowDays.value;
  return d == null ? undefined : new Date(Date.now() - d * DAY_MS).toISOString();
});

// Tires section window: the page window in days. /analytics/tires caps at
// 365, so 2 years / 3 years / All time read the last year — tagged as such.
const TIRES_MAX_DAYS = 365;
const tireDays = computed(() => Math.min(windowDays.value ?? TIRES_MAX_DAYS, TIRES_MAX_DAYS));
const tireTag = computed(() =>
  (windowDays.value ?? Infinity) > TIRES_MAX_DAYS ? "max: 1 year" : windowLabel.value,
);

// Monthly MPG for the "MPG over time" panel. Always monthly buckets: the
// panel has its own 1 / 2 / 3 yr / All span (synced to ?mpgspan=), so the
// page-level window doesn't scope it. /analytics/mpg returns every month
// since the first fillup; the span is cut client-side (lib/fuelInsights).
const mpgQ = useAsync(
  () =>
    vehicleId.value
      ? api.mpgTrend(vehicleId.value, "month")
      : Promise.resolve({ points: [] }),
  [vehicleId],
);

const rpmQ = useAsync(
  () =>
    vehicleId.value
      ? api.aggregateReadings({
          vehicle_id: vehicleId.value,
          metric: "engine_rpm",
          from: fromIso.value,
          bucket: "day",
        })
      : Promise.resolve([]),
  [vehicleId, window],
);

const tempCoolantQ = useAsync(
  () =>
    vehicleId.value
      ? api.aggregateReadings({
          vehicle_id: vehicleId.value,
          metric: "coolant_temp",
          from: fromIso.value,
          bucket: "day",
        })
      : Promise.resolve([]),
  [vehicleId, window],
);


// (No manual watch on vehicleId — useAsync already re-fetches when any
// dep ref changes, so each of these queries reloads on vehicle switch.
// A manual watch fired a second, redundant round of requests.)

// ── MPG over time ────────────────────────────────────────────────────
// Monthly MPG as faint dots, a 3-month rolling median line with a light
// area and an emphasised end point, and the vehicle's EPA combined sticker
// as a dashed reference when set. Headline = fillup-weighted average over
// the span. Maths in lib/fuelInsights.ts (shared shape with the phone).
type MpgSpanKey = "12m" | "2y" | "3y" | "all";
const MPG_SPAN_OPTIONS = [
  { value: "12m" as const, label: "1 yr" },
  { value: "2y" as const, label: "2 yr" },
  { value: "3y" as const, label: "3 yr" },
  { value: "all" as const, label: "All" },
];
const MPG_SPAN_MONTHS: Record<MpgSpanKey, number | null> = { "12m": 12, "2y": 24, "3y": 36, all: null };
const mpgSpan = useQueryParam<MpgSpanKey>("mpgspan", "3y", ["12m", "2y", "3y", "all"]);
const epaMpg = computed(() => vehicles.selectedVehicle?.epa_mpg_combined ?? null);
const mpgSeries = computed(() =>
  mpgOverSpan(
    mpgQ.data.value?.points ?? [],
    MPG_SPAN_MONTHS[mpgSpan.value],
  ),
);
const mpgHeadline = computed(() => {
  const { points, average } = mpgSeries.value;
  if (average == null) return null;
  return {
    value: nf(1).format(convEconomyMpg(average)),
    sub:
      mpgSpan.value === "all"
        ? `average over all ${points.length} months`
        : mpgSpan.value === "12m"
          ? "average over the last 12 months"
          : `average over the last ${MPG_SPAN_MONTHS[mpgSpan.value]! / 12} years`,
  };
});
const mpgChart = computed(() => {
  const pts = mpgSeries.value.points;
  if (pts.length === 0) return null;
  const c = insightColors();
  const ys = pts.map((p) => convEconomyMpg(p.mpg));
  const med = pts.map((p) => convEconomyMpg(p.median));
  const epa = epaMpg.value != null ? convEconomyMpg(epaMpg.value) : null;
  const x0 = pts[0].t - 15 * DAY_MS;
  const x1 = pts[pts.length - 1].t + 15 * DAY_MS;
  const y = padRange(epa != null ? [...ys, epa] : ys);
  const unit = economyUnitLabel();
  const series: uPlot.Series[] = [
    {},
    dotSeries("Monthly", c.accent, 3, 0.55),
    lineSeries("3-mo median", c.accent, { fill: true }),
    endDotSeries(c.accent),
  ];
  const data: (number | null)[][] = [
    pts.map((p) => p.t),
    ys,
    med,
    med.map((v, i) => (i === med.length - 1 ? v : null)),
  ];
  if (epa != null) {
    series.push({
      label: "EPA combined",
      stroke: "rgba(154,160,170,0.65)",
      width: 1,
      dash: [4, 3],
      points: { show: false },
    });
    data.push(pts.map(() => epa));
  }
  const scales = fixedScales([x0, x1], y);
  const axes = insightAxes(
    mpgSpan.value === "12m"
      ? monthTicks(x0, x1, 3)
      : mpgSpan.value === "2y"
        ? monthTicks(x0, x1, 6)
        : yearTicks(x0, x1),
    (v) => nf(0).format(v),
  );
  // Outside temperature on its own right-hand scale: cold months are the
  // usual reason for an MPG dip, so the two read against each other.
  const temps = pts.map((p) => (p.tempC != null ? convTempC(p.tempC) : null));
  const knownTemps = temps.filter((v): v is number => v != null);
  if (knownTemps.length > 0) {
    const ty = padRange(knownTemps);
    scales.temp = { range: () => ty };
    series.push({ ...lineSeries("Outside temp", c.compare, { width: 1.5 }), scale: "temp", spanGaps: false });
    data.push(temps);
    axes.push({
      scale: "temp",
      side: 1,
      splits: (_u, _i, min, max) => niceTicks(min, max, 6),
      values: (_u, splits) => splits.map((v) => `${nf(0).format(v)}°`),
      grid: { show: false },
      ticks: { show: false },
      size: 40,
    });
  }
  const opts: uPlot.Options = {
    width: 600,
    height: 240,
    scales,
    axes,
    series,
    // Summer/winter gasoline bands behind the MPG (lib/fuelSeasons.ts).
    plugins: [fuelSeasonPlugin(fuelSeasonBands(x0, x1))],
  };
  return {
    data: data as uPlot.AlignedData,
    opts,
    hover: pts.map((p, i) => ({ x: p.t, y: med[i] })),
    tips: pts.map((p, i) => ({
      title: `${MONTH_ABBR[Number(p.period.slice(5)) - 1]} ${p.period.slice(0, 4)}`,
      main: `${nf(1).format(ys[i])} ${unit}`,
      fills: `${p.fills} fill${p.fills === 1 ? "" : "s"}`,
      sub: `3-mo median ${nf(1).format(med[i])}`,
      temp: temps[i] != null ? `${nf(0).format(temps[i] as number)} ${tempUnitLabel()} outside` : null,
      season: SEASON_TIP[classifyMonth(p.period) ?? "winter"],
    })),
    hasTemp: knownTemps.length > 0,
  };
});

const SEASON_TIP = {
  summer: "Summer-blend gas",
  transition: "Changeover month (mixed blend)",
  winter: "Winter-blend gas",
} as const;
const MON_DAY = (d: Date) => `${MONTH_ABBR[d.getMonth()]} ${d.getDate()}`;
// "Jun 1–Sep 15": the summer band, end shown inclusively (the day before
// winter blend returns). Built from FUEL_SEASON so the legend follows an edit.
const SUMMER_RANGE_TEXT = (() => {
  const { summerStart: a, winterStart: b } = FUEL_SEASON;
  return `${MON_DAY(new Date(2001, a.month - 1, a.day))}–${MON_DAY(new Date(2001, b.month - 1, b.day - 1))}`;
})();
// "Apr–May": the months classifyMonth files as changeover (left out of both
// season averages).
const CHANGEOVER_MONTHS_TEXT = (() => {
  const ms = MONTH_ABBR.filter((_, i) => classifyMonth(`2001-${String(i + 1).padStart(2, "0")}`) === "transition");
  return ms.length ? (ms.length === 1 ? ms[0] : `${ms[0]}–${ms[ms.length - 1]}`) : null;
})();
// Summer- vs winter-blend MPG over the same span and the same monthly,
// fill-up-based MPG the chart plots (classification rules in fuelSeasons.ts).
const mpgBySeason = computed(() => {
  const r = seasonalMpg(mpgSeries.value.points);
  const fmt = (v: number | null) => (v == null ? "—" : nf(1).format(convEconomyMpg(v)));
  const diffPct =
    r.summer.mpg != null && r.winter.mpg != null
      ? ((r.winter.mpg - r.summer.mpg) / r.summer.mpg) * 100
      : null;
  return {
    summer: fmt(r.summer.mpg),
    summerMonths: r.summer.months,
    winter: fmt(r.winter.mpg),
    winterMonths: r.winter.months,
    diff: diffPct == null ? null : `${diffPct > 0 ? "+" : diffPct < 0 ? "−" : "±"}${nf(1).format(Math.abs(diffPct))}%`,
  };
});

// RPM histogram (avg per bucket → bar series via paths.bars)
const rpmOpts = computed<uPlot.Options>(() => ({
  width: 600,
  height: 220,
  scales: { x: { time: true } },
  axes: [{}, { label: "avg rpm" }],
  series: [
    {},
    {
      label: "Avg RPM",
      stroke: PAL[1],
      width: 1.5,
      fill: withAlpha(PAL[1], 0.18),
    },
  ],
}));
const rpmData = computed<uPlot.AlignedData | null>(() => {
  const data = rpmQ.data.value ?? [];
  if (data.length === 0) return null;
  const t = data.map((d) => Math.round((Date.parse(d.bucket) || 0) / 1000));
  const y = data.map((d) => d.avg ?? null);
  return [t, y];
});

// Temp distribution. coolant_temp is stored canonically in °C; convert to
// °F when the user's resolved unit system is imperial and label the axis to
// match. (The axis was hardcoded "°F" while plotting raw °C — a 2x mislabel.)
// Only the Coolant series is currently plotted, so the series shape is fixed;
// opts recomputes only when the unit system flips (rare). Data holds the
// aligned columns and refreshes cheaply via setData().
// Unit labels read the units store, so opts rebuild when the system flips.
const tempOpts = computed<uPlot.Options>(() => ({
  width: 600,
  height: 220,
  scales: { x: { time: true } },
  axes: [{}, { label: tempUnitLabel() }],
  series: [{}, { label: "Coolant", stroke: PAL[2], width: 1.5 }],
}));
const tempData = computed<uPlot.AlignedData | null>(() => {
  const points = tempCoolantQ.data.value;
  if (!points || points.length === 0) return null;
  const toDisplay = (c: number | null): number | null => (c == null ? null : convTempC(c));
  const ts = points
    .map((p) => Math.round((Date.parse(p.bucket) || 0) / 1000))
    .sort((a, b) => a - b);
  const tsIdx = new Map<number, number>();
  ts.forEach((t, i) => tsIdx.set(t, i));
  const col: (number | null)[] = new Array(ts.length).fill(null);
  for (const p of points) {
    const i = tsIdx.get(Math.round((Date.parse(p.bucket) || 0) / 1000));
    if (i != null) col[i] = toDisplay(p.avg ?? null);
  }
  return [ts, col];
});

// Engine hours vs miles (Task #96). Backend walks
// time_since_engine_start, sums per-cycle maxes, and pairs each
// month with the odometer high-water mark.
const hoursQ = useAsync(
  () =>
    vehicleId.value
      ? api.getEngineHours(vehicleId.value)
      : Promise.resolve(null as api.EngineHours | null),
  [vehicleId],
);
const hoursOpts = computed<uPlot.Options>(() => ({
  width: 600,
  height: 220,
  scales: { x: { time: true }, hrs: {}, ratio: {} },
  axes: [
    {},
    { scale: "hrs", label: "engine hours", side: 3 },
    { scale: "ratio", label: `hrs / 100 ${distUnitLabel()}`, side: 1, grid: { show: false } },
  ],
  series: [
    {},
    { label: "Cum. engine hrs", scale: "hrs", stroke: PAL[1], width: 1.6 },
    { label: `Hrs / 100 ${distUnitLabel()}`, scale: "ratio", stroke: PAL[3], width: 1.4, dash: [4, 3] },
  ],
}));
// /analytics/engine-hours always returns every month (cumulative totals), so
// the window is a client-side crop. Monthly points: a 30-day / 3-month crop
// would leave one or two dots, so windows under a year show the last year
// (tagged). Cumulative values stay lifetime totals either way.
const HOURS_MIN_DAYS = 365;
const hoursTag = computed(() =>
  (windowDays.value ?? Infinity) < HOURS_MIN_DAYS ? "min: 1 year" : windowLabel.value,
);
const hoursData = computed<uPlot.AlignedData | null>(() => {
  const days = windowDays.value == null ? null : Math.max(windowDays.value, HOURS_MIN_DAYS);
  // Month buckets are stamped on the 1st, so allow a month of slack at the
  // cut so a 1-year window still includes the month it started in.
  const cut = days == null ? -Infinity : Date.now() - (days + 31) * DAY_MS;
  const points = (hoursQ.data.value?.points ?? []).filter((p) => (Date.parse(p.month) || 0) >= cut);
  if (points.length < 2) return null;
  const t: number[] = [];
  const hrs: (number | null)[] = [];
  const ratio: (number | null)[] = []; // hrs per 100 mi
  for (const p of points) {
    const ts = Math.round((Date.parse(p.month) || 0) / 1000);
    t.push(ts);
    hrs.push(p.cumulative_hours);
    if (p.cumulative_km != null && p.cumulative_km > 0) {
      const d = convDistance(p.cumulative_km, "km");
      ratio.push(d > 0 ? (p.cumulative_hours / d) * 100 : null);
    } else {
      ratio.push(null);
    }
  }
  return [t, hrs, ratio] as uPlot.AlignedData;
});

// /analytics/fuel-trim takes 7..3650 days; All time asks for the maximum.
const trimDays = computed(() => Math.min(Math.max(windowDays.value ?? 3650, 7), 3650));

// Long-term fuel trim drift (Task #89). LTFT trending up over
// months signals the ECU has learned to add fuel — vacuum leak,
// weak O2 sensor, clogging air filter, etc.
const trimQ = useAsync(
  () =>
    vehicleId.value
      ? api.getFuelTrimHistory(vehicleId.value, trimDays.value)
      : Promise.resolve(null as api.FuelTrimResponse | null),
  [vehicleId, window],
);

// Which trim banks actually have samples — drives both the series defs and
// the aligned columns. Derived once so opts + data can never desync on series
// count. Only changes on vehicle switch (trimQ's sole dep), so the resulting
// opts identity is stable across everything else.
const TRIM_SPECS: Array<{ key: string; label: string; stroke: string; dash?: number[] }> = [
  { key: "ltft_b1", label: "LTFT B1", stroke: PAL[0] },
  { key: "ltft_b2", label: "LTFT B2", stroke: PAL[3] },
  { key: "stft_b1", label: "STFT B1", stroke: withAlpha(PAL[0], 0.55), dash: [3, 3] },
  { key: "stft_b2", label: "STFT B2", stroke: withAlpha(PAL[3], 0.55), dash: [3, 3] },
];
const trimBuild = computed<{ aligned: uPlot.AlignedData; opts: uPlot.Options } | null>(() => {
  const series = trimQ.data.value?.series;
  if (!series) return null;
  // Union of every distinct timestamp across all 4 series so uPlot
  // gets a single x-axis. Each series fills with null where it
  // didn't have a sample for that day.
  const tsSet = new Set<number>();
  for (const m of Object.values(series)) {
    for (const p of m) tsSet.add(Math.round((Date.parse(p.time) || 0) / 1000));
  }
  if (tsSet.size === 0) return null;
  const ts = Array.from(tsSet).sort((a, b) => a - b);
  const idx = new Map<number, number>();
  ts.forEach((t, i) => idx.set(t, i));
  const cols: (number | null)[][] = [];
  const seriesDefs: uPlot.Series[] = [{}];
  // Pair colors so STFT/LTFT for each bank read together.
  for (const s of TRIM_SPECS) {
    const col: (number | null)[] = new Array(ts.length).fill(null);
    for (const p of series[s.key] ?? []) {
      const i = idx.get(Math.round((Date.parse(p.time) || 0) / 1000));
      if (i != null) col[i] = p.pct;
    }
    if (col.some((v) => v != null)) {
      cols.push(col);
      seriesDefs.push({
        label: s.label,
        stroke: s.stroke,
        width: 1.4,
        ...(s.dash ? { dash: s.dash } : {}),
      });
    }
  }
  if (cols.length === 0) return null;
  return {
    aligned: [ts, ...cols] as uPlot.AlignedData,
    opts: {
      width: 600,
      height: 220,
      scales: { x: { time: true } },
      axes: [{}, { label: "%" }],
      series: seriesDefs,
    },
  };
});
const trimData = computed<uPlot.AlignedData | null>(() => trimBuild.value?.aligned ?? null);
const trimOpts = computed<uPlot.Options | null>(() => trimBuild.value?.opts ?? null);

// Calendar months for /analytics/cost-breakdown (1..120); All time = 120,
// the endpoint's maximum (10 years).
const breakdownMonths = computed(() =>
  windowDays.value == null ? 120 : Math.min(Math.max(Math.round(windowDays.value / 30.4), 1), 120),
);

// Cost breakdown (Task #92). Per-month spend by category for a
// stacked bar — fuel, maintenance, registration, etc.
const breakdownQ = useAsync(
  () =>
    vehicleId.value
      ? api.getCostBreakdown(vehicleId.value, breakdownMonths.value)
      : Promise.resolve(null as api.CostBreakdown | null),
  [vehicleId, window],
);
// Stable per-category color so the legend matches the bars.
const CATEGORY_COLORS: Record<string, string> = {
  Fuel: "var(--chart-1)",
  Service: "var(--chart-3)",
  Maintenance: "var(--chart-3)",
  Repair: "var(--chart-5)",
  Tires: "var(--chart-4)",
  Insurance: "var(--chart-6)",
  Registration: "var(--chart-2)",
  Oil: "var(--chart-8)",
  Tax: "var(--chart-7)",
  Other: "var(--c-ink3)",
};
function categoryColor(name: string): string {
  return CATEGORY_COLORS[name] ?? CATEGORY_COLORS.Other;
}
const breakdownMaxMonth = computed(() => {
  const months = breakdownQ.data.value?.months ?? [];
  return months.length ? Math.max(...months.map((m) => m.total)) : 0;
});

// MPG by driving environment (Task #87). Each trip classified by
// avg_speed_kph into Highway / Mixed / City, MPG averaged per class.
const mpgClassQ = useAsync(
  () =>
    vehicleId.value
      ? api.getMpgBySpeedClass(vehicleId.value)
      : Promise.resolve({ classes: [] as api.MpgBySpeedClassRow[] }),
  [vehicleId],
);
/** Display names for the backend's class keys. These classes are by TRIP
 *  AVERAGE speed (35 / 55 mph cutoffs), which is not the same thing as the
 *  per-second speed buckets on trip detail (City = 2–22 mph etc.) — hence
 *  "Mostly …" rather than reusing the bucket names. Display mapping only; the
 *  endpoint contract is unchanged. */
const SPEED_CLASS_LABEL: Record<string, string> = {
  Highway: "Mostly highway",
  Mixed: "Mixed",
  City: "Mostly city",
};
function speedClassLabel(key: string): string {
  return SPEED_CLASS_LABEL[key] ?? key;
}
function speedClassColor(label: string): string {
  return label === "Highway" ? "var(--c-info)"
    : label === "Mixed" ? "var(--c-success)"
    : "var(--c-warn)";
}
/** Class cutoffs (35 / 55 mph) in the display unit. */
const speedClassText = computed(() => {
  const u = speedUnitLabel();
  const [lo, hi] = u === "mph" ? [35, 55] : [56, 89];
  return `mostly highway ≥ ${hi} ${u}, mixed ${lo}–${hi} ${u}, mostly city < ${lo} ${u}`;
});
const breakdownTotal = computed(() =>
  Object.values(breakdownQ.data.value?.summary ?? {}).reduce((a, b) => a + b, 0),
);
const volSrc = computed(() => vehicleVolUnit(vehicles.selectedVehicle));
/** Odometer can't go below a year (the backend's shortest window). */
const odoTag = computed(() =>
  window.value === "month" || window.value === "3m" ? "min: 1 year" : windowLabel.value,
);

// Fuel-grade comparison (Task #93). Per-grade chain MPG + price.
// Independent of `window` — comparison only makes sense over the
// vehicle's full history; a 30-day window has too few fillups per
// grade.
const gradeQ = useAsync(
  () =>
    vehicleId.value
      ? api.getFuelGradeBreakdown(vehicleId.value)
      : Promise.resolve({ grades: [] as api.FuelGradeRow[] }),
  [vehicleId],
);
function gradeLabel(g: number): string {
  return api.FUEL_GRADE_LABELS[g] ?? `Grade ${g}`;
}

// Odometer history (Task #101). Backend takes a "year"/"3y"/"all"
// window — translate from the page window on the way in. 2 years fetches
// "3y" and is cropped client-side in odoData; 30d/3m read a year.
const odoWindow = computed<"year" | "3y" | "all">(() =>
  window.value === "all" ? "all"
  : window.value === "2y" || window.value === "3y" ? "3y"
  : "year",
);
const odoQ = useAsync(
  () =>
    vehicleId.value
      ? api.getOdometerHistory(vehicleId.value, odoWindow.value)
      : Promise.resolve({ points: [], summary: { window: "all", n_points: 0 } }),
  [vehicleId, odoWindow],
);

const odoOpts = computed<uPlot.Options>(() => ({
  width: 600,
  height: 220,
  scales: { x: { time: true } },
  axes: [{}, { label: distUnitLabel(), values: (_u, v) => v.map((x) => fmtInt(x)) }],
  series: [
    {},
    { label: "Odometer", stroke: PAL[0], width: 1.6, fill: withAlpha(PAL[0], 0.08) },
  ],
}));
const odoData = computed<uPlot.AlignedData | null>(() => {
  const cut = window.value === "2y" ? Date.now() - 730 * DAY_MS : -Infinity;
  const pts = (odoQ.data.value?.points ?? []).filter((p) => (Date.parse(p.time) || 0) >= cut);
  if (pts.length < 2) return null;
  const t: number[] = [];
  const y: (number | null)[] = [];
  for (const p of pts) {
    const ts = Math.round((Date.parse(p.time) || 0) / 1000);
    t.push(ts);
    y.push(p.odo_km != null ? convDistance(p.odo_km, "km") : null);
  }
  return [t, y];
});
</script>

<template>
  <div class="analytics">
    <header class="head">
      <h1>Engine analytics</h1>
      <WindowChips v-model="window" :options="WINDOW_OPTIONS" />
    </header>

    <StateCard v-if="!vehicleId" state="empty" title="Select a vehicle." />
    <template v-else>
      <nav class="elsewhere" aria-label="Related pages">
        <span class="muted small">Also see</span>
        <RouterLink to="/fuel?tab=stats">Spending &amp; fuel stats →</RouterLink>
        <RouterLink to="/dtcs">DTC history →</RouterLink>
        <RouterLink to="/">Lifetime cost →</RouterLink>
      </nav>
      <div class="grid">
        <section class="card wide">
          <header class="head-inline">
            <h3>MPG over time</h3>
            <WindowChips v-model="mpgSpan" :options="MPG_SPAN_OPTIONS" label="MPG time span" />
          </header>
          <StateCard v-if="mpgQ.loading.value && !mpgQ.data.value" state="loading" bare />
          <StateCard v-else-if="mpgQ.error.value" state="error" bare :message="mpgQ.error.value" @retry="mpgQ.reload()" />
          <StateCard v-else-if="!mpgChart" state="empty" bare title="No fillups with economy yet." />
          <template v-else>
            <div v-if="mpgHeadline" class="insight-head">
              <span class="insight-big">{{ mpgHeadline.value }}<small>{{ economyUnitLabel() }}</small></span>
              <span class="insight-sub">{{ mpgHeadline.sub }}</span>
            </div>
            <div class="insight-legend">
              <span><i class="sw dot" style="background: var(--c-accent); opacity: 0.55"></i>Monthly</span>
              <span><i class="sw" style="background: var(--c-accent)"></i>3-month median</span>
              <span v-if="epaMpg != null"><i class="sw epa"></i>EPA combined ({{ fmtMpg(epaMpg) }})</span>
              <span v-if="mpgChart.hasTemp"><i class="sw" style="background: var(--c-compare)"></i>Outside temp ({{ tempUnitLabel() }}, right axis)</span>
              <span><i class="sw band summer"></i>Summer-blend gas ({{ SUMMER_RANGE_TEXT }})</span>
              <span><i class="sw band changeover"></i>Spring changeover</span>
            </div>
            <InsightChart
              :data="mpgChart.data"
              :options="mpgChart.opts"
              :hover="mpgChart.hover"
              mode="x"
              label="Monthly fuel economy with a 3-month rolling median"
            >
              <template #tip="{ index }">
                <span class="k">{{ mpgChart.tips[index].title }}</span><br />
                <b>{{ mpgChart.tips[index].main }}</b> · {{ mpgChart.tips[index].fills }}<br />
                <span class="k">{{ mpgChart.tips[index].sub }}</span>
                <template v-if="mpgChart.tips[index].temp"><br /><span class="k">{{ mpgChart.tips[index].temp }}</span></template>
                <br /><span class="k">{{ mpgChart.tips[index].season }}</span>
              </template>
            </InsightChart>
            <dl class="season-mpg" aria-label="Average fuel economy by gasoline season">
              <div>
                <dt>Summer blend</dt>
                <dd>
                  <span class="num">{{ mpgBySeason.summer }}</span>
                  <small v-if="mpgBySeason.summerMonths">{{ economyUnitLabel() }} · {{ mpgBySeason.summerMonths }} mo</small>
                </dd>
              </div>
              <div>
                <dt>Winter blend</dt>
                <dd>
                  <span class="num">{{ mpgBySeason.winter }}</span>
                  <small v-if="mpgBySeason.winterMonths">{{ economyUnitLabel() }} · {{ mpgBySeason.winterMonths }} mo</small>
                </dd>
              </div>
              <div v-if="mpgBySeason.diff">
                <dt>Winter vs summer</dt>
                <dd><span class="num">{{ mpgBySeason.diff }}</span></dd>
              </div>
              <p class="season-note">
                Monthly fill-up MPG<template v-if="CHANGEOVER_MONTHS_TEXT">; {{ CHANGEOVER_MONTHS_TEXT }} changeover months left out</template>.
                Federal EPA dates; state and reformulated-gas rules can differ.
              </p>
            </dl>
          </template>
        </section>

        <TireAnalyticsSection :vehicle-id="vehicleId" :days="tireDays" :tag="tireTag" />

        <section class="card">
          <h3>Engine RPM (daily avg) <span class="tag-fixed">{{ windowLabel }}</span></h3>
          <StateCard v-if="rpmQ.loading.value" state="loading" bare />
          <StateCard v-else-if="rpmQ.error.value" state="error" bare :message="rpmQ.error.value" @retry="rpmQ.reload()" />
          <StateCard v-else-if="!rpmData" state="empty" bare title="No readings." />
          <UPlotChart v-else :data="rpmData" :options="rpmOpts" />
        </section>

        <section class="card">
          <h3>Coolant temperature (daily avg) <span class="tag-fixed">{{ windowLabel }}</span></h3>
          <StateCard v-if="tempCoolantQ.loading.value" state="loading" bare />
          <StateCard v-else-if="tempCoolantQ.error.value" state="error" bare :message="tempCoolantQ.error.value" @retry="tempCoolantQ.reload()" />
          <StateCard v-else-if="!tempData" state="empty" bare title="No temperature readings." />
          <UPlotChart v-else :data="tempData" :options="tempOpts" />
        </section>

        <section v-if="hoursData" class="card">
          <header class="head-inline">
            <h3>Engine hours vs distance <span class="tag-fixed">{{ hoursTag }}</span></h3>
            <span v-if="hoursQ.data.value" class="muted small">
              {{ fmtInt(hoursQ.data.value.total_hours) }} hrs total
            </span>
          </header>
          <UPlotChart :data="hoursData" :options="hoursOpts" />
        </section>

        <section v-if="trimData && trimOpts" class="card">
          <header class="head-inline">
            <h3>Fuel trim drift <span class="tag-fixed">{{ windowLabel }}</span></h3>
            <span class="muted small">LTFT solid, STFT dashed</span>
          </header>
          <UPlotChart :data="trimData" :options="trimOpts" />
          <p class="muted small">
            LTFT drifting steadily &gt;+5% suggests vacuum leak, weak O2 sensor, or
            clogging air filter. Steady &lt;-5% suggests a richening fault.
          </p>
        </section>

        <section v-if="breakdownQ.data.value?.months?.length" class="card">
          <header class="head-inline">
            <h3>Cost by category <span class="tag-fixed">{{ window === "all" ? "max: 10 years" : windowLabel }}</span></h3>
            <span class="muted small num">{{ fmtMoney(breakdownTotal, 0) }} total</span>
          </header>
          <div class="breakdown-rows">
            <div
              v-for="m in breakdownQ.data.value!.months"
              :key="m.month"
              class="breakdown-row"
            >
              <span class="breakdown-month muted">
                {{ new Date(m.month).toLocaleDateString([], { month: "short", year: "2-digit" }) }}
              </span>
              <span
                class="breakdown-bar"
                :title="
                  Object.entries(m.categories)
                    .map(([k, v]) => `${k}: ${fmtMoney(v, 0)}`)
                    .join('  ·  ')
                "
              >
                <span
                  v-for="cat in breakdownQ.data.value!.category_order.filter(c => m.categories[c])"
                  :key="cat"
                  class="breakdown-seg"
                  :style="{
                    width: ((m.categories[cat] / breakdownMaxMonth) * 100).toFixed(2) + '%',
                    background: categoryColor(cat),
                  }"
                />
              </span>
              <span class="breakdown-total num">{{ fmtMoney(m.total, 0) }}</span>
            </div>
          </div>
          <div class="breakdown-legend">
            <span
              v-for="cat in breakdownQ.data.value!.category_order"
              :key="`leg-${cat}`"
              class="legend-chip"
            >
              <span class="dot" :style="{ background: categoryColor(cat) }"></span>
              {{ cat }}
              <span class="muted num">{{ fmtMoney(breakdownQ.data.value!.summary[cat] ?? 0, 0) }}</span>
            </span>
          </div>
        </section>

        <section
          v-if="mpgClassQ.data.value && mpgClassQ.data.value.classes.some(c => c.trip_count > 0)"
          class="card"
        >
          <header class="head-inline">
            <h3>Economy by driving environment <span class="tag-fixed">fixed: all time</span></h3>
          </header>
          <ul class="speed-class-rows">
            <li
              v-for="c in mpgClassQ.data.value!.classes.filter(c => c.trip_count > 0)"
              :key="c.class"
            >
              <span class="dot" :style="{ background: speedClassColor(c.class) }"></span>
              <span class="speed-class-label">{{ speedClassLabel(c.class) }}</span>
              <span class="num speed-class-mpg">{{ fmtMpg(c.avg_mpg) }}</span>
              <span class="muted small">{{ c.trip_count }} trip{{ c.trip_count === 1 ? "" : "s" }}</span>
            </li>
          </ul>
          <p class="muted small">
            Each trip is classed by its <strong>average speed</strong> over the whole trip
            ({{ speedClassText }}) — not by time spent at each speed, so these differ from the
            speed breakdown on a trip's detail page. OBD-derived economy averaged per class.
          </p>
        </section>

        <section v-if="(gradeQ.data.value?.grades?.length ?? 0) >= 2" class="card">
          <h3>Fuel grade comparison <span class="tag-fixed">fixed: all time</span></h3>
          <table class="data grade-table">
            <thead>
              <tr>
                <th>Grade</th>
                <th class="num">Fillups</th>
                <th class="num">Avg {{ economyUnitLabel() }}</th>
                <th class="num">Avg price</th>
                <th class="num">Total cost</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="g in gradeQ.data.value!.grades" :key="g.grade">
                <td>{{ gradeLabel(g.grade) }}</td>
                <td class="num">{{ g.fillup_count }}</td>
                <td class="num">{{ fmtMpg(g.avg_mpg) }}</td>
                <td class="num">{{ fmtPricePerVolume(g.avg_price_per_unit, volSrc) }}</td>
                <td class="num">{{ fmtMoney(g.total_cost) }}</td>
              </tr>
            </tbody>
          </table>
        </section>

        <section class="card">
          <header class="head-inline">
            <h3>Odometer history <span class="tag-fixed">{{ odoTag }}</span></h3>
            <span v-if="odoQ.data.value?.summary.current_km ?? odoQ.data.value?.summary.current_mi" class="muted small num">
              <template v-if="odoQ.data.value!.summary.current_km != null">{{ fmtDistance(odoQ.data.value!.summary.current_km, "km", 0) }}</template>
              <template v-else>{{ fmtDistance(odoQ.data.value!.summary.current_mi, "mi", 0) }}</template>
              <template v-if="odoQ.data.value!.summary.delta_mi != null">
                · +{{ fmtDistance(odoQ.data.value!.summary.delta_mi, "mi", 0) }} in window
              </template>
            </span>
          </header>
          <StateCard v-if="odoQ.loading.value" state="loading" bare />
          <StateCard v-else-if="odoQ.error.value" state="error" bare :message="odoQ.error.value" @retry="odoQ.reload()" />
          <StateCard v-else-if="!odoData" state="empty" bare title="No odometer history in this window." />
          <UPlotChart v-else :data="odoData" :options="odoOpts" />
        </section>
      </div>
    </template>
  </div>
</template>

<style scoped>
.analytics {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}
.head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.head {
  flex-wrap: wrap;
  gap: 0.6rem;
}
.elsewhere {
  display: flex;
  flex-wrap: wrap;
  gap: 0.4rem 1rem;
  align-items: baseline;
  font-size: 0.85rem;
}
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(360px, 100%), 1fr));
  gap: 1rem;
}
.head-inline {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 0.6rem;
  margin-bottom: 0.4rem;
}
.head-inline h3 {
  margin: 0;
}
.card.wide {
  grid-column: 1 / -1;
}
.insight-legend .sw.epa {
  height: 0;
  border-top: 1px dashed rgba(154, 160, 170, 0.8);
  border-radius: 0;
}
.insight-legend .sw.band {
  height: 9px;
  border-radius: 2px;
}
.insight-legend .sw.band.summer {
  background: var(--c-warn);
  opacity: 0.45;
}
.insight-legend .sw.band.changeover {
  background: var(--c-warn);
  opacity: 0.2;
}
.season-mpg {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 0.4rem 1.5rem;
  margin: 0.6rem 0 0;
}
.season-mpg > div {
  display: flex;
  flex-direction: column;
  gap: 0.1rem;
}
.season-mpg dt {
  font-size: 0.75rem;
  color: var(--c-ink2);
}
.season-mpg dd {
  margin: 0;
  font-size: 1.05rem;
  color: var(--c-ink0);
}
.season-mpg dd small {
  margin-left: 0.3rem;
  font-size: 0.75rem;
  color: var(--c-ink2);
}
.season-note {
  flex-basis: 100%;
  margin: 0;
  font-size: 0.72rem;
  color: var(--c-ink3);
}
.breakdown-rows {
  display: flex;
  flex-direction: column;
  gap: 0.3rem;
  margin-top: 0.4rem;
}
.breakdown-row {
  display: grid;
  grid-template-columns: 4rem 1fr 4rem;
  gap: 0.5rem;
  align-items: center;
  font-size: 0.85rem;
}
.breakdown-month {
  font-size: 0.78rem;
}
.breakdown-bar {
  display: flex;
  height: 14px;
  border-radius: 3px;
  overflow: hidden;
  background: var(--c-bg3);
}
.breakdown-seg {
  display: inline-block;
  height: 100%;
}
.breakdown-total {
  text-align: right;
  font-variant-numeric: tabular-nums;
}
.num {
  font-variant-numeric: tabular-nums;
  text-align: right;
}
.breakdown-legend {
  display: flex;
  flex-wrap: wrap;
  gap: 0.6rem;
  margin-top: 0.6rem;
  font-size: 0.78rem;
}
.legend-chip {
  display: inline-flex;
  align-items: center;
  gap: 0.3rem;
  padding: 0.1rem 0.4rem;
  border: 1px solid var(--c-border-soft);
  border-radius: 999px;
}
.legend-chip .dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  display: inline-block;
}
.speed-class-rows {
  list-style: none;
  margin: 0.3rem 0 0.6rem;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 0.3rem;
}
.speed-class-rows li {
  display: grid;
  grid-template-columns: 12px 8rem 1fr auto;
  gap: 0.6rem;
  align-items: baseline;
  font-size: 0.9rem;
}
.speed-class-rows .dot {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  display: inline-block;
}
.speed-class-label {
  font-weight: 500;
}
.speed-class-mpg {
  font-variant-numeric: tabular-nums;
}
.small {
  font-size: 0.78rem;
}
.ts {
  font-variant-numeric: tabular-nums;
}
</style>
