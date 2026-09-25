<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import maplibregl from "maplibre-gl";
import "maplibre-gl/dist/maplibre-gl.css";
import { useVehiclesStore } from "@/stores/vehicles";
import { getRouteTrace, listTrips, type RouteTraceResponse } from "@/api/endpoints";
import type { AnalyticsWindow } from "@/api/types";
import { DARK_STYLE, LIGHT_STYLE } from "@/lib/mapStyles";
import StateCard from "@/components/StateCard.vue";
import WindowChips from "@/components/WindowChips.vue";
import { useQueryParam } from "@/composables/useQueryParam";
import { fmtInt, fmtSpeedKph } from "@/composables/useFormat";

type Mode = "density" | "speed" | "single";

const MODE_KEY = "pitstop_heatmap_mode";
function loadMode(): Mode {
  const v = localStorage.getItem(MODE_KEY);
  return v === "speed" || v === "single" || v === "density" ? v : "density";
}

const vehicles = useVehiclesStore();
const vehicleId = computed(() => vehicles.selectedVehicleId);
const mode = ref<Mode>(loadMode());

const data = ref<RouteTraceResponse | null>(null);
const loading = ref(false);
const error = ref<string | null>(null);

const MAP_DARK_KEY = "pitstop_heatmap_dark";
const darkMode = ref<boolean>(localStorage.getItem(MAP_DARK_KEY) !== "false");

const root = ref<HTMLDivElement | null>(null);
let map: maplibregl.Map | null = null;
let fitted = false;

// Matches trip-detail's speedColor — continuous HSL hue from red (0)
// to magenta (300) across 0–36 m/s (0–80 mph). Stopping uses a neutral
// blue so the user can tell stopped-at-light from cold-route.
const HEATMAP_MAX_MPS = 36;
function speedColor(speedMps: number): string {
  if (!Number.isFinite(speedMps) || speedMps < 0) return "#2f81f7";
  const clamped = Math.min(speedMps, HEATMAP_MAX_MPS);
  const t = clamped / HEATMAP_MAX_MPS;
  const hue = t * 300;
  return `hsl(${hue.toFixed(0)}, 80%, 50%)`;
}

// Discrete tiers for visit-count density. Each polyline segment is
// coloured by how many GPS fixes landed in the same ~11m cell as the
// segment-start. Heavy commute corridors hit the high tiers; one-off
// detours sit at the bottom.
// MapLibre paint needs concrete colours; the legend renders from this
// same table so the two can't drift.
const DENSITY_TIERS: { max: number; color: string; label: string }[] = [
  { max: 1, color: "#475569", label: "1×" },          // slate — rare
  { max: 3, color: "#06b6d4", label: "≤3" },          // cyan — occasional
  { max: 8, color: "#22c55e", label: "≤8" },          // green — regular
  { max: 20, color: "#eab308", label: "≤20" },        // yellow — frequent
  { max: 50, color: "#f97316", label: "≤50" },        // orange — commute
  { max: Infinity, color: "#ef4444", label: "50+" },  // red — heavy
];
function densityColor(count: number): string {
  return (DENSITY_TIERS.find((t) => count <= t.max) ?? DENSITY_TIERS[DENSITY_TIERS.length - 1]).color;
}

