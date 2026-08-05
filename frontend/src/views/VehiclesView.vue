<script setup lang="ts">
import { computed, ref } from "vue";
import { useVehiclesStore } from "@/stores/vehicles";
import { useUnitsStore } from "@/stores/units";
import { fmtRelative } from "@/composables/useFormat";
import { Plus, Pencil, X } from "lucide-vue-next";
import * as api from "@/api/endpoints";
import type { Vehicle } from "@/api/types";
import ConfirmDialog from "@/components/ConfirmDialog.vue";

const store = useVehiclesStore();
const units = useUnitsStore();

const KM_PER_MI = 1.609344;
const KM_TO_MI = (km: number) => km / KM_PER_MI;
const MI_TO_KM = (mi: number) => mi * KM_PER_MI;

/** `v-model.number` leaves the raw string in place when it doesn't parse, so a
 *  cleared numeric input arrives here as "" rather than undefined. Collapse
 *  every non-numeric shape to null — for the odometer offset that's meaningful:
 *  NULL means "not calibrated", which is distinct from a measured 0. */
function toNumOrNull(v: unknown): number | null {
  if (v === "" || v == null) return null;
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
}

const showModal = ref(false);
const editing = ref<Vehicle | null>(null);

/** Distance unit for the odometer-offset input. Honours an explicit
 *  metric/imperial preference; under 'auto' it reads the vehicle being edited,
 *  because the units store's own auto-resolution follows the *selected*
 *  vehicle, which isn't necessarily the row whose modal is open. */
const editorImperial = computed<boolean>(() => {
  if (units.preference === "metric") return false;
  if (units.preference === "imperial") return true;
  const v = editing.value;
  if (!v) return units.resolved === "imperial";
  return v.dist_unit === 1 || v.fuel_unit === 1 || v.fuel_unit === 2;
});
const offsetUnit = computed(() => (editorImperial.value ? "mi" : "km"));

/** km (storage) → the offset input's display unit. undefined for an
 *  uncalibrated vehicle so the input renders blank rather than "0". */
function kmToOffsetInput(km: number | null | undefined): number | undefined {
  if (km == null) return undefined;
  const v = editorImperial.value ? KM_TO_MI(km) : km;
  return Math.round(v * 10) / 10;
}

/** The offset input's display unit → km (storage). */
function offsetInputToKm(raw: unknown): number | null {
  const n = toNumOrNull(raw);
  if (n == null) return null;
  const km = editorImperial.value ? MI_TO_KM(n) : n;
  return Math.round(km * 1000) / 1000;
}

const form = ref({
  name: "",
  slug: "",
  slug_touched: false,
  description: "",
  year: undefined as number | undefined,
  make: "",
  model: "",
  vin: "",
  fuelio_guid: "",
  active: true,
  purchase_price: undefined as number | undefined,
  purchase_date: "" as string,
  epa_mpg_combined: undefined as number | undefined,
  // Usable tank volume in liters — what the hybrid fuel-level estimator
  // (migration 0017) reads to convert its persisted estimate_l into a
  // percentage. Distinct from Fuelio's tank1_capacity (user-unit, used by
  // the legacy range-to-empty card).
  tank_capacity_l: undefined as number | undefined,
  // Odometer offset in the USER'S distance unit (see [editorImperial]) — the
  // column is km, so this is converted on load and on save. undefined = the
  // field is blank = "not calibrated" (stored as NULL), which is deliberately
  // distinct from a measured 0.
  odometer_offset: undefined as number | undefined,
});

function slugify(s: string): string {
  return s
    .toLowerCase()
    .replace(/[^a-z0-9_-]+/g, "-")
    .replace(/-{2,}/g, "-")
    .replace(/^-+|-+$/g, "");
}

function onNameInput() {
  if (!form.value.slug_touched) {
    form.value.slug = slugify(form.value.name);
  }
}
function onSlugInput() {
  form.value.slug_touched = true;
  form.value.slug = slugify(form.value.slug);
}
const submitError = ref<string | null>(null);
const submitting = ref(false);

function openCreate() {
  editing.value = null;
  form.value = {
    name: "",
    slug: "",
    slug_touched: false,
    description: "",
    year: undefined,
    make: "",
    model: "",
    vin: "",
    fuelio_guid: "",
    active: true,
    purchase_price: undefined,
    purchase_date: "",
    epa_mpg_combined: undefined,
    tank_capacity_l: undefined,
    odometer_offset: undefined,
  };
  showModal.value = true;
  submitError.value = null;
}

