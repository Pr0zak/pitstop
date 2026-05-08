<script setup lang="ts">
import { ref, watch, computed } from "vue";
import { X, MapPin } from "lucide-vue-next";
import * as api from "@/api/endpoints";
import type { Fillup, Vehicle } from "@/api/types";

const props = defineProps<{
  vehicle: Vehicle;
  initial: Partial<Fillup> | null;
  stationSuggestions: string[];
}>();
const emit = defineEmits<{
  (e: "close"): void;
  (e: "saved", fillup: Fillup): void;
}>();

const form = ref({
  fillup_date: "",
  odometer: undefined as number | undefined,
  volume: undefined as number | undefined,
  total_price: undefined as number | undefined,
  unit_price: undefined as number | undefined,
  full_tank: true,
  partial: false,
  station_name: "",
  latitude: undefined as number | undefined,
  longitude: undefined as number | undefined,
  city: "",
  notes: "",
  tank_number: 1,
  fuel_type: 100,
  exclude_distance: false,
  missed: false,
});
const saving = ref(false);
const saveError = ref<string | null>(null);
const showStationDropdown = ref(false);

const isEdit = computed(() => Boolean(props.initial?.id));
const tankCount = computed(() => props.vehicle.tank_count ?? 1);

watch(
  () => props.initial,
  (init) => {
    if (init) {
      form.value = {
        fillup_date: init.fillup_date?.slice(0, 16).replace("T", "T") ?? new Date().toISOString().slice(0, 16),
        odometer: init.odometer ?? undefined,
        volume: init.volume ?? undefined,
        total_price: init.total_price ?? undefined,
        unit_price: init.unit_price ?? undefined,
        full_tank: init.full_tank ?? true,
        partial: init.partial ?? false,
        station_name: init.station_name ?? "",
        latitude: init.latitude ?? undefined,
        longitude: init.longitude ?? undefined,
        city: init.city ?? "",
        notes: init.notes ?? "",
        tank_number: init.tank_number ?? 1,
        fuel_type: init.fuel_type ?? 100,
        exclude_distance: init.exclude_distance ?? false,
        missed: init.missed ?? false,
      };
    } else {
      form.value = {
        fillup_date: new Date().toISOString().slice(0, 16),
        odometer: undefined,
        volume: undefined,
        total_price: undefined,
        unit_price: undefined,
        full_tank: true,
        partial: false,
        station_name: "",
        latitude: undefined,
        longitude: undefined,
        city: "",
        notes: "",
        tank_number: 1,
        fuel_type: 100,
        exclude_distance: false,
        missed: false,
      };
    }
  },
  { immediate: true },
);

const filteredSuggestions = computed(() => {
  const q = form.value.station_name.trim().toLowerCase();
  if (!q) return props.stationSuggestions.slice(0, 8);
  return props.stationSuggestions
    .filter((s) => s.toLowerCase().includes(q))
    .slice(0, 8);
});

function pickStation(name: string) {
  form.value.station_name = name;
  showStationDropdown.value = false;
}

function hideStationDropdownLater() {
  // Delay so a click on a suggestion fires before the dropdown closes.
  window.setTimeout(() => {
    showStationDropdown.value = false;
  }, 150);
}

function geolocate() {
  if (!("geolocation" in navigator)) {
    alert("Geolocation not available");
    return;
  }
  navigator.geolocation.getCurrentPosition(
    (pos) => {
      form.value.latitude = Math.round(pos.coords.latitude * 1e5) / 1e5;
      form.value.longitude = Math.round(pos.coords.longitude * 1e5) / 1e5;
    },
    (err) => alert(`Geolocation failed: ${err.message}`),
    { enableHighAccuracy: false, timeout: 10_000 },
  );
}

