<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useVehiclesStore } from "@/stores/vehicles";
import { useAsync } from "@/composables/useAsync";
import * as api from "@/api/endpoints";
import type uPlot from "uplot";
import UPlotChart from "@/components/charts/UPlotChart.vue";
import type { AnalyticsWindow } from "@/api/types";
import { fmtDateTime, fmtMpg, fmtRelative } from "@/composables/useFormat";

const vehicles = useVehiclesStore();
const vehicleId = computed(() => vehicles.selectedVehicleId);
const window = ref<AnalyticsWindow>("all");

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
const tempOilQ = useAsync(
  () =>
    vehicleId.value
      ? api.aggregateReadings({
          vehicle_id: vehicleId.value,
          metric: "oil_temp",
          from: fromIso.value,
          bucket: "day",
        })
      : Promise.resolve([]),
  [vehicleId, window],
);
const tempAtfQ = useAsync(
  () =>
    vehicleId.value
      ? api.aggregateReadings({
          vehicle_id: vehicleId.value,
          metric: "atf_temp_f",
          from: fromIso.value,
          bucket: "day",
        })
      : Promise.resolve([]),
  [vehicleId, window],
);

const dtcsQ = useAsync(
  () =>
    vehicleId.value
      ? api.listDtcs(vehicleId.value, false)
      : Promise.resolve([]),
  [vehicleId],
);

watch(vehicleId, () => {
  void mpgQ.reload();
  void rpmQ.reload();
  void tempCoolantQ.reload();
  void tempOilQ.reload();
  void tempAtfQ.reload();
  void dtcsQ.reload();
});

// MPG line chart
const mpgChart = computed(() => {
  const points = mpgQ.data.value?.points ?? [];
  if (points.length === 0) return null;
  const t = points.map((p) => Math.round((Date.parse(p.period) || 0) / 1000));
  const y = points.map((p) => p.mpg ?? null);
  // EPA reference line (Task #90). Constant value across the window
  // when the vehicle has an epa_mpg_combined set; rendered as a
  // dashed grey line under the actual-MPG primary so the user can
  // see how their real-world economy compares to the sticker.
  const epa = vehicles.selectedVehicle?.epa_mpg_combined ?? null;
  const epaCol = epa != null ? t.map(() => epa) : null;
  const aligned: uPlot.AlignedData = epaCol ? [t, y, epaCol] : [t, y];
  const series: uPlot.Series[] = [
    {},
    { label: "MPG", stroke: "#2f81f7", width: 2 },
  ];
  if (epaCol) {
    series.push({
      label: `EPA combined (${epa})`,
      stroke: "rgba(154,160,170,0.65)",
      width: 1,
      dash: [4, 3],
    });
  }
  const opts: uPlot.Options = {
    width: 600,
    height: 220,
    scales: { x: { time: true } },
    axes: [{ stroke: "#9aa0aa" }, { stroke: "#9aa0aa", label: "mpg" }],
    series,
  };
  return { aligned, opts };
});

// RPM histogram (avg per bucket → bar series via paths.bars)
const rpmChart = computed(() => {
  const data = rpmQ.data.value ?? [];
  if (data.length === 0) return null;
  const t = data.map((d) => Math.round((Date.parse(d.bucket) || 0) / 1000));
  const y = data.map((d) => d.avg ?? null);
  const aligned: uPlot.AlignedData = [t, y];
  const opts: uPlot.Options = {
    width: 600,
    height: 220,
    scales: { x: { time: true } },
    axes: [{ stroke: "#9aa0aa" }, { stroke: "#9aa0aa", label: "avg rpm" }],
    series: [
      {},
      {
        label: "Avg RPM",
        stroke: "#3fb950",
        width: 1.5,
        fill: "rgba(63, 185, 80, 0.18)",
      },
    ],
  };
  return { aligned, opts };
});

