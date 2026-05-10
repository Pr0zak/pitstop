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
  fmtDistanceKm,
  fmtSpeedKph,
  fmtRpm,
  fmtVolumeL,
  fmtTemp,
  fmtTempC,
} from "@/composables/useFormat";
import { WMO_CODE } from "@/api/types";

function weatherEmoji(code: number | null | undefined): string {
  if (code == null) return "—";
  return WMO_CODE[code]?.icon ?? "·";
}
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
  // Backend returns long-form rows: {time, metric, value_num}. Pivot
  // to wide form keyed on bucket time so uPlot can render multi-axis.
  // Each metric maps to its target series array.
  const buckets = new Map<number, Record<string, number | null>>();
  for (const s of trip.value.samples as Array<{
    time: string;
    metric?: string;
    value_num?: number | null;
    vehicle_speed?: number | null;
    engine_rpm?: number | null;
    coolant_temp?: number | null;
  }>) {
    const ts = Math.round((Date.parse(s.time) || 0) / 1000);
    const slot = buckets.get(ts) ?? {};
    if (s.metric && s.value_num !== undefined && s.value_num !== null) {
      slot[s.metric] = s.value_num;
    }
    // Backwards compat: pre-pivot wide-form responses still work.
    if (s.vehicle_speed != null) slot["vehicle_speed"] = s.vehicle_speed;
    if (s.engine_rpm != null) slot["engine_rpm"] = s.engine_rpm;
    if (s.coolant_temp != null) slot["coolant_temp"] = s.coolant_temp;
    buckets.set(ts, slot);
  }
  const sortedTs = Array.from(buckets.keys()).sort((a, b) => a - b);
  const t: number[] = [];
  const speed: (number | null)[] = [];
  const rpm: (number | null)[] = [];
  const coolant: (number | null)[] = [];
  for (const ts of sortedTs) {
    const slot = buckets.get(ts)!;
    t.push(ts);
    // Convert kph→mph if user prefers imperial; backend stores SI.
    const vs = slot["vehicle_speed"];
    speed.push(vs == null ? null : vs * 0.621371);
    rpm.push(slot["engine_rpm"] ?? null);
    const c = slot["coolant_temp"];
    coolant.push(c == null ? null : (c * 9) / 5 + 32);
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
      { stroke: "#9aa0aa" },
      { scale: "speed", stroke: "#9aa0aa", label: "mph" },
      { scale: "rpm", side: 1, stroke: "#9aa0aa", label: "rpm" },
      { scale: "temp", side: 1, stroke: "#9aa0aa", grid: { show: false } },
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

/**
 * Speed-bucketed polyline segments. Each segment is a 2-point
 * LineString colored by the speed at the segment's start.
 *   stopped (<1 m/s)        red    — traffic / parked / lights
 *   city (1-10 m/s, ~2-22mph) amber — surface streets
 *   suburban (10-20, ~22-45) green — main arterials
 *   highway (20+, 45+ mph)    blue  — interstate
 * Falls back to a single blue segment when speed data is absent
 * (legacy trips with no /route endpoint coverage).
 */
function bucketColor(speedMps: number | null): string {
  if (speedMps == null) return "#2f81f7";
  if (speedMps < 1) return "#ef4444";       // red — stopped
  if (speedMps < 10) return "#f59e0b";      // amber — city
  if (speedMps < 20) return "#22c55e";      // green — suburban
  return "#2f81f7";                         // blue — highway
}

const routeSegments = computed<{ coords: [number, number][]; color: string }[]>(() => {
  const points = routeData.value?.points;
  if (!points || points.length < 2) return [];
  const out: { coords: [number, number][]; color: string }[] = [];
  for (let i = 0; i < points.length - 1; i++) {
    out.push({
      coords: [
        [points[i].lon, points[i].lat],
        [points[i + 1].lon, points[i + 1].lat],
      ],
      color: bucketColor(points[i].speed_mps),
    });
  }
  return out;
});

/** Speed distribution: seconds spent in each bucket. Inferred from
 *  consecutive GPS points; assumes ~5s cadence per fix. */
interface SpeedBucket { label: string; color: string; seconds: number }
const speedDistribution = computed<SpeedBucket[]>(() => {
  const points = routeData.value?.points;
  if (!points || points.length < 2) return [];
  const buckets: SpeedBucket[] = [
    { label: "Stopped", color: "#ef4444", seconds: 0 },
    { label: "City", color: "#f59e0b", seconds: 0 },
    { label: "Suburban", color: "#22c55e", seconds: 0 },
    { label: "Highway", color: "#2f81f7", seconds: 0 },
  ];
  for (let i = 1; i < points.length; i++) {
    const dt = (Date.parse(points[i].t) - Date.parse(points[i - 1].t)) / 1000;
    if (!Number.isFinite(dt) || dt <= 0 || dt > 60) continue;
    const sp = points[i - 1].speed_mps ?? 0;
    const idx = sp < 1 ? 0 : sp < 10 ? 1 : sp < 20 ? 2 : 3;
    buckets[idx].seconds += dt;
  }
  return buckets;
});
const speedTotalSeconds = computed(() =>
  speedDistribution.value.reduce((s, b) => s + b.seconds, 0),
);

/** Elevation profile: list of [cumulative_km, alt_m] pairs from
 *  gps_points. Skip if fewer than 4 points have altitude data. */
interface ElevationPoint { km: number; alt_m: number }
const elevationProfile = computed<ElevationPoint[]>(() => {
  const points = routeData.value?.points;
  if (!points || points.length < 4) return [];
  const out: ElevationPoint[] = [];
  let cumKm = 0;
  let prev: typeof points[0] | null = null;
  for (const p of points) {
    if (prev != null) {
      cumKm += haversineKm(prev.lat, prev.lon, p.lat, p.lon);
    }
    if (p.alt_m != null) out.push({ km: cumKm, alt_m: p.alt_m });
    prev = p;
  }
  return out.length >= 4 ? out : [];
});
const elevationStats = computed(() => {
  const pts = elevationProfile.value;
  if (pts.length === 0) return null;
  const alts = pts.map((p) => p.alt_m);
  const min = Math.min(...alts);
  const max = Math.max(...alts);
  let climb = 0;
  for (let i = 1; i < pts.length; i++) {
    const d = pts[i].alt_m - pts[i - 1].alt_m;
    if (d > 0) climb += d;
  }
  return { min, max, climb };
});

function haversineKm(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const r = 6371;
  const dLat = ((lat2 - lat1) * Math.PI) / 180;
  const dLon = ((lon2 - lon1) * Math.PI) / 180;
  const a = Math.sin(dLat / 2) ** 2 +
    Math.cos((lat1 * Math.PI) / 180) * Math.cos((lat2 * Math.PI) / 180) *
    Math.sin(dLon / 2) ** 2;
  return 2 * r * Math.asin(Math.sqrt(a));
}

function fmtBucketSeconds(s: number): string {
  if (s < 60) return `${Math.round(s)}s`;
  const m = Math.round(s / 60);
  return m >= 60 ? `${Math.floor(m / 60)}h ${m % 60}m` : `${m}m`;
}

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
            <template v-else>
              <MapLibreMap
                :route-segments="routeSegments.length ? routeSegments : undefined"
                :route="routeSegments.length ? undefined : route2D"
                :height="360"
              />
              <div v-if="routeSegments.length" class="speed-legend">
                <span class="dot" style="background:#ef4444"></span> stopped
                <span class="dot" style="background:#f59e0b"></span> city
                <span class="dot" style="background:#22c55e"></span> suburban
                <span class="dot" style="background:#2f81f7"></span> highway
              </div>
            </template>
          </div>

          <div v-if="speedTotalSeconds > 0" class="card">
            <h3>Speed distribution</h3>
            <div class="bucket-bar">
              <div
                v-for="b in speedDistribution"
                :key="b.label"
                class="bucket"
                :style="{
                  background: b.color,
                  width: ((b.seconds / speedTotalSeconds) * 100).toFixed(2) + '%',
                }"
                :title="`${b.label} · ${fmtBucketSeconds(b.seconds)}`"
              />
            </div>
            <dl class="bucket-list">
              <template v-for="b in speedDistribution" :key="b.label">
                <dt>
                  <span class="dot" :style="{ background: b.color }"></span>
                  {{ b.label }}
                </dt>
                <dd>
                  {{ fmtBucketSeconds(b.seconds) }}
                  <span class="muted small">
                    ({{ ((b.seconds / speedTotalSeconds) * 100).toFixed(0) }}%)
                  </span>
                </dd>
              </template>
            </dl>
          </div>

          <div v-if="elevationStats" class="card">
            <header class="chart-head">
              <h3>Elevation</h3>
              <span class="muted small">
                {{ elevationStats.min.toFixed(0) }}–{{ elevationStats.max.toFixed(0) }} m
                · climb {{ elevationStats.climb.toFixed(0) }} m
              </span>
            </header>
            <svg
              :viewBox="`0 0 ${elevationProfile.length} 100`"
              preserveAspectRatio="none"
              class="elev-svg"
            >
              <polyline
                :points="elevationProfile.map((p, i) => {
                  const min = elevationStats!.min;
                  const max = elevationStats!.max;
                  const range = Math.max(1, max - min);
                  const y = 100 - ((p.alt_m - min) / range) * 90 - 5;
                  return `${i},${y}`;
                }).join(' ')"
                fill="none"
                stroke="#22c55e"
                stroke-width="1.4"
                vector-effect="non-scaling-stroke"
              />
            </svg>
          </div>
        </section>

        <aside class="side-col">
          <div class="card">
            <h3>Stats</h3>
            <dl class="stats">
              <dt>Duration</dt>
              <dd>{{ fmtDuration(trip.duration_s) }}</dd>
              <dt>Distance</dt>
              <dd>{{ fmtDistanceKm(trip.distance_km ?? null) }}</dd>
              <dt>Max speed</dt>
              <dd>{{ fmtSpeedKph(trip.max_speed_kph ?? null) }}</dd>
              <dt>Max RPM</dt>
              <dd>{{ fmtRpm(trip.max_rpm) }}</dd>
              <dt>Fuel used</dt>
              <dd>{{ fmtVolumeL(trip.fuel_used_l ?? null) }}</dd>
              <dt v-if="trip.weather_temp_c != null">Weather</dt>
              <dd v-if="trip.weather_temp_c != null">
                <span>{{ weatherEmoji(trip.weather_code) }}</span>
                {{ fmtTempC(trip.weather_temp_c) }}
                <span v-if="trip.weather_wind_kph != null" class="muted small">
                  · wind {{ Math.round(trip.weather_wind_kph * 0.621371) }} mph
                </span>
                <span v-if="trip.weather_humidity_pct != null" class="muted small">
                  · {{ trip.weather_humidity_pct }}% rh
                </span>
              </dd>
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
.speed-legend {
  display: flex;
  flex-wrap: wrap;
  gap: 0.8rem;
  margin-top: 0.5rem;
  font-size: 0.78rem;
  color: var(--c-muted);
  align-items: center;
}
.speed-legend .dot,
.bucket-list .dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-right: 0.4em;
  vertical-align: middle;
}
.bucket-bar {
  display: flex;
  width: 100%;
  height: 14px;
  border-radius: 999px;
  overflow: hidden;
  margin-bottom: 0.6rem;
}
.bucket-bar .bucket {
  height: 100%;
}
.bucket-list {
  display: grid;
  grid-template-columns: max-content auto;
  gap: 0.3rem 0.8rem;
  margin: 0;
  font-size: 0.85rem;
}
.bucket-list dt {
  color: var(--c-ink1);
}
.bucket-list dd {
  margin: 0;
  color: var(--c-muted);
}
.elev-svg {
  width: 100%;
  height: 80px;
}
</style>