// ── Filters: time window + trip purpose (both client-side) ───────────
// route-trace has no window / purpose params, but each point carries its
// epoch second, so a window is a timestamp cut and a purpose is "inside a
// trip tagged X". Both round-trip through the URL.
const WINDOW_OPTIONS = [
  { value: "month" as const, label: "30 days" },
  { value: "3m" as const, label: "3 months" },
  { value: "year" as const, label: "12 months" },
  { value: "all" as const, label: "All time" },
];
const windowSel = useQueryParam<AnalyticsWindow>("window", "all", ["month", "3m", "year", "all"]);
const purpose = useQueryParam<string>("purpose", "all");
const cutoffS = computed<number | null>(() => {
  const days = { month: 30, "3m": 90, year: 365, all: 0 }[windowSel.value];
  return days ? Math.floor(Date.now() / 1000) - days * 86_400 : null;
});
interface TripSpan { start: number; end: number; category: string }
const tripSpans = ref<TripSpan[]>([]);
async function fetchTrips() {
  tripSpans.value = [];
  const vid = vehicleId.value;
  if (!vid) return;
  const out: TripSpan[] = [];
  // Up to 2,000 trips in 500-row pages — enough for every purpose chip.
  for (let offset = 0; offset < 2000; offset += 500) {
    const r = await listTrips({ vehicle_id: vid, limit: 500, offset });
    for (const t of r.items) {
      const start = Math.floor(Date.parse(t.started_at) / 1000);
      const end = t.ended_at ? Math.ceil(Date.parse(t.ended_at) / 1000) : start + (t.duration_s ?? 0);
      out.push({ start, end, category: t.category?.trim() || "" });
    }
    if (offset + 500 >= r.total) break;
  }
  if (vid === vehicleId.value) tripSpans.value = out;
}
const purposes = computed<string[]>(() => {
  const set = new Set<string>();
  for (const t of tripSpans.value) if (t.category) set.add(t.category);
  return Array.from(set).sort();
});
const PURPOSE_OPTIONS = computed(() => [
  { value: "all", label: "All purposes" },
  ...purposes.value.map((p) => ({ value: p, label: p })),
  ...(tripSpans.value.some((t) => !t.category) ? [{ value: "__untagged", label: "Untagged" }] : []),
]);
/** Spans of the selected purpose, sorted by start, for binary search. */
const purposeSpans = computed<TripSpan[] | null>(() => {
  if (purpose.value === "all") return null;
  const want = purpose.value === "__untagged" ? "" : purpose.value;
  return tripSpans.value.filter((t) => t.category === want).sort((x, y) => x.start - y.start);
});
function inPurpose(ts: number, spans: TripSpan[]): boolean {
  let lo = 0;
  let hi = spans.length - 1;
  let hit = -1;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    if (spans[mid].start <= ts) {
      hit = mid;
      lo = mid + 1;
    } else hi = mid - 1;
  }
  return hit >= 0 && ts <= spans[hit].end;
}
const filteredPoints = computed<[number, number, number, number][]>(() => {
  const pts = data.value?.points ?? [];
  const cut = cutoffS.value;
  const spans = purposeSpans.value;
  if (cut == null && spans == null) return pts;
  return pts.filter((p) => (cut == null || p[3] >= cut) && (spans == null || inPurpose(p[3], spans)));
});
const speedLegendMax = computed(() => fmtSpeedKph(HEATMAP_MAX_MPS * 3.6));

// Single-colour mode: every trip painted the same, so the map reads as
// "where have I driven" without any per-segment encoding competing for
// attention. Orange survives both basemaps.
const SINGLE_COLOR = "#f97316";

// Widths and opacity are zoom-ramped with a floor so a fully zoomed-out
// map still shows solid routes instead of sub-pixel hairlines that
// alpha-blend into the basemap.
const LINE_WIDTH = [
  "interpolate", ["linear"], ["zoom"],
  4, 1.8, 9, 2.0, 12, 2.4, 14, 2.8, 16, 4.0,
] as unknown as maplibregl.ExpressionSpecification;
const LINE_OPACITY = [
  "interpolate", ["linear"], ["zoom"],
  6, 1.0, 11, 0.9, 14, 0.78,
] as unknown as maplibregl.ExpressionSpecification;

// Round lat/lon to 4 decimals (~11 m cells) for visit-count keying.
function cellKey(lat: number, lon: number): string {
  return `${lat.toFixed(4)}|${lon.toFixed(4)}`;
}

function setDarkMode(v: boolean) {
  darkMode.value = v;
  try { localStorage.setItem(MAP_DARK_KEY, String(v)); } catch { /* ignore */ }
  if (!map) return;
  map.setStyle(v ? DARK_STYLE : LIGHT_STYLE);
  map.once("style.load", applyData);
}

async function fetchData() {
  if (!vehicleId.value) return;
  loading.value = true;
  error.value = null;
  try {
    const [trace] = await Promise.all([
      getRouteTrace(vehicleId.value, 25000),
      fetchTrips().catch(() => {
        /* purpose chips just stay empty */
      }),
    ]);
    data.value = trace;
  } catch (e) {
    error.value = (e as Error).message ?? "fetch failed";
    data.value = null;
  } finally {
    loading.value = false;
  }
  applyData();
}

