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
    if (map.getLayer("heat")) map.removeLayer("heat");
    if (map.getSource("heat")) map.removeSource("heat");
    return;
  }

  // Render each cell as a small colored circle, not a Gaussian-blurred
  // heatmap. The heatmap layer aggregates by density-per-pixel no matter
  // what weight you give it, so speed and density looked identical
  // (everywhere you drive, points overlap enough times to saturate the
  // colour ramp). Circles let the per-cell value drive the colour directly.

  // For density: p95 cap keeps one outlier (driveway = 200+ visits) from
  // washing everything else out.
  const weights = points.map((p) => p[2]).filter((w) => Number.isFinite(w));
  weights.sort((a, b) => a - b);
  const p95 = weights[Math.floor(weights.length * 0.95)] || 1;
  const isSpeed = data.value.metric === "speed";

  const features = {
    type: "FeatureCollection" as const,
    features: points.map((p, i) => ({
      type: "Feature" as const,
      id: i,
      properties: {
        // Density: 0..1 normalised. Speed: raw m/s for direct colour
        // mapping (color stops below use m/s).
        weight: isSpeed ? p[2] : Math.min(1, p[2] / p95),
      },
      geometry: { type: "Point" as const, coordinates: [p[1], p[0]] },
    })),
  };

  const colorExpr = isSpeed
    ? // Speed (m/s → mph rough markers): 0 stopped, 5 (~11 mph) city
      // crawl, 11 (~25 mph) neighborhood, 18 (~40 mph) arterial,
      // 25 (~56 mph) highway threshold, 32 (~72 mph) full highway.
      [
        "interpolate", ["linear"], ["get", "weight"],
        0,  "#475569",   // slate (stopped)
        5,  "#06b6d4",   // cyan
        11, "#22c55e",   // green
        18, "#eab308",   // yellow
        25, "#f97316",   // orange
        32, "#ef4444",   // red
      ]
    : // Density (normalised 0..1): same green-amber-orange-red ramp.
      [
        "interpolate", ["linear"], ["get", "weight"],
        0.0, "#22c55e",
        0.3, "#eab308",
        0.6, "#f97316",
        1.0, "#ef4444",
      ];

  const radiusExpr = isSpeed
    ? // Speed: fixed cell radius — we want the route lines clearly
      // visible without overlap-blur.
      [
        "interpolate", ["linear"], ["zoom"],
        10, 2.0, 14, 3.0, 16, 4.5,
      ]
    : // Density: scale modestly with weight so frequent corridors fatten
      // up and rare segments stay slim.
      [
        "interpolate", ["linear"], ["zoom"],
        10, ["+", 1.5, ["*", 2.5, ["get", "weight"]]],
        14, ["+", 2.5, ["*", 4.0, ["get", "weight"]]],
        16, ["+", 3.5, ["*", 6.0, ["get", "weight"]]],
      ];

  const src = map.getSource("heat") as maplibregl.GeoJSONSource | undefined;
  if (src) {
    src.setData(features as unknown as GeoJSON.FeatureCollection);
    if (map.getLayer("heat")) {
      map.setPaintProperty("heat", "circle-color", colorExpr as unknown as maplibregl.ExpressionSpecification);
      map.setPaintProperty("heat", "circle-radius", radiusExpr as unknown as maplibregl.ExpressionSpecification);
    }
  } else {
    map.addSource("heat", { type: "geojson", data: features as unknown as GeoJSON.FeatureCollection });
    map.addLayer({
      id: "heat",
      type: "circle",
      source: "heat",
      paint: {
        "circle-color": colorExpr as unknown as maplibregl.ExpressionSpecification,
        "circle-radius": radiusExpr as unknown as maplibregl.ExpressionSpecification,
        "circle-opacity": 0.85,
        "circle-stroke-width": 0,
        // Blending so overlapping cells in density brighten the line.
        "circle-blur": 0.05,
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
    <div class="legend-row">
      <div v-if="metric === 'speed'" class="legend">
        <span class="muted small">slower</span>
        <span class="swatch" style="background:#475569"></span>
        <span class="muted small">0</span>
        <span class="swatch" style="background:#06b6d4"></span>
        <span class="muted small">11</span>
        <span class="swatch" style="background:#22c55e"></span>
        <span class="muted small">25</span>
        <span class="swatch" style="background:#eab308"></span>
        <span class="muted small">40</span>
        <span class="swatch" style="background:#f97316"></span>
        <span class="muted small">55</span>
        <span class="swatch" style="background:#ef4444"></span>
        <span class="muted small">72 mph</span>
      </div>
      <div v-else class="legend">
        <span class="muted small">rare</span>
        <span class="swatch" style="background:#22c55e"></span>
        <span class="swatch" style="background:#eab308"></span>
        <span class="swatch" style="background:#f97316"></span>
        <span class="swatch" style="background:#ef4444"></span>
        <span class="muted small">most-driven</span>
      </div>
      <p class="muted small caption">
        Grid-aggregated to ~11 m cells (4 decimal lat/lon).
        <span v-if="metric === 'density'">Cell color + size = how often you've driven through (95th-percentile normalised).</span>
        <span v-else>Cell color = average speed through that cell.</span>
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
.caption {
  margin: 0;
}
.small {
  font-size: 0.85rem;
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
  gap: 4px;
}
.swatch {
  display: inline-block;
  width: 14px;
  height: 14px;
  border-radius: 3px;
}
</style>
