<script setup lang="ts">
/**
 * Scatter for an insight card (e.g. warm-up minutes vs outside temp).
 * Numeric x and y axes with the server's unit labels; one dot per pair.
 */
import { computed, ref } from "vue";
import type { InsightPointsChart } from "@/api/types";
import { useElementWidth } from "@/composables/useElementWidth";
import {
  fmtInsightNumber,
  fmtTick,
  linearScale,
  niceTicks,
  textWidth,
  valueDomain,
} from "@/lib/insightCharts";

const props = withDefaults(defineProps<{ chart: InsightPointsChart; height?: number }>(), {
  height: 140,
});

const wrap = ref<HTMLElement | null>(null);
const width = useElementWidth(wrap);

const PAD_TOP = 8;
const PAD_RIGHT = 6;
const PAD_BOTTOM = 32;

const layout = computed(() => {
  const W = Math.max(160, width.value);
  const H = props.height;
  const pts = props.chart.points.filter((p) => Number.isFinite(p[0]) && Number.isFinite(p[1]));
  const [x0, x1] = valueDomain(pts.map((p) => p[0]));
  const [y0, y1] = valueDomain(pts.map((p) => p[1]));
  const yt = niceTicks(y0, y1, 3);
  const ys = yt.length > 1 ? yt[1] - yt[0] : (y1 - y0) / 3;
  const yLabels = yt.map((v) => fmtTick(v, ys));
  const padLeft = Math.max(22, Math.max(...yLabels.map((l) => textWidth(l, 6.2))) + 8);
  const xt = niceTicks(x0, x1, W < 360 ? 4 : 6);
  const xs = xt.length > 1 ? xt[1] - xt[0] : (x1 - x0) / 4;
  const x = linearScale(x0, x1, padLeft, W - PAD_RIGHT);
  const y = linearScale(y0, y1, H - PAD_BOTTOM, PAD_TOP);
  return {
    W,
    H,
    padLeft,
    plotRight: W - PAD_RIGHT,
    plotBottom: H - PAD_BOTTOM,
    yTicks: yt.map((v, i) => ({ v, y: y(v), label: yLabels[i] })),
    xTicks: xt.map((v) => ({ v, x: x(v), label: fmtTick(v, xs) })),
    dots: pts.map((p) => ({
      cx: x(p[0]),
      cy: y(p[1]),
      title: `${fmtInsightNumber(p[0])} ${props.chart.x_unit}: ${fmtInsightNumber(p[1])} ${props.chart.y_unit}`,
    })),
  };
});

const summary = computed(
  () =>
    `Scatter of ${props.chart.y_unit} against ${props.chart.x_unit}, ${props.chart.points.length} points`,
);
</script>

<template>
  <div ref="wrap" class="ic">
    <div class="ic-cap">{{ chart.y_unit }}</div>
    <svg
      :width="layout.W"
      :height="layout.H"
      :viewBox="`0 0 ${layout.W} ${layout.H}`"
      role="img"
      :aria-label="summary"
    >
      <g v-for="t in layout.yTicks" :key="`y${t.v}`">
        <line class="grid" :x1="layout.padLeft" :x2="layout.plotRight" :y1="t.y" :y2="t.y" />
        <text class="tick" :x="layout.padLeft - 6" :y="t.y + 3.5" text-anchor="end">
          {{ t.label }}
        </text>
      </g>
      <text
        v-for="t in layout.xTicks"
        :key="`x${t.v}`"
        class="tick"
        :x="t.x"
        :y="layout.plotBottom + 13"
        text-anchor="middle"
      >
        {{ t.label }}
      </text>
      <text class="axis-unit" :x="layout.plotRight" :y="layout.H - 2" text-anchor="end">
        {{ chart.x_unit }} →
      </text>
      <circle
        v-for="(d, i) in layout.dots"
        :key="i"
        class="dot"
        :cx="d.cx"
        :cy="d.cy"
        r="3"
      >
        <title>{{ d.title }}</title>
      </circle>
    </svg>
  </div>
</template>

<style scoped>
.ic {
  position: relative;
  width: 100%;
  min-width: 0;
}
.ic-cap {
  font-size: 11px;
  color: var(--c-ink3);
  margin-bottom: 4px;
}
svg {
  display: block;
  overflow: visible;
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
.axis-unit {
  font-size: 10.5px;
  fill: var(--c-ink3);
}
.dot {
  fill: var(--chart-1);
  fill-opacity: 0.55;
}
</style>
