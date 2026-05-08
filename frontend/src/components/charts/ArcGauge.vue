<script setup lang="ts">
import { computed } from "vue";

const props = defineProps<{
  value: number | null;
  min?: number;
  max: number;
  label?: string;
  unit?: string;
  size?: number;
  digits?: number;
  warnAt?: number; // value above which the arc turns warn color
  dangerAt?: number; // value above which the arc turns danger color
}>();

const size = computed(() => props.size ?? 200);
const stroke = 14;
const radius = computed(() => size.value / 2 - stroke);
const cx = computed(() => size.value / 2);
const cy = computed(() => size.value / 2);

// Sweep ~270° from 135° (bottom-left) to 45° (bottom-right) clockwise.
const startAngleDeg = 135;
const endAngleDeg = 405; // 360 + 45
const sweepRange = endAngleDeg - startAngleDeg; // 270°

const min = computed(() => props.min ?? 0);
const range = computed(() => Math.max(props.max - min.value, 0.0001));
const fraction = computed(() => {
  if (props.value == null) return 0;
  const f = (props.value - min.value) / range.value;
  return Math.max(0, Math.min(1, f));
});
const valueAngleDeg = computed(() => startAngleDeg + sweepRange * fraction.value);

function pointOnCircle(angleDeg: number, r: number) {
  const a = (angleDeg * Math.PI) / 180;
  return [cx.value + r * Math.cos(a), cy.value + r * Math.sin(a)];
}

function describeArc(startDeg: number, endDeg: number) {
  const r = radius.value;
  const [x1, y1] = pointOnCircle(startDeg, r);
  const [x2, y2] = pointOnCircle(endDeg, r);
  const largeArc = endDeg - startDeg > 180 ? 1 : 0;
  return `M ${x1} ${y1} A ${r} ${r} 0 ${largeArc} 1 ${x2} ${y2}`;
}

const trackPath = computed(() => describeArc(startAngleDeg, endAngleDeg));
const valuePath = computed(() => {
  if (fraction.value <= 0) return "";
  return describeArc(startAngleDeg, valueAngleDeg.value);
});

const arcColor = computed(() => {
  if (props.value == null) return "var(--c-faint)";
  if (props.dangerAt != null && props.value >= props.dangerAt) return "var(--c-danger)";
  if (props.warnAt != null && props.value >= props.warnAt) return "var(--c-warn)";
  return "var(--c-accent)";
});

const display = computed(() => {
  if (props.value == null) return "—";
  const d = props.digits ?? 0;
  return Number(props.value).toFixed(d);
});
</script>

<template>
  <div class="gauge" :style="{ width: size + 'px' }">
    <svg :width="size" :height="size" :viewBox="`0 0 ${size} ${size}`">
      <path
        :d="trackPath"
        fill="none"
        stroke="var(--c-surface-3)"
        :stroke-width="stroke"
        stroke-linecap="round"
      />
      <path
        v-if="valuePath"
        :d="valuePath"
        fill="none"
        :stroke="arcColor"
        :stroke-width="stroke"
        stroke-linecap="round"
      />
      <text
        :x="cx"
        :y="cy + 6"
        text-anchor="middle"
        class="value"
      >{{ display }}</text>
      <text
        v-if="unit"
        :x="cx"
        :y="cy + 28"
        text-anchor="middle"
        class="unit"
      >{{ unit }}</text>
    </svg>
    <div v-if="label" class="label">{{ label }}</div>
  </div>
</template>

<style scoped>
.gauge {
  display: inline-flex;
  flex-direction: column;
  align-items: center;
  gap: 0.3rem;
}
.value {
  font-size: 2rem;
  font-weight: 600;
  fill: var(--c-text);
  letter-spacing: -0.02em;
}
.unit {
  font-size: 0.85rem;
  fill: var(--c-muted);
}
.label {
  font-size: 0.78rem;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  color: var(--c-muted);
}
</style>
