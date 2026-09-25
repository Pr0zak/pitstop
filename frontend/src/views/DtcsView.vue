<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { RouterLink } from "vue-router";
import { useVehiclesStore } from "@/stores/vehicles";
import { useAsync } from "@/composables/useAsync";
import { useQueryParam } from "@/composables/useQueryParam";
import { getDtcsTimeline, type DtcTimelineCode } from "@/api/endpoints";
import { fmtDateTime, fmtDistanceKm } from "@/composables/useFormat";
import StateCard from "@/components/StateCard.vue";
import WindowChips from "@/components/WindowChips.vue";

const vehicles = useVehiclesStore();
// Window selector — same options as the Trips page so users
// don't relearn the chrome. "all" caps at 10y to keep the SQL
// honest (matches the backend's 3650-day clamp).
type Window = "30d" | "90d" | "year" | "all";
const WINDOW_OPTIONS = [
  { value: "30d" as const, label: "30 days" },
  { value: "90d" as const, label: "90 days" },
  { value: "year" as const, label: "12 months" },
  { value: "all" as const, label: "All time" },
];
const window = useQueryParam<Window>("window", "year", ["30d", "90d", "year", "all"]);
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
// One ResizeObserver on the chart wrapper. (A function :ref used to create
// a fresh observer on every re-render and never disconnect them.)
const chartWrap = ref<HTMLElement | null>(null);
let ro: ResizeObserver | null = null;
watch(chartWrap, (el) => {
  ro?.disconnect();
  if (!el || typeof ResizeObserver === "undefined") return;
  ro = new ResizeObserver(() => {
    svgWidth.value = Math.max(600, el.clientWidth - 16);
  });
  ro.observe(el);
});
onBeforeUnmount(() => ro?.disconnect());

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

// Tooltip: hover previews, click / tap / Enter PINS it (with a link to
// the trip the code fired in). Esc or a click elsewhere unpins.
interface TipState {
  code: string;
  active: boolean;
  seen_at: string;
  trip_id: string | null;
  trip_distance_km: number | null;
  x: number;
  y: number;
}
const hoverEvent = ref<TipState | null>(null);
const pinned = ref(false);
function tipFor(c: DtcTimelineCode, idx: number, target: Element): TipState {
  const e = c.events[idx];
  const rect = target.getBoundingClientRect();
  return {
    code: c.code,
    active: c.active,
    seen_at: e.seen_at,
    trip_id: e.trip_id ?? null,
    trip_distance_km: e.trip_distance_km ?? null,
    x: rect.left + rect.width / 2,
    y: rect.top,
  };
}
function onDotEnter(c: DtcTimelineCode, idx: number, ev: MouseEvent) {
  if (pinned.value) return;
  hoverEvent.value = tipFor(c, idx, ev.currentTarget as Element);
}
function onDotLeave() {
  if (!pinned.value) hoverEvent.value = null;
}
function onDotPin(c: DtcTimelineCode, idx: number, ev: Event) {
  ev.stopPropagation();
  hoverEvent.value = tipFor(c, idx, ev.currentTarget as Element);
  pinned.value = true;
}
function unpin() {
  pinned.value = false;
  hoverEvent.value = null;
}
function onDocClick(e: MouseEvent) {
  if (!pinned.value) return;
  const t = e.target as Element | null;
  if (t?.closest?.(".tooltip")) return;
  unpin();
}
function onDocKey(e: KeyboardEvent) {
  if (e.key === "Escape" && pinned.value) unpin();
}
onMounted(() => {
  document.addEventListener("click", onDocClick);
  document.addEventListener("keydown", onDocKey);
});
onBeforeUnmount(() => {
  document.removeEventListener("click", onDocClick);
  document.removeEventListener("keydown", onDocKey);
});
watch([vehicleId, window], unpin);
</script>

