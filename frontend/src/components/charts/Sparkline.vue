<script setup lang="ts">
/**
 * Tiny inline trend for KPI cards — SVG, no axes, no interaction.
 *
 *   kind="area"  line + translucent fill, last point emphasised
 *   kind="line"  line only, last point emphasised
 *   kind="bars"  one bar per value; `highlightIndex` (default: last) is drawn
 *                in the ink colour, the rest muted
 *
 * Null entries are gaps (a month with no fillups), never zeros. The parent
 * decides whether there is enough data to draw at all — this component
 * renders nothing below two finite values (one for bars).
 */
import { computed } from "vue";

const props = withDefaults(
  defineProps<{
    values: (number | null | undefined)[];
    kind?: "area" | "line" | "bars";
    color?: string;
    height?: number;
    highlightIndex?: number | null;
    /** Accessible summary; the SVG is decorative without it. */
    label?: string;
  }>(),
  { kind: "area", color: "var(--chart-1)", height: 34, highlightIndex: null, label: "" },
);

const W = 100; // viewBox width; the SVG stretches to its container
const PAD = 3;

const finite = computed(() =>
  props.values
    .map((v, i) => ({ i, v }))
    .filter((p): p is { i: number; v: number } => p.v != null && Number.isFinite(p.v)),
);
const drawable = computed(() =>
  props.kind === "bars" ? finite.value.length >= 1 : finite.value.length >= 2,
);
const range = computed(() => {
  const vs = finite.value.map((p) => p.v);
  const min = props.kind === "bars" ? 0 : Math.min(...vs);
  const max = Math.max(...vs);
  return { min, span: max - min || 1 };
});
function x(i: number): number {
  const n = props.values.length;
  return n <= 1 ? W / 2 : (i / (n - 1)) * W;
}
function y(v: number): number {
  const h = props.height;
  return h - PAD - ((v - range.value.min) / range.value.span) * (h - PAD * 2);
}

/** Line path; a null value lifts the pen so gaps stay gaps. */
const linePath = computed(() => {
  let d = "";
  let pen = false;
  props.values.forEach((v, i) => {
    if (v == null || !Number.isFinite(v)) {
      pen = false;
      return;
    }
    d += `${pen ? "L" : "M"}${x(i).toFixed(2)},${y(v).toFixed(2)} `;
    pen = true;
  });
  return d.trim();
});
const areaPath = computed(() => {
  const pts = finite.value;
  if (pts.length < 2) return "";
  const h = props.height;
  const first = pts[0];
  const last = pts[pts.length - 1];
  const body = pts.map((p) => `L${x(p.i).toFixed(2)},${y(p.v).toFixed(2)}`).join(" ");
  return `M${x(first.i).toFixed(2)},${h} ${body} L${x(last.i).toFixed(2)},${h} Z`;
});
const endpoint = computed(() => {
  const pts = finite.value;
  const last = pts[pts.length - 1];
  return last ? { cx: x(last.i), cy: y(last.v) } : null;
});

const bars = computed(() => {
  const n = props.values.length;
  const slot = W / Math.max(1, n);
  const w = Math.max(1, slot * 0.66);
  const hi = props.highlightIndex ?? n - 1;
  return props.values.map((v, i) => {
    const ok = v != null && Number.isFinite(v) && v > 0;
    const top = ok ? y(v as number) : props.height - PAD;
    return {
      x: i * slot + (slot - w) / 2,
      y: top,
      w,
      h: Math.max(ok ? 1 : 0, props.height - PAD - top),
      hi: i === hi,
    };
  });
});
</script>

<template>
  <div v-if="drawable" class="spark-box">
  <svg
    class="spark"
    :viewBox="`0 0 ${W} ${height}`"
    preserveAspectRatio="none"
    :style="{ height: height + 'px' }"
    :role="label ? 'img' : undefined"
    :aria-label="label || undefined"
    :aria-hidden="label ? undefined : 'true'"
  >
    <template v-if="kind === 'bars'">
      <rect
        v-for="(b, i) in bars"
        :key="i"
        :x="b.x"
        :y="b.y"
        :width="b.w"
        :height="b.h"
        rx="0.6"
        :class="b.hi ? 'bar-hi' : 'bar'"
      />
    </template>
    <template v-else>
      <path v-if="kind === 'area'" :d="areaPath" :fill="color" fill-opacity="0.14" stroke="none" />
      <path
        :d="linePath"
        fill="none"
        :stroke="color"
        stroke-width="1.5"
        stroke-linejoin="round"
        stroke-linecap="round"
        vector-effect="non-scaling-stroke"
      />
    </template>
  </svg>
  <!-- The endpoint dot lives outside the stretched SVG so it stays round. -->
  <span
    v-if="kind !== 'bars' && endpoint"
    class="spark-dot"
    :style="{
      left: endpoint.cx + '%',
      top: endpoint.cy + 'px',
      background: color,
    }"
    aria-hidden="true"
  />
  </div>
</template>

<style scoped>
.spark-box {
  position: relative;
  width: 100%;
}
.spark {
  display: block;
  width: 100%;
  overflow: visible;
}
.bar {
  fill: var(--c-ink4);
}
.bar-hi {
  fill: var(--c-ink1);
}
.spark-dot {
  position: absolute;
  width: 6px;
  height: 6px;
  margin: -3px 0 0 -3px;
  border-radius: 50%;
  box-shadow: 0 0 0 2px var(--c-bg2);
  pointer-events: none;
}
</style>
