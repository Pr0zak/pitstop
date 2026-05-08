<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref } from "vue";
import maplibregl from "maplibre-gl";
import "maplibre-gl/dist/maplibre-gl.css";
import { roundCoords } from "@/utils/parseLatLon";

interface Props {
  initialLat: number | null;
  initialLon: number | null;
}
const props = defineProps<Props>();
const emit = defineEmits<{
  (e: "pick", lat: number, lon: number): void;
  (e: "cancel"): void;
}>();

const root = ref<HTMLDivElement | null>(null);
const lat = ref<number | null>(props.initialLat);
const lon = ref<number | null>(props.initialLon);

let map: maplibregl.Map | null = null;
let marker: maplibregl.Marker | null = null;

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

function setMarker(lng: number, lat_: number) {
  if (!map) return;
  if (!marker) {
    const el = document.createElement("div");
    el.className = "home-pick-marker";
    marker = new maplibregl.Marker({ element: el, draggable: true })
      .setLngLat([lng, lat_])
      .addTo(map);
    marker.on("dragend", () => {
      const ll = marker!.getLngLat();
      const r = roundCoords(ll.lat, ll.lng);
      lat.value = r.lat;
      lon.value = r.lon;
    });
  } else {
    marker.setLngLat([lng, lat_]);
  }
}

onMounted(() => {
  if (!root.value) return;
  const startLng = props.initialLon ?? -74.006;
  const startLat = props.initialLat ?? 40.7128;
  const startZoom = props.initialLat != null && props.initialLon != null ? 14 : 3;

  map = new maplibregl.Map({
    container: root.value,
    style: OSM_STYLE,
    center: [startLng, startLat],
    zoom: startZoom,
  });
  map.addControl(new maplibregl.NavigationControl({ showCompass: false }), "top-right");

  map.on("load", () => {
    if (props.initialLat != null && props.initialLon != null) {
      setMarker(props.initialLon, props.initialLat);
    }
  });

  map.on("click", (e) => {
    const r = roundCoords(e.lngLat.lat, e.lngLat.lng);
    lat.value = r.lat;
    lon.value = r.lon;
    setMarker(e.lngLat.lng, e.lngLat.lat);
  });
});

onBeforeUnmount(() => {
  if (marker) {
    marker.remove();
    marker = null;
  }
  if (map) {
    map.remove();
    map = null;
  }
});

function confirm() {
  if (lat.value == null || lon.value == null) return;
  emit("pick", lat.value, lon.value);
}

function useGeolocation() {
  if (!("geolocation" in navigator)) return;
  navigator.geolocation.getCurrentPosition(
    (pos) => {
      const r = roundCoords(pos.coords.latitude, pos.coords.longitude);
      lat.value = r.lat;
      lon.value = r.lon;
      if (map) {
        map.flyTo({ center: [r.lon, r.lat], zoom: 14, animate: false });
        setMarker(r.lon, r.lat);
      }
    },
    (err) => alert(`Geolocation failed: ${err.message}`),
    { enableHighAccuracy: false, timeout: 10_000 },
  );
}
</script>

<template>
  <div class="modal-backdrop" @click.self="emit('cancel')">
    <div class="modal">
      <header>
        <h3>Pick home location</h3>
        <button class="ghost" type="button" @click="emit('cancel')">×</button>
      </header>
      <p class="muted hint">
        Click anywhere on the map to drop a marker. Drag the marker to fine-tune.
      </p>
      <div ref="root" class="map" />
      <footer>
        <span class="muted coords">
          <template v-if="lat != null && lon != null">
            {{ lat.toFixed(5) }}, {{ lon.toFixed(5) }}
          </template>
          <template v-else>(no location selected yet)</template>
        </span>
        <span class="spacer" />
        <button type="button" @click="useGeolocation">Use current location</button>
        <button type="button" @click="emit('cancel')">Cancel</button>
        <button class="primary" type="button" :disabled="lat == null || lon == null" @click="confirm">
          Use this location
        </button>
      </footer>
    </div>
  </div>
</template>

<style scoped>
.modal-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  padding: 1rem;
}
.modal {
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--r-lg);
  width: min(720px, 100%);
  max-height: calc(100vh - 2rem);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
header {
  display: flex;
  align-items: center;
  padding: 0.75rem 1rem;
  border-bottom: 1px solid var(--c-border-soft);
}
header h3 {
  margin: 0;
  flex: 1;
}
header button {
  font-size: 1.4rem;
  line-height: 1;
  padding: 0 0.5rem;
}
.hint {
  margin: 0.6rem 1rem 0;
  font-size: 0.85rem;
}
.map {
  flex: 1;
  min-height: 360px;
  margin: 0.6rem 1rem;
  border-radius: var(--r-md);
  border: 1px solid var(--c-border-soft);
  overflow: hidden;
}
footer {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.75rem 1rem;
  border-top: 1px solid var(--c-border-soft);
}
.spacer {
  flex: 1;
}
.coords {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 0.85rem;
}
</style>

<style>
.home-pick-marker {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: var(--c-accent);
  border: 3px solid #fff;
  box-shadow: 0 0 0 1px var(--c-accent), 0 1px 4px rgba(0, 0, 0, 0.4);
  cursor: grab;
}
.home-pick-marker:active {
  cursor: grabbing;
}
</style>