async function save() {
  saving.value = true;
  saveError.value = null;
  try {
    const payload: Partial<Fillup> = {
      vehicle_id: props.vehicle.id,
      fillup_date: form.value.fillup_date
        ? new Date(form.value.fillup_date).toISOString()
        : undefined,
      odometer: form.value.odometer ?? null,
      volume: form.value.volume ?? null,
      total_price: form.value.total_price ?? null,
      unit_price: form.value.unit_price ?? null,
      full_tank: form.value.full_tank,
      partial: form.value.partial,
      station_name: form.value.station_name || null,
      latitude: form.value.latitude ?? null,
      longitude: form.value.longitude ?? null,
      city: form.value.city || null,
      notes: form.value.notes || null,
      tank_number: form.value.tank_number,
      fuel_type: form.value.fuel_type,
      exclude_distance: form.value.exclude_distance,
      missed: form.value.missed,
    };
    let saved: Fillup;
    if (props.initial?.id) {
      saved = await api.updateFillup(props.initial.id, payload);
    } else {
      saved = await api.createFillup(payload);
    }
    emit("saved", saved);
    emit("close");
  } catch (e: unknown) {
    saveError.value = e instanceof Error ? e.message : "save failed";
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <Teleport to="body">
    <div class="modal-mask" @click.self="emit('close')">
      <div class="modal">
        <header class="m-head">
          <h3>{{ isEdit ? "Edit fillup" : "New fillup" }}</h3>
          <button class="ghost" type="button" @click="emit('close')">
            <X :size="14" />
          </button>
        </header>
        <form @submit.prevent="save">
          <div class="row two">
            <label>
              Date / time
              <input type="datetime-local" v-model="form.fillup_date" required />
            </label>
            <label>
              Odometer (mi)
              <input type="number" step="0.1" v-model.number="form.odometer" />
            </label>
          </div>
          <div class="row three">
            <label>
              Volume (gal)
              <input type="number" step="0.001" v-model.number="form.volume" />
            </label>
            <label>
              Total ($)
              <input type="number" step="0.01" v-model.number="form.total_price" />
            </label>
            <label>
              Unit price ($/gal)
              <input type="number" step="0.001" v-model.number="form.unit_price" />
            </label>
          </div>
          <div class="row" :class="tankCount > 1 ? 'two' : ''">
            <label v-if="tankCount > 1">
              Tank
              <select v-model.number="form.tank_number">
                <option v-for="n in tankCount" :key="n" :value="n">Tank {{ n }}</option>
              </select>
            </label>
            <label class="cb">
              <input type="checkbox" v-model="form.full_tank" /> Full tank
            </label>
            <label class="cb">
              <input type="checkbox" v-model="form.partial" /> Partial
            </label>
          </div>
          <div class="station">
            <label class="suggest">
              Station
              <input
                v-model="form.station_name"
                @focus="showStationDropdown = true"
                @blur="hideStationDropdownLater"
                placeholder="Station name"
              />
              <div v-if="showStationDropdown && filteredSuggestions.length" class="suggest-pop">
                <button
                  v-for="s in filteredSuggestions"
                  :key="s"
                  type="button"
                  @mousedown.prevent="pickStation(s)"
                >
                  {{ s }}
                </button>
              </div>
            </label>
          </div>
          <div class="row three">
            <label>
              Latitude
              <input type="number" step="0.00001" v-model.number="form.latitude" />
            </label>
            <label>
              Longitude
              <input type="number" step="0.00001" v-model.number="form.longitude" />
            </label>
            <label>
              City
              <input v-model="form.city" />
            </label>
          </div>
          <div class="row">
            <button type="button" class="ghost" @click="geolocate">
              <MapPin :size="14" /> Use current location
            </button>
          </div>
          <label>
            Notes
            <textarea v-model="form.notes" rows="2" />
          </label>
          <div class="row">
            <label class="cb">
              <input type="checkbox" v-model="form.missed" /> Missed (skip MPG)
            </label>
            <label class="cb">
              <input type="checkbox" v-model="form.exclude_distance" />
              Exclude distance
            </label>
          </div>
          <p v-if="saveError" class="error">{{ saveError }}</p>
          <div class="m-actions">
            <button type="button" @click="emit('close')">Cancel</button>
            <button type="submit" class="primary" :disabled="saving">
              {{ saving ? "Saving…" : "Save" }}
            </button>
          </div>
        </form>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
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
  max-width: 560px;
  padding: 1.2rem;
  max-height: calc(100vh - 2rem);
  overflow-y: auto;
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
  font-size: 0.85rem;
  color: var(--c-muted);
  margin-bottom: 0.6rem;
}
form label.cb {
  flex-direction: row;
  align-items: center;
  gap: 0.4rem;
}
.row {
  display: flex;
  gap: 0.6rem;
  align-items: flex-end;
  margin-bottom: 0.5rem;
}
.row label {
  flex: 1;
  margin-bottom: 0;
}
.row.two {
  display: grid;
  grid-template-columns: 1fr 1fr;
}
.row.three {
  display: grid;
  grid-template-columns: 1fr 1fr 1fr;
}
.station {
  position: relative;
  margin-bottom: 0.6rem;
}
.suggest {
  position: relative;
}
.suggest-pop {
  position: absolute;
  top: 100%;
  left: 0;
  right: 0;
  background: var(--c-surface-2);
  border: 1px solid var(--c-border);
  border-top: none;
  border-radius: 0 0 var(--r-sm) var(--r-sm);
  z-index: 5;
  display: flex;
  flex-direction: column;
}
.suggest-pop button {
  background: transparent;
  border: none;
  border-radius: 0;
  padding: 0.4rem 0.6rem;
  text-align: left;
  cursor: pointer;
  color: var(--c-text);
}
.suggest-pop button:hover {
  background: var(--c-surface-3);
}
.error {
  color: var(--c-danger);
  margin: 0.4rem 0;
}
.m-actions {
  display: flex;
  justify-content: flex-end;
  gap: 0.5rem;
  margin-top: 0.6rem;
}
</style>
