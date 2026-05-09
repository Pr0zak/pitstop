<script setup lang="ts">
import { computed } from "vue";

/**
 * Arc gauge — claude.ai/design canonical implementation.
 *
 * Geometry: 270° sweep from -135° (lower-left) to +135° (lower-right),
 * mirroring the brand mark exactly so the live cluster reads as the
 * literal logo motif made functional. Renders four arc strands stacked:
 *
 *   1. Cool body  (bg3)        — unfilled portion of the cool zone
 *   2. Active fill (ink1/warn) — value-up-to-redline, opacity 0.85
 *   3. Redline    (accent)     — fixed segment from redlineFrac to end
 *   4. Past-redline overlay    — value past redlineFrac flashes accent
 *
 * Plus 41 ticks (5 minors per major × 8 major + 1), tick labels at
 * majors, a needle line from a small offset behind centre to a point
 * just inside the arc, and a hub disc.
 *
 * The component preserves the legacy warnAt / dangerAt prop API so
 * existing callers (LiveView's small gauges) work unchanged. When
 * dangerAt is supplied we derive redlineFrac from it and flip the
 * danger flag so the overlay flashes.
 */
const props = defineProps<{
  value: number | null;
  min?: number;
  max: number;
  label?: string;
  unit?: string;
  size?: number;
  digits?: number;
  warnAt?: number;
  dangerAt?: number;
  /** 0..1 along the sweep where the redline begins. Overrides dangerAt. */
  redlineFrac?: number;
  /** Optional formatter for the centre value. Falls back to .toFixed(digits ?? 0). */
  formatter?: (v: number) => string;
  /** Optional sub-value rendered below the unit label. */
  subValue?: string;
}>();

const W = computed(() => props.size ?? 240);
const H = computed(() => W.value);
const cx = computed(() => W.value / 2);
const cy = computed(() => W.value / 2 + Math.round(W.value * 0.025));
const r = computed(() => W.value / 2 - W.value * 0.10);
const arcStroke = computed(() => Math.max(3, Math.round(W.value / 90)));

const startA = -135;
const endA = 135;
const sweep = endA - startA; // 270°

const min = computed(() => props.min ?? 0);
const range = computed(() => Math.max(props.max - min.value, 0.0001));
const valueOrZero = computed(() => (props.value == null ? 0 : props.value));
const valFrac = computed(() => {
  const f = (valueOrZero.value - min.value) / range.value;
  return Math.max(0, Math.min(1.05, f));
});
const valA = computed(() => startA + sweep * valFrac.value);

const redlineFrac = computed(() => {
  if (props.redlineFrac != null) return Math.max(0, Math.min(1, props.redlineFrac));
  if (props.dangerAt != null) {
    return Math.max(0, Math.min(1, (props.dangerAt - min.value) / range.value));
  }
  return 1; // no redline
});
const redA = computed(() => startA + sweep * redlineFrac.value);

const isDanger = computed(
  () => props.value != null && props.dangerAt != null && props.value >= props.dangerAt,
);
const isWarn = computed(
  () =>
    props.value != null &&
    !isDanger.value &&
    props.warnAt != null &&
    props.value >= props.warnAt,
);

function pointOnCircle(angleDeg: number, rad = r.value) {
  // SVG uses screen coords (y down). Logo + design library both use
  // (a-90)° rotation so 0° is straight up. Mirror that here so the
  // gauge geometry lines up perfectly with the brand mark.
  const a = ((angleDeg - 90) * Math.PI) / 180;
  return [cx.value + Math.cos(a) * rad, cy.value + Math.sin(a) * rad];
}

function describeArc(a0: number, a1: number, rad = r.value) {
  const [x0, y0] = pointOnCircle(a0, rad);
  const [x1, y1] = pointOnCircle(a1, rad);
  const large = a1 - a0 > 180 ? 1 : 0;
  const dir = a1 > a0 ? 1 : 0;
  return `M ${x0.toFixed(2)} ${y0.toFixed(2)} A ${rad} ${rad} 0 ${large} ${dir} ${x1.toFixed(2)} ${y1.toFixed(2)}`;
}