// Temp distribution (3 series)
const tempChart = computed(() => {
  const series: { points: typeof tempCoolantQ.data.value; label: string; color: string }[] =
    [
      { points: tempCoolantQ.data.value, label: "Coolant", color: "#d29922" },
      { points: tempOilQ.data.value, label: "Oil", color: "#f85149" },
      { points: tempAtfQ.data.value, label: "ATF", color: "#2f81f7" },
    ];
  const all = series.filter((s) => s.points && s.points.length > 0);
  if (all.length === 0) return null;
  // Build a unified time axis from the union of buckets (assumes daily alignment).
  const tsSet = new Set<number>();
  for (const s of all) {
    for (const p of s.points!) {
      tsSet.add(Math.round((Date.parse(p.bucket) || 0) / 1000));
    }
  }
  const ts = Array.from(tsSet).sort((a, b) => a - b);
  const tsIdx = new Map<number, number>();
  ts.forEach((t, i) => tsIdx.set(t, i));
  const cols: uPlot.AlignedData = [ts];
  const seriesDefs: uPlot.Series[] = [{}];
  for (const s of all) {
    const col: (number | null)[] = new Array(ts.length).fill(null);
    for (const p of s.points!) {
      const i = tsIdx.get(Math.round((Date.parse(p.bucket) || 0) / 1000));
      if (i != null) col[i] = p.avg ?? null;
    }
    cols.push(col);
    seriesDefs.push({ label: s.label, stroke: s.color, width: 1.5 });
  }
  const opts: uPlot.Options = {
    width: 600,
    height: 220,
    scales: { x: { time: true } },
    axes: [{ stroke: "#9aa0aa" }, { stroke: "#9aa0aa", label: "°F" }],
    series: seriesDefs,
  };
  return { aligned: cols, opts };
});

