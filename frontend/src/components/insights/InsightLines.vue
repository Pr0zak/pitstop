<script setup lang="ts">
/**
 * Time-series lines for an insight card: one colour per series with a
 * legend, calendar-aligned date ticks, a few y ticks, optional shaded
 * `band` and dashed `ref`. Hover / tap shows the nearest reading of every
 * series. All values are drawn as sent — nothing is smoothed or derived.
 */
import { computed, ref } from "vue";
import type { InsightLinesChart } from "@/api/types";
import { useElementWidth } from "@/composables/useElementWidth";
import {
  fmtHoverTime,
  fmtInsightNumber,
  fmtTick,
  linearScale,
  nearestIndex,
  niceTicks,
  textWidth,
  timeTicks,
  valueDomain,
} from "@/lib/insightCharts";

const props = withDefaults(defineProps<{ chart: InsightLinesChart; height?: number }>(), {
  height: 140,
});

const wrap = ref<HTMLElement | null>(null);
const width = useElementWidth(wrap);

const PAD_TOP = 8;
const PAD_RIGHT = 6;
const PAD_BOTTOM = 20;
/** Series colours: the categorical chart palette, in order. */
const COLORS = Array.from({ length: 8 }, (_, i) => `var(--chart-${i + 1})`);

const series = computed(() =>
  props.chart.series
    .map((s, i) => ({
      name: s.name,
      color: COLORS[i % COLORS.length],
      points: [...s.points].sort((a, b) => a[0] - b[0]),
    }))
    .filter((s) => s.points.length > 0),
);

const layout = computed(() => {
  const W = Math.max(160, width.value);
  const H = props.height;
  const all = series.value.flatMap((s) => s.points);
  const ts = all.map((p) => p[0]);
  const t0 = Math.min(...ts);
  const t1 = Math.max(...ts);
  const [d0, d1] = valueDomain(
    all.map((p) => p[1]),
    { band: props.chart.band, ref: props.chart.ref },
  );
  const yTicks = niceTicks(d0, d1, 4);
  const step = yTicks.length > 1 ? yTicks[1] - yTicks[0] : Math.abs(d1 - d0) / 3;
  const yLabels = yTicks.map((v) => fmtTick(v, step));
  const padLeft = Math.max(22, Math.max(...yLabels.map((l) => textWidth(l, 6.2))) + 8);
  const x =
    t1 > t0 ? linearScale(t0, t1, padLeft, W - PAD_RIGHT) : () => (padLeft + W - PAD_RIGHT) / 2;
  const y = linearScale(d0, d1, H - PAD_BOTTOM, PAD_TOP);

  const paths = series.value.map((s) => ({
    name: s.name,
    color: s.color,
    d: s.points.map((p, i) => `${i ? "L" : "M"}${x(p[0]).toFixed(1)},${y(p[1]).toFixed(1)}`).join(""),
    dots: s.points.length <= 24 ? s.points.map((p) => ({ cx: x(p[0]), cy: y(p[1]) })) : [],
    last: { cx: x(s.points[s.points.length - 1][0]), cy: y(s.points[s.points.length - 1][1]) },
  }));

  const xTicks = timeTicks(t0, t1, W < 360 ? 3 : 4).map((k) => ({ ...k, x: x(k.t) }));
  const band = props.chart.band
    ? {
        y: y(props.chart.band.hi),
        h: Math.max(1, y(props.chart.band.lo) - y(props.chart.band.hi)),
        label: props.chart.band.label,
      }
    : null;
  const refLine = props.chart.ref
    ? {
        y: y(props.chart.ref.value),
        label: `${props.chart.ref.label} · ${fmtInsightNumber(props.chart.ref.value)}`,
      }
    : null;

  return {
    W,
    H,
    padLeft,
    x,
    y,
    t0,
    t1,
    plotRight: W - PAD_RIGHT,
    yTicks: yTicks.map((v, i) => ({ v, y: y(v), label: yLabels[i] })),
    xTicks,
    paths,
    band,
    refLine,
  };
});