const coolPath = computed(() => describeArc(startA, redA.value));
const fillPath = computed(() => {
  if (valFrac.value <= 0) return "";
  const stop = Math.min(valA.value, redA.value);
  if (stop <= startA + 0.01) return "";
  return describeArc(startA, stop);
});
const redlinePath = computed(() => describeArc(redA.value, endA));
const pastRedlinePath = computed(() => {
  if (valFrac.value <= redlineFrac.value) return "";
  return describeArc(redA.value, Math.min(valA.value, endA));
});

interface Tick {
  v: number;
  a: number;
  isMaj: boolean;
  isRed: boolean;
}
const ticks = computed<Tick[]>(() => {
  const minorsPerMajor = 5;
  const majors = 8;
  const total = majors * minorsPerMajor;
  const out: Tick[] = [];
  for (let i = 0; i <= total; i++) {
    const v = min.value + (i / total) * range.value;
    const a = startA + ((v - min.value) / range.value) * sweep;
    out.push({
      v,
      a,
      isMaj: i % minorsPerMajor === 0,
      isRed: a >= redA.value - 0.01,
    });
  }
  return out;
});

function tickLine(tk: Tick) {
  const len = tk.isMaj ? W.value * 0.05 : W.value * 0.022;
  const w = tk.isMaj ? Math.max(1.2, W.value / 220) : Math.max(0.8, W.value / 360);
  const [x1, y1] = pointOnCircle(tk.a, r.value - arcStroke.value / 2 - 2);
  const [x0, y0] = pointOnCircle(tk.a, r.value - arcStroke.value / 2 - 2 - len);
  const col = tk.isRed
    ? "var(--c-accent)"
    : tk.isMaj
      ? "var(--c-ink2)"
      : "var(--c-ink4)";
  return { x0, y0, x1, y1, w, col };
}

const showLabels = computed(() => W.value >= 220);
const labelR = computed(() => r.value - W.value * 0.13);

function tickLabel(tk: Tick) {
  const [lx, ly] = pointOnCircle(tk.a, labelR.value);
  return {
    x: lx,
    y: ly + 4,
    fill: tk.isRed ? "var(--c-accent)" : "var(--c-ink2)",
    text: Math.round(tk.v).toString(),
  };
}

const needle = computed(() => {
  const [tipX, tipY] = pointOnCircle(valA.value, r.value - arcStroke.value - 6);
  const [bx, by] = pointOnCircle(valA.value + 180, W.value * 0.04);
  return { tipX, tipY, bx, by };
});

const display = computed(() => {
  if (props.value == null) return "—";
  if (props.formatter) return props.formatter(props.value);
  const d = props.digits ?? 0;
  return Number(props.value).toFixed(d);
});

const fillColor = computed(() => {
  if (isDanger.value) return "var(--c-danger)";
  if (isWarn.value) return "var(--c-warn)";
  return "var(--c-ink1)";
});
</script>

