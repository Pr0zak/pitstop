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

// Series definitions keyed on metric name. Each defines its display
// label, stroke color, axis scale, and a transform from canonical SI
// units to the display unit (kph→mph, °C→°F, etc).
interface TripSeries {
  metric: string;
  label: string;
  stroke: string;
  scale: string;
  axisLabel: string;
  transform: (v: number) => number;
  defaultVisible: boolean;
}
const TRIP_SERIES: TripSeries[] = [
  { metric: "vehicle_speed",          label: "Speed (mph)",      stroke: "#2f81f7", scale: "speed",  axisLabel: "mph",   transform: (v) => v * 0.621371, defaultVisible: true },
  { metric: "engine_rpm",             label: "RPM",              stroke: "#3fb950", scale: "rpm",    axisLabel: "rpm",   transform: (v) => v,            defaultVisible: true },
  { metric: "coolant_temp",           label: "Coolant (°F)",     stroke: "#d29922", scale: "temp",   axisLabel: "°F",    transform: (v) => (v * 9) / 5 + 32, defaultVisible: true },
  { metric: "throttle_position",      label: "Throttle (%)",     stroke: "#a78bfa", scale: "pct",    axisLabel: "%",     transform: (v) => v,            defaultVisible: false },
  { metric: "engine_load",            label: "Load (%)",         stroke: "#ec4899", scale: "pct",    axisLabel: "%",     transform: (v) => v,            defaultVisible: false },
  { metric: "manifold_pressure",      label: "MAP (kPa)",        stroke: "#06b6d4", scale: "kpa",    axisLabel: "kPa",   transform: (v) => v,            defaultVisible: false },
  { metric: "maf_air_flow",           label: "MAF (g/s)",        stroke: "#14b8a6", scale: "maf",    axisLabel: "g/s",   transform: (v) => v,            defaultVisible: false },
  { metric: "fuel_level",             label: "Fuel (%)",         stroke: "#f97316", scale: "pct",    axisLabel: "%",     transform: (v) => v,            defaultVisible: false },
  { metric: "control_module_voltage", label: "Battery (V)",      stroke: "#facc15", scale: "volt",   axisLabel: "V",     transform: (v) => v,            defaultVisible: false },
  { metric: "intake_air_temp",        label: "Intake (°F)",      stroke: "#94a3b8", scale: "temp",   axisLabel: "°F",    transform: (v) => (v * 9) / 5 + 32, defaultVisible: false },
  { metric: "engine_oil_temp",        label: "Oil (°F)",         stroke: "#f87171", scale: "temp",   axisLabel: "°F",    transform: (v) => (v * 9) / 5 + 32, defaultVisible: false },
  { metric: "atf_temp_f",             label: "ATF (°F)",         stroke: "#dc2626", scale: "temp",   axisLabel: "°F",    transform: (v) => v,            defaultVisible: false },
];

// Persisted visibility selection — survives reload + revisit.
const SERIES_VIS_KEY = "pitstop_trip_series_visible";
function loadSeriesVis(): Record<string, boolean> {
  try {
    const raw = localStorage.getItem(SERIES_VIS_KEY);
    if (raw) return JSON.parse(raw);
  } catch { /* ignore */ }
  return Object.fromEntries(TRIP_SERIES.map((s) => [s.metric, s.defaultVisible]));
}
const seriesVisible = ref<Record<string, boolean>>(loadSeriesVis());
watch(seriesVisible, (v) => {
  try { localStorage.setItem(SERIES_VIS_KEY, JSON.stringify(v)); } catch { /* ignore */ }
}, { deep: true });

