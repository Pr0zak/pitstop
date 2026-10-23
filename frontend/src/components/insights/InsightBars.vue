<script setup lang="ts">
/**
 * Category bars for an insight card. Values print above each bar (below it
 * when negative), categories print underneath, the server's `highlight`
 * bars take the accent and the rest stay muted. Optional shaded `band`
 * (e.g. "Healthy ±5%") and dashed `ref` line. Drawn in real pixels from the
 * measured width so text stays readable at phone width.
 */
import { computed, ref } from "vue";
import type { InsightBarsChart } from "@/api/types";
import { useElementWidth } from "@/composables/useElementWidth";
import {
  barDomain,
  fmtInsightNumber,
  labelStride,
  linearScale,
  textWidth,
  valueLabelMask,
  wrapLabel,
} from "@/lib/insightCharts";

const props = withDefaults(defineProps<{ chart: InsightBarsChart; height?: number }>(), {
  height: 140,
});

const wrap = ref<HTMLElement | null>(null);
const width = useElementWidth(wrap);

const PAD_X = 2;
const PAD_TOP = 16;
const LINE_H = 12;

const layout = computed(() => {
  const bars = props.chart.bars;
  const n = Math.max(1, bars.length);
  const W = Math.max(120, width.value);
  const slot = (W - PAD_X * 2) / n;

  // Category labels: few bars → wrap onto two lines to fit the slot;
  // many bars → one line and show every k-th.
  const maxChars = Math.max(3, Math.floor((slot - 4) / 6));
  let catStride = 1;
  let catLines: string[][];
  if (n <= 8) {
    catLines = bars.map((b) => wrapLabel(b.label, maxChars));
  } else {
    const widest = Math.max(...bars.map((b) => textWidth(b.label)));
    catStride = labelStride(n, slot, widest + 8);
    catLines = bars.map((b) => [b.label]);
  }
  const lines = Math.max(1, ...catLines.map((l) => l.length));
  const hasNeg = bars.some((b) => b.value < 0);
  const bottom = 6 + lines * LINE_H + (hasNeg ? 18 : 0);

  const [d0, d1] = barDomain(
    bars.map((b) => b.value),
    { band: props.chart.band, ref: props.chart.ref },
  );
  const H = props.height;
  const plotBottom = H - bottom;
  const y = linearScale(d0, d1, plotBottom, PAD_TOP);
  const y0 = y(0);

  const labels = bars.map((b) => fmtInsightNumber(b.value, 1));
  const widestValue = Math.max(...labels.map((l) => textWidth(l, 6.2)));
  const valueMask = valueLabelMask(
    bars.map((b) => b.highlight),
    labelStride(n, slot, widestValue + 4),
  );
  const barW = Math.max(2, Math.min(slot * 0.66, 56));

  const items = bars.map((b, i) => {
    const cx = PAD_X + slot * (i + 0.5);
    const yv = y(b.value);
    const top = Math.min(yv, y0);
    const h = Math.abs(yv - y0);
    return {
      key: `${i}-${b.label}`,
      cx,
      x: cx - barW / 2,
      top: h < 1 ? y0 - 0.5 : top,
      h: Math.max(h, 1),
      highlight: b.highlight,
      valueText: labels[i],
      showValue: valueMask[i],
      valueY: b.value < 0 ? yv + 12 : yv - 4,
      cat: i % catStride === 0 ? catLines[i] : [],
      title:
        `${b.label}: ${fmtInsightNumber(b.value)} ${props.chart.y_unit}` +
        (b.n > 0 ? ` (from ${b.n.toLocaleString()})` : ""),
    };
  });

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

  return { W, H, y0, barW, items, band, refLine, catTop: plotBottom + (hasNeg ? 18 : 0) + 11 };
});

const summary = computed(
  () =>
    `Bar chart in ${props.chart.y_unit}: ` +
    props.chart.bars.map((b) => `${b.label} ${fmtInsightNumber(b.value)}`).join(", "),
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
      <g v-if="layout.band">
        <rect
          class="band"
          :x="0"
          :y="layout.band.y"
          :width="layout.W"
          :height="layout.band.h"
        />
      </g>

      <line class="base" :x1="0" :x2="layout.W" :y1="layout.y0" :y2="layout.y0" />

      <rect
        v-for="b in layout.items"
        :key="b.key"
        class="bar"
        :class="{ hl: b.highlight }"
        :x="b.x"
        :y="b.top"
        :width="layout.barW"
        :height="b.h"
        rx="2"
      >
        <title>{{ b.title }}</title>
      </rect>

      <text v-if="layout.band" class="lbl halo" :x="4" :y="layout.band.y + 11">
        {{ layout.band.label }}
      </text>
      <g v-if="layout.refLine">
        <line
          class="ref"
          :x1="0"
          :x2="layout.W"
          :y1="layout.refLine.y"
          :y2="layout.refLine.y"
        />
        <text class="lbl halo" :x="layout.W - 2" :y="layout.refLine.y - 4" text-anchor="end">
          {{ layout.refLine.label }}
        </text>
      </g>

      <template v-for="b in layout.items" :key="`v-${b.key}`">
        <text
          v-if="b.showValue"
          class="val halo"
          :class="{ hl: b.highlight }"
          :x="b.cx"
          :y="b.valueY"
          text-anchor="middle"
        >
          {{ b.valueText }}
        </text>
        <text
          v-if="b.cat.length"
          class="cat"
          :class="{ hl: b.highlight }"
          :x="b.cx"
          :y="layout.catTop"
          text-anchor="middle"
        >
          <tspan v-for="(l, li) in b.cat" :key="li" :x="b.cx" :dy="li === 0 ? 0 : 12">{{ l }}</tspan>
        </text>
      </template>
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
  margin-bottom: 2px;
}
svg {
  display: block;
  overflow: visible;
}
.band {
  fill: var(--c-ink2);
  fill-opacity: 0.08;
}
.base {
  stroke: var(--c-line2);
  stroke-width: 1;
}
.bar {
  fill: var(--c-ink2);
  fill-opacity: 0.32;
}
.bar.hl {
  fill: var(--c-accent);
  fill-opacity: 0.9;
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
.val {
  font-family: "Geist Mono", ui-monospace, monospace;
  font-variant-numeric: tabular-nums;
  font-size: 10.5px;
  fill: var(--c-ink2);
}
.val.hl {
  fill: var(--c-ink0);
  font-weight: 600;
}
.cat {
  font-size: 10.5px;
  fill: var(--c-ink3);
}
.cat.hl {
  fill: var(--c-ink1);
  font-weight: 600;
}
.halo {
  paint-order: stroke;
  stroke: var(--c-bg2);
  stroke-width: 3px;
  stroke-linejoin: round;
}
</style>
