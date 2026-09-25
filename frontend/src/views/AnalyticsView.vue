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
import type { AnalyticsWindow } from "@/api/types";
import {
  fmtMpg,
  fmtMoney,
  fmtInt,
  fmtDistance,
  fmtPricePerVolume,
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
// Shared window chips, synced to ?window=.
const WINDOW_OPTIONS = [
  { value: "month" as const, label: "30 days" },
  { value: "3m" as const, label: "3 months" },
  { value: "year" as const, label: "12 months" },
  { value: "all" as const, label: "All time" },
];
const window = useQueryParam<AnalyticsWindow>("window", "all", ["month", "3m", "year", "all"]);
const PAL = chartPalette();

// Time range for aggregate calls
const fromIso = computed(() => {
  const now = Date.now();
  let ms: number;
  switch (window.value) {
    case "month":
      ms = 30 * 24 * 3600 * 1000;
      break;
    case "3m":
      ms = 90 * 24 * 3600 * 1000;
      break;
    case "year":
      ms = 365 * 24 * 3600 * 1000;
      break;
    default:
      return undefined;
  }
  return new Date(now - ms).toISOString();
});

const mpgQ = useAsync(
  () =>
    vehicleId.value
      ? api.mpgTrend(vehicleId.value, window.value)
      : Promise.resolve({ points: [] }),
  [vehicleId, window],
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

// MPG line chart. Split into a stable opts computed and a data computed so a
// pure data refresh (vehicle switch, window change) hits UPlotChart's cheap
// setData() path instead of a full destroy()+rebuild. Opts still recomputes
// when the EPA reference line's presence/value changes (rare — vehicle switch).
const epaMpg = computed(() => vehicles.selectedVehicle?.epa_mpg_combined ?? null);
const mpgOpts = computed<uPlot.Options>(() => {
  // EPA reference line (Task #90). Constant value across the window
  // when the vehicle has an epa_mpg_combined set; rendered as a
  // dashed grey line under the actual-MPG primary so the user can
  // see how their real-world economy compares to the sticker.
  const epa = epaMpg.value;
  const series: uPlot.Series[] = [
    {},
    { label: economyUnitLabel() === "mpg" ? "MPG" : economyUnitLabel(), stroke: PAL[0], width: 2 },
  ];
  if (epa != null) {
    series.push({
      label: `EPA combined (${fmtMpg(epa)})`,
      stroke: "rgba(154,160,170,0.65)",
      width: 1,
      dash: [4, 3],
    });
  }
  return {
    width: 600,
    height: 220,
    scales: { x: { time: true } },
    axes: [{}, { label: economyUnitLabel() }],
    series,
  };
});
const mpgData = computed<uPlot.AlignedData | null>(() => {
  const points = mpgQ.data.value?.points ?? [];
  if (points.length === 0) return null;
  const t = points.map((p) => Math.round((Date.parse(p.period) || 0) / 1000));
  const y = points.map((p) => (p.mpg != null ? convEconomyMpg(p.mpg) : null));
  // The per-point EPA column belongs to the aligned data; it's only present
  // when the vehicle has a sticker value. Series count in mpgOpts matches.
  const epa = epaMpg.value;
  const epaCol = epa != null ? t.map(() => convEconomyMpg(epa)) : null;
  return epaCol ? [t, y, epaCol] : [t, y];
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
const hoursData = computed<uPlot.AlignedData | null>(() => {
  const points = hoursQ.data.value?.points ?? [];
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

// Long-term fuel trim drift (Task #89). LTFT trending up over
// months signals the ECU has learned to add fuel — vacuum leak,
// weak O2 sensor, clogging air filter, etc.
const trimQ = useAsync(
  () =>
    vehicleId.value
      ? api.getFuelTrimHistory(vehicleId.value, 180)
      : Promise.resolve(null as api.FuelTrimResponse | null),
  [vehicleId],
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

// Cost breakdown (Task #92). Per-month spend by category for a
// stacked bar — fuel, maintenance, registration, etc.
const breakdownQ = useAsync(
  () =>
    vehicleId.value
      ? api.getCostBreakdown(vehicleId.value, 12)
      : Promise.resolve(null as api.CostBreakdown | null),
  [vehicleId],
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
function speedClassColor(label: string): string {
  return label === "Highway" ? "var(--c-info)"
    : label === "Mixed" ? "var(--c-success)"
    : "var(--c-warn)";
}
/** Class cutoffs (35 / 55 mph) in the display unit. */
const speedClassText = computed(() => {
  const u = speedUnitLabel();
  const [lo, hi] = u === "mph" ? [35, 55] : [56, 89];
  return `Highway ≥ ${hi} ${u}, Mixed ${lo}–${hi} ${u}, City < ${lo} ${u}`;
});
const breakdownTotal = computed(() =>
  Object.values(breakdownQ.data.value?.summary ?? {}).reduce((a, b) => a + b, 0),
);
const volSrc = computed(() => vehicleVolUnit(vehicles.selectedVehicle));
/** Odometer ignores 30d/3m (the backend's shortest window is a year). */
const odoFixedTag = computed(() =>
  window.value === "month" || window.value === "3m" ? "fixed: 12 mo" : null,
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
// window — translate from the page's AnalyticsWindow on the way in.
const odoWindow = computed<"year" | "3y" | "all">(() =>
  window.value === "year" ? "year"
  : window.value === "all" ? "all"
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
  const pts = odoQ.data.value?.points ?? [];
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
        <section class="card">
          <h3>Economy trend</h3>
          <StateCard v-if="mpgQ.loading.value" state="loading" bare />
          <StateCard v-else-if="mpgQ.error.value" state="error" bare :message="mpgQ.error.value" @retry="mpgQ.reload()" />
          <StateCard v-else-if="!mpgData" state="empty" bare title="No fillups in window." />
          <UPlotChart v-else :data="mpgData" :options="mpgOpts" />
          <p class="muted small" v-if="mpgQ.data.value?.points.length">
            Latest: {{ fmtMpg(mpgQ.data.value.points[mpgQ.data.value.points.length - 1].mpg) }}
          </p>
        </section>

        <section class="card">
          <h3>Engine RPM (daily avg)</h3>
          <StateCard v-if="rpmQ.loading.value" state="loading" bare />
          <StateCard v-else-if="rpmQ.error.value" state="error" bare :message="rpmQ.error.value" @retry="rpmQ.reload()" />
          <StateCard v-else-if="!rpmData" state="empty" bare title="No readings." />
          <UPlotChart v-else :data="rpmData" :options="rpmOpts" />
        </section>

        <section class="card">
          <h3>Coolant temperature (daily avg)</h3>
          <StateCard v-if="tempCoolantQ.loading.value" state="loading" bare />
          <StateCard v-else-if="tempCoolantQ.error.value" state="error" bare :message="tempCoolantQ.error.value" @retry="tempCoolantQ.reload()" />
          <StateCard v-else-if="!tempData" state="empty" bare title="No temperature readings." />
          <UPlotChart v-else :data="tempData" :options="tempOpts" />
        </section>

        <section v-if="hoursData" class="card">
          <header class="head-inline">
            <h3>Engine hours vs distance <span class="tag-fixed">fixed: all time</span></h3>
            <span v-if="hoursQ.data.value" class="muted small">
              {{ fmtInt(hoursQ.data.value.total_hours) }} hrs total
            </span>
          </header>
          <UPlotChart :data="hoursData" :options="hoursOpts" />
        </section>

        <section v-if="trimData && trimOpts" class="card">
          <header class="head-inline">
            <h3>Fuel trim drift <span class="tag-fixed">fixed: 180d</span></h3>
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
            <h3>Cost by category <span class="tag-fixed">fixed: 12 mo</span></h3>
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
              <span class="speed-class-label">{{ c.class }}</span>
              <span class="num speed-class-mpg">{{ fmtMpg(c.avg_mpg) }}</span>
              <span class="muted small">{{ c.trip_count }} trip{{ c.trip_count === 1 ? "" : "s" }}</span>
            </li>
          </ul>
          <p class="muted small">
            Trips classified by average speed: {{ speedClassText }}. OBD-derived economy averaged per class.
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
            <h3>Odometer history <span v-if="odoFixedTag" class="tag-fixed">{{ odoFixedTag }}</span></h3>
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
  grid-template-columns: 12px 5rem 1fr auto;
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