const chart = computed<ChartData>(() => {
  if (!trip.value || !trip.value.samples || trip.value.samples.length === 0) return null;
  // Pivot long-form samples ({time, metric, value_num}) to wide form
  // keyed on bucket time. Backwards-compat: handle the old wide-form
  // shape too in case an older API response sneaks in.
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
    if (s.vehicle_speed != null) slot["vehicle_speed"] = s.vehicle_speed;
    if (s.engine_rpm != null) slot["engine_rpm"] = s.engine_rpm;
    if (s.coolant_temp != null) slot["coolant_temp"] = s.coolant_temp;
    buckets.set(ts, slot);
  }
  const sortedTs = Array.from(buckets.keys()).sort((a, b) => a - b);
  const visible = TRIP_SERIES.filter((s) => seriesVisible.value[s.metric]);
  if (visible.length === 0) return null;
  const t: number[] = [];
  const arrays = visible.map(() => [] as (number | null)[]);
  for (const ts of sortedTs) {
    const slot = buckets.get(ts)!;
    t.push(ts);
    visible.forEach((s, i) => {
      const v = slot[s.metric];
      arrays[i].push(v == null ? null : s.transform(v));
    });
  }
  const aligned: uPlot.AlignedData = [t, ...arrays] as uPlot.AlignedData;
  // Build the scales object: every distinct scale used by visible series.
  const scales: Record<string, { time?: boolean }> = { x: { time: true } };
  for (const s of visible) scales[s.scale] = {};
  // Axes — first is x; then one per *unique* scale, alternating sides.
  const axisScalesSeen = new Set<string>();
  const axes: uPlot.Axis[] = [{ stroke: "#9aa0aa" }];
  let side = 0; // 0 = left (3 for top, but we want bottom-default), 1 = right
  for (const s of visible) {
    if (axisScalesSeen.has(s.scale)) continue;
    axisScalesSeen.add(s.scale);
    axes.push({
      scale: s.scale,
      stroke: "#9aa0aa",
      label: s.axisLabel,
      side: side === 0 ? 3 : 1,
      grid: { show: side === 0 },
    });
    side = 1 - side;
  }
  // Vertical rules for each DTC fire event during the trip window
  // (Task #110). Rendered as a uPlot hooks plugin so we don't have
  // to fight the series shape — we just paint over the chart at the
  // x-pixel for each event's seen_at.
  const dtcMarkers: { ts: number; code: string }[] = (trip.value.dtcs ?? [])
    .map((d) => ({ ts: Math.round(Date.parse(d.seen_at) / 1000), code: d.code }))
    .filter((d) => Number.isFinite(d.ts));
  const dtcPlugin: uPlot.Plugin | null = dtcMarkers.length
    ? {
        hooks: {
          draw: (u) => {
            const ctx = u.ctx;
            ctx.save();
            ctx.strokeStyle = "#ef4444";
            ctx.fillStyle = "#ef4444";
            ctx.lineWidth = 1;
            ctx.font = "11px ui-sans-serif";
            for (const m of dtcMarkers) {
              const x = u.valToPos(m.ts, "x", true);
              if (x < u.bbox.left || x > u.bbox.left + u.bbox.width) continue;
              ctx.beginPath();
              ctx.moveTo(x, u.bbox.top);
              ctx.lineTo(x, u.bbox.top + u.bbox.height);
              ctx.stroke();
              ctx.fillText(m.code, x + 4, u.bbox.top + 12);
            }
            ctx.restore();
          },
        },
      }
    : null;
  const opts: uPlot.Options = {
    width: 800,
    height: 320,
    cursor: { drag: { x: true, y: false, setScale: true } },
    scales,
    series: [
      {},
      ...visible.map((s) => ({
        label: s.label,
        stroke: s.stroke,
        scale: s.scale,
        width: 1.4,
      })),
    ],
    axes,
    ...(dtcPlugin ? { plugins: [dtcPlugin] } : {}),
  };
  return { aligned, opts };
});

const odoDelta = computed<number | null>(() => {
  const t = trip.value;
  if (!t || t.odo_start_km == null || t.odo_end_km == null) return null;
  const d = t.odo_end_km - t.odo_start_km;
  // Reject obviously-wrong deltas (engine-off readings can return the
  // *previous* trip's value when the ECU hasn't logged yet, producing
  // a negative or 100x-too-large delta).
  if (d < 0 || d > 1000) return null;
  return d;
});

