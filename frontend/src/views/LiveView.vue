<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useVehiclesStore } from "@/stores/vehicles";
import { useAuthStore } from "@/stores/auth";
import { useLive } from "@/composables/useLive";
import ArcGauge from "@/components/charts/ArcGauge.vue";
import { fmtTemp, fmtPct } from "@/composables/useFormat";

const vehicles = useVehiclesStore();
const auth = useAuthStore();
const vehicleIdRef = ref<string | null>(vehicles.selectedVehicleId);

// Keep ref in sync with store selection.
watch(
  () => vehicles.selectedVehicleId,
  (id) => {
    vehicleIdRef.value = id;
  },
);

const { metrics, status } = useLive(vehicleIdRef);

function num(key: string): number | null {
  const v = metrics.value?.[key]?.value;
  if (typeof v === "number") return v;
  if (typeof v === "string") {
    const n = Number(v);
    return Number.isFinite(n) ? n : null;
  }
  return null;
}

const statusLabel = computed(() => {
  switch (status.value) {
    case "connecting":
      return { text: "connecting", cls: "warn" };
    case "open":
      return { text: "live", cls: "success" };
    case "stale":
      return { text: "stale", cls: "warn" };
    case "disconnected":
      return { text: "disconnected", cls: "danger" };
    default:
      return { text: "idle", cls: "" };
  }
});

const rpm = computed(() => num("engine_rpm"));
const speed = computed(() => num("vehicle_speed"));
const coolant = computed(() => num("coolant_temp"));
const atf = computed(() => num("atf_temp_f"));
const fuelLvl = computed(() => num("fuel_level"));
const voltage = computed(() => num("control_module_voltage"));
const throttle = computed(() => num("throttle_position"));
const load = computed(() => num("engine_load"));
</script>

<template>
  <div class="live">
    <header class="head">
      <h1>Live</h1>
      <div class="status">
        <span class="badge" :class="statusLabel.cls">{{ statusLabel.text }}</span>
      </div>
    </header>

    <div v-if="!auth.hasQueryToken" class="card">
      <p class="muted">Set up your QUERY token in Settings to start the live feed.</p>
    </div>
    <div v-else-if="!vehicleIdRef" class="card">
      <p class="muted">Select a vehicle from the picker above.</p>
    </div>
    <template v-else>
      <!-- TODO design pass: hero gauges layout could be revisited once we see real driving data on the screen. -->
      <section class="hero">
        <ArcGauge
          :value="rpm"
          :max="8000"
          :warn-at="5500"
          :danger-at="6500"
          label="Engine RPM"
          unit="rpm"
          :size="240"
        />
        <ArcGauge
          :value="speed"
          :max="120"
          :warn-at="80"
          label="Speed"
          unit="mph"
          :size="240"
        />
      </section>

      <section class="tiles">
        <div class="card tile">
          <h3>Coolant</h3>
          <div class="big">{{ fmtTemp(coolant) }}</div>
        </div>
        <div class="card tile">
          <h3>ATF temp</h3>
          <div class="big">{{ fmtTemp(atf) }}</div>
        </div>
        <div class="card tile">
          <h3>Fuel level</h3>
          <div class="big">{{ fuelLvl != null ? Math.round(fuelLvl) + "%" : "—" }}</div>
        </div>
        <div class="card tile">
          <h3>Battery</h3>
          <div class="big">{{ voltage != null ? voltage.toFixed(2) + " V" : "—" }}</div>
        </div>
        <div class="card tile">
          <h3>Throttle</h3>
          <div class="big">{{ fmtPct(throttle) }}</div>
        </div>
        <div class="card tile">
          <h3>Engine load</h3>
          <div class="big">{{ fmtPct(load) }}</div>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.live {
  display: flex;
  flex-direction: column;
  gap: 1.2rem;
}
.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.status .badge {
  font-size: 0.78rem;
}
.hero {
  display: flex;
  flex-wrap: wrap;
  gap: 2rem;
  justify-content: center;
  background: var(--c-surface);
  border: 1px solid var(--c-border-soft);
  border-radius: var(--r-md);
  padding: 1.5rem 1rem;
}
.tiles {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 0.8rem;
}
.tile .big {
  font-size: 1.6rem;
  font-weight: 600;
  letter-spacing: -0.02em;
}
</style>
