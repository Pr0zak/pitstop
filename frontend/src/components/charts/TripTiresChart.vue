<script setup lang="ts">
/**
 * Trip-detail Tires chart: the four corners on ONE axis (the spread between
 * them is the story), pressure in whole psi or tire temp in the unit setting.
 * Step lines — each corner holds its last reading until the next — with gaps
 * left as gaps (lib/tripTires does the carry/break). The x range is the trip
 * window, so a chart whose tire data covers only part of the drive shows it
 * where it happened. HTML legend FL / FR / RL / RR with the value under the
 * cursor (the trip's start → end at rest).
 */
import { computed, ref } from "vue";
import uPlot from "uplot";
import UPlotChart from "@/components/charts/UPlotChart.vue";
import { TIRE_CORNERS, TIRE_CORNER_COLOR, TIRE_CORNER_LABEL, type TireCorner } from "@/lib/vehicleSignals";
import type { TireChartData } from "@/lib/tripTires";

const props = withDefaults(
  defineProps<{
    data: TireChartData;
    /** Axis + readout unit, e.g. "psi" / "°F". */
    unit: string;
    /** Trip window, unix seconds. */
    start: number;
    end: number;
    height?: number;
  }>(),
  { height: 200 },
);

const cursorIdx = ref<number | null>(null);
const clockFmt = new Intl.DateTimeFormat(undefined, { hour: "numeric", minute: "2-digit" });

const aligned = computed<uPlot.AlignedData>(() => [
  props.data.x,
  ...TIRE_CORNERS.map((c) => props.data.cols[c]),
]);

function firstLast(col: (number | null)[]): [number | null, number | null] {
  let first: number | null = null;
  let last: number | null = null;
  for (const v of col) {
    if (v == null) continue;
    if (first == null) first = v;
    last = v;
  }
  return [first, last];
}
function fmt(v: number | null | undefined): string {
  return v == null || !Number.isFinite(v) ? "—" : String(Math.round(v));
}
const legend = computed(() =>
  TIRE_CORNERS.map((c: TireCorner) => {
    const col = props.data.cols[c];
    const idx = cursorIdx.value;
    let text: string;
    if (idx != null) {
      text = fmt(col[idx]);
    } else {
      const [a, b] = firstLast(col);
      text = a == null ? "—" : a === b || Math.round(a) === Math.round(b ?? a) ? fmt(b) : `${fmt(a)} → ${fmt(b)}`;
    }
    return { corner: c, label: TIRE_CORNER_LABEL[c], color: TIRE_CORNER_COLOR[c], text, present: props.data.present.includes(c) };
  }),
);
const cursorTime = computed(() => {
  const idx = cursorIdx.value;
  return idx != null && props.data.x[idx] != null ? clockFmt.format(new Date(props.data.x[idx] * 1000)) : null;
});

const stepped = uPlot.paths.stepped!({ align: 1 });

const opts = computed<uPlot.Options>(() => ({
  width: 600,
  height: props.height,
  legend: { show: false },
  padding: [8, 16, 0, 0],
  cursor: { drag: { x: true, y: false, setScale: true }, points: { size: 6 } },
  scales: {
    x: { time: true, range: () => [props.start, Math.max(props.end, props.start + 60)] },
    y: {
      range: (_u, lo, hi) => {
        if (lo == null || hi == null) return [0, 1];
        // Whole-unit data: keep at least a 4-unit band so a steady tire
        // doesn't fill the plot with one flat line at the edge.
        const pad = Math.max(2, (hi - lo) * 0.15);
        return [Math.floor(lo - pad), Math.ceil(hi + pad)];
      },
    },
  },
  axes: [
    { values: (_u: uPlot, splits: number[]) => splits.map((v) => clockFmt.format(new Date(v * 1000))), space: 80 },
    { size: 44, values: (_u: uPlot, splits: number[]) => splits.map((v) => `${Math.round(v)}`), label: props.unit },
  ],
  series: [
    {},
    ...TIRE_CORNERS.map((c) => ({
      label: TIRE_CORNER_LABEL[c],
      stroke: TIRE_CORNER_COLOR[c],
      width: 1.6,
      spanGaps: false,
      paths: stepped,
      points: { show: false },
    })),
  ],
  hooks: {
    setCursor: [
      (u: uPlot) => {
        const idx = u.cursor.idx;
        cursorIdx.value = idx == null || u.cursor.left == null || u.cursor.left < 0 ? null : idx;
      },
    ],
  },
}));
</script>

<template>
  <div class="tires-chart" @mouseleave="cursorIdx = null">
    <div class="legend" role="list" :aria-label="`Tires, ${unit}`">
      <span
        v-for="l in legend"
        :key="l.corner"
        role="listitem"
        class="leg"
        :class="{ absent: !l.present }"
      >
        <i class="sw" :style="{ background: l.color }" aria-hidden="true" />
        <span class="name">{{ l.label }}</span>
        <span class="val num">{{ l.text }}</span>
      </span>
      <span class="unit muted small">{{ unit }}</span>
      <span v-if="cursorTime" class="time muted small num">{{ cursorTime }}</span>
    </div>
    <UPlotChart :data="aligned" :options="opts" />
  </div>
</template>

<style scoped>
.legend {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 0.3rem 1rem;
  margin-bottom: 0.35rem;
  font-size: 0.82rem;
}
.leg {
  display: inline-flex;
  align-items: baseline;
  gap: 0.35rem;
}
.leg.absent {
  opacity: 0.4;
}
.sw {
  display: inline-block;
  width: 10px;
  height: 3px;
  border-radius: 2px;
  align-self: center;
}
.name {
  color: var(--c-ink2);
  font-weight: 500;
  letter-spacing: 0.04em;
}
.val {
  color: var(--c-ink0);
  font-family: "Geist Mono", ui-monospace, monospace;
}
.time {
  margin-left: auto;
}
.num {
  font-variant-numeric: tabular-nums;
}
</style>