function fmtOdoMi(km: number | null): string {
  if (km == null) return "—";
  return (km * 0.621371).toFixed(0);
}
function fmtOdoDeltaMi(km: number | null): string {
  if (km == null) return "—";
  return `+${(km * 0.621371).toFixed(1)} mi`;
}

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

// Stop list (Task #113): consecutive GPS points where speed_mps < 1
// for ≥30 s. Reveals red-light wait times, brief errands, garage
// stops. Centroid is the median of the run's lat/lon.
interface TripStop { started_at: string; duration_s: number; lat: number; lon: number }
const stops = computed<TripStop[]>(() => {
  const points = routeData.value?.points;
  if (!points || points.length < 2) return [];
  const out: TripStop[] = [];
  let runStart = -1;
  for (let i = 0; i <= points.length; i++) {
    const stopped = i < points.length && (points[i].speed_mps ?? 0) < 1;
    if (stopped && runStart < 0) {
      runStart = i;
    } else if (!stopped && runStart >= 0) {
      const startP = points[runStart];
      const endP = points[i - 1];
      const dur = (Date.parse(endP.t) - Date.parse(startP.t)) / 1000;
      if (dur >= 30) {
        const mid = points[runStart + Math.floor((i - 1 - runStart) / 2)];
        out.push({
          started_at: startP.t,
          duration_s: Math.round(dur),
          lat: mid.lat,
          lon: mid.lon,
        });
      }
      runStart = -1;
    }
  }
  return out;
});

// Time-of-day badge (Task #113). Driven entirely by trip.started_at —
// hour-of-week categorisation. Useful context for MPG analysis ("rush
// hour drives average X mpg vs off-peak Y").
function todBadge(startedAt?: string | null): { label: string; tone: string } | null {
  if (!startedAt) return null;
  const d = new Date(startedAt);
  if (Number.isNaN(d.getTime())) return null;
  const dow = d.getDay();      // 0 Sun .. 6 Sat
  const hr = d.getHours();
  const isWeekend = dow === 0 || dow === 6;
  if (hr >= 22 || hr < 5) return { label: "Late night", tone: "tone-night" };
  if (isWeekend) {
    if (hr < 11) return { label: "Weekend morning", tone: "tone-weekend" };
    if (hr < 17) return { label: "Weekend afternoon", tone: "tone-weekend" };
    return { label: "Weekend evening", tone: "tone-weekend" };
  }
  if (hr >= 7 && hr < 9) return { label: "Morning rush", tone: "tone-rush" };
  if (hr >= 16 && hr < 19) return { label: "Evening rush", tone: "tone-rush" };
  return { label: "Off-peak", tone: "tone-offpeak" };
}
const tripBadge = computed(() => (trip.value ? todBadge(trip.value.started_at) : null));