<template>
  <div class="dtcs">
    <header class="head">
      <h1>Diagnostic codes</h1>
      <WindowChips v-model="window" :options="WINDOW_OPTIONS" />
    </header>

    <StateCard v-if="!vehicleId" state="empty" title="Select a vehicle to view its DTC history." />
    <StateCard v-else-if="loading && !data" state="loading" title="Loading codes…" />
    <StateCard v-else-if="error" state="error" :message="error" @retry="reload()" />
    <StateCard v-else-if="!data || data.codes.length === 0" state="empty" title="No diagnostic codes recorded in this window." />
    <template v-else>
      <div class="legend-line muted small">
        <span><span class="dot active" aria-hidden="true"></span> active</span>
        <span><span class="dot cleared" aria-hidden="true"></span> cleared</span>
        <span>Click a dot to pin it and jump to its trip.</span>
      </div>
      <div ref="chartWrap" class="card chart-card">
        <svg
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
              :fill="i % 2 === 0 ? 'var(--c-bg3)' : 'transparent'"
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
                :r="5"
                :class="['ev-dot', c.active ? 'active' : 'cleared']"
                tabindex="0"
                role="button"
                :aria-label="`${c.code} ${c.active ? 'active' : 'cleared'}, seen ${fmtDateTime(e.seen_at)}`"
                @mouseenter="(ev) => onDotEnter(c, j, ev)"
                @mouseleave="onDotLeave"
                @click="(ev) => onDotPin(c, j, ev)"
                @keydown.enter.prevent="(ev) => onDotPin(c, j, ev)"
                @keydown.space.prevent="(ev) => onDotPin(c, j, ev)"
              />
              <!-- Active end-cap: a thicker square at the right edge -->
              <rect
                v-if="c.active"
                :x="xFor(bounds.tMax, bounds, plotW) + 4"
                :y="GUTTER_TOP + i * ROW_H + ROW_H / 2 - 5"
                width="10"
                height="10"
                rx="2"
                fill="var(--c-danger)"
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
        <div class="table-scroll">
        <table class="data">
          <thead>
            <tr>
              <th>Code</th>
              <th>Description</th>
              <th class="num">Count</th>
              <th>First seen</th>
              <th>Last seen</th>
              <th>Status</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="c in data.codes" :key="`row-${c.code}`">
              <td><code :class="c.active ? 'code-active' : 'code-cleared'">{{ c.code }}</code></td>
              <td>{{ c.description ?? "—" }}</td>
              <td class="num">{{ c.count }}</td>
              <td>{{ fmtDateTime(c.first_seen) }}</td>
              <td>{{ fmtDateTime(c.last_seen) }}</td>
              <td>
                <span class="badge" :class="c.active ? 'danger' : ''">
                  {{ c.active ? "active" : "cleared" }}
                </span>
              </td>
            </tr>
          </tbody>
        </table>
        </div>
      </div>
    </template>

    <!-- Tooltip: hover preview, pinned on click / Enter -->
    <div
      v-if="hoverEvent"
      class="tooltip"
      :class="{ pinned }"
      :role="pinned ? 'dialog' : 'tooltip'"
      :aria-label="pinned ? `${hoverEvent.code} occurrence` : undefined"
      :style="{
        left: hoverEvent.x + 'px',
        top: hoverEvent.y - (pinned ? 70 : 44) + 'px',
      }"
    >
      <div>
        <code :class="hoverEvent.active ? 'code-active' : 'code-cleared'">{{ hoverEvent.code }}</code>
        <span class="muted small"> · {{ hoverEvent.active ? "active" : "cleared" }}</span>
      </div>
      <div class="muted small">{{ fmtDateTime(hoverEvent.seen_at) }}</div>
      <template v-if="pinned">
        <RouterLink v-if="hoverEvent.trip_id" :to="`/trips/${hoverEvent.trip_id}`" class="trip-link">
          Trip<template v-if="hoverEvent.trip_distance_km != null"> · {{ fmtDistanceKm(hoverEvent.trip_distance_km) }}</template> →
        </RouterLink>
        <span v-else class="muted small">No trip recorded at this time</span>
      </template>
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
.head {
  flex-wrap: wrap;
  gap: 0.5rem;
}
.legend-line {
  display: flex;
  flex-wrap: wrap;
  gap: 0.4rem 1rem;
  align-items: center;
}
.legend-line .dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-right: 0.25rem;
}
.dot.active,
.ev-dot.active {
  background: var(--c-danger);
  fill: var(--c-danger);
}
.dot.cleared,
.ev-dot.cleared {
  background: var(--c-ink3);
  fill: var(--c-ink3);
}
.ev-dot {
  cursor: pointer;
  outline: none;
}
.ev-dot:hover,
.ev-dot:focus-visible {
  stroke: var(--c-ink0);
  stroke-width: 2;
}
.code-active {
  color: var(--c-danger);
}
.code-cleared {
  color: var(--c-ink2);
}
.table-scroll {
  overflow-x: auto;
}
.trip-link {
  display: inline-block;
  margin-top: 0.3rem;
  font-size: 0.82rem;
}
/* Phone: the timeline can't be read at 390 px — table only. */
@media (max-width: 700px) {
  .chart-card,
  .legend-line {
    display: none;
  }
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
.tooltip.pinned {
  pointer-events: auto;
  border-color: var(--c-line2);
}
.tooltip code {
  font-size: 0.85rem;
}
.small {
  font-size: 0.72rem;
}
</style>
