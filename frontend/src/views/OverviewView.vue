<script setup lang="ts">
import { computed } from "vue";
import { RouterLink } from "vue-router";
import { useVehiclesStore } from "@/stores/vehicles";
import { useAuthStore } from "@/stores/auth";
import { useAsync } from "@/composables/useAsync";
import * as api from "@/api/endpoints";
import {
  fmtRelative,
  fmtRpm,
  fmtSpeed,
  fmtTemp,
  fmtMpg,
  fmtMiles,
  fmtMoney,
  fmtDate,
} from "@/composables/useFormat";
import { Activity, Route, Fuel, AlertTriangle } from "lucide-vue-next";

const auth = useAuthStore();
const vehicles = useVehiclesStore();
const vehicleId = computed(() => vehicles.selectedVehicleId);

const tripsQ = useAsync(
  () =>
    vehicleId.value
      ? api.listTrips({ vehicle_id: vehicleId.value, limit: 5 })
      : Promise.resolve({ items: [], total: 0 }),
  [vehicleId],
);
const fillupsQ = useAsync(
  () =>
    vehicleId.value
      ? api.listFillups({ vehicle_id: vehicleId.value, limit: 5 })
      : Promise.resolve({ items: [], total: 0 }),
  [vehicleId],
);
const dtcsQ = useAsync(
  () => (vehicleId.value ? api.listDtcs(vehicleId.value, true) : Promise.resolve([])),
  [vehicleId],
);

const latest = computed(() => vehicles.selectedVehicle?.latest ?? {});
function num(key: string): number | null {
  const v = latest.value?.[key];
  if (typeof v === "number") return v;
  if (typeof v === "string") {
    const n = Number(v);
    return Number.isFinite(n) ? n : null;
  }
  return null;
}
</script>

<template>
  <div class="overview">
    <header class="head">
      <h1>{{ vehicles.selectedVehicle?.name ?? "Overview" }}</h1>
      <p class="muted" v-if="vehicles.selectedVehicle">
        Last seen {{ vehicles.selectedVehicle.last_seen_at ? fmtRelative(vehicles.selectedVehicle.last_seen_at) : "never" }}
      </p>
    </header>

    <div v-if="!auth.hasQueryToken" class="card need-token">
      <h3>Set up your API tokens</h3>
      <p class="muted">
        pitstop hasn't been authenticated with this browser yet. Add your QUERY token in
        Settings to start loading data.
      </p>
      <RouterLink to="/settings" class="link">Open Settings →</RouterLink>
    </div>

    <div v-else-if="vehicles.loading && !vehicles.loaded" class="card">
      <p class="muted">Loading vehicles…</p>
    </div>

    <div v-else-if="vehicles.vehicles.length === 0" class="card">
      <h3>No vehicles yet</h3>
      <p class="muted">
        Add a vehicle on the
        <RouterLink to="/vehicles">Vehicles</RouterLink>
        page, or import your Fuelio history on the
        <RouterLink to="/fuel/import">Fuel Import</RouterLink>
        page.
      </p>
    </div>

    <template v-else-if="vehicles.selectedVehicle">
      <section class="metric-grid">
        <div class="card metric">
          <h3>RPM</h3>
          <div class="big">{{ fmtRpm(num("engine_rpm")) }}</div>
        </div>
        <div class="card metric">
          <h3>Speed</h3>
          <div class="big">{{ fmtSpeed(num("vehicle_speed")) }}</div>
        </div>
        <div class="card metric">
          <h3>Coolant</h3>
          <div class="big">{{ fmtTemp(num("coolant_temp")) }}</div>
        </div>
        <div class="card metric">
          <h3>Fuel level</h3>
          <div class="big">
            {{ num("fuel_level") != null ? Math.round(num("fuel_level")!) + "%" : "—" }}
          </div>
        </div>
      </section>

      <!-- Brand tape: a thin redline accent under the metric grid that
           visually echoes the gauge cluster you'd see on the Live page.
           Subtle on its own but ties the page to the rest of the system. -->
      <div class="brand-tape" aria-hidden="true">
        <span class="tape-track" />
        <span class="tape-redline" />
      </div>

      <div class="row-grid">
        <section class="card">
          <h3>
            <Route :size="14" /> Recent trips
            <RouterLink to="/trips" class="more">all →</RouterLink>
          </h3>
          <div v-if="tripsQ.loading.value" class="muted">Loading…</div>
          <div v-else-if="tripsQ.error.value" class="muted">
            Failed to load: {{ tripsQ.error.value }}
          </div>
          <div v-else-if="!tripsQ.data.value || tripsQ.data.value.items.length === 0" class="muted">
            No trips yet.
          </div>
          <ul v-else class="recent">
            <li v-for="t in tripsQ.data.value.items" :key="t.id">
              <RouterLink :to="`/trips/${t.id}`">
                <span>{{ fmtDate(t.started_at) }}</span>
                <span class="muted">{{ fmtMiles(t.distance_mi) }}</span>
              </RouterLink>
            </li>
          </ul>
        </section>

        <section class="card">
          <h3>
            <Fuel :size="14" /> Recent fillups
            <RouterLink to="/fuel" class="more">all →</RouterLink>
          </h3>
          <div v-if="fillupsQ.loading.value" class="muted">Loading…</div>
          <div v-else-if="fillupsQ.error.value" class="muted">
            Failed to load: {{ fillupsQ.error.value }}
          </div>
          <div v-else-if="!fillupsQ.data.value || fillupsQ.data.value.items.length === 0" class="muted">
            No fillups recorded.
          </div>
          <ul v-else class="recent">
            <li v-for="f in fillupsQ.data.value.items" :key="f.id">
              <span>{{ fmtDate(f.fillup_date) }}</span>
              <span class="muted">{{ fmtMpg(f.mpg) }} · {{ fmtMoney(f.price_total) }}</span>
            </li>
          </ul>
        </section>

        <section class="card">
          <h3>
            <AlertTriangle :size="14" /> Active DTCs
            <RouterLink to="/dtcs" class="more">all →</RouterLink>
          </h3>
          <div v-if="dtcsQ.loading.value" class="muted">Loading…</div>
          <div v-else-if="dtcsQ.error.value" class="muted">
            Failed to load: {{ dtcsQ.error.value }}
          </div>
          <div v-else-if="!dtcsQ.data.value || dtcsQ.data.value.length === 0" class="muted">
            No active codes.
          </div>
          <ul v-else class="recent">
            <li v-for="d in dtcsQ.data.value" :key="d.id">
              <span><code>{{ d.code }}</code> {{ d.description ?? "" }}</span>
            </li>
          </ul>
        </section>

        <section class="card">
          <h3>
            <Activity :size="14" /> Live
            <RouterLink to="/live" class="more">open →</RouterLink>
          </h3>
          <p class="muted">
            Real-time gauges for {{ vehicles.selectedVehicle?.name }}. Open the Live view
            to subscribe to the WebSocket feed.
          </p>
        </section>
      </div>
    </template>
  </div>
