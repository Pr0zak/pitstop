<script setup lang="ts">
// Route outline for a trips-list row. Fetches /trips/{id}/route only once
// the row scrolls into view, caches the reduced shape per trip for the
// session, and caps concurrent fetches so a 50-row page doesn't fire 50
// requests at once. Shape only — no map, no coordinates.
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { fitShape, type RouteShape } from "@/lib/routeThumb";
import { loadRouteShape } from "@/lib/routeThumbLoader";

const props = withDefaults(defineProps<{ tripId: string; size?: number }>(), { size: 40 });

const el = ref<SVGSVGElement | null>(null);
const shape = ref<RouteShape | null>(null);
const done = ref(false);
let io: IntersectionObserver | null = null;

function start() {
  void loadRouteShape(props.tripId).then((s) => {
    shape.value = s;
    done.value = true;
  });
}
onMounted(() => {
  if (typeof IntersectionObserver === "undefined" || !el.value) {
    start();
    return;
  }
  io = new IntersectionObserver(
    (entries) => {
      if (entries.some((e) => e.isIntersecting)) {
        io?.disconnect();
        io = null;
        start();
      }
    },
    { rootMargin: "200px" },
  );
  io.observe(el.value);
});
onBeforeUnmount(() => io?.disconnect());

const pts = computed(() => (shape.value ? fitShape(shape.value, props.size, props.size, 5) : null));
const line = computed(() => pts.value?.map((p) => `${p[0].toFixed(1)},${p[1].toFixed(1)}`).join(" ") ?? "");
</script>

<template>
  <svg
    ref="el"
    class="trip-thumb"
    :width="size"
    :height="size"
    :viewBox="`0 0 ${size} ${size}`"
    :role="pts ? 'img' : undefined"
    :aria-label="pts ? 'Route outline' : undefined"
    :aria-hidden="pts ? undefined : 'true'"
  >
    <rect :width="size" :height="size" rx="8" class="bg" />
    <template v-if="pts">
      <polyline :points="line" class="route" />
      <circle :cx="pts[0][0]" :cy="pts[0][1]" r="2.5" class="start" />
      <circle :cx="pts[pts.length - 1][0]" :cy="pts[pts.length - 1][1]" r="3" class="end" />
    </template>
    <line v-else-if="done" :x1="size * 0.3" :x2="size * 0.7" :y1="size / 2" :y2="size / 2" class="none" />
  </svg>
</template>

<style scoped>
.trip-thumb {
  display: block;
  flex: none;
}
.bg {
  fill: var(--c-bg3);
}
.route {
  fill: none;
  stroke: var(--c-accent);
  stroke-width: 1.8;
  stroke-linejoin: round;
  stroke-linecap: round;
}
.start {
  fill: var(--c-ink1);
}
.end {
  fill: var(--c-accent);
  stroke: var(--c-bg3);
  stroke-width: 1.5;
}
.none {
  stroke: var(--c-ink4);
  stroke-width: 1.5;
  stroke-linecap: round;
}
</style>
