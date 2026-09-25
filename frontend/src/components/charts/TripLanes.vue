<script setup lang="ts">
/**
 * Stacked, synced trip-timeline lanes.
 *
 * One thin uPlot per metric instead of one chart with a y-axis per unit:
 * every lane gets its own y scale (so a 0–6000 rpm trace and a 0–100 %
 * throttle trace are both readable), all lanes share the x range, and the
 * cursor is synced across them (uPlot `cursor.sync`). Drag-zoom on any lane
 * zooms every lane; double-click or `resetZoom()` restores the full trip.
 *
 * Each row: label on the left, the chart, and the value under the cursor on
 * the right (the lane's min–max at rest). Legends are off — the row label is
 * the legend. Only the bottom lane draws time labels, formatted as a plain
 * clock time: uPlot's default formatter prefixes the first tick with the date
 * ("9/25/26 6:33a"), which collided with the next tick.
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import uPlot from "uplot";
import "uplot/dist/uPlot.min.css";
import { applyChartTheme, chartColors, withAlpha } from "@/lib/chartTheme";

export interface Lane {
  key: string;
  label: string;
  unit: string;
  color: string;
  values: (number | null)[];
  /** Fixed decimals for ticks and the readout (λ series need 3). */
  decimals?: number;
}

const props = withDefaults(
  defineProps<{
    /** Shared x column, unix seconds, ascending. */
    x: number[];
    lanes: Lane[];
    markers?: { ts: number; code: string }[];
    laneHeight?: number;
  }>(),
  { markers: () => [], laneHeight: 78 },
);
const emit = defineEmits<{ (e: "cursor", ts: number | null): void }>();

const root = ref<HTMLDivElement | null>(null);
const hosts = ref<HTMLDivElement[]>([]);
let charts: uPlot[] = [];
let builtKeys = "";
let syncingScale = false;
let ro: ResizeObserver | null = null;
const syncKey = `trip-lanes-${Math.random().toString(36).slice(2)}`;

const cursorIdx = ref<number | null>(null);
/** Current x zoom, or null for the full trip. Survives a rebuild. */
let zoom: { min: number; max: number } | null = null;

const X_AXIS_H = 26;

const clockFmt = new Intl.DateTimeFormat(undefined, { hour: "numeric", minute: "2-digit" });
const clockSecFmt = new Intl.DateTimeFormat(undefined, {
  hour: "numeric",
  minute: "2-digit",
  second: "2-digit",
});

/** `scale` picks the precision (the lane's larger magnitude for a range, so
 *  "0–44" doesn't render as "0.0–44"). */
function fmtVal(lane: Lane, v: number | null | undefined, scale = v): string {
  if (v == null || !Number.isFinite(v)) return "—";
  const dp = lane.decimals ?? (Math.abs(scale ?? v) >= 10 ? 0 : 1);
  return v.toFixed(dp);
}

const ranges = computed(() =>
  props.lanes.map((l) => {
    let lo = Infinity;
    let hi = -Infinity;
    for (const v of l.values) {
      if (v == null || !Number.isFinite(v)) continue;
      if (v < lo) lo = v;
      if (v > hi) hi = v;
    }
    return Number.isFinite(lo) ? { lo, hi } : null;
  }),
);

function readout(i: number): { text: string; live: boolean } {
  const lane = props.lanes[i];
  const idx = cursorIdx.value;
  if (idx != null && lane) return { text: fmtVal(lane, lane.values[idx]), live: true };
  const r = ranges.value[i];
  if (!r || !lane) return { text: "—", live: false };
  const m = Math.max(Math.abs(r.lo), Math.abs(r.hi));
  return { text: `${fmtVal(lane, r.lo, m)}–${fmtVal(lane, r.hi, m)}`, live: false };
}

const cursorTime = computed(() => {
  const idx = cursorIdx.value;
  const ts = idx != null ? props.x[idx] : null;
  return ts != null ? clockSecFmt.format(new Date(ts * 1000)) : null;
});

function destroyAll() {
  for (const c of charts) {
    try {
      c.destroy();
    } catch {
      /* ignore */
    }
  }
  charts = [];
}

