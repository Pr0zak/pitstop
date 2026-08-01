<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref, watch } from "vue";
import maplibregl from "maplibre-gl";
import "maplibre-gl/dist/maplibre-gl.css";

interface Props {
  // Each feature is rendered as a circle marker. Use `route` for a polyline.
  markers?: { id: string; lng: number; lat: number; properties?: Record<string, unknown> }[];
  route?: [number, number][]; // array of [lng, lat]
  // Optional: pre-segmented route with per-segment colors. When set,
  // overrides the single-color `route` rendering. Each segment is a
  // 2+-point LineString with a CSS color string. Used by TripDetail's
  // speed-colored polyline.
  routeSegments?: { coords: [number, number][]; color: string }[];
  // Optional: a single position to spotlight on the route. Used by
  // TripDetail to sync the chart cursor with the map — hovering a
  // point on the timeline pulses a marker at the matching GPS fix.
  hoverMarker?: { lat: number; lon: number } | null;
  height?: number;
  initialCenter?: [number, number]; // [lng, lat]
  initialZoom?: number;
}
const props = defineProps<Props>();
const emit = defineEmits<{
  (e: "marker-click", id: string, properties: Record<string, unknown>): void;
}>();

const root = ref<HTMLDivElement | null>(null);
let map: maplibregl.Map | null = null;
let markerInstances: maplibregl.Marker[] = [];
let hoverMarkerInstance: maplibregl.Marker | null = null;