function fmtClock(iso?: string | null): string {
  if (!iso) return "—";
  const d = new Date(iso);
  return d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
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
        <span v-if="tripBadge" class="tod-badge" :class="tripBadge.tone">
          {{ tripBadge.label }}
        </span>
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
            <div class="series-chips">
              <button
                v-for="s in TRIP_SERIES"
                :key="s.metric"
                class="chip"
                :class="{ active: seriesVisible[s.metric] }"
                :style="seriesVisible[s.metric] ? { borderColor: s.stroke, color: s.stroke } : {}"
                type="button"
                @click="seriesVisible[s.metric] = !seriesVisible[s.metric]"
              >{{ s.label.replace(/ \(.*\)/, '') }}</button>
            </div>
            <div v-if="!chart" class="muted">No metrics selected (or no samples in this trip).</div>
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

          <div v-if="stops.length" class="card">
            <header class="chart-head">
              <h3>Stops</h3>
              <span class="muted small">
                {{ stops.length }} stop{{ stops.length === 1 ? "" : "s" }}
                ≥30s · total
                {{ fmtBucketSeconds(stops.reduce((a, s) => a + s.duration_s, 0)) }}
              </span>
            </header>
            <ul class="stops">
              <li v-for="(s, i) in stops" :key="i">
                <span class="num">{{ fmtClock(s.started_at) }}</span>
                <span class="dot" style="background:#ef4444"></span>
                <span>{{ fmtBucketSeconds(s.duration_s) }} stop</span>
                <a
                  class="muted small"
                  :href="`https://www.openstreetmap.org/?mlat=${s.lat}&mlon=${s.lon}&zoom=17`"
                  target="_blank"
                  rel="noopener"
                >
                  {{ s.lat.toFixed(4) }}, {{ s.lon.toFixed(4) }}
                </a>
              </li>
            </ul>
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
              <dt v-if="odoDelta != null">Odometer Δ</dt>
              <dd v-if="odoDelta != null">
                <span class="num">{{ fmtOdoDeltaMi(odoDelta) }}</span>
                <span class="muted small">
                  · {{ fmtOdoMi(trip.odo_start_km ?? null) }}
                  → {{ fmtOdoMi(trip.odo_end_km ?? null) }} mi
                </span>
              </dd>
              <dt>DTCs</dt>
              <dd>
                <span>{{ trip.dtc_count ?? trip.dtcs?.length ?? 0 }}</span>
                <ul v-if="trip.dtcs?.length" class="dtc-inline">
                  <li v-for="d in trip.dtcs" :key="d.id">
                    <code>{{ d.code }}</code>
                    <span v-if="d.description" class="muted small">
                      — {{ d.description }}
                    </span>
                  </li>
                </ul>
              </dd>
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
              <input
                v-model="categoryDraft"
                list="trip-category-suggestions"
                placeholder="Commute, Errands, Road trip, …"
              />
              <datalist id="trip-category-suggestions">
                <option value="Commute" />
                <option value="Errands" />
                <option value="Road trip" />
                <option value="Work" />
                <option value="Leisure" />
                <option value="Personal" />
              </datalist>
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
.tod-badge {
  display: inline-flex;
  align-items: center;
  padding: 0.05rem 0.55rem;
  border-radius: 999px;
  font-size: 0.78rem;
  font-weight: 500;
  border: 1px solid var(--c-border-soft);
  background: var(--c-surface-soft);
  color: var(--c-muted);
  margin-left: 0.6rem;
}
.tod-badge.tone-rush {
  border-color: #f59e0b66;
  background: #f59e0b22;
  color: #f59e0b;
}
.tod-badge.tone-night {
  border-color: #6366f166;
  background: #6366f122;
  color: #818cf8;
}
.tod-badge.tone-weekend {
  border-color: #22c55e66;
  background: #22c55e22;
  color: #22c55e;
}
.tod-badge.tone-offpeak {
  border-color: #2f81f766;
  background: #2f81f722;
  color: #2f81f7;
}
.stops {
  list-style: none;
  margin: 0.3rem 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 0.3rem;
}
.stops li {
  display: grid;
  grid-template-columns: 4rem auto 1fr auto;
  gap: 0.6rem;
  align-items: center;
  font-size: 0.88rem;
}
.stops a {
  color: var(--c-muted);
  text-decoration: none;
}
.stops a:hover {
  color: var(--c-text);
  text-decoration: underline;
}
.dtc-inline {
  list-style: none;
  margin: 0.3rem 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 0.15rem;
}
.dtc-inline code {
  background: var(--c-surface-soft);
  padding: 0 0.3rem;
  border-radius: 3px;
  color: #ef4444;
  font-size: 0.78rem;
}
.num {
  font-variant-numeric: tabular-nums;
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
.series-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 0.4rem;
  margin: 0.4rem 0 0.6rem 0;
}
.series-chips .chip {
  padding: 0.28rem 0.6rem;
  border-radius: 999px;
  border: 1px solid var(--c-border-soft);
  background: var(--c-surface);
  color: var(--c-muted);
  font-size: 0.78rem;
  cursor: pointer;
}
.series-chips .chip:hover {
  background: var(--c-surface-2);
}
.series-chips .chip.active {
  background: var(--c-surface-2);
  border-width: 1.5px;
}
</style>
