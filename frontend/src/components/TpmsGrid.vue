<script setup lang="ts">
// Four-corner tire pressure layout (FL FR / RL RR around a car outline).
// Pressures always render in psi (as on the dash), temps in °F / °C; a tire
// ≥ 2 psi below the highest of the four is highlighted (see tpmsReadings).
// Corners with no reading still render, reading "—".
import { computed } from "vue";
import QtyValue from "@/components/QtyValue.vue";
import { fmtTempC } from "@/composables/useFormat";
import { fmtTirePressure, tpmsReadings, type TireCorner } from "@/lib/vehicleSignals";

const props = defineProps<{
  pressureKpa: Partial<Record<TireCorner, number | null>>;
  tempC?: Partial<Record<TireCorner, number | null>>;
}>();

const tires = computed(() => tpmsReadings(props.pressureKpa, props.tempC ?? {}));
</script>

<template>
  <div class="tpms" role="group" aria-label="Tire pressures">
    <div
      v-for="t in tires"
      :key="t.corner"
      class="tire"
      :class="[t.corner, { low: t.low }]"
      :aria-label="`${t.label} ${fmtTirePressure(t.pressureKpa)}${t.low ? ', low' : ''}`"
    >
      <span class="corner">{{ t.label }}<span v-if="t.low" class="low-tag">low</span></span>
      <span class="press"><QtyValue :text="fmtTirePressure(t.pressureKpa)" /></span>
      <span class="temp">{{ fmtTempC(t.tempC) }}</span>
    </div>
    <div class="body" aria-hidden="true" />
  </div>
</template>

<style scoped>
.tpms {
  display: grid;
  grid-template-columns: 1fr 44px 1fr;
  grid-template-rows: auto auto;
  gap: 0.5rem 0.7rem;
  align-items: stretch;
}
.tire {
  display: flex;
  flex-direction: column;
  gap: 0.1rem;
  padding: 0.45rem 0.6rem;
  border: 1px solid var(--c-line1);
  border-radius: var(--r-md);
}
.tire.fl {
  grid-area: 1 / 1;
}
.tire.fr {
  grid-area: 1 / 3;
}
.tire.rl {
  grid-area: 2 / 1;
}
.tire.rr {
  grid-area: 2 / 3;
}
.tire.fr,
.tire.rr {
  text-align: right;
  align-items: flex-end;
}
.body {
  grid-area: 1 / 2 / 3 / 3;
  border: 1px solid var(--c-line1);
  border-radius: 14px 14px 8px 8px;
  margin: 0.2rem 0;
}
.corner {
  font-size: 0.72rem;
  color: var(--c-ink3);
  letter-spacing: 0.04em;
}
.low-tag {
  margin-left: 0.35rem;
  color: var(--c-warn);
  text-transform: uppercase;
  font-weight: 600;
}
.press {
  font-size: 1.35rem;
  font-weight: 500;
}
.temp {
  font-size: 0.72rem;
  color: var(--c-ink3);
  font-variant-numeric: tabular-nums;
}
.tire.low {
  border-color: rgba(255, 176, 32, 0.55);
  background: var(--c-warn-soft);
}
.tire.low .press {
  color: var(--c-warn);
}
</style>