const OSM_STYLE = {
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

// Carto's Dark Matter raster basemap. Free for non-commercial use,
// no API key required. Attribution combines OSM + Carto per the
// provider's terms.
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

const MAP_DARK_KEY = "pitstop_map_dark";
// Default to dark; respect explicit "false" if the user toggled to light.
// (Earlier default was "false unless explicit true"; flipped 2026-05-12 to
// match the phone app's dark-by-default trip-detail map.)
const darkMode = ref<boolean>(localStorage.getItem(MAP_DARK_KEY) !== "false");
function setDarkMode(v: boolean) {
  darkMode.value = v;
  try { localStorage.setItem(MAP_DARK_KEY, String(v)); } catch { /* ignore */ }
  if (!map) return;
  // map.setStyle wipes our route source / layers — re-add once the
  // new style finishes loading.
  map.setStyle(v ? DARK_STYLE : OSM_STYLE);
  map.once("style.load", () => {
    applyRoute();
    applyMarkers();
    applyHoverMarker();
  });
}

function clearMarkers() {
  for (const m of markerInstances) m.remove();
  markerInstances = [];
}

function applyMarkers() {
  if (!map) return;
  clearMarkers();
  if (!props.markers) return;
  for (const m of props.markers) {
    const el = document.createElement("div");
    el.className = "ps-marker";
    el.title = String(m.properties?.label ?? "");
    el.addEventListener("click", () => emit("marker-click", m.id, m.properties ?? {}));
    const marker = new maplibregl.Marker({ element: el })
      .setLngLat([m.lng, m.lat])
      .addTo(map);
    markerInstances.push(marker);
  }
}

function buildRouteData(): GeoJSON.FeatureCollection {
  // Prefer the pre-segmented form when callers provide it (speed-
  // colored polyline). Otherwise fall back to one feature with the
  // default blue color for the simple `route` prop.
  if (props.routeSegments && props.routeSegments.length > 0) {
    return {
      type: "FeatureCollection",
      features: props.routeSegments.map((s) => ({
        type: "Feature",
        properties: { color: s.color },
        geometry: { type: "LineString", coordinates: s.coords },
      })),
    };
  }
  return {
    type: "FeatureCollection",
    features: props.route && props.route.length > 0 ? [{
      type: "Feature",
      properties: { color: "#2f81f7" },
      geometry: { type: "LineString", coordinates: props.route },
    }] : [],
  };
}

function applyRoute() {
  if (!map) return;
  if (!map.getSource("trip-route")) {
    map.addSource("trip-route", {
      type: "geojson",
      data: buildRouteData(),
      // routeSegments are short per-pair LineStrings; geojson-vt drops
      // lines shorter than the simplification tolerance, which made the
      // route thin out / disappear when zoomed out. 0 = keep them all.
      tolerance: 0,
    });
    map.addLayer({
      id: "trip-route",
      type: "line",
      source: "trip-route",
      paint: {
        "line-color": ["get", "color"],
        "line-width": 5,
      },
    });
  } else {
    const src = map.getSource("trip-route") as maplibregl.GeoJSONSource;
    src.setData(buildRouteData());
  }
  fitBounds();
}

function applyHoverMarker() {
  if (!map) return;
  const pos = props.hoverMarker;
  if (!pos) {
    if (hoverMarkerInstance) {
      hoverMarkerInstance.remove();
      hoverMarkerInstance = null;
    }
    return;
  }
  if (hoverMarkerInstance) {
    hoverMarkerInstance.setLngLat([pos.lon, pos.lat]);
    return;
  }
  const el = document.createElement("div");
  el.className = "ps-hover-marker";
  hoverMarkerInstance = new maplibregl.Marker({ element: el })
    .setLngLat([pos.lon, pos.lat])
    .addTo(map);
}

function fitBounds() {
  if (!map) return;
  const points: [number, number][] = [];
  if (props.route) points.push(...props.route);
  if (props.routeSegments) {
    for (const s of props.routeSegments) points.push(...s.coords);
  }
  if (props.markers) {
    for (const m of props.markers) points.push([m.lng, m.lat]);
  }
  if (points.length < 2) return;
  const bounds = new maplibregl.LngLatBounds(points[0], points[0]);
  for (const p of points) bounds.extend(p);
  map.fitBounds(bounds, { padding: 40, animate: false });
}

onMounted(() => {
  if (!root.value) return;
  map = new maplibregl.Map({
    container: root.value,
    style: darkMode.value ? DARK_STYLE : OSM_STYLE,
    center: props.initialCenter ?? [-74.006, 40.7128],
    zoom: props.initialZoom ?? 10,
  });
  map.addControl(new maplibregl.NavigationControl({ showCompass: false }), "top-right");
  map.on("load", () => {
    // Always call applyRoute on load so any data populated before
    // the map finished initialising still draws. Previously this
    // only fired when props.route was set, but trip detail uses
    // props.routeSegments (speed-bucket-colored) which raced —
    // the segments arrived before the map loaded, the watcher
    // saw map?.loaded() === false and bailed, then the load
    // handler didn't notice routeSegments either, so nothing
    // ever rendered until the user re-triggered the watcher.
    applyRoute();
    applyMarkers();
    applyHoverMarker();
  });
});

watch(
  () => props.markers,
  () => {
    if (map?.loaded()) applyMarkers();
  },
);
watch(
  () => props.route,
  () => {
    if (map?.loaded()) applyRoute();
  },
);
watch(
  () => props.routeSegments,
  () => {
    if (map?.loaded()) applyRoute();
  },
  { deep: true },
);
watch(
  () => props.hoverMarker,
  () => {
    if (map?.loaded()) applyHoverMarker();
  },
  { deep: true },
);
// Re-center when the caller changes initialCenter mid-flight (e.g.
// "Use current location" toggle on the stations map). Only flies if
// markers don't drive the bounds-fit anyway — we still prefer
// fitBounds() when there are 2+ marker points so a single click of
// "current location" doesn't yank a meaningful map view.
watch(
  () => props.initialCenter,
  (c) => {
    if (!c || !map) return;
    if ((props.markers?.length ?? 0) >= 2) return;
    map.flyTo({ center: c, zoom: props.initialZoom ?? 11, animate: false });
  },
);

onBeforeUnmount(() => {
  clearMarkers();
  if (hoverMarkerInstance) {
    hoverMarkerInstance.remove();
    hoverMarkerInstance = null;
  }
  if (map) {
    map.remove();
    map = null;
  }
});
</script>

<template>
  <div class="map-wrap">
    <div ref="root" class="map" :style="{ height: (height ?? 360) + 'px' }" />
    <button
      class="map-theme-toggle"
      type="button"
      :title="darkMode ? 'Switch to light basemap' : 'Switch to dark basemap'"
      @click="setDarkMode(!darkMode)"
    >{{ darkMode ? '☀' : '☾' }}</button>
  </div>
</template>

<style scoped>
.map {
  width: 100%;
  border-radius: var(--r-md);
  overflow: hidden;
  border: 1px solid var(--c-border-soft);
}
.map-wrap {
  position: relative;
}
.map-theme-toggle {
  position: absolute;
  top: 10px;
  left: 10px;
  z-index: 5;
  width: 30px;
  height: 30px;
  border-radius: 4px;
  border: 1px solid rgba(0, 0, 0, 0.18);
  background: #fff;
  color: #333;
  cursor: pointer;
  font-size: 16px;
  line-height: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.15);
}
.map-theme-toggle:hover {
  background: #f5f5f5;
}
</style>

<style>
.ps-marker {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: var(--c-accent);
  border: 2px solid var(--c-bg);
  box-shadow: 0 0 0 1px var(--c-accent);
  cursor: pointer;
}
.ps-marker:hover {
  transform: scale(1.2);
}
.ps-hover-marker {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: #f59e0b;
  border: 3px solid #fff;
  box-shadow: 0 0 0 2px #f59e0b, 0 0 12px rgba(245, 158, 11, 0.8);
  pointer-events: none;
}
.maplibregl-popup-content {
  background: var(--c-surface) !important;
  color: var(--c-text) !important;
  border: 1px solid var(--c-border) !important;
  border-radius: var(--r-md) !important;
}
.maplibregl-popup-tip {
  border-top-color: var(--c-surface) !important;
  border-bottom-color: var(--c-surface) !important;
}
</style>