function applyData() {
  if (!map || !data.value) return;
  // Break the ordered point stream into segments where consecutive
  // timestamps are >30 s apart (= new trip / engine off / pause).
  // Build one short LineString feature per consecutive pair so each
  // segment can carry its own color (one for speed, one for density).
  const points = filteredPoints.value;
  const MAX_GAP_S = 30;

  // Pre-pass: count visits per ~11 m cell so density mode can colour
  // each segment by how often the user has driven through it. With
  // stride-downsampled data this slightly under-counts in absolute
  // terms but the relative tiers stay correct.
  const counts = new Map<string, number>();
  for (const p of points) {
    const k = cellKey(p[0], p[1]);
    counts.set(k, (counts.get(k) ?? 0) + 1);
  }

  const features: GeoJSON.Feature[] = [];
  for (let i = 0; i < points.length - 1; i++) {
    const a = points[i];
    const b = points[i + 1];
    if (b[3] - a[3] > MAX_GAP_S) continue;  // gap → new trip
    const visitCount = counts.get(cellKey(a[0], a[1])) ?? 1;
    features.push({
      type: "Feature",
      properties: {
        // Two colour attributes — the active one swaps on toggle.
        speedColor: speedColor(a[2]),
        densityColor: densityColor(visitCount),
        visits: visitCount,
      },
      geometry: {
        type: "LineString",
        coordinates: [[a[1], a[0]], [b[1], b[0]]],
      },
    });
  }
  const fc: GeoJSON.FeatureCollection = { type: "FeatureCollection", features };

  const src = map.getSource("trace") as maplibregl.GeoJSONSource | undefined;
  const hasLayer = !!map.getLayer("trace");
  if (src && hasLayer) {
    src.setData(fc);
  } else {
    // After setStyle(), MapLibre's default diff=true keeps custom
    // sources alive but wipes layers — so a source can exist without
    // a layer. Drop whichever survived and rebuild both cleanly.
    if (map.getLayer("trace")) map.removeLayer("trace");
    if (map.getSource("trace")) map.removeSource("trace");
    map.addSource("trace", {
      type: "geojson",
      data: fc,
      // Each feature is a single 2-point segment. geojson-vt drops any
      // line whose tile-space length is under the simplification
      // tolerance, so with the default (0.375) every segment vanished
      // as you zoomed out and the traces faded to nothing. 0 disables
      // simplification — the point stream is already server-strided.
      tolerance: 0,
    });
    map.addLayer({
      id: "trace",
      type: "line",
      source: "trace",
      layout: { "line-join": "round", "line-cap": "round" },
      paint: {
        // Initial colour overridden immediately by applyMode below.
        "line-color": ["get", "densityColor"],
        "line-opacity": LINE_OPACITY,
        "line-width": LINE_WIDTH,
      },
    });
  }
  // Switch the paint between modes without re-adding the layer.
  applyMode();

  if (!fitted && points.length) {
    let minLng = +Infinity, minLat = +Infinity, maxLng = -Infinity, maxLat = -Infinity;
    for (const p of points) {
      if (p[0] < minLat) minLat = p[0];
      if (p[0] > maxLat) maxLat = p[0];
      if (p[1] < minLng) minLng = p[1];
      if (p[1] > maxLng) maxLng = p[1];
    }
    if (Number.isFinite(minLat)) {
      map.fitBounds([[minLng, minLat], [maxLng, maxLat]], { padding: 40, animate: false });
      fitted = true;
    }
  }
}

function applyMode() {
  if (!map?.getLayer("trace")) return;
  // Single mode paints a constant; the other two read the per-segment
  // colour baked into feature properties, so switching is a paint swap
  // with no re-tiling.
  const color: maplibregl.ExpressionSpecification | string =
    mode.value === "single"
      ? SINGLE_COLOR
      : (["get", mode.value === "density" ? "densityColor" : "speedColor"] as unknown as maplibregl.ExpressionSpecification);
  map.setPaintProperty("trace", "line-color", color);
  map.setPaintProperty("trace", "line-opacity", LINE_OPACITY);
  map.setPaintProperty("trace", "line-width", LINE_WIDTH);
}

watch(mode, (m) => {
  try { localStorage.setItem(MODE_KEY, m); } catch { /* ignore */ }
  applyMode();
});

onMounted(() => {
  if (!root.value) return;
  map = new maplibregl.Map({
    container: root.value,
    style: darkMode.value ? DARK_STYLE : LIGHT_STYLE,
    center: [-95.7, 37.1],
    zoom: 3,
    attributionControl: { compact: true },
  });
  map.addControl(new maplibregl.NavigationControl({ showCompass: false }), "top-right");
  map.once("load", fetchData);
});

onBeforeUnmount(() => {
  map?.remove();
  map = null;
});

watch(vehicleId, () => {
  fitted = false;
  fetchData();
});
watch([windowSel, purpose], () => {
  fitted = false;
  applyData();
});
</script>