function openEdit(v: Vehicle) {
  editing.value = v;
  form.value = {
    name: v.name ?? "",
    slug: v.slug ?? "",
    slug_touched: true,
    description: v.description ?? "",
    year: v.year ?? undefined,
    make: v.make ?? "",
    model: v.model ?? "",
    vin: v.vin ?? "",
    fuelio_guid: v.fuelio_guid ?? "",
    active: v.active ?? true,
    purchase_price: v.purchase_price ?? undefined,
    purchase_date: v.purchase_date ?? "",
    epa_mpg_combined: v.epa_mpg_combined ?? undefined,
    tank_capacity_l: v.tank_capacity_l ?? undefined,
    odometer_offset: kmToOffsetInput(v.odometer_offset_km),
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
      slug: form.value.slug.trim() || undefined, // backend derives if blank
      description: form.value.description.trim() || null,
      year: form.value.year ?? null,
      make: form.value.make.trim() || null,
      model: form.value.model.trim() || null,
      vin: form.value.vin.trim() || null,
      fuelio_guid: form.value.fuelio_guid.trim() || null,
      active: form.value.active,
      purchase_price: form.value.purchase_price ?? null,
      purchase_date: form.value.purchase_date || null,
      epa_mpg_combined: form.value.epa_mpg_combined ?? null,
      tank_capacity_l: form.value.tank_capacity_l ?? null,
      odometer_offset_km: offsetInputToKm(form.value.odometer_offset),
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

// Delete vehicle — in-app confirm instead of native window.confirm/alert.
const deleteTarget = ref<Vehicle | null>(null);
const deleteBusy = ref(false);
const deleteError = ref<string | null>(null);
function requestRemove(v: Vehicle) {
  deleteTarget.value = v;
  deleteError.value = null;
}
async function confirmRemove() {
  const v = deleteTarget.value;
  if (!v) return;
  deleteBusy.value = true;
  deleteError.value = null;
  try {
    await api.deleteVehicle(v.id);
    deleteTarget.value = null;
    await store.fetchVehicles();
  } catch (e: unknown) {
    deleteError.value = e instanceof Error ? e.message : "delete failed";
  } finally {
    deleteBusy.value = false;
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
              <button class="ghost" type="button" @click="requestRemove(v)" title="Delete">
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
            <label>
              Name
              <input v-model="form.name" required @input="onNameInput" />
            </label>
            <label>
              Slug
              <input
                v-model="form.slug"
                placeholder="auto-derived from name"
                pattern="[a-z0-9_-]+"
                @input="onSlugInput"
              />
              <small class="muted">
                Lowercase letters, digits, <code>_</code>, <code>-</code>.
                This is the topic prefix the WiCAN device uses
                (<code>wican/&lt;slug&gt;/...</code>).
              </small>
            </label>
            <label>Description<input v-model="form.description" /></label>
            <div class="grid">
              <label>Year<input type="number" v-model.number="form.year" /></label>
              <label>Make<input v-model="form.make" /></label>
              <label>Model<input v-model="form.model" /></label>
            </div>
            <label>VIN<input v-model="form.vin" /></label>
            <label>Fuelio GUID<input v-model="form.fuelio_guid" /></label>
            <div class="grid">
              <label>
                Purchase price
                <input
                  type="number"
                  step="0.01"
                  v-model.number="form.purchase_price"
                  placeholder="0.00"
                />
                <small class="muted">
                  Used for the lifetime $/mile card.
                </small>
              </label>
              <label>
                Purchase date
                <input type="date" v-model="form.purchase_date" />
              </label>
              <label>
                EPA combined MPG
                <input
                  type="number"
                  step="0.1"
                  v-model.number="form.epa_mpg_combined"
                  placeholder="e.g. 23"
                />
                <small class="muted">
                  Reference line on the MPG chart.
                </small>
              </label>
              <label>
                Tank capacity (L)
                <input
                  type="number"
                  step="0.1"
                  v-model.number="form.tank_capacity_l"
                  placeholder="e.g. 80"
                />
                <small class="muted">
                  Usable tank volume in liters. Feeds the fuel-level
                  estimate and range-to-empty.
                </small>
              </label>
              <label>
                Odometer offset ({{ offsetUnit }})
                <input
                  type="number"
                  step="0.1"
                  v-model.number="form.odometer_offset"
                  :placeholder="editorImperial ? 'e.g. 32' : 'e.g. 51'"
                />
                <small class="muted">
                  How far the car's OBD odometer runs ahead of the dash
                  (OBD&nbsp;−&nbsp;dash) — the engine computer and the
                  instrument cluster keep separate counters. Fillup odometers
                  are read off the dash, so this keeps live readings
                  comparable. Negative if the OBD reads low; leave blank if
                  you haven't measured it.
                </small>
              </label>
            </div>
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

    <ConfirmDialog
      :open="deleteTarget != null"
      :title="deleteTarget ? `Delete vehicle “${deleteTarget.name}”?` : 'Delete vehicle?'"
      :message="deleteError ? deleteError : 'This cannot be undone.'"
      confirm-label="Delete"
      tone="danger"
      :busy="deleteBusy"
      @confirm="confirmRemove"
      @cancel="deleteTarget = null"
    />
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
