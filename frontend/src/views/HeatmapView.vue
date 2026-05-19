<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import maplibregl from "maplibre-gl";
import "maplibre-gl/dist/maplibre-gl.css";
import { useVehiclesStore } from "@/stores/vehicles";
import { getHeatmap, type HeatmapMetric, type HeatmapResponse } from "@/api/endpoints";

const vehicles = useVehiclesStore();
const vehicleId = computed(() => vehicles.selectedVehicleId);
const metric = ref<HeatmapMetric>("density");

const data = ref<HeatmapResponse | null>(null);
const loading = ref(false);
const error = ref<string | null>(null);

// Carto Dark Matter raster basemap — same source the trip-detail map
// uses, no API key. Default to dark since a heatmap reads best on a
// muted basemap. A light toggle is provided.
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
    data.value = await getHeatmap(vehicleId.value, metric.value);
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
  const points = data.value.points;
  if (!points.length) {
    // No data — make sure the previous layer is removed if present.
    if (map.getLayer("heat")) map.removeLayer("heat");
    if (map.getSource("heat")) map.removeSource("heat");
    return;
  }

  // Compute weight bounds so we can normalise into [0, 1].
  // For density, the long tail of low counts gets crushed by a single
  // big value; use the 95th percentile instead of max so the colour
  // ramp covers the routine commute, not just the rare hotspot.
  const weights = points.map((p) => p[2]).filter((w) => Number.isFinite(w));
  weights.sort((a, b) => a - b);
  const p95 = weights[Math.floor(weights.length * 0.95)] || 1;

  const features = {
    type: "FeatureCollection" as const,
    features: points.map((p, i) => ({
      type: "Feature" as const,
      id: i,
      properties: { weight: Math.min(1, p[2] / p95) },
      geometry: { type: "Point" as const, coordinates: [p[1], p[0]] },
    })),
  };

  const src = map.getSource("heat") as maplibregl.GeoJSONSource | undefined;
  if (src) {
    src.setData(features as unknown as GeoJSON.FeatureCollection);
  } else {
    map.addSource("heat", { type: "geojson", data: features as unknown as GeoJSON.FeatureCollection });
    map.addLayer({
      id: "heat",
      type: "heatmap",
      source: "heat",
      paint: {
        // 0..1 weight per feature → scale into the heatmap engine
        "heatmap-weight": ["get", "weight"],
        "heatmap-intensity": [
          "interpolate", ["linear"], ["zoom"],
          // boost intensity at high zoom so individual cells light up
          // when you're zoomed in close.
          8, 1, 16, 3,
        ],
        "heatmap-radius": [
          "interpolate", ["linear"], ["zoom"],
          8, 6, 12, 14, 16, 26,
        ],
        "heatmap-opacity": 0.85,
        "heatmap-color": [
          "interpolate", ["linear"], ["heatmap-density"],
          0, "rgba(0,0,0,0)",
          0.2, "rgba(34,197,94,0.55)",   // green — cold
          0.45, "rgba(250,204,21,0.7)",  // amber — warm
          0.7, "rgba(249,115,22,0.85)",  // orange
          1, "rgba(239,68,68,1)",        // red — hot
        ],
      },
    });
  }

  // Fit bounds to the data the first time we get results.
  if (!fitted) {
    let minLng = +Infinity, minLat = +Infinity, maxLng = -Infinity, maxLat = -Infinity;
    for (const [lat, lon] of points) {
      if (lat < minLat) minLat = lat;
      if (lat > maxLat) maxLat = lat;
      if (lon < minLng) minLng = lon;
      if (lon > maxLng) maxLng = lon;
    }
    if (Number.isFinite(minLat) && Number.isFinite(minLng)) {
      map.fitBounds([[minLng, minLat], [maxLng, maxLat]], { padding: 40, animate: false });
      fitted = true;
    }
  }
}

let fitted = false;

onMounted(() => {
  if (!root.value) return;
  map = new maplibregl.Map({
    container: root.value,
    style: darkMode.value ? DARK_STYLE : LIGHT_STYLE,
    center: [-95.7, 37.1],   // continental US center — replaced by fitBounds
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

watch([vehicleId, metric], () => {
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
          <button
            type="button"
            :class="{ active: metric === 'density' }"
            @click="metric = 'density'"
          >Density</button>
          <button
            type="button"
            :class="{ active: metric === 'speed' }"
            @click="metric = 'speed'"
          >Speed</button>
        </div>
        <label class="dark">
          <input type="checkbox" :checked="darkMode" @change="(e) => setDarkMode((e.target as HTMLInputElement).checked)" />
          Dark map
        </label>
        <span v-if="data" class="muted small">
          {{ data.count.toLocaleString() }} cells
        </span>
        <span v-if="loading" class="muted small">loading…</span>
        <span v-if="error" class="muted small" style="color: var(--danger);">err: {{ error }}</span>
      </div>
    </header>
    <div ref="root" class="map"></div>
    <p class="muted small caption">
      Grid-aggregated to ~11 m cells (4 decimal lat/lon).
      <span v-if="metric === 'density'">Color intensity = how often you've driven through each cell (95th-percentile normalised).</span>
      <span v-else>Color intensity = average speed through each cell (m/s).</span>
    </p>
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
.caption {
  margin: 0;
}
.small {
  font-size: 0.85rem;
}
</style>