// ── hover readout ──
const hoverPx = ref<number | null>(null);
function onMove(e: PointerEvent) {
  const svg = e.currentTarget as SVGSVGElement;
  const r = svg.getBoundingClientRect();
  const px = e.clientX - r.left;
  const L = layout.value;
  hoverPx.value = px < L.padLeft - 4 || px > L.plotRight + 4 ? null : px;
}
function onLeave() {
  hoverPx.value = null;
}
const hover = computed(() => {
  if (hoverPx.value == null) return null;
  const L = layout.value;
  const span = L.t1 - L.t0;
  const t = span > 0 ? L.t0 + ((hoverPx.value - L.padLeft) / (L.plotRight - L.padLeft)) * span : L.t0;
  // Snap to the closest real reading across all series.
  let best: { t: number; dist: number } | null = null;
  for (const s of series.value) {
    const i = nearestIndex(s.points, t);
    const dist = Math.abs(s.points[i][0] - t);
    if (!best || dist < best.dist) best = { t: s.points[i][0], dist };
  }
  if (!best) return null;
  const rows = series.value.map((s) => {
    const i = nearestIndex(s.points, best!.t);
    const p = s.points[i];
    return { name: s.name, color: s.color, value: fmtInsightNumber(p[1]), cx: L.x(p[0]), cy: L.y(p[1]) };
  });
  const xPx = L.x(best.t);
  return {
    x: xPx,
    when: fmtHoverTime(best.t, span),
    rows,
    // Keep the readout inside the card: flip to the left past the middle.
    left: xPx > L.W / 2 ? undefined : `${xPx + 10}px`,
    right: xPx > L.W / 2 ? `${L.W - xPx + 10}px` : undefined,
  };
});

const summary = computed(
  () =>
    `Line chart in ${props.chart.y_unit}: ` +
    series.value
      .map((s) => `${s.name}, latest ${fmtInsightNumber(s.points[s.points.length - 1][1])}`)
      .join("; "),
);
</script>

