<script setup lang="ts">
import { ref, watch, computed, useId } from "vue";
import { format, parseISO } from "date-fns";
import { X, MapPin } from "lucide-vue-next";
import * as api from "@/api/endpoints";
import type { Fillup, Vehicle } from "@/api/types";
import { useModalA11y } from "@/composables/useModalA11y";
import { fmtInt, vehicleDistUnit, vehicleVolUnit } from "@/composables/useFormat";

// `fillup_date` is a timestamptz (UTC) on the wire. A <input type="datetime-local">
// works in LOCAL wall-clock, so display must convert UTC ISO → local wall-clock
// and save must re-interpret the local string as UTC. Doing `.slice(0,16)` on the
// raw ISO would render the UTC wall-clock as if it were local, and saving back
// shifted every untouched edit by the UTC offset (the v0.1.181 phone bug class).
function isoToLocalInput(iso: string | null | undefined): string {
  if (!iso) return localNowInput();
  try {
    return format(parseISO(iso), "yyyy-MM-dd'T'HH:mm");
  } catch {
    return localNowInput();
  }
}
function localNowInput(): string {
  return format(new Date(), "yyyy-MM-dd'T'HH:mm");
}

const props = defineProps<{
  vehicle: Vehicle;
  initial: Partial<Fillup> | null;
  stationSuggestions: string[];
  // Most-recent fillup's odo + date for the parent vehicle. Surfaces
  // as "Last value: 76,304 mi · 2026-04-28" hint under the Odometer
  // input so the user knows what number to expect on a fresh entry.
  lastFillupOdo?: number | null;
  lastFillupDate?: string | null;
  // Full vehicle list for the in-modal picker — lets the user save
  // the fillup against a different vehicle without changing the
  // global vehicle selection. Defaults to props.vehicle when blank.
  allVehicles?: Vehicle[];
}>();
// Per-modal vehicle override. Defaults to whichever vehicle the
// modal was opened against; user can switch via the dropdown.
const selectedVehicleId = ref<string>(props.vehicle.id);
watch(
  () => props.vehicle.id,
  (id) => {
    selectedVehicleId.value = id;
  },
);
const availableVehicles = computed<Vehicle[]>(() =>
  props.allVehicles && props.allVehicles.length > 0
    ? props.allVehicles
    : [props.vehicle],
);
const emit = defineEmits<{
  (e: "close"): void;
  (e: "saved", fillup: Fillup): void;
}>();

// Form state mirrors the backend Fillup shape exactly (odo / fuel_volume /
// price_total / is_full / is_missed / lat / lon). station_name is a UI-only
// label that maps to `city` on save (the import preserves city; station_id
// is opaque per the schema and not surfaced as a label).
const form = ref({
  fillup_date: "",
  odo: undefined as number | undefined,
  fuel_volume: undefined as number | undefined,
  price_total: undefined as number | undefined,
  price_per_unit: undefined as number | undefined,
  is_full: true,
  is_missed: false,
  station_name: "",
  lat: undefined as number | undefined,
  lon: undefined as number | undefined,
  city: "",
  notes: "",
  tank_number: 1,
  fuel_type: 100,
  exclude_distance: false,
});
const saving = ref(false);
const saveError = ref<string | null>(null);
const showStationDropdown = ref(false);

const isEdit = computed(() => Boolean(props.initial?.id));

// Units the numbers are ENTERED in — the target vehicle's stored units, not
// the display preference (fillup rows persist in the vehicle's own units).
const targetVehicle = computed<Vehicle>(
  () => availableVehicles.value.find((v) => v.id === selectedVehicleId.value) ?? props.vehicle,
);
const distUnit = computed(() => vehicleDistUnit(targetVehicle.value));
const volUnit = computed(() => vehicleVolUnit(targetVehicle.value));

