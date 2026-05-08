<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useVehiclesStore } from "@/stores/vehicles";
import { useAsync } from "@/composables/useAsync";
import { listDtcs } from "@/api/endpoints";
import { fmtDateTime } from "@/composables/useFormat";

const vehicles = useVehiclesStore();
const showActiveOnly = ref(true);

const vehicleId = computed(() => vehicles.selectedVehicleId);
const { data: dtcs, loading, error, reload } = useAsync(
  () =>
    vehicleId.value
      ? listDtcs(vehicleId.value, showActiveOnly.value)
      : Promise.resolve([]),
  [vehicleId, showActiveOnly],
);

watch(vehicleId, () => void reload());
</script>

<template>
  <div class="dtcs">
    <header class="head">
      <h1>Diagnostic codes</h1>
      <label class="toggle">
        <input type="checkbox" v-model="showActiveOnly" />
        Active only
      </label>
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
    <div v-else-if="!dtcs || dtcs.length === 0" class="card">
      <p class="muted">No diagnostic codes recorded.</p>
    </div>
    <div v-else class="card no-pad">
      <table class="data">
        <thead>
          <tr>
            <th>Code</th>
            <th>Description</th>
            <th>Detected</th>
            <th>Cleared</th>
            <th>Status</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="d in dtcs" :key="d.id">
            <td><code>{{ d.code }}</code></td>
            <td>{{ d.description ?? "—" }}</td>
            <td>{{ fmtDateTime(d.detected_at) }}</td>
            <td>{{ fmtDateTime(d.cleared_at) }}</td>
            <td>
              <span class="badge" :class="d.active ? 'danger' : 'success'">
                {{ d.active ? "active" : "cleared" }}
              </span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 1rem;
}
.toggle {
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  font-size: 0.85rem;
  color: var(--c-muted);
}
.no-pad {
  padding: 0;
  overflow: hidden;
}
</style>
