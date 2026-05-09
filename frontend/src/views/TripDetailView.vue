<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useRoute, RouterLink } from "vue-router";
import { useAsync } from "@/composables/useAsync";
import * as api from "@/api/endpoints";
import type uPlot from "uplot";
import UPlotChart from "@/components/charts/UPlotChart.vue";
import MapLibreMap from "@/components/charts/MapLibreMap.vue";
import {
  fmtDateTime,
  fmtDuration,
  fmtMiles,
  fmtSpeed,
  fmtRpm,
  fmtGallons,
  fmtTemp,
} from "@/composables/useFormat";
import { ChevronLeft, RefreshCw } from "lucide-vue-next";

const route = useRoute();
const tripId = computed(() => String(route.params.id ?? ""));

const { data: trip, loading, error, reload } = useAsync(
  () => api.getTrip(tripId.value),
  [tripId],
);
// Route polyline now comes from /trips/{id}/route which queries the
// dedicated gps_points hypertable. The legacy `samples.gps_lat/gps_lon`
// path is kept for backwards-compat with trips ingested before v0.1.78.
const { data: routeData } = useAsync(
  () => api.getTripRoute(tripId.value),
  [tripId],
);

// Build uPlot data + options from samples.
type ChartData = { aligned: uPlot.AlignedData; opts: uPlot.Options } | null;

const chart = computed<ChartData>(() => {
  if (!trip.value || !trip.value.samples || trip.value.samples.length === 0) return null;
  const samples = trip.value.samples;
  const t: number[] = [];
  const speed: (number | null)[] = [];
  const rpm: (number | null)[] = [];
  const coolant: (number | null)[] = [];
  for (const s of samples) {
    t.push(Math.round((Date.parse(s.time) || 0) / 1000));
    speed.push(s.vehicle_speed ?? null);
    rpm.push(s.engine_rpm ?? null);
    coolant.push(s.coolant_temp ?? null);
  }
  const aligned: uPlot.AlignedData = [t, speed, rpm, coolant];
  const opts: uPlot.Options = {
    width: 800,
    height: 320,
    cursor: { drag: { x: true, y: false, setScale: true } },
    scales: {
      x: { time: true },
      speed: {},
      rpm: {},
      temp: {},
    },
    series: [
      {},
      {
        label: "Speed (mph)",
        stroke: "#2f81f7",
        scale: "speed",
        width: 1.5,
      },
      {
        label: "RPM",
        stroke: "#3fb950",
        scale: "rpm",
        width: 1.5,
      },
      {
        label: "Coolant (°F)",
        stroke: "#d29922",
        scale: "temp",
        width: 1,
        dash: [4, 3],
      },
    ],
    axes: [
      { stroke: "var(--c-muted)" },
      { scale: "speed", stroke: "var(--c-muted)", label: "mph" },
      { scale: "rpm", side: 1, stroke: "var(--c-muted)", label: "rpm" },
      { scale: "temp", side: 1, stroke: "var(--c-muted)", grid: { show: false } },
    ],
  };
  return { aligned, opts };
});

const route2D = computed<[number, number][]>(() => {
  // Prefer gps_points (richer + accurate); fall back to legacy
  // samples.gps_lat/gps_lon for trips from before v0.1.78.
  if (routeData.value?.points?.length) {
    return routeData.value.points.map((p) => [p.lon, p.lat] as [number, number]);
  }
  if (!trip.value?.samples) return [];
  return trip.value.samples
    .filter((s) => s.gps_lat != null && s.gps_lon != null)
    .map((s) => [s.gps_lon as number, s.gps_lat as number]);
});

let chartRef: uPlot | null = null;
function onChartReady(c: uPlot) {
  chartRef = c;
}
function resetZoom() {
  if (!chartRef) return;
  const [t] = chartRef.data;
  if (!t || t.length === 0) return;
  chartRef.setScale("x", { min: t[0] as number, max: t[t.length - 1] as number });
}

// Editable inline notes/category.
const editingNotes = ref(false);
const notesDraft = ref("");
const categoryDraft = ref("");
const savingMeta = ref(false);
const metaError = ref<string | null>(null);

watch(trip, (t) => {
  if (t) {
    notesDraft.value = t.notes ?? "";
    categoryDraft.value = t.category ?? "";
  }
});

async function saveMeta() {
  if (!trip.value) return;
  savingMeta.value = true;
  metaError.value = null;
  try {
    await api.updateTrip(trip.value.id, {
      notes: notesDraft.value.trim() || null,
      category: categoryDraft.value.trim() || null,
    });
    editingNotes.value = false;
    await reload();
  } catch (e: unknown) {
    metaError.value = e instanceof Error ? e.message : "save failed";
  } finally {
    savingMeta.value = false;
  }
}
</script>