// ── Any two of volume / total / unit price compute the third ──────────
// The two most-recently typed fields are the inputs; the remaining one is
// derived and badged "calc". Typing into the calc field makes it an input
// and the oldest input becomes the derived one.
type MoneyField = "fuel_volume" | "price_total" | "price_per_unit";
const touched = ref<MoneyField[]>([]);
const calcField = computed<MoneyField | null>(() => {
  if (touched.value.length < 2) return null;
  const inputs = touched.value.slice(-2);
  const all: MoneyField[] = ["fuel_volume", "price_total", "price_per_unit"];
  return all.find((f) => !inputs.includes(f)) ?? null;
});
function onMoneyInput(f: MoneyField) {
  touched.value = [...touched.value.filter((x) => x !== f), f];
  recompute();
}
function recompute() {
  const c = calcField.value;
  if (!c) return;
  const { fuel_volume: v, price_total: t, price_per_unit: p } = form.value;
  const ok = (n: number | undefined): n is number => n != null && Number.isFinite(n) && n > 0;
  if (c === "price_total" && ok(v) && ok(p)) form.value.price_total = Math.round(v * p * 100) / 100;
  else if (c === "price_per_unit" && ok(v) && ok(t)) form.value.price_per_unit = Math.round((t / v) * 1000) / 1000;
  else if (c === "fuel_volume" && ok(t) && ok(p)) form.value.fuel_volume = Math.round((t / p) * 1000) / 1000;
}

// Odometer sanity: a new fillup at or below the previous one breaks the
// Δodo chain MPG is computed from. Warn inline; don't block (backfills
// are legitimate).
const odoWarning = computed<string | null>(() => {
  if (isEdit.value) return null;
  const last = props.lastFillupOdo;
  const odo = form.value.odo;
  if (last == null || odo == null || !Number.isFinite(odo)) return null;
  if (odo <= last) {
    return `At or below the last fillup (${fmtInt(last)} ${distUnit.value}) — check the reading, or this is a backfill.`;
  }
  return null;
});

const panel = ref<HTMLElement | null>(null);
const titleId = useId();
useModalA11y(() => true, panel, () => emit("close"));
const geoError = ref<string | null>(null);
const tankCount = computed(() => props.vehicle.tank_count ?? 1);

/** Live-OBD odometer prefill (Add Fillup new entry). Backend stores
 *  vehicles.latest_odo_km — the freshest pid_readings odometer value;
 *  convert to miles when the vehicle's dist_unit says so. Rounded to
 *  nearest whole unit so the input doesn't surface decimal-millimetre
 *  precision the user has to delete.
 *
 *  `latest_odo_km` is PCM-sourced, but the number the user is about to
 *  confirm is the one on the DASH, and the two are separate modules with
 *  separate counters — on the Pilot the PCM runs tens of km ahead.
 *  `odometer_offset_km` (migration 0020) is the measured (PCM − dash)
 *  difference, so a positive value means the PCM reads high and we
 *  SUBTRACT it; NULL = "not calibrated" → 0, i.e. exactly the previous
 *  behaviour for every uncalibrated vehicle.
 *
 *  The subtraction happens in KILOMETRES, before the km→mi conversion:
 *  the column is km, and applying it to the miles value would be a 1.61x
 *  error in the correction itself.
 *
 *  This MUST stay in step with LiveView's odometer tile and the phone's
 *  FuelAddViewModel.autoFillOdometer(). A prefill that silently carries
 *  the PCM offset into a dash-sourced odo chain corrupts the Δodo that
 *  recomputed MPG divides by — for that interval AND the next one, which
 *  is the entire reason the offset exists. */
function prefillOdoForVehicle(v: Vehicle): number | undefined {
  const rawKm = v.latest_odo_km;
  if (rawKm == null || !Number.isFinite(rawKm)) return undefined;
  const km = rawKm - (v.odometer_offset_km ?? 0);
  // dist_unit: 0 = km, 1 = mi (per Fuelio import; see types.ts)
  const isMiles = (v.dist_unit ?? 0) === 1;
  const out = isMiles ? km * 0.621371 : km;
  return Math.round(out);
}

