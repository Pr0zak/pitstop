<script setup lang="ts">
// Four-corner tire pressures, "dash view" (picked 2026-10-31; Android
// TpmsGrid.kt parity): a one-line verdict strip, then a top-down car whose four
// tires are coloured by status, with the big whole-psi number beside each
// wheel, its temp and the delta to the door placard. Status (tireStatus):
// "low" (≥ 2 psi below the highest tire) full amber, "placard" (only ≥ 3 psi
// under placard) dim amber, "under" (≥ 25 % under placard) red, fine green,
// no reading grey "—". Pressures always psi (as on the dash), temps °F / °C.
import { computed } from "vue";
import QtyValue from "@/components/QtyValue.vue";
import { fmtTempC } from "@/composables/useFormat";
import {
  fmtPlacardDelta,
  fmtTirePressure,
  placardCaption,
  tireHeadline,
  tireStatus,
  tpmsReadings,
  type TireCorner,
  type TirePlacard,
} from "@/lib/vehicleSignals";

const props = defineProps<{
  pressureKpa: Partial<Record<TireCorner, number | null>>;
  tempC?: Partial<Record<TireCorner, number | null>>;
  placard?: TirePlacard | null;
}>();

const readings = computed(() => tpmsReadings(props.pressureKpa, props.tempC ?? {}, props.placard ?? null));
const tires = computed(() =>
  readings.value.map((t) => ({
    ...t,
    status: tireStatus(t),
    delta: fmtPlacardDelta(t.belowPlacardPsi),
  })),
);
const by = computed(() => Object.fromEntries(tires.value.map((t) => [t.corner, t])) as Record<TireCorner, (typeof tires.value)[number]>);
const headline = computed(() => tireHeadline(readings.value, props.placard ?? null));
const caption = computed(() => placardCaption(props.placard ?? null));

const STATUS_WORD = { none: "no reading", good: "", placard: ", below placard", low: ", low", under: ", under-inflated" };
</script>

<template>
  <div class="tpms" role="group" aria-label="Tire pressures">
    <div v-if="headline" class="verdict" :class="headline.status">
      <span class="dot" aria-hidden="true" />{{ headline.text }}
    </div>
    <div class="layout">
      <div
        v-for="t in tires"
        :key="t.corner"
        class="tire"
        :class="[t.corner, t.status]"
        :aria-label="`${t.label} ${t.pressureKpa == null ? 'no reading' : fmtTirePressure(t.pressureKpa)}${t.pressureKpa == null ? '' : STATUS_WORD[t.status]}`"
      >
        <span class="corner">{{ t.label }}</span>
        <span class="press"
          ><QtyValue :text="fmtTirePressure(t.pressureKpa)" /></span
        >
        <span class="sub">{{ fmtTempC(t.tempC) }}</span>
        <span v-if="t.delta" class="sub delta" :class="{ spec: t.belowSpec, bad: t.underInflated }"
          >{{ t.delta }} vs spec</span
        >
      </div>
      <svg class="car" viewBox="0 0 84 172" aria-hidden="true">
        <rect class="wheel" :class="by.fl.status" x="0" y="24" width="17" height="34" rx="6" />
        <rect class="wheel" :class="by.fr.status" x="67" y="24" width="17" height="34" rx="6" />
        <rect class="wheel" :class="by.rl.status" x="0" y="113" width="17" height="34" rx="6" />
        <rect class="wheel" :class="by.rr.status" x="67" y="113" width="17" height="34" rx="6" />
        <rect class="body" x="9.5" y="1" width="65" height="170" rx="24" />
        <rect class="glass" x="18" y="41" width="48" height="22" rx="4" />
        <rect class="glass" x="20" y="134" width="44" height="14" rx="4" />
      </svg>
    </div>
    <div v-if="caption" class="caption">{{ caption }}</div>
  </div>
</template>

<style scoped>
.tpms {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}
.verdict {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.5rem 0.75rem;
  border-radius: var(--r-md);
  font-size: 0.88rem;
  font-weight: 500;
  color: var(--c-success);
  background: var(--c-success-soft);
}
.verdict .dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: currentColor;
  flex: none;
}
.verdict.placard,
.verdict.low {
  color: var(--c-warn);
  background: var(--c-warn-soft);
}
.verdict.under {
  color: var(--c-danger);
  background: var(--c-danger-soft);
}
.layout {
  display: grid;
  grid-template-columns: 1fr 84px 1fr;
  grid-template-rows: auto auto;
  gap: 2rem 0.75rem;
  align-items: center;
  max-width: 26rem;
  margin: 0 auto;
  width: 100%;
}
.car {
  grid-area: 1 / 2 / 3 / 3;
  width: 84px;
  height: 172px;
}
.tire {
  display: flex;
  flex-direction: column;
  line-height: 1.2;
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
.tire.fl,
.tire.rl {
  align-items: flex-end;
  text-align: right;
}
.corner,
.sub {
  font-size: 0.72rem;
  color: var(--c-ink3);
  letter-spacing: 0.04em;
  font-variant-numeric: tabular-nums;
}
.press {
  font-size: 2rem;
  font-weight: 500;
  font-variant-numeric: tabular-nums;
}
.tire.low .press {
  color: var(--c-warn);
}
.tire.under .press {
  color: var(--c-danger);
}
.delta.spec {
  color: var(--c-warn);
  font-weight: 600;
}
.delta.bad {
  color: var(--c-danger);
}
.wheel {
  fill: var(--c-line1);
}
.wheel.good {
  fill: var(--c-success);
}
.wheel.placard {
  fill: var(--c-warn);
  fill-opacity: 0.45;
}
.wheel.low {
  fill: var(--c-warn);
}
.wheel.under {
  fill: var(--c-danger);
}
.body {
  fill: var(--c-bg3);
  stroke: var(--c-line2);
  stroke-width: 1.5;
}
.glass {
  fill: var(--c-line1);
}
.caption {
  text-align: center;
  font-size: 0.72rem;
  color: var(--c-ink3);
  letter-spacing: 0.04em;
}
</style>
