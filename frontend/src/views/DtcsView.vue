<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useVehiclesStore } from "@/stores/vehicles";
import { useAsync } from "@/composables/useAsync";
import { getDtcsTimeline, type DtcTimelineCode } from "@/api/endpoints";
import { fmtDateTime } from "@/composables/useFormat";

const vehicles = useVehiclesStore();
// Window selector — same options as the Trips page so users
// don't relearn the chrome. "all" caps at 10y to keep the SQL
// honest (matches the backend's 3650-day clamp).
type Window = "30d" | "90d" | "year" | "all";
const window = ref<Window>("year");
const days = computed(() =>
  window.value === "30d" ? 30
  : window.value === "90d" ? 90
  : window.value === "year" ? 365
  : 3650,
);

const vehicleId = computed(() => vehicles.selectedVehicleId);
const { data, loading, error, reload } = useAsync(
  () =>
    vehicleId.value
      ? getDtcsTimeline(vehicleId.value, days.value)
      : Promise.resolve({ codes: [], window_days: days.value }),
  [vehicleId, days],
);
watch(vehicleId, () => void reload());

// Bounds across all events — used to scale the SVG.
interface Bounds { tMin: number; tMax: number }
const bounds = computed<Bounds | null>(() => {
  const codes = data.value?.codes ?? [];
  if (codes.length === 0) return null;
  let tMin = Infinity;
  let tMax = -Infinity;
  for (const c of codes) {
    for (const e of c.events) {
      const t = Date.parse(e.seen_at);
      if (Number.isFinite(t)) {
        if (t < tMin) tMin = t;
        if (t > tMax) tMax = t;
      }
    }
  }
  if (!Number.isFinite(tMin) || !Number.isFinite(tMax)) return null;
  // 2 % padding on each side so the first/last dot aren't flush
  // with the row edge.
  const pad = Math.max((tMax - tMin) * 0.02, 30 * 60 * 1000);
  return { tMin: tMin - pad, tMax: tMax + pad };
});

const ROW_H = 38;
const GUTTER_LEFT = 110;
const GUTTER_RIGHT = 60;
const GUTTER_TOP = 18;
const GUTTER_BOTTOM = 24;

const svgWidth = ref(960);
function onSvgRef(el: unknown) {
  if (!(el instanceof Element)) return;
  // ResizeObserver keeps the timeline responsive without a full
  // re-render — we just rescale the x-positions.
  const ro = new ResizeObserver(() => {
    svgWidth.value = (el as SVGElement).clientWidth || 960;
  });
  ro.observe(el);
}

function xFor(t: number, b: Bounds, plotW: number): number {
  if (b.tMax === b.tMin) return GUTTER_LEFT + plotW / 2;
  return GUTTER_LEFT + ((t - b.tMin) / (b.tMax - b.tMin)) * plotW;
}

const plotW = computed(() => svgWidth.value - GUTTER_LEFT - GUTTER_RIGHT);
const svgHeight = computed(
  () => GUTTER_TOP + (data.value?.codes?.length ?? 0) * ROW_H + GUTTER_BOTTOM,
);

interface AxisTick { x: number; label: string }
const axisTicks = computed<AxisTick[]>(() => {
  if (!bounds.value) return [];
  const span = bounds.value.tMax - bounds.value.tMin;
  // Pick a sensible step: month for >90 days, week for 30-90, day else.
  const day = 86400_000;
  let step: number;
  let fmt: (d: Date) => string;
  if (span > 180 * day) { step = 30 * day; fmt = (d) => d.toLocaleString([], { month: "short", year: "2-digit" }); }
  else if (span > 60 * day) { step = 7 * day; fmt = (d) => d.toLocaleDateString([], { month: "short", day: "numeric" }); }
  else if (span > 7 * day) { step = day; fmt = (d) => d.toLocaleDateString([], { month: "short", day: "numeric" }); }
  else { step = day; fmt = (d) => d.toLocaleDateString([], { weekday: "short", day: "numeric" }); }
  const out: AxisTick[] = [];
  // Start at the nearest step boundary at-or-before tMin.
  const start = Math.floor(bounds.value.tMin / step) * step;
  for (let t = start; t <= bounds.value.tMax + step; t += step) {
    out.push({ x: xFor(t, bounds.value, plotW.value), label: fmt(new Date(t)) });
  }
  return out;
});

// Hover state — show seen_at on the dot under the cursor.
const hoverEvent = ref<{ code: string; seen_at: string; x: number; y: number } | null>(null);
function onDotEnter(c: DtcTimelineCode, idx: number, ev: MouseEvent) {
  const e = c.events[idx];
  const target = ev.currentTarget as SVGElement;
  const rect = target.getBoundingClientRect();
  hoverEvent.value = {
    code: c.code,
    seen_at: e.seen_at,
    x: rect.left + rect.width / 2,
    y: rect.top,
  };
}
function onDotLeave() {
  hoverEvent.value = null;
}
</script>