function laneOpts(lane: Lane, i: number, width: number): uPlot.Options {
  const last = i === props.lanes.length - 1;
  const c = chartColors();
  const dp = lane.decimals;
  const markers = props.markers;
  const markerPlugin: uPlot.Plugin = {
    hooks: {
      draw: (u) => {
        if (!markers.length) return;
        const ctx = u.ctx;
        ctx.save();
        ctx.strokeStyle = c.danger;
        ctx.fillStyle = c.danger;
        ctx.lineWidth = 1;
        ctx.font = "11px Geist, ui-sans-serif, system-ui";
        for (const m of markers) {
          const px = u.valToPos(m.ts, "x", true);
          if (px < u.bbox.left || px > u.bbox.left + u.bbox.width) continue;
          ctx.beginPath();
          ctx.moveTo(px, u.bbox.top);
          ctx.lineTo(px, u.bbox.top + u.bbox.height);
          ctx.stroke();
          if (i === 0) ctx.fillText(m.code, px + 4, u.bbox.top + 11);
        }
        ctx.restore();
      },
    },
  };
  const base: uPlot.Options = {
    width,
    height: props.laneHeight + (last ? X_AXIS_H : 0),
    legend: { show: false },
    // Right padding leaves room for the last clock label ("6:10 PM").
    padding: [6, 22, last ? 0 : 2, 0],
    cursor: {
      sync: { key: syncKey, setSeries: false },
      drag: { x: true, y: false, setScale: true },
      points: { size: 6 },
      y: false,
    },
    scales: {
      x: { time: true },
      y: {
        range: (_u, lo, hi) => {
          if (lo == null || hi == null) return [0, 1];
          if (lo === hi) return [lo - 1, hi + 1];
          const pad = (hi - lo) * 0.08;
          return [lo - pad, hi + pad];
        },
      },
    },
    axes: [
      {
        show: true,
        size: last ? X_AXIS_H : 0,
        space: 80,
        // Clock time on every tick — no date prefix on the first one.
        values: last
          ? (_u: uPlot, splits: number[]) => splits.map((v) => clockFmt.format(new Date(v * 1000)))
          : (_u: uPlot, splits: number[]) => splits.map(() => ""),
        ticks: { show: last },
      },
      {
        size: 46,
        space: Math.max(18, Math.floor(props.laneHeight / 2.4)),
        ticks: { show: false },
        values: (_u: uPlot, splits: number[]) =>
          splits.map((v) => (dp != null ? v.toFixed(dp) : Math.abs(v) >= 1000 ? `${Math.round(v / 100) / 10}k` : String(+v.toFixed(2)))),
      },
    ],
    series: [
      {},
      {
        label: lane.label,
        stroke: lane.color,
        width: 1.4,
        fill: withAlpha(lane.color, 0.08),
        points: { show: false },
      },
    ],
    hooks: {
      setCursor: [
        (u) => {
          const idx = u.cursor.idx;
          // Every synced lane fires this; they share x so the index agrees.
          cursorIdx.value = idx == null || u.cursor.left == null || u.cursor.left < 0 ? null : idx;
          const ts = cursorIdx.value != null ? props.x[cursorIdx.value] : null;
          emit("cursor", ts ?? null);
        },
      ],
      setScale: [
        (u, key) => {
          if (key !== "x" || syncingScale) return;
          const { min, max } = u.scales.x;
          if (min == null || max == null) return;
          const full = props.x.length ? { min: props.x[0], max: props.x[props.x.length - 1] } : null;
          zoom = full && min <= full.min && max >= full.max ? null : { min, max };
          syncingScale = true;
          try {
            for (const other of charts) {
              if (other !== u) other.setScale("x", { min, max });
            }
          } finally {
            syncingScale = false;
          }
        },
      ],
    },
    plugins: [markerPlugin],
  };
  // Theme first (token axis colours, grid), then our per-lane specifics win.
  const themed = applyChartTheme(base);
  return { ...themed, series: base.series };
}

function build() {
  destroyAll();
  builtKeys = props.lanes.map((l) => l.key).join("|");
  if (!props.lanes.length || !props.x.length) return;
  props.lanes.forEach((lane, i) => {
    const host = hosts.value[i];
    if (!host) return;
    const w = host.clientWidth || 600;
    const u = new uPlot(laneOpts(lane, i, w), [props.x, lane.values], host);
    charts.push(u);
  });
  if (zoom) {
    const z = zoom;
    syncingScale = true;
    try {
      for (const c of charts) c.setScale("x", { min: z.min, max: z.max });
    } finally {
      syncingScale = false;
    }
  }
}

