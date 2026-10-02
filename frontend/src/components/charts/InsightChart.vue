<script setup lang="ts">
// uPlot chart with a snap-to-point hover layer: a dashed crosshair (mode
// "x") or nothing (mode "xy"), a ring on the hovered point, and a tooltip
// rendered from the #tip slot. The parent lists the hoverable points in data
// units; uPlot's own cursor points, legend and drag-zoom are switched off so
// the chart reads as a static figure with one precise readout.
import { computed, ref, shallowRef } from "vue";
import type uPlot from "uplot";
import UPlotChart from "@/components/charts/UPlotChart.vue";

interface HoverPoint {
  x: number;
  y: number;
}
const props = withDefaults(
  defineProps<{
    data: uPlot.AlignedData;
    options: uPlot.Options;
    hover: HoverPoint[];
    /** "x" snaps to the nearest x (line charts); "xy" to the nearest point. */
    mode?: "x" | "xy";
    label: string;
  }>(),
  { mode: "x" },
);

const wrap = ref<HTMLDivElement | null>(null);
const chart = shallowRef<uPlot | null>(null);
const idx = ref<number | null>(null);
const pos = ref<{ x: number; y: number; top: number; bottom: number } | null>(null);
const wrapWidth = ref(0);

function place(i: number | null) {
  const u = chart.value;
  const el = wrap.value;
  const p = i != null ? props.hover[i] : undefined;
  if (!u || !el || !p) {
    idx.value = null;
    pos.value = null;
    return;
  }
  const over = u.over.getBoundingClientRect();
  const host = el.getBoundingClientRect();
  const ox = over.left - host.left;
  const oy = over.top - host.top;
  idx.value = i;
  wrapWidth.value = host.width;
  pos.value = {
    x: ox + u.valToPos(p.x, "x"),
    y: oy + u.valToPos(p.y, "y"),
    top: oy,
    bottom: oy + over.height,
  };
}

function nearest(u: uPlot, left: number, top: number): number | null {
  let best: number | null = null;
  let bd = Infinity;
  props.hover.forEach((p, i) => {
    const dx = u.valToPos(p.x, "x") - left;
    const dy = props.mode === "xy" ? u.valToPos(p.y, "y") - top : 0;
    const d = dx * dx + dy * dy;
    if (d < bd) {
      bd = d;
      best = i;
    }
  });
  return best;
}

const opts = computed<uPlot.Options>(() => ({
  ...props.options,
  legend: { show: false },
  cursor: {
    x: false,
    y: false,
    points: { show: false },
    drag: { x: false, y: false, setScale: false },
  },
  hooks: {
    ...props.options.hooks,
    setCursor: [
      ...(props.options.hooks?.setCursor ?? []),
      (u: uPlot) => {
        const { left, top } = u.cursor;
        if (left == null || top == null || left < 0) {
          place(null);
          return;
        }
        place(nearest(u, left, top));
      },
    ],
  },
}));

function onReady(u: uPlot) {
  chart.value = u;
  idx.value = null;
  pos.value = null;
}

function onKey(e: KeyboardEvent) {
  const n = props.hover.length;
  if (!n) return;
  if (e.key === "ArrowRight" || e.key === "ArrowLeft") {
    e.preventDefault();
    const cur = idx.value ?? n;
    place(Math.max(0, Math.min(n - 1, cur + (e.key === "ArrowRight" ? 1 : -1))));
  } else if (e.key === "Escape") {
    place(null);
  }
}

/** Keep the tooltip inside the card horizontally. */
const tipStyle = computed(() => {
  if (!pos.value) return {};
  const half = 110;
  const x = Math.min(Math.max(pos.value.x, half), Math.max(half, wrapWidth.value - half));
  return { left: `${x}px`, top: `${Math.max(pos.value.y, 64)}px` };
});
</script>

<template>
  <div
    ref="wrap"
    class="insight-chart"
    tabindex="0"
    role="img"
    :aria-label="label"
    @keydown="onKey"
    @blur="place(null)"
  >
    <UPlotChart :data="data" :options="opts" @ready="onReady" />
    <template v-if="pos && idx != null">
      <div
        v-if="mode === 'x'"
        class="cross"
        :style="{ left: `${pos.x}px`, top: `${pos.top}px`, height: `${pos.bottom - pos.top}px` }"
        aria-hidden="true"
      />
      <div class="ring" :style="{ left: `${pos.x}px`, top: `${pos.y}px` }" aria-hidden="true" />
      <div class="tip" :style="tipStyle" role="status">
        <slot name="tip" :index="idx" />
      </div>
    </template>
  </div>
</template>

<style scoped>
.insight-chart {
  position: relative;
  outline: none;
  border-radius: var(--r-sm);
}
.insight-chart:focus-visible {
  box-shadow: var(--focus-ring);
}
.cross {
  position: absolute;
  width: 0;
  border-left: 1px dashed var(--c-ink3);
  pointer-events: none;
}
.ring {
  position: absolute;
  width: 12px;
  height: 12px;
  margin: -6px 0 0 -6px;
  border: 2px solid var(--c-ink0);
  border-radius: 50%;
  pointer-events: none;
}
.tip {
  position: absolute;
  transform: translate(-50%, calc(-100% - 12px));
  min-width: 9rem;
  max-width: 220px;
  padding: 0.4rem 0.55rem;
  background: var(--c-bg4);
  border: 1px solid var(--c-line2);
  border-radius: var(--r-md);
  box-shadow: 0 4px 14px rgba(0, 0, 0, 0.45);
  font-size: 0.78rem;
  line-height: 1.4;
  color: var(--c-ink1);
  white-space: nowrap;
  pointer-events: none;
  z-index: 2;
}
.tip :deep(.k) {
  color: var(--c-ink2);
}
.tip :deep(b) {
  color: var(--c-ink0);
  font-weight: 600;
}
</style>