<template>
  <div class="trip-detail">
    <header class="head">
      <div class="left">
        <RouterLink to="/trips" class="back"><ChevronLeft :size="14" /> Trips</RouterLink>
        <h1 v-if="trip">{{ fmtDateTime(trip.started_at) }}</h1>
        <h1 v-else>Trip</h1>
      </div>
      <button class="ghost" type="button" @click="reload"><RefreshCw :size="14" /></button>
    </header>

    <div v-if="loading && !trip" class="card">
      <p class="muted">Loading trip…</p>
    </div>
    <div v-else-if="error" class="card">
      <p class="muted">Failed to load: {{ error }}</p>
    </div>
    <template v-else-if="trip">
      <div class="layout">
        <section class="main-col">
          <div class="card">
            <header class="chart-head">
              <h3>Timeline</h3>
              <button class="ghost" type="button" @click="resetZoom">Reset zoom</button>
            </header>
            <div v-if="!chart" class="muted">No samples in this trip.</div>
            <UPlotChart
              v-else
              :data="chart.aligned"
              :options="chart.opts"
              @ready="onChartReady"
            />
          </div>

          <div class="card">
            <header class="chart-head">
              <h3>Route</h3>
              <span v-if="routeData?.points?.length" class="muted small">
                {{ routeData.points.length }} GPS points
              </span>
            </header>
            <div v-if="route2D.length === 0" class="muted">
              No GPS data captured for this trip.
            </div>
            <MapLibreMap v-else :route="route2D" :height="360" />
          </div>
        </section>

        <aside class="side-col">
          <div class="card">
            <h3>Stats</h3>
            <dl class="stats">
              <dt>Duration</dt>
              <dd>{{ fmtDuration(trip.duration_s) }}</dd>
              <dt>Distance</dt>
              <dd>{{ fmtMiles(trip.distance_mi) }}</dd>
              <dt>Max speed</dt>
              <dd>{{ fmtSpeed(trip.max_speed) }}</dd>
              <dt>Max RPM</dt>
              <dd>{{ fmtRpm(trip.max_rpm) }}</dd>
              <dt>Fuel used</dt>
              <dd>{{ fmtGallons(trip.fuel_used) }}</dd>
              <dt>DTCs</dt>
              <dd>{{ trip.dtc_count ?? 0 }}</dd>
              <dt>Started</dt>
              <dd>{{ fmtDateTime(trip.started_at) }}</dd>
              <dt>Ended</dt>
              <dd>{{ fmtDateTime(trip.ended_at) }}</dd>
            </dl>
          </div>

          <div class="card">
            <h3>Notes &amp; category</h3>
            <label>
              Category
              <input v-model="categoryDraft" placeholder="Private, Work, …" />
            </label>
            <label>
              Notes
              <textarea v-model="notesDraft" rows="4" />
            </label>
            <p v-if="metaError" class="error">{{ metaError }}</p>
            <button
              class="primary"
              type="button"
              :disabled="savingMeta"
              @click="saveMeta"
            >
              {{ savingMeta ? "Saving…" : "Save" }}
            </button>
          </div>

          <div v-if="trip.samples?.length" class="card">
            <h3>Quick stats</h3>
            <dl class="stats">
              <dt>Samples</dt>
              <dd>{{ trip.samples.length }}</dd>
              <dt>Coolant peak</dt>
              <dd>
                {{
                  fmtTemp(
                    trip.samples.reduce(
                      (m, s) => Math.max(m, s.coolant_temp ?? -Infinity),
                      -Infinity,
                    ) === -Infinity
                      ? null
                      : trip.samples.reduce(
                          (m, s) => Math.max(m, s.coolant_temp ?? -Infinity),
                          -Infinity,
                        ),
                  )
                }}
              </dd>
            </dl>
          </div>
        </aside>
      </div>
    </template>
  </div>
</template>

<style scoped>
.trip-detail {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}
.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.head .left {
  display: flex;
  align-items: center;
  gap: 0.7rem;
}
.back {
  display: inline-flex;
  align-items: center;
  gap: 0.2rem;
  color: var(--c-muted);
}
.layout {
  display: grid;
  grid-template-columns: 1fr 320px;
  gap: 1rem;
  align-items: start;
}
.main-col,
.side-col {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}
.chart-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.stats {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0.4rem 0.6rem;
  margin: 0;
}
.stats dt {
  color: var(--c-muted);
  font-size: 0.78rem;
  text-transform: uppercase;
  letter-spacing: 0.05em;
}
.stats dd {
  margin: 0;
  font-weight: 500;
}
.card label {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  font-size: 0.85rem;
  color: var(--c-muted);
  margin-bottom: 0.6rem;
}
.error {
  color: var(--c-danger);
}
@media (max-width: 900px) {
  .layout {
    grid-template-columns: 1fr;
  }
}
</style>