<template>
  <div class="dtcs">
    <header class="head">
      <h1>Diagnostic codes</h1>
      <div class="filters">
        <button
          v-for="w in ['30d','90d','year','all'] as Window[]"
          :key="w"
          type="button"
          class="ghost"
          :class="{ active: window === w }"
          @click="window = w"
        >
          {{ w === '30d' ? '30 d' : w === '90d' ? '90 d' : w === 'year' ? '1 yr' : 'All' }}
        </button>
      </div>
    </header>

    <div v-if="!vehicleId" class="card">
      <p class="muted">Select a vehicle to view its DTC history.</p>
    </div>
    <div v-else-if="loading" class="card">
      <p class="muted">Loading…</p>
    </div>
    <div v-else-if="error" class="card">
      <p class="muted">Failed to load DTCs: {{ error }}</p>
    </div>
    <div v-else-if="!data || data.codes.length === 0" class="card">
      <p class="muted">No diagnostic codes recorded in this window.</p>
    </div>
    <template v-else>
      <div class="card chart-card">
        <svg
          :ref="onSvgRef"
          class="timeline"
          :viewBox="`0 0 ${svgWidth} ${svgHeight}`"
          :width="svgWidth"
          :height="svgHeight"
          preserveAspectRatio="none"
        >
          <!-- Row backgrounds -->
          <g v-if="bounds">
            <rect
              v-for="(c, i) in data.codes"
              :key="`bg-${c.code}`"
              :x="GUTTER_LEFT"
              :y="GUTTER_TOP + i * ROW_H + 4"
              :width="plotW"
              :height="ROW_H - 8"
              :fill="i % 2 === 0 ? 'var(--c-surface-soft)' : 'transparent'"
              rx="4"
            />
          </g>
          <!-- Code labels -->
          <g v-if="bounds">
            <g v-for="(c, i) in data.codes" :key="`lbl-${c.code}`">
              <text
                :x="GUTTER_LEFT - 12"
                :y="GUTTER_TOP + i * ROW_H + ROW_H / 2 + 4"
                text-anchor="end"
                class="row-code"
              >
                {{ c.code }}
              </text>
              <text
                :x="GUTTER_LEFT - 12"
                :y="GUTTER_TOP + i * ROW_H + ROW_H / 2 + 16"
                text-anchor="end"
                class="row-count"
              >
                {{ c.count }}×
              </text>
            </g>
          </g>
          <!-- Event dots + active end-cap -->
          <g v-if="bounds">
            <g v-for="(c, i) in data.codes" :key="`dots-${c.code}`">
              <circle
                v-for="(e, j) in c.events"
                :key="e.id"
                :cx="xFor(Date.parse(e.seen_at), bounds, plotW)"
                :cy="GUTTER_TOP + i * ROW_H + ROW_H / 2"
                :r="4"
                :fill="c.active ? 'var(--c-warn, #ef4444)' : 'var(--c-accent, #2f81f7)'"
                :opacity="0.85"
                @mouseenter="(ev) => onDotEnter(c, j, ev)"
                @mouseleave="onDotLeave"
              />
              <!-- Active end-cap: a thicker square at the right edge -->
              <rect
                v-if="c.active"
                :x="xFor(bounds.tMax, bounds, plotW) + 4"
                :y="GUTTER_TOP + i * ROW_H + ROW_H / 2 - 5"
                width="10"
                height="10"
                rx="2"
                fill="var(--c-warn, #ef4444)"
              />
            </g>
          </g>
          <!-- X-axis ticks -->
          <g v-if="bounds">
            <g v-for="(t, i) in axisTicks" :key="`tick-${i}`">
              <line
                :x1="t.x" :x2="t.x"
                :y1="GUTTER_TOP - 4"
                :y2="svgHeight - GUTTER_BOTTOM + 4"
                stroke="var(--c-border-soft)"
                stroke-width="1"
                stroke-dasharray="2,3"
              />
              <text
                :x="t.x"
                :y="svgHeight - 6"
                text-anchor="middle"
                class="axis-label"
              >{{ t.label }}</text>
            </g>
          </g>
        </svg>
      </div>

      <!-- Code legend / detail strip beneath the chart -->
      <div class="card no-pad">
        <table class="data">
          <thead>
            <tr>
              <th>Code</th>
              <th>Description</th>
              <th>Count</th>
              <th>First seen</th>
              <th>Last seen</th>
              <th>Status</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="c in data.codes" :key="`row-${c.code}`">
              <td><code>{{ c.code }}</code></td>
              <td>{{ c.description ?? "—" }}</td>
              <td>{{ c.count }}</td>
              <td>{{ fmtDateTime(c.first_seen) }}</td>
              <td>{{ fmtDateTime(c.last_seen) }}</td>
              <td>
                <span class="badge" :class="c.active ? 'danger' : 'success'">
                  {{ c.active ? "active" : "cleared" }}
                </span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>

    <!-- Floating tooltip on hover -->
    <div
      v-if="hoverEvent"
      class="tooltip"
      :style="{
        left: hoverEvent.x + 'px',
        top: hoverEvent.y - 36 + 'px',
      }"
    >
      <div><code>{{ hoverEvent.code }}</code></div>
      <div class="muted small">{{ fmtDateTime(hoverEvent.seen_at) }}</div>
    </div>
  </div>
</template>

<style scoped>
.dtcs {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}
.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 0.4rem;
}
.head h1 {
  margin: 0;
}
.filters {
  display: flex;
  gap: 0.3rem;
}
.filters .ghost.active {
  background: var(--c-surface-soft);
  color: var(--c-text);
  border-color: var(--c-accent);
}
.chart-card {
  padding: 0.5rem;
  overflow-x: auto;
}
.timeline {
  display: block;
  min-width: 600px;
  width: 100%;
}
.row-code {
  fill: var(--c-text);
  font-family: ui-monospace, monospace;
  font-size: 0.85rem;
  font-weight: 600;
}
.row-count {
  fill: var(--c-muted);
  font-size: 0.7rem;
}
.axis-label {
  fill: var(--c-muted);
  font-size: 0.72rem;
}
.no-pad {
  padding: 0;
  overflow: hidden;
}
.tooltip {
  position: fixed;
  transform: translateX(-50%);
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--r-sm);
  padding: 0.3rem 0.5rem;
  font-size: 0.78rem;
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.4);
  pointer-events: none;
  z-index: 200;
}
.tooltip code {
  font-size: 0.85rem;
}
.small {
  font-size: 0.72rem;
}
</style>
