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
const window = ref<AnalyticsWindow>("year");

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
  const aligned: uPlot.AlignedData = [t, y];
  const opts: uPlot.Options = {
    width: 600,
    height: 220,
    scales: { x: { time: true } },
    axes: [{ stroke: "#9aa0aa" }, { stroke: "#9aa0aa", label: "mpg" }],
    series: [{}, { label: "MPG", stroke: "#2f81f7", width: 2 }],
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