function resize() {
  charts.forEach((c, i) => {
    const host = hosts.value[i];
    if (!host) return;
    const last = i === charts.length - 1;
    c.setSize({ width: host.clientWidth, height: props.laneHeight + (last ? X_AXIS_H : 0) });
  });
}

function resetZoom() {
  zoom = null;
  if (!props.x.length) return;
  const min = props.x[0];
  const max = props.x[props.x.length - 1];
  syncingScale = true;
  try {
    for (const c of charts) c.setScale("x", { min, max });
  } finally {
    syncingScale = false;
  }
}
defineExpose({ resetZoom });

onMounted(() => {
  build();
  if (typeof ResizeObserver !== "undefined" && root.value) {
    ro = new ResizeObserver(() => resize());
    ro.observe(root.value);
  }
});

// Same lanes (e.g. a Smooth-level change) → setData in place, keeping zoom.
// Different lane set → rebuild after the DOM has the new hosts.
watch(
  () => [props.lanes, props.x] as const,
  async () => {
    const keys = props.lanes.map((l) => l.key).join("|");
    if (keys === builtKeys && charts.length === props.lanes.length) {
      props.lanes.forEach((lane, i) => charts[i].setData([props.x, lane.values], zoom == null));
      if (zoom) {
        const z = zoom;
        syncingScale = true;
        try {
          for (const c of charts) c.setScale("x", { min: z.min, max: z.max });
        } finally {
          syncingScale = false;
        }
      }
      return;
    }
    await nextTick();
    build();
  },
);
watch(
  () => props.markers,
  async () => {
    await nextTick();
    build();
  },
);

onBeforeUnmount(() => {
  ro?.disconnect();
  ro = null;
  destroyAll();
});
</script>

<template>
  <div ref="root" class="lanes" @mouseleave="cursorIdx = null">
    <div v-if="cursorTime" class="cursor-time num" aria-live="off">{{ cursorTime }}</div>
    <div
      v-for="(lane, i) in lanes"
      :key="lane.key"
      class="lane"
      :class="{ last: i === lanes.length - 1 }"
    >
      <div class="lane-label">
        <span class="swatch" :style="{ background: lane.color }" aria-hidden="true" />
        <span class="name">{{ lane.label }}</span>
        <span v-if="lane.unit" class="unit">{{ lane.unit }}</span>
      </div>
      <div
        :ref="(el) => { if (el) hosts[i] = el as HTMLDivElement }"
        class="lane-chart"
      />
      <div class="lane-value num" :class="{ live: readout(i).live }">
        {{ readout(i).text }}
      </div>
    </div>
  </div>
</template>

<style scoped>
.lanes {
  position: relative;
  display: flex;
  flex-direction: column;
}
.lane {
  display: grid;
  grid-template-columns: 7.5rem minmax(0, 1fr) 5.5rem;
  grid-template-areas: "label chart value";
  align-items: start;
  border-top: 1px solid var(--c-line0);
}
.lane:first-of-type,
.cursor-time + .lane {
  border-top: 0;
}
.lane-label {
  grid-area: label;
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 0.15rem 0.35rem;
  padding: 0.55rem 0.4rem 0 0;
  min-width: 0;
}
.swatch {
  width: 8px;
  height: 8px;
  border-radius: 2px;
  flex: none;
  align-self: center;
}
.name {
  font-size: 0.8rem;
  font-weight: 500;
  color: var(--c-ink1);
}
.unit {
  font-size: 0.72rem;
  color: var(--c-ink3);
}
.lane-chart {
  grid-area: chart;
  min-width: 0;
}
.lane-value {
  grid-area: value;
  padding: 0.55rem 0 0 0.5rem;
  text-align: right;
  font-size: 0.78rem;
  color: var(--c-ink3);
  white-space: nowrap;
}
.lane-value.live {
  font-size: 0.95rem;
  color: var(--c-ink0);
}
.cursor-time {
  position: absolute;
  top: -1.55rem;
  right: 0;
  font-size: 0.75rem;
  color: var(--c-ink2);
}
.num {
  font-family: "Geist Mono", ui-monospace, monospace;
  font-variant-numeric: tabular-nums;
}
@media (max-width: 700px) {
  /* Lanes go full width; label and readout share a line above the chart. */
  .lane {
    grid-template-columns: minmax(0, 1fr) auto;
    grid-template-areas:
      "label value"
      "chart chart";
  }
  .lane-label,
  .lane-value {
    padding-top: 0.4rem;
  }
}
</style>