<template>
  <div class="heatmap-view">
    <header class="head">
      <h1>Map</h1>
      <div class="controls">
        <div class="toggle" role="group" aria-label="Colour mode">
          <button type="button" :aria-pressed="mode === 'density'" :class="{ active: mode === 'density' }" @click="mode = 'density'">Density</button>
          <button type="button" :aria-pressed="mode === 'speed'" :class="{ active: mode === 'speed' }" @click="mode = 'speed'">Speed</button>
          <button type="button" :aria-pressed="mode === 'single'" :class="{ active: mode === 'single' }" @click="mode = 'single'">Single</button>
        </div>
        <label class="dark">
          <input type="checkbox" :checked="darkMode"
                 @change="(e) => setDarkMode((e.target as HTMLInputElement).checked)" />
          Dark map
        </label>
        <span v-if="data" class="muted small num">
          {{ fmtInt(filteredPoints.length) }} of {{ fmtInt(data.total) }} points
          <span v-if="data.stride > 1">(every {{ data.stride }})</span>
        </span>
        <span v-if="loading" class="muted small" role="status">Loading…</span>
      </div>
    </header>
    <div class="filters">
      <WindowChips v-model="windowSel" :options="WINDOW_OPTIONS" />
      <WindowChips
        v-if="PURPOSE_OPTIONS.length > 1"
        v-model="purpose"
        :options="PURPOSE_OPTIONS"
        label="Trip purpose"
      />
    </div>
    <StateCard
      v-if="error"
      state="error"
      title="Couldn't load the route trace"
      :message="error"
      @retry="fetchData"
    />
    <div ref="root" class="map" role="region" aria-label="Driven routes map"></div>
    <div class="legend-row">
      <div v-if="mode === 'speed'" class="legend">
        <span class="muted small">0</span>
        <span class="ramp ramp-speed" aria-hidden="true"></span>
        <span class="muted small">{{ speedLegendMax }}+</span>
      </div>
      <div v-else-if="mode === 'single'" class="legend">
        <span class="swatch" :style="{ background: SINGLE_COLOR }"></span>
        <span class="muted small">all trips, one colour</span>
      </div>
      <div v-else class="legend">
        <template v-for="t in DENSITY_TIERS" :key="t.label">
          <span class="swatch" :style="{ background: t.color }"></span>
          <span class="muted small">{{ t.label }}</span>
        </template>
        <span class="muted small">visits</span>
      </div>
      <p class="muted small caption">
        Each pair of consecutive GPS fixes is one polyline segment.
        <span v-if="mode === 'density'">
          Segment colour = how many GPS fixes fell in the same ~11 m cell. Slate / cyan are rare; orange / red are commute corridors.
        </span>
        <span v-else-if="mode === 'single'">
          Every trip painted the same colour — overlap alone shows which roads get driven most.
        </span>
        <span v-else>
          Per-segment colour from continuous speed (matches the trip-detail map).
        </span>
      </p>
    </div>
  </div>
</template>

<style scoped>
.heatmap-view {
  display: flex;
  flex-direction: column;
  height: 100%;
  gap: 8px;
}
.head {
  display: flex;
  align-items: baseline;
  gap: 16px;
  flex-wrap: wrap;
}
.head h1 {
  margin: 0;
  font-size: 1.4rem;
}
.controls {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.toggle {
  display: inline-flex;
  border: 1px solid var(--c-line1);
  border-radius: var(--r-md);
  overflow: hidden;
}
.toggle button {
  background: transparent;
  color: var(--c-ink2);
  border: 0;
  border-radius: 0;
  padding: 6px 12px;
  cursor: pointer;
  font-size: 0.9rem;
}
.toggle button.active {
  background: var(--c-accent-soft);
  color: var(--c-ink0);
}
.dark {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 0.9rem;
  color: var(--c-ink2);
  cursor: pointer;
}
.filters {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem 1.2rem;
}
.map {
  flex: 1;
  min-height: 520px;
  border-radius: var(--r-lg);
  overflow: hidden;
  border: 1px solid var(--c-line1);
}
@media (max-width: 700px) {
  .map {
    min-height: 60vh;
  }
  .ramp {
    width: 140px;
  }
}
.legend-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 16px;
}
.legend {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.ramp {
  display: inline-block;
  width: 220px;
  height: 10px;
  border-radius: 4px;
}
.ramp-speed {
  /* HSL hue 0..300 across 0..36 m/s, matching speedColor() */
  background: linear-gradient(
    to right,
    hsl(0, 80%, 50%), hsl(60, 80%, 50%), hsl(120, 80%, 50%),
    hsl(180, 80%, 50%), hsl(240, 80%, 50%), hsl(300, 80%, 50%)
  );
}
.swatch {
  display: inline-block;
  width: 14px;
  height: 14px;
  border-radius: 3px;
}
.caption {
  margin: 0;
}
.small {
  font-size: 0.85rem;
}
</style>
