<script setup lang="ts">
// Oil life from the Maintenance Minder (cluster DID 7068, `oil_life_pct`).
// Phone parity: OilLifeCard.kt. Thresholds + wording live in vehicleSignals.ts.
// Renders nothing until the car has reported once — a summary card, not a
// Live tile.
import { computed } from "vue";
import { Droplet } from "lucide-vue-next";
import { useVehiclesStore } from "@/stores/vehicles";
import { formatReadingAge } from "@/lib/fuelDisplay";
import { OIL_LIFE_METRIC, oilLifeHeadline, oilLifeStatus } from "@/lib/vehicleSignals";

const vehicles = useVehiclesStore();

const oil = computed(() => {
  const e = vehicles.selectedVehicle?.latest?.[OIL_LIFE_METRIC];
  const raw = e?.value_num;
  const pct = raw == null ? null : Number(raw);
  if (pct == null || !Number.isFinite(pct)) return null;
  return {
    pct,
    status: oilLifeStatus(pct),
    headline: oilLifeHeadline(pct),
    age: e?.time ? formatReadingAge(e.time) : null,
  };
});
</script>

<template>
  <section v-if="oil" class="card oil" :class="oil.status">
    <h3>
      <Droplet :size="14" aria-hidden="true" /> Oil life
      <span v-if="oil.age" class="muted oil-age">updated {{ oil.age }}</span>
    </h3>
    <div class="oil-head">
      <span class="oil-big">{{ Math.round(oil.pct) }}<small>%</small></span>
      <span class="oil-line">{{ oil.headline }}</span>
    </div>
    <div
      class="oil-bar"
      role="meter"
      aria-label="Oil life"
      aria-valuemin="0"
      aria-valuemax="100"
      :aria-valuenow="Math.round(oil.pct)"
    >
      <span :style="{ width: `${Math.max(0, Math.min(100, oil.pct))}%` }" />
    </div>
    <p class="muted oil-note">From the car's Maintenance Minder. Due soon at 15 %, due now at 5 %.</p>
  </section>
</template>

<style scoped>
.oil {
  --oil-c: var(--c-success);
}
.oil.due_soon {
  --oil-c: var(--c-warn);
}
.oil.due_now {
  --oil-c: var(--c-danger);
}
.oil h3 {
  display: flex;
  align-items: center;
  gap: 0.35rem;
}
.oil-age {
  margin-left: auto;
  font-size: 0.75rem;
  font-weight: 400;
  text-transform: none;
  letter-spacing: 0;
}
.oil-head {
  display: flex;
  align-items: baseline;
  gap: 0.7rem;
  margin: 0.3rem 0 0.5rem;
}
.oil-big {
  font-size: 2rem;
  font-weight: 700;
  color: var(--oil-c);
  font-variant-numeric: tabular-nums;
}
.oil-big small {
  font-size: 1rem;
  margin-left: 0.1rem;
}
.oil-line {
  font-weight: 600;
  color: var(--oil-c);
}
.oil.good .oil-line {
  color: var(--c-ink2);
  font-weight: 400;
}
.oil-bar {
  height: 8px;
  border-radius: 999px;
  background: var(--c-border, rgba(127, 127, 127, 0.25));
  overflow: hidden;
}
.oil-bar span {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: var(--oil-c);
}
.oil-note {
  margin: 0.5rem 0 0;
  font-size: 0.75rem;
}
</style>