</template>

<style scoped>
.overview {
  display: flex;
  flex-direction: column;
  gap: 1.2rem;
}
.head h1 {
  margin-bottom: 0.2rem;
}
.metric-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 0.8rem;
}
.metric .big {
  font-family: 'Geist Mono', ui-monospace, monospace;
  font-size: 2rem;
  font-weight: 500;
  letter-spacing: -0.04em;
  line-height: 1.0;
  font-variant-numeric: tabular-nums;
  color: var(--c-ink0);
}
.metric h3 {
  font-size: 11px;
  font-weight: 500;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: var(--c-ink3);
}
.brand-tape {
  position: relative;
  height: 2px;
  margin: 0.2rem 0 0.4rem 0;
  border-radius: 1px;
  overflow: hidden;
}
.tape-track {
  position: absolute; left: 0; top: 0; bottom: 0; right: 26%;
  background: var(--c-line0);
}
.tape-redline {
  position: absolute; left: 74%; top: 0; bottom: 0; right: 0;
  background: linear-gradient(90deg, transparent 0%, var(--c-accent) 35%);
  border-radius: 1px;
}
.row-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 1rem;
}
.recent {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
}
.recent li {
  display: flex;
  justify-content: space-between;
  padding: 0.5rem 0;
  border-bottom: 1px solid var(--c-line0);
  font-size: 0.9rem;
}
.recent li .muted {
  font-family: 'Geist Mono', ui-monospace, monospace;
  font-variant-numeric: tabular-nums;
  font-size: 0.85rem;
}
.recent li:last-child {
  border-bottom: none;
}
.recent a {
  display: flex;
  justify-content: space-between;
  width: 100%;
}
.card h3 {
  display: flex;
  align-items: center;
  gap: 0.4rem;
}
.more {
  margin-left: auto;
  font-size: 0.75rem;
  text-transform: none;
  letter-spacing: 0;
  font-weight: 500;
}
.need-token .link {
  display: inline-block;
  margin-top: 0.6rem;
}
</style>
