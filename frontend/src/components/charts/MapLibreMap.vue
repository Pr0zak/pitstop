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
    });
    map.addLayer({
      id: "trip-route",
      type: "line",
      source: "trip-route",
      paint: {
        "line-color": ["get", "color"],
        "line-width": 3,
      },
    });
  } else {
    const src = map.getSource("trip-route") as maplibregl.GeoJSONSource;
    src.setData(buildRouteData());
  }
  fitBounds();
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
    style: OSM_STYLE,
    center: props.initialCenter ?? [-74.006, 40.7128],
    zoom: props.initialZoom ?? 10,
  });
  map.addControl(new maplibregl.NavigationControl({ showCompass: false }), "top-right");
  map.on("load", () => {
    if (props.route) applyRoute();
    applyMarkers();
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
  if (map) {
    map.remove();
    map = null;
  }
});
</script>

<template>
  <div ref="root" class="map" :style="{ height: (height ?? 360) + 'px' }" />
</template>

<style scoped>
.map {
  width: 100%;
  border-radius: var(--r-md);
  overflow: hidden;
  border: 1px solid var(--c-border-soft);
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
