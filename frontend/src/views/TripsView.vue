<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useRouter } from "vue-router";
import { useVehiclesStore } from "@/stores/vehicles";
import { useAsync } from "@/composables/useAsync";
import * as api from "@/api/endpoints";
import {
  fmtDateTime,
  fmtDuration,
  fmtDistanceKm,
  fmtSpeedKph,
  fmtRpm,
  fmtVolumeL,
} from "@/composables/useFormat";

const vehicles = useVehiclesStore();
const router = useRouter();
const fromDate = ref<string>("");
const toDate = ref<string>("");
const limit = ref(50);
const offset = ref(0);

const vehicleId = computed(() => vehicles.selectedVehicleId);

function isoOrUndef(s: string): string | undefined {
  return s ? new Date(s).toISOString() : undefined;
}

const { data, loading, error, reload } = useAsync(
  () =>
    vehicleId.value
      ? api.listTrips({
          vehicle_id: vehicleId.value,
          from: isoOrUndef(fromDate.value),
          to: isoOrUndef(toDate.value),
          limit: limit.value,
          offset: offset.value,
        })
      : Promise.resolve({ items: [], total: 0 }),
  [vehicleId, fromDate, toDate, limit, offset],
);

watch([vehicleId, fromDate, toDate], () => {
  offset.value = 0;
});

function open(tripId: string) {
  router.push(`/trips/${tripId}`);
}
function nextPage() {
  if (data.value && offset.value + limit.value < data.value.total) {
    offset.value += limit.value;
  }
}
function prevPage() {
  offset.value = Math.max(0, offset.value - limit.value);
}
function reset() {
  fromDate.value = "";
  toDate.value = "";
  offset.value = 0;
  void reload();
}
</script>

<template>
  <div class="trips">
    <header class="head">
      <h1>Trips</h1>
      <div class="filters">
        <label>
          <span class="lbl">From</span>
          <input type="date" v-model="fromDate" />
        </label>
        <label>
          <span class="lbl">To</span>
          <input type="date" v-model="toDate" />
        </label>
        <button type="button" class="ghost" @click="reset">Reset</button>
      </div>
    </header>

    <div v-if="!vehicleId" class="card">
      <p class="muted">Select a vehicle to view its trips.</p>
    </div>
    <div v-else-if="loading" class="card">
      <p class="muted">Loading trips…</p>
    </div>
    <div v-else-if="error" class="card">
      <p class="muted">Failed to load: {{ error }}</p>
    </div>
    <div v-else-if="!data || data.items.length === 0" class="card">
      <p class="muted">No trips in this range.</p>
    </div>
    <template v-else>
      <div class="card no-pad">
        <table class="data">
          <thead>
            <tr>
              <th>Started</th>
              <th>Duration</th>
              <th>Distance</th>
              <th>Max speed</th>
              <th>Max RPM</th>
              <th>Fuel</th>
              <th>DTCs</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="t in data.items"
              :key="t.id"
              class="clickable"
              @click="open(t.id)"
            >
              <td>{{ fmtDateTime(t.started_at) }}</td>
              <td>{{ fmtDuration(t.duration_s) }}</td>
              <td>{{ fmtDistanceKm(t.distance_km ?? null) }}</td>
              <td>{{ fmtSpeedKph(t.max_speed_kph ?? null) }}</td>
              <td>{{ fmtRpm(t.max_rpm) }}</td>
              <td>{{ fmtVolumeL(t.fuel_used_l ?? null) }}</td>
              <td>{{ t.dtc_count ?? 0 }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <footer class="pager">
        <span class="muted">
          {{ offset + 1 }}–{{ Math.min(offset + data.items.length, data.total) }}
          of {{ data.total }}
        </span>
        <button type="button" :disabled="offset === 0" @click="prevPage">Prev</button>
        <button
          type="button"
          :disabled="offset + limit >= data.total"
          @click="nextPage"
        >Next</button>
      </footer>
    </template>
  </div>
</template>

<style scoped>
.trips {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}
.head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 1rem;
  flex-wrap: wrap;
}
.head h1 {
  margin: 0;
}
.filters {
  display: flex;
  align-items: flex-end;
  gap: 0.5rem;
}
.filters label {
  display: flex;
  flex-direction: column;
  gap: 0.2rem;
  font-size: 0.78rem;
  color: var(--c-muted);
}
.no-pad {
  padding: 0;
  overflow: hidden;
}
.pager {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 0.5rem;
}
</style>