<template>
  <div ref="wrap" class="ic">
    <div class="ic-cap">
      <span class="unit-cap">{{ chart.y_unit }}</span>
      <span v-for="s in layout.paths" :key="s.name" class="leg">
        <i class="sw" :style="{ background: s.color }" />{{ s.name }}
      </span>
    </div>
    <svg
      v-if="series.length"
      :width="layout.W"
      :height="layout.H"
      :viewBox="`0 0 ${layout.W} ${layout.H}`"
      role="img"
      :aria-label="summary"
      @pointermove="onMove"
      @pointerdown="onMove"
      @pointerleave="onLeave"
    >
      <g v-if="layout.band">
        <rect
          class="band"
          :x="layout.padLeft"
          :y="layout.band.y"
          :width="layout.plotRight - layout.padLeft"
          :height="layout.band.h"
        />
      </g>

      <g v-for="t in layout.yTicks" :key="`y${t.v}`">
        <line class="grid" :x1="layout.padLeft" :x2="layout.plotRight" :y1="t.y" :y2="t.y" />
        <text class="tick" :x="layout.padLeft - 6" :y="t.y + 3.5" text-anchor="end">
          {{ t.label }}
        </text>
      </g>
      <text
        v-for="(t, i) in layout.xTicks"
        :key="`x${t.t}`"
        class="tick"
        :x="t.x"
        :y="layout.H - 5"
        :text-anchor="
          layout.xTicks.length > 1 && i === layout.xTicks.length - 1 && t.x > layout.plotRight - 24
            ? 'end'
            : 'middle'
        "
      >
        {{ t.label }}
      </text>

      <g v-if="layout.refLine">
        <line
          class="ref"
          :x1="layout.padLeft"
          :x2="layout.plotRight"
          :y1="layout.refLine.y"
          :y2="layout.refLine.y"
        />
      </g>

      <g v-for="p in layout.paths" :key="p.name">
        <path class="line" :d="p.d" :style="{ stroke: p.color }" />
        <circle
          v-for="(d, i) in p.dots"
          :key="i"
          :cx="d.cx"
          :cy="d.cy"
          r="2.2"
          :style="{ fill: p.color }"
        />
        <circle class="end" :cx="p.last.cx" :cy="p.last.cy" r="3.2" :style="{ fill: p.color }" />
      </g>

      <text
        v-if="layout.band"
        class="lbl halo"
        :x="layout.padLeft + 4"
        :y="layout.band.y + 11"
      >
        {{ layout.band.label }}
      </text>
      <text
        v-if="layout.refLine"
        class="lbl halo"
        :x="layout.plotRight - 2"
        :y="layout.refLine.y - 4"
        text-anchor="end"
      >
        {{ layout.refLine.label }}
      </text>

      <g v-if="hover" class="hover">
        <line
          class="cross"
          :x1="hover.x"
          :x2="hover.x"
          :y1="8"
          :y2="layout.H - 20"
        />
        <circle
          v-for="r in hover.rows"
          :key="r.name"
          class="hdot"
          :cx="r.cx"
          :cy="r.cy"
          r="3.5"
          :style="{ fill: r.color }"
        />
      </g>
    </svg>
    <div
      v-if="hover"
      class="tip"
      :style="{ left: hover.left, right: hover.right }"
      aria-hidden="true"
    >
      <div class="when">{{ hover.when }}</div>
      <div v-for="r in hover.rows" :key="r.name" class="trow">
        <i class="sw" :style="{ background: r.color }" />
        <span class="tname">{{ r.name }}</span>
        <span class="tval">{{ r.value }}</span>
        <span class="tunit">{{ chart.y_unit }}</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ic {
  position: relative;
  width: 100%;
  min-width: 0;
}
.ic-cap {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.15rem 0.8rem;
  font-size: 11px;
  color: var(--c-ink2);
  margin-bottom: 4px;
}
.unit-cap {
  color: var(--c-ink3);
}
.leg {
  display: inline-flex;
  align-items: center;
}
.sw {
  display: inline-block;
  width: 12px;
  height: 3px;
  border-radius: 2px;
  margin-right: 0.35rem;
}
svg {
  display: block;
  overflow: visible;
  touch-action: pan-y;
}
.band {
  fill: var(--c-ink2);
  fill-opacity: 0.08;
}
.grid {
  stroke: var(--chart-grid);
  stroke-width: 1;
}
.tick {
  font-family: "Geist Mono", ui-monospace, monospace;
  font-variant-numeric: tabular-nums;
  font-size: 10px;
  fill: var(--chart-axis);
}
.ref {
  stroke: var(--c-ink2);
  stroke-width: 1;
  stroke-dasharray: 4 3;
}
.lbl {
  font-size: 10.5px;
  fill: var(--c-ink2);
}
.halo {
  paint-order: stroke;
  stroke: var(--c-bg2);
  stroke-width: 3px;
  stroke-linejoin: round;
}
.line {
  fill: none;
  stroke-width: 1.75;
  stroke-linejoin: round;
  stroke-linecap: round;
}
.end,
.hdot {
  stroke: var(--c-bg2);
  stroke-width: 1.5;
}
.cross {
  stroke: var(--c-line2);
  stroke-width: 1;
}
.tip {
  position: absolute;
  top: 22px;
  z-index: 2;
  pointer-events: none;
  padding: 0.35rem 0.55rem;
  border-radius: var(--r-sm);
  background: var(--c-bg4);
  border: 1px solid var(--c-line1);
  font-size: 11.5px;
  color: var(--c-ink1);
  white-space: nowrap;
  box-shadow: var(--shadow-soft);
}
.when {
  color: var(--c-ink3);
  margin-bottom: 2px;
}
.trow {
  display: flex;
  align-items: center;
  gap: 0.2rem;
}
.tname {
  flex: 1;
  margin-right: 0.6rem;
}
.tunit {
  margin-left: 0.25rem;
  color: var(--c-ink3);
}
.tval {
  font-family: "Geist Mono", ui-monospace, monospace;
  font-variant-numeric: tabular-nums;
  color: var(--c-ink0);
}
</style>
