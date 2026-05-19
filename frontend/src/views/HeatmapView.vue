<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import maplibregl from "maplibre-gl";
import "maplibre-gl/dist/maplibre-gl.css";
import { useVehiclesStore } from "@/stores/vehicles";
import { getRouteTrace, type RouteTraceResponse } from "@/api/endpoints";

type Mode = "density" | "speed";

const vehicles = useVehiclesStore();
const vehicleId = computed(() => vehicles.selectedVehicleId);
const mode = ref<Mode>("density");

const data = ref<RouteTraceResponse | null>(null);
const loading = ref(false);
const error = ref<string | null>(null);

const DARK_STYLE = {
  version: 8,
  sources: {
    dark: {
      type: "raster",
      tiles: [
        "https://a.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png",
        "https://b.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png",
        "https://c.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png",
        "https://d.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png",
      ],
      tileSize: 256,
      attribution: "&copy; OpenStreetMap contributors &copy; CARTO",
      maxzoom: 19,
    },
  },
  layers: [{ id: "dark", type: "raster", source: "dark" }],
} as unknown as maplibregl.StyleSpecification;

const LIGHT_STYLE = {
  version: 8,
  sources: {
    osm: {
      type: "raster",
      tiles: ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
      tileSize: 256,
      attribution: "&copy; OpenStreetMap contributors",
      maxzoom: 19,
    },
  },
  layers: [{ id: "osm", type: "raster", source: "osm" }],
} as unknown as maplibregl.StyleSpecification;

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
function densityColor(count: number): string {
  if (count <= 1) return "#475569";   // slate — rare (1 fix)
  if (count <= 3) return "#06b6d4";   // cyan — occasional
  if (count <= 8) return "#22c55e";   // green — regular
  if (count <= 20) return "#eab308";  // yellow — frequent
  if (count <= 50) return "#f97316";  // orange — commute
  return "#ef4444";                   // red — heavy
}

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
    data.value = await getRouteTrace(vehicleId.value, 25000);
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
  const points = data.value.points;
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
    map.addSource("trace", { type: "geojson", data: fc });
    map.addLayer({
      id: "trace",
      type: "line",
      source: "trace",
      layout: { "line-join": "round", "line-cap": "round" },
      paint: {
        // Initial colour overridden immediately by applyMode below.
        "line-color": ["get", "densityColor"],
        "line-opacity": 0.78,
        "line-width": [
          "interpolate", ["linear"], ["zoom"],
          10, 1.2, 14, 2.5, 16, 4.0,
        ],
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
  const attr = mode.value === "density" ? "densityColor" : "speedColor";
  map.setPaintProperty(
    "trace",
    "line-color",
    ["get", attr] as unknown as maplibregl.ExpressionSpecification,
  );
  // Density tiers are discrete and self-explanatory; speed is a hue
  // ramp where overlap can mislead. Both get the same opacity for
  // consistency — higher than the previous density-overlap trick.
  map.setPaintProperty("trace", "line-opacity", 0.78);
}

watch(mode, applyMode);

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
</script>

<template>
  <div class="heatmap-view">
    <header class="head">
      <h1>Heatmap</h1>
      <div class="controls">
        <div class="toggle">
          <button type="button" :class="{ active: mode === 'density' }" @click="mode = 'density'">Density</button>
          <button type="button" :class="{ active: mode === 'speed' }" @click="mode = 'speed'">Speed</button>
        </div>
        <label class="dark">
          <input type="checkbox" :checked="darkMode"
                 @change="(e) => setDarkMode((e.target as HTMLInputElement).checked)" />
          Dark map
        </label>
        <span v-if="data" class="muted small">
          {{ data.count.toLocaleString() }} of {{ data.total.toLocaleString() }} points
          <span v-if="data.stride > 1">(every {{ data.stride }})</span>
        </span>
        <span v-if="loading" class="muted small">loading…</span>
        <span v-if="error" class="muted small" style="color: var(--danger);">err: {{ error }}</span>
      </div>
    </header>
    <div ref="root" class="map"></div>
    <div class="legend-row">
      <div v-if="mode === 'speed'" class="legend">
        <span class="muted small">slow</span>
        <span class="ramp ramp-speed"></span>
        <span class="muted small">fast (~80 mph)</span>
      </div>
      <div v-else class="legend">
        <span class="muted small">1×</span>
        <span class="swatch" style="background:#475569"></span>
        <span class="swatch" style="background:#06b6d4"></span>
        <span class="muted small">3</span>
        <span class="swatch" style="background:#22c55e"></span>
        <span class="muted small">8</span>
        <span class="swatch" style="background:#eab308"></span>
        <span class="muted small">20</span>
        <span class="swatch" style="background:#f97316"></span>
        <span class="muted small">50</span>
        <span class="swatch" style="background:#ef4444"></span>
        <span class="muted small">50+ visits</span>
      </div>
      <p class="muted small caption">
        Each pair of consecutive GPS fixes is one polyline segment.
        <span v-if="mode === 'density'">
          Segment colour = how many GPS fixes fell in the same ~11 m cell. Slate / cyan are rare; orange / red are commute corridors.
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
  border: 1px solid var(--border, #2a2d33);
  border-radius: 8px;
  overflow: hidden;
}
.toggle button {
  background: transparent;
  color: var(--text);
  border: 0;
  padding: 6px 12px;
  cursor: pointer;
  font-size: 0.9rem;
}
.toggle button.active {
  background: var(--accent, #f97316);
  color: #fff;
}
.dark {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 0.9rem;
  color: var(--muted, #9aa0aa);
  cursor: pointer;
}
.map {
  flex: 1;
  min-height: 520px;
  border-radius: 12px;
  overflow: hidden;
  border: 1px solid var(--border, #2a2d33);
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
.ramp-density {
  background: linear-gradient(to right,
    rgba(249,115,22,0.05), rgba(249,115,22,0.4), rgba(249,115,22,0.85), #f97316);
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
