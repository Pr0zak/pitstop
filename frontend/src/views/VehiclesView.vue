<script setup lang="ts">
import { ref } from "vue";
import { useVehiclesStore } from "@/stores/vehicles";
import { fmtRelative } from "@/composables/useFormat";
import { Plus, Pencil, X } from "lucide-vue-next";
import * as api from "@/api/endpoints";
import type { Vehicle } from "@/api/types";

const store = useVehiclesStore();

const showModal = ref(false);
const editing = ref<Vehicle | null>(null);
const form = ref({
  name: "",
  description: "",
  year: undefined as number | undefined,
  make: "",
  model: "",
  vin: "",
  fuelio_guid: "",
  active: true,
});
const submitError = ref<string | null>(null);
const submitting = ref(false);

function openCreate() {
  editing.value = null;
  form.value = {
    name: "",
    description: "",
    year: undefined,
    make: "",
    model: "",
    vin: "",
    fuelio_guid: "",
    active: true,
  };
  showModal.value = true;
  submitError.value = null;
}

function openEdit(v: Vehicle) {
  editing.value = v;
  form.value = {
    name: v.name ?? "",
    description: v.description ?? "",
    year: v.year ?? undefined,
    make: v.make ?? "",
    model: v.model ?? "",
    vin: v.vin ?? "",
    fuelio_guid: v.fuelio_guid ?? "",
    active: v.active ?? true,
  };
  showModal.value = true;
  submitError.value = null;
}

async function submit() {
  submitError.value = null;
  submitting.value = true;
  try {
    const payload: Partial<Vehicle> = {
      name: form.value.name.trim(),
      description: form.value.description.trim() || null,
      year: form.value.year ?? null,
      make: form.value.make.trim() || null,
      model: form.value.model.trim() || null,
      vin: form.value.vin.trim() || null,
      fuelio_guid: form.value.fuelio_guid.trim() || null,
      active: form.value.active,
    };
    if (editing.value) {
      await api.updateVehicle(editing.value.id, payload);
    } else {
      await api.createVehicle(payload);
    }
    showModal.value = false;
    await store.fetchVehicles();
  } catch (e: unknown) {
    submitError.value = e instanceof Error ? e.message : "save failed";
  } finally {
    submitting.value = false;
  }
}

async function remove(v: Vehicle) {
  if (!window.confirm(`Delete vehicle "${v.name}"? This cannot be undone.`)) return;
  try {
    await api.deleteVehicle(v.id);
    await store.fetchVehicles();
  } catch (e: unknown) {
    alert(e instanceof Error ? e.message : "delete failed");
  }
}
</script>

<template>
  <div class="vehicles">
    <header class="head">
      <h1>Vehicles</h1>
      <button class="primary" type="button" @click="openCreate">
        <Plus :size="14" /> Add vehicle
      </button>
    </header>

    <div v-if="store.loading && store.vehicles.length === 0" class="card">
      <p class="muted">Loading…</p>
    </div>
    <div v-else-if="store.error" class="card">
      <p class="muted">Failed to load: {{ store.error }}</p>
    </div>
    <div v-else-if="store.vehicles.length === 0" class="card">
      <p class="muted">No vehicles yet. Add one to get started.</p>
    </div>
    <div v-else class="card no-pad">
      <table class="data">
        <thead>
          <tr>
            <th>Name</th>
            <th>Year/Make/Model</th>
            <th>Last seen</th>
            <th>Status</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="v in store.vehicles" :key="v.id">
            <td><strong>{{ v.name }}</strong></td>
            <td>{{ [v.year, v.make, v.model].filter(Boolean).join(" ") || "—" }}</td>
            <td>{{ v.last_seen_at ? fmtRelative(v.last_seen_at) : "never" }}</td>
            <td>
              <span class="badge" :class="v.active === false ? '' : 'success'">
                {{ v.active === false ? "inactive" : "active" }}
              </span>
            </td>
            <td class="actions">
              <button class="ghost" type="button" @click="openEdit(v)" title="Edit">
                <Pencil :size="14" />
              </button>
              <button class="ghost" type="button" @click="remove(v)" title="Delete">
                <X :size="14" />
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <Teleport to="body">
      <div v-if="showModal" class="modal-mask" @click.self="showModal = false">
        <div class="modal">
          <header class="m-head">
            <h3>{{ editing ? "Edit vehicle" : "New vehicle" }}</h3>
            <button class="ghost" type="button" @click="showModal = false">
              <X :size="14" />
            </button>
          </header>
          <form @submit.prevent="submit">
            <label>Name<input v-model="form.name" required /></label>
            <label>Description<input v-model="form.description" /></label>
            <div class="grid">
              <label>Year<input type="number" v-model.number="form.year" /></label>
              <label>Make<input v-model="form.make" /></label>
              <label>Model<input v-model="form.model" /></label>
            </div>
            <label>VIN<input v-model="form.vin" /></label>
            <label>Fuelio GUID<input v-model="form.fuelio_guid" /></label>
            <label class="cb">
              <input type="checkbox" v-model="form.active" /> Active
            </label>
            <p v-if="submitError" class="error">{{ submitError }}</p>
            <div class="m-actions">
              <button type="button" @click="showModal = false">Cancel</button>
              <button type="submit" class="primary" :disabled="submitting">
                {{ submitting ? "Saving…" : "Save" }}
              </button>
            </div>
          </form>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 1rem;
}
.head .primary {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
}
.no-pad {
  padding: 0;
  overflow: hidden;
}
.actions {
  display: flex;
  gap: 0.3rem;
  justify-content: flex-end;
}
.modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.55);
  z-index: 100;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 1rem;
}
.modal {
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--r-lg);
  width: 100%;
  max-width: 480px;
  padding: 1.2rem;
}
.m-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 0.8rem;
}
form label {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  margin-bottom: 0.7rem;
  font-size: 0.85rem;
  color: var(--c-muted);
}
form label.cb {
  flex-direction: row;
  align-items: center;
  gap: 0.4rem;
}
.grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 0.6rem;
}
.error {
  color: var(--c-danger);
  margin: 0.5rem 0;
}
.m-actions {
  display: flex;
  justify-content: flex-end;
  gap: 0.5rem;
  margin-top: 0.8rem;
}
</style>