<template>
  <div
    class="gauge"
    :class="{ danger: isDanger }"
    :style="{ width: W + 'px', height: H + 'px' }"
  >
    <svg :width="W" :height="H" :viewBox="`0 0 ${W} ${H}`">
      <path
        :d="coolPath"
        fill="none"
        stroke="var(--c-bg3)"
        :stroke-width="arcStroke"
        stroke-linecap="butt"
      />
      <path
        v-if="fillPath"
        :d="fillPath"
        fill="none"
        :stroke="fillColor"
        :stroke-width="arcStroke"
        stroke-linecap="butt"
        opacity="0.85"
      />
      <path
        :d="redlinePath"
        fill="none"
        stroke="var(--c-accent)"
        :stroke-width="arcStroke"
        stroke-linecap="round"
      />
      <path
        v-if="pastRedlinePath"
        :d="pastRedlinePath"
        fill="none"
        stroke="var(--c-accent)"
        :stroke-width="arcStroke + 1"
        stroke-linecap="round"
        opacity="0.95"
      />

      <!-- ticks -->
      <line
        v-for="(tk, i) in ticks"
        :key="`t-${i}`"
        v-bind="(({ x0, y0, x1, y1, w, col }) => ({ x1: x0, y1: y0, x2: x1, y2: y1, stroke: col, 'stroke-width': w }))(tickLine(tk))"
        stroke-linecap="round"
      />

      <!-- tick labels at majors -->
      <template v-if="showLabels">
        <text
          v-for="(tk, i) in ticks.filter((t) => t.isMaj)"
          :key="`l-${i}`"
          :x="tickLabel(tk).x"
          :y="tickLabel(tk).y"
          text-anchor="middle"
          :fill="tickLabel(tk).fill"
          class="tick-label"
        >{{ tickLabel(tk).text }}</text>
      </template>

      <!-- needle -->
      <g class="needle" :class="{ flash: isDanger }">
        <line
          :x1="needle.bx"
          :y1="needle.by"
          :x2="needle.tipX"
          :y2="needle.tipY"
          :stroke="isDanger ? 'var(--c-danger)' : 'var(--c-ink0)'"
          :stroke-width="Math.max(2, W / 110)"
          stroke-linecap="round"
          opacity="0.96"
        />
        <circle
          :cx="cx"
          :cy="cy"
          :r="W * 0.024"
          fill="var(--c-bg)"
          :stroke="isDanger ? 'var(--c-danger)' : 'var(--c-ink0)'"
          :stroke-width="Math.max(1, W / 200)"
        />
      </g>
    </svg>

    <!-- centre value -->
    <div class="centre" :class="{ danger: isDanger }">
      <div class="value">{{ display }}</div>
      <div v-if="unit" class="unit">{{ unit }}</div>
      <div v-if="subValue" class="sub">{{ subValue }}</div>
    </div>

    <!-- bottom label -->
    <div v-if="label" class="label">{{ label }}</div>
  </div>
</template>

<style scoped>
.gauge {
  position: relative;
  display: inline-block;
}
.gauge svg {
  display: block;
  transition: filter 200ms;
}
.gauge.danger svg {
  filter: drop-shadow(0 0 12px rgba(255, 91, 58, 0.18));
}

.tick-label {
  font-family: 'Geist Mono', ui-monospace, monospace;
  font-size: 11px;
  font-weight: 500;
  font-variant-numeric: tabular-nums;
}

.needle line {
  /* Springy needle settle animation. The CB curve overshoots slightly,
     reading like a real instrument. */
  transition: all 220ms cubic-bezier(0.34, 1.56, 0.64, 1);
}
.needle.flash line {
  animation: needle-flash 800ms ease-in-out infinite;
}
@keyframes needle-flash {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.6; }
}

.centre {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  pointer-events: none;
  /* Lift the centre group up just enough to clear the bottom label
     and balance the lower 90° of the arc geometry. */
  padding-bottom: calc(var(--gauge-bottom-pad, 6%));
  text-align: center;
}
.centre .value {
  font-family: 'Geist Mono', ui-monospace, monospace;
  font-size: clamp(28px, 18%, 64px);
  font-weight: 500;
  letter-spacing: -0.04em;
  font-variant-numeric: tabular-nums;
  line-height: 0.92;
  color: var(--c-ink0);
}
.centre.danger .value {
  color: var(--c-danger);
}
.centre .unit {
  margin-top: 6px;
  font-size: 11px;
  font-weight: 500;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: var(--c-ink2);
}
.centre .sub {
  margin-top: 4px;
  font-family: 'Geist Mono', ui-monospace, monospace;
  font-size: 11px;
  color: var(--c-ink3);
}

.label {
  position: absolute;
  bottom: 8px;
  left: 0;
  right: 0;
  text-align: center;
  color: var(--c-ink2);
  font-size: 11px;
  font-weight: 500;
  letter-spacing: 0.16em;
  text-transform: uppercase;
  pointer-events: none;
}
</style>