watch(
  () => props.initial,
  (init) => {
    touched.value = [];
    if (init) {
      form.value = {
        fillup_date: isoToLocalInput(init.fillup_date),
        odo: init.odo ?? undefined,
        fuel_volume: init.fuel_volume ?? undefined,
        price_total:
          init.price_total != null ? Number(init.price_total) : undefined,
        price_per_unit:
          init.price_per_unit != null ? Number(init.price_per_unit) : undefined,
        is_full: init.is_full ?? true,
        is_missed: init.is_missed ?? false,
        station_name: init.city ?? "",
        lat: init.lat ?? undefined,
        lon: init.lon ?? undefined,
        city: init.city ?? "",
        notes: init.notes ?? "",
        tank_number: init.tank_number ?? 1,
        fuel_type: init.fuel_type ?? 100,
        exclude_distance: init.exclude_distance ?? false,
      };
    } else {
      form.value = {
        fillup_date: localNowInput(),
        odo: prefillOdoForVehicle(props.vehicle),
        fuel_volume: undefined,
        price_total: undefined,
        price_per_unit: undefined,
        is_full: true,
        is_missed: false,
        station_name: "",
        lat: undefined,
        lon: undefined,
        city: "",
        notes: "",
        tank_number: 1,
        fuel_type: 100,
        exclude_distance: false,
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
  geoError.value = null;
  if (!("geolocation" in navigator)) {
    geoError.value = "Geolocation isn't available in this browser.";
    return;
  }
  navigator.geolocation.getCurrentPosition(
    (pos) => {
      form.value.lat = Math.round(pos.coords.latitude * 1e5) / 1e5;
      form.value.lon = Math.round(pos.coords.longitude * 1e5) / 1e5;
    },
    (err) => (geoError.value = `Geolocation failed: ${err.message}`),
    { enableHighAccuracy: false, timeout: 10_000 },
  );
}

async function save() {
  saving.value = true;
  saveError.value = null;
  // Client-side guards. The backend requires non-null odo + fuel_volume;
  // surfacing this here is friendlier than letting it 422 with a generic
  // "save failed" toast.
  if (form.value.odo == null || Number.isNaN(form.value.odo)) {
    saveError.value = "Odometer is required";
    saving.value = false;
    return;
  }
  if (form.value.fuel_volume == null || Number.isNaN(form.value.fuel_volume)) {
    saveError.value = "Fuel volume is required";
    saving.value = false;
    return;
  }
  try {
    const payload: Partial<Fillup> = {
      vehicle_id: selectedVehicleId.value,
      fillup_date: form.value.fillup_date
        ? new Date(form.value.fillup_date).toISOString()
        : undefined,
      odo: form.value.odo,
      fuel_volume: form.value.fuel_volume,
      price_total: form.value.price_total ?? null,
      price_per_unit: form.value.price_per_unit ?? null,
      is_full: form.value.is_full,
      is_missed: form.value.is_missed,
      lat: form.value.lat ?? null,
      lon: form.value.lon ?? null,
      city: form.value.city || form.value.station_name || null,
      notes: form.value.notes || null,
      tank_number: form.value.tank_number,
      fuel_type: form.value.fuel_type,
      exclude_distance: form.value.exclude_distance,
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
    // Pull a useful detail off Axios errors. FastAPI returns 422 with
    // a `detail: [{loc, msg, ...}]` shape — surface the first message
    // so the user sees "Input should be a valid number on body.odo"
    // instead of a generic "save failed".
    const axiosErr = e as {
      response?: { data?: { detail?: unknown } };
      message?: string;
    };
    const detail = axiosErr?.response?.data?.detail;
    if (Array.isArray(detail) && detail.length > 0) {
      const first = detail[0] as { loc?: unknown[]; msg?: string };
      const field = Array.isArray(first.loc) ? first.loc.slice(-1)[0] : "field";
      saveError.value = `${first.msg ?? "validation failed"} (${field})`;
    } else if (typeof detail === "string") {
      saveError.value = detail;
    } else {
      saveError.value = e instanceof Error ? e.message : "save failed";
    }
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <Teleport to="body">
    <div class="modal-mask" @click.self="emit('close')">
      <div ref="panel" class="modal" role="dialog" aria-modal="true" :aria-labelledby="titleId">
        <header class="m-head">
          <h3 :id="titleId">{{ isEdit ? "Edit fillup" : "New fillup" }}</h3>
          <button class="ghost" type="button" aria-label="Close" @click="emit('close')">
            <X :size="14" />
          </button>
        </header>
        <form @submit.prevent="save">
          <!-- Vehicle picker — lets the user log this fillup against a
               different vehicle than the one the global picker has
               selected. Hidden when there's only one vehicle to choose
               from. -->
          <label v-if="availableVehicles.length > 1">
            Vehicle
            <select v-model="selectedVehicleId">
              <option
                v-for="v in availableVehicles"
                :key="v.id"
                :value="v.id"
              >
                {{ v.name }}
                <template v-if="v.active === false">(archived)</template>
              </option>
            </select>
          </label>
          <div class="row two">
            <label>
              Date / time
              <input type="datetime-local" v-model="form.fillup_date" required />
            </label>
            <label>
              Odometer ({{ distUnit }})
              <input
                type="number"
                step="0.1"
                autofocus
                v-model.number="form.odo"
                :aria-invalid="odoWarning ? 'true' : undefined"
                :placeholder="props.lastFillupOdo != null ? fmtInt(props.lastFillupOdo) : undefined"
              />
              <small v-if="odoWarning" class="warn-text" role="status">{{ odoWarning }}</small>
              <small v-else-if="props.lastFillupOdo != null" class="muted">
                Last:
                <code class="tabular">{{ fmtInt(props.lastFillupOdo) }} {{ distUnit }}</code>
                <template v-if="props.lastFillupDate">
                  · {{ props.lastFillupDate.slice(0, 10) }}
                </template>
              </small>
            </label>
          </div>
          <div class="row three">
            <label>
              <span>Volume ({{ volUnit }}) <span v-if="calcField === 'fuel_volume'" class="calc">calc</span></span>
              <input
                type="number"
                step="0.001"
                v-model.number="form.fuel_volume"
                :class="{ calculated: calcField === 'fuel_volume' }"
                @input="onMoneyInput('fuel_volume')"
              />
            </label>
            <label>
              <span>Total ($) <span v-if="calcField === 'price_total'" class="calc">calc</span></span>
              <input
                type="number"
                step="0.01"
                v-model.number="form.price_total"
                :class="{ calculated: calcField === 'price_total' }"
                @input="onMoneyInput('price_total')"
              />
            </label>
            <label>
              <span>Price ($/{{ volUnit }}) <span v-if="calcField === 'price_per_unit'" class="calc">calc</span></span>
              <input
                type="number"
                step="0.001"
                v-model.number="form.price_per_unit"
                :class="{ calculated: calcField === 'price_per_unit' }"
                @input="onMoneyInput('price_per_unit')"
              />
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
              <input type="checkbox" v-model="form.is_full" /> Full tank
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
          <p class="hint muted">Enter any two of volume, total and price — the third is calculated.</p>
          <div class="row three">
            <label>
              Latitude
              <input type="number" step="0.00001" v-model.number="form.lat" />
            </label>
            <label>
              Longitude
              <input type="number" step="0.00001" v-model.number="form.lon" />
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
            <small v-if="geoError" class="warn-text" role="status">{{ geoError }}</small>
          </div>
          <label>
            Notes
            <textarea v-model="form.notes" rows="2" />
          </label>
          <div class="row">
            <label class="cb">
              <input type="checkbox" v-model="form.is_missed" /> Missed (skip MPG)
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
.calc {
  display: inline-block;
  margin-left: 0.3rem;
  font-size: 10px;
  padding: 0 0.35rem;
  border-radius: 999px;
  background: var(--c-info-soft);
  color: var(--c-info);
  text-transform: uppercase;
  letter-spacing: 0.06em;
}
input.calculated {
  border-style: dashed;
  color: var(--c-info);
}
.hint {
  font-size: 0.78rem;
  margin: -0.2rem 0 0.6rem;
}
.warn-text {
  color: var(--c-warn);
}
@media (max-width: 560px) {
  .row.two,
  .row.three {
    grid-template-columns: 1fr;
  }
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