// Hard accel/brake events from IMU (Task #95).
const hardQ = useAsync(
  () =>
    vehicleId.value
      ? api.getHardEvents(vehicleId.value, 90)
      : Promise.resolve(null as api.HardEvents | null),
  [vehicleId],
);
const hardSpark = computed<{ aligned: uPlot.AlignedData; opts: uPlot.Options } | null>(() => {
  const pts = hardQ.data.value?.series ?? [];
  if (pts.length < 2) return null;
  const t = pts.map((p) => Math.round((Date.parse(p.date) || 0) / 1000));
  const y = pts.map((p) => p.count);
  const opts: uPlot.Options = {
    width: 600,
    height: 160,
    scales: { x: { time: true } },
    axes: [{ stroke: "#9aa0aa" }, { stroke: "#9aa0aa", label: "events/day" }],
    series: [
      {},
      { label: "Hard events", stroke: "#ef4444", width: 1.4, fill: "rgba(239,68,68,0.18)" },
    ],
  };
  return { aligned: [t, y], opts };
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
const hoursChart = computed<{ aligned: uPlot.AlignedData; opts: uPlot.Options } | null>(() => {
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
      const mi = p.cumulative_km / 1.609344;
      ratio.push(mi > 0 ? (p.cumulative_hours / mi) * 100 : null);
    } else {
      ratio.push(null);
    }
  }
  const opts: uPlot.Options = {
    width: 600,
    height: 220,
    scales: { x: { time: true }, hrs: {}, ratio: {} },
    axes: [
      { stroke: "#9aa0aa" },
      { scale: "hrs", stroke: "#9aa0aa", label: "engine hours", side: 3 },
      { scale: "ratio", stroke: "#9aa0aa", label: "hrs / 100 mi", side: 1, grid: { show: false } },
    ],
    series: [
      {},
      { label: "Cum. engine hrs", scale: "hrs", stroke: "#3fb950", width: 1.6 },
      { label: "Hrs / 100 mi", scale: "ratio", stroke: "#a78bfa", width: 1.4, dash: [4, 3] },
    ],
  };
  return { aligned: [t, hrs, ratio] as uPlot.AlignedData, opts };
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

const trimChart = computed<{ aligned: uPlot.AlignedData; opts: uPlot.Options } | null>(() => {
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
  const SPECS: Array<{ key: string; label: string; stroke: string; dash?: number[] }> = [
    { key: "ltft_b1", label: "LTFT B1", stroke: "#2f81f7" },
    { key: "ltft_b2", label: "LTFT B2", stroke: "#a78bfa" },
    { key: "stft_b1", label: "STFT B1", stroke: "rgba(47,129,247,0.55)", dash: [3, 3] },
    { key: "stft_b2", label: "STFT B2", stroke: "rgba(167,139,250,0.55)", dash: [3, 3] },
  ];
  for (const s of SPECS) {
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
  const aligned: uPlot.AlignedData = [ts, ...cols] as uPlot.AlignedData;
  const opts: uPlot.Options = {
    width: 600,
    height: 220,
    scales: { x: { time: true } },
    axes: [{ stroke: "#9aa0aa" }, { stroke: "#9aa0aa", label: "%" }],
    series: seriesDefs,
  };
  return { aligned, opts };
});

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
  Fuel: "#2f81f7",
  Service: "#f59e0b",
  Maintenance: "#f59e0b",
  Repair: "#ef4444",
  Tires: "#a78bfa",
  Insurance: "#06b6d4",
  Registration: "#3fb950",
  Oil: "#facc15",
  Tax: "#14b8a6",
  Other: "#94a3b8",
};
function categoryColor(name: string): string {
  return CATEGORY_COLORS[name] ?? CATEGORY_COLORS.Other;
}
const breakdownMaxMonth = computed(() => {
  const months = breakdownQ.data.value?.months ?? [];
  return months.length ? Math.max(...months.map((m) => m.total)) : 0;
});

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

const odoChart = computed<{ aligned: uPlot.AlignedData; opts: uPlot.Options } | null>(() => {
  const pts = odoQ.data.value?.points ?? [];
  if (pts.length < 2) return null;
  const t: number[] = [];
  const y: (number | null)[] = [];
  for (const p of pts) {
    const ts = Math.round((Date.parse(p.time) || 0) / 1000);
    t.push(ts);
    // km → mi for display
    y.push(p.odo_km != null ? p.odo_km * 0.621371 : null);
  }
  const opts: uPlot.Options = {
    width: 600,
    height: 220,
    scales: { x: { time: true } },
    axes: [{ stroke: "#9aa0aa" }, { stroke: "#9aa0aa", label: "mi" }],
    series: [
      {},
      { label: "Odometer", stroke: "#2f81f7", width: 1.6, fill: "rgba(47,129,247,0.08)" },
    ],
  };
  return { aligned: [t, y], opts };
});
</script>

<template>
  <div class="analytics">
    <header class="head">
      <h1>Engine analytics</h1>
      <div class="window">
        <button
          v-for="w in (['month', '3m', 'year', 'all'] as AnalyticsWindow[])"
          :key="w"
          type="button"
          class="ghost"
          :class="{ active: window === w }"
          @click="window = w"
        >
          {{ w }}
        </button>
      </div>
    </header>

    <div v-if="!vehicleId" class="card">
      <p class="muted">Select a vehicle.</p>
    </div>
    <template v-else>
      <div class="grid">
        <section class="card">
          <h3>MPG trend</h3>
          <div v-if="mpgQ.loading.value" class="muted">Loading…</div>
          <div v-else-if="!mpgChart" class="muted">No fillups in window.</div>
          <UPlotChart v-else :data="mpgChart.aligned" :options="mpgChart.opts" />
          <p class="muted small" v-if="mpgQ.data.value?.points.length">
            Latest: {{ fmtMpg(mpgQ.data.value.points[mpgQ.data.value.points.length - 1].mpg) }}
          </p>
        </section>

        <section class="card">
          <h3>Engine RPM (daily avg)</h3>
          <div v-if="rpmQ.loading.value" class="muted">Loading…</div>
          <div v-else-if="!rpmChart" class="muted">No readings.</div>
          <UPlotChart v-else :data="rpmChart.aligned" :options="rpmChart.opts" />
        </section>

        <section class="card">
          <h3>Engine temps</h3>
          <div v-if="tempCoolantQ.loading.value" class="muted">Loading…</div>
          <div v-else-if="!tempChart" class="muted">No temperature readings.</div>
          <UPlotChart v-else :data="tempChart.aligned" :options="tempChart.opts" />
        </section>

        <section v-if="hardSpark || (hardQ.data.value && hardQ.data.value.total > 0)" class="card">
          <header class="head-inline">
            <h3>Hard events</h3>
            <span v-if="hardQ.data.value" class="muted small">
              {{ hardQ.data.value.total }} in 90 d
              <span v-if="hardQ.data.value.rate_per_100mi != null">
                · {{ hardQ.data.value.rate_per_100mi.toFixed(1) }} / 100 mi
              </span>
              · |a−g| &gt; {{ hardQ.data.value.threshold_mps2 }} m/s²
            </span>
          </header>
          <UPlotChart v-if="hardSpark" :data="hardSpark.aligned" :options="hardSpark.opts" />
          <p class="muted small">
            Counts each 1-second bucket where |IMU magnitude − 9.81 m/s²| exceeds the
            threshold. Captures hard braking, hard acceleration, sharp cornering, or
            pothole hits regardless of phone orientation.
          </p>
        </section>

        <section v-if="hoursChart" class="card">
          <header class="head-inline">
            <h3>Engine hours vs miles</h3>
            <span v-if="hoursQ.data.value" class="muted small">
              {{ hoursQ.data.value.total_hours.toFixed(0) }} hrs total
              <span v-if="hoursQ.data.value.hrs_per_100mi != null">
                · {{ hoursQ.data.value.hrs_per_100mi.toFixed(2) }} hrs/100 mi
              </span>
            </span>
          </header>
          <UPlotChart :data="hoursChart.aligned" :options="hoursChart.opts" />
        </section>

        <section v-if="trimChart" class="card">
          <header class="head-inline">
            <h3>Fuel trim drift</h3>
            <span class="muted small">180 days · LTFT solid, STFT dashed</span>
          </header>
          <UPlotChart :data="trimChart.aligned" :options="trimChart.opts" />
          <p class="muted small">
            LTFT drifting steadily &gt;+5% suggests vacuum leak, weak O2 sensor, or
            clogging air filter. Steady &lt;-5% suggests a richening fault.
          </p>
        </section>

        <section v-if="breakdownQ.data.value?.months?.length" class="card">
          <header class="head-inline">
            <h3>Annual cost breakdown</h3>
            <span v-if="breakdownQ.data.value!.summary" class="muted small">
              {{ Object.values(breakdownQ.data.value!.summary).reduce((a, b) => a + b, 0).toFixed(0) }}
              total · 12 mo
            </span>
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
                    .map(([k, v]) => `${k}: $${v.toFixed(0)}`)
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
              <span class="breakdown-total num">${{ m.total.toFixed(0) }}</span>
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
              <span class="muted">${{ (breakdownQ.data.value!.summary[cat] ?? 0).toFixed(0) }}</span>
            </span>
          </div>
        </section>

        <section v-if="(gradeQ.data.value?.grades?.length ?? 0) >= 2" class="card">
          <h3>Fuel grade comparison</h3>
          <table class="data grade-table">
            <thead>
              <tr>
                <th>Grade</th>
                <th>Fillups</th>
                <th>Avg MPG</th>
                <th>Avg $/gal</th>
                <th>Total cost</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="g in gradeQ.data.value!.grades" :key="g.grade">
                <td>{{ gradeLabel(g.grade) }}</td>
                <td>{{ g.fillup_count }}</td>
                <td>{{ g.avg_mpg != null ? g.avg_mpg.toFixed(1) : "—" }}</td>
                <td>{{ g.avg_price_per_unit != null ? "$" + g.avg_price_per_unit.toFixed(3) : "—" }}</td>
                <td>${{ g.total_cost.toFixed(2) }}</td>
              </tr>
            </tbody>
          </table>
        </section>

        <section class="card">
          <header class="head-inline">
            <h3>Odometer history</h3>
            <span v-if="odoQ.data.value?.summary.current_mi" class="muted small">
              {{ Math.round(odoQ.data.value.summary.current_mi).toLocaleString() }} mi
              <span v-if="odoQ.data.value.summary.delta_mi != null">
                · +{{ Math.round(odoQ.data.value.summary.delta_mi).toLocaleString() }} in window
              </span>
              <span v-if="odoQ.data.value.summary.miles_per_day != null">
                · {{ odoQ.data.value.summary.miles_per_day.toFixed(1) }} mi/day
              </span>
            </span>
          </header>
          <div v-if="odoQ.loading.value" class="muted">Loading…</div>
          <div v-else-if="!odoChart" class="muted">
            No odometer history in this window.
          </div>
          <UPlotChart v-else :data="odoChart.aligned" :options="odoChart.opts" />
        </section>

        <section class="card">
          <h3>DTC history</h3>
          <div v-if="dtcsQ.loading.value" class="muted">Loading…</div>
          <div v-else-if="!dtcsQ.data.value || dtcsQ.data.value.length === 0" class="muted">
            No DTCs ever recorded.
          </div>
          <ul v-else class="dtc-list">
            <li v-for="d in dtcsQ.data.value" :key="d.id">
              <code>{{ d.code }}</code>
              <span>{{ d.description ?? "—" }}</span>
              <span class="muted small">
                {{ fmtRelative(d.detected_at) }}
                · {{ d.active ? "active" : "cleared" }}
                · <span class="ts">{{ fmtDateTime(d.detected_at) }}</span>
              </span>
            </li>
          </ul>
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
.window {
  display: inline-flex;
  gap: 0.2rem;
  background: var(--c-surface-2);
  border: 1px solid var(--c-border-soft);
  border-radius: var(--r-sm);
  padding: 2px;
}
.window button {
  border: none;
  background: transparent;
  text-transform: capitalize;
  font-size: 0.85rem;
  padding: 0.3rem 0.7rem;
}
.window button.active {
  background: var(--c-accent-soft);
  color: var(--c-accent);
}
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(360px, 1fr));
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
  background: var(--c-surface-soft);
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
.dtc-list {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 0.4rem;
}
.dtc-list li {
  display: grid;
  grid-template-columns: auto 1fr auto;
  gap: 0.5rem;
  align-items: baseline;
  padding: 0.4rem 0;
  border-bottom: 1px solid var(--c-border-soft);
}
.small {
  font-size: 0.78rem;
}
.ts {
  font-variant-numeric: tabular-nums;
}
</style>
