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

// === Live values, by category ============================================

// Hero
const rpm = computed(() => num("engine_rpm"));
const speed = computed(() => num("vehicle_speed"));

// Engine health
const coolant = computed(() => num("coolant_temp"));
const oilTemp = computed(() => num("engine_oil_temp"));
const atf = computed(() => num("atf_temp_f"));
const voltage = computed(() => num("control_module_voltage"));
const fuelLvl = computed(() => num("fuel_level"));

// Drivability
const throttle = computed(() => num("throttle_position"));
const load = computed(() => num("engine_load"));
const maf = computed(() => num("maf_air_flow"));
const torque = computed(() => num("engine_torque_pct"));
const refTorque = computed(() => num("engine_reference_torque"));

// Fuel + economy
const fuelRate = computed(() => num("fuel_rate")); // l/h
const stftB1 = computed(() => num("stft_b1"));
const ltftB1 = computed(() => num("ltft_b1"));
const stftB2 = computed(() => num("stft_b2"));
const ltftB2 = computed(() => num("ltft_b2"));

// Air / atmosphere
const iat = computed(() => num("intake_air_temp"));
const map = computed(() => num("intake_manifold_pressure"));
const baro = computed(() => num("barometric_pressure"));
const ambient = computed(() => num("ambient_air_c"));
const timing = computed(() => num("timing_advance"));

// Trip context
const odometer = computed(() => num("odometer"));
const timeSinceStart = computed(() => num("time_since_engine_start"));
const distSinceClear = computed(() => num("distance_since_code_clear"));

// === Derived widgets =====================================================

// Instant MPG = (mph * 3785.41 / fuel_rate_l_h * gal_per_l_factor)
// fuel_rate from PID 9D is l/h. Convert speed mph→kph→km/h, then:
//   km/h ÷ (l/h) = km/l → ×2.35215 = mpg (US)
const instantMpg = computed<number | null>(() => {
  const s = speed.value; // km/h per WiCAN convention
  const fr = fuelRate.value; // l/h
  if (s == null || fr == null || fr <= 0.05) return null;
  const kml = s / fr;
  return kml * 2.35215;
});

// Computed instant lb-ft from torque % and reference torque (Honda Mode 01)
const instantTorqueLbft = computed<number | null>(() => {
  const pct = torque.value;
  const ref = refTorque.value;
  if (pct == null || ref == null) return null;
  // ref is N·m; lb-ft = N·m × 0.7376
  return (pct / 100) * ref * 0.7376;
});

// Helpers
function fmtNum(v: number | null, digits = 1): string {
  return v == null ? "—" : v.toFixed(digits);
}
function fmtInt(v: number | null): string {
  return v == null ? "—" : Math.round(v).toLocaleString();
}
function fmtSpeedMph(kph: number | null): string {
  return kph == null ? "—" : (kph * 0.62137).toFixed(0);
}
function fmtRunTime(seconds: number | null): string {
  if (seconds == null) return "—";
  const s = Math.round(seconds);
  if (s < 60) return s + " s";
  const m = Math.floor(s / 60);
  const r = s % 60;
  if (m < 60) return `${m}m ${r}s`;
  const h = Math.floor(m / 60);
  const rm = m % 60;
  return `${h}h ${rm}m`;
}
function fmtTrim(v: number | null): string {
  return v == null ? "—" : (v >= 0 ? "+" : "") + v.toFixed(1) + "%";
}
function trimClass(v: number | null): string {
  if (v == null) return "";
  if (Math.abs(v) >= 10) return "danger";
  if (Math.abs(v) >= 5) return "warn";
  return "ok";
}
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
      <!-- Hero: RPM + speed -->
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
          :max="200"
          :warn-at="120"
          label="Speed"
          unit="km/h"
          :size="240"
        />
      </section>

      <!-- Engine health -->
      <section>
        <h3 class="section-title">Engine</h3>
        <div class="tiles">
          <div class="card tile">
            <h3>Coolant</h3>
            <div class="big">{{ fmtTemp(coolant) }}</div>
          </div>
          <div class="card tile">
            <h3>Oil temp</h3>
            <div class="big">{{ fmtTemp(oilTemp) }}</div>
          </div>
          <div class="card tile">
            <h3>ATF temp</h3>
            <div class="big">{{ atf != null ? atf.toFixed(0) + " °F" : "—" }}</div>
          </div>
          <div class="card tile">
            <h3>Battery</h3>
            <div class="big">
              {{ voltage != null ? voltage.toFixed(2) + " V" : "—" }}
            </div>
          </div>
          <div class="card tile">
            <h3>Fuel level</h3>
            <div class="big">
              {{ fuelLvl != null ? Math.round(fuelLvl) + "%" : "—" }}
            </div>
          </div>
        </div>
      </section>

      <!-- Drivability -->
      <section>
        <h3 class="section-title">Drivability</h3>
        <div class="tiles">
          <div class="card tile">
            <h3>Throttle</h3>
            <div class="big">{{ fmtPct(throttle) }}</div>
          </div>
          <div class="card tile">
            <h3>Engine load</h3>
            <div class="big">{{ fmtPct(load) }}</div>
          </div>
          <div class="card tile">
            <h3>MAF</h3>
            <div class="big">
              {{ maf != null ? maf.toFixed(2) + " g/s" : "—" }}
            </div>
          </div>
          <div class="card tile">
            <h3>Torque</h3>
            <div class="big">
              {{ torque != null ? Math.round(torque) + "%" : "—" }}
            </div>
            <div class="muted small" v-if="instantTorqueLbft != null">
              ≈ {{ instantTorqueLbft.toFixed(0) }} lb·ft
            </div>
          </div>
          <div class="card tile">
            <h3>Speed (mph)</h3>
            <div class="big">{{ fmtSpeedMph(speed) }} <span class="unit">mph</span></div>
          </div>
        </div>
      </section>

      <!-- Fuel / economy -->
      <section>
        <h3 class="section-title">Fuel &amp; economy</h3>
        <div class="tiles">
          <div class="card tile">
            <h3>Fuel rate</h3>
            <div class="big">
              {{ fuelRate != null ? fuelRate.toFixed(2) + " l/h" : "—" }}
            </div>
          </div>
          <div class="card tile highlight">
            <h3>Instant MPG</h3>
            <div class="big">
              {{ instantMpg != null ? instantMpg.toFixed(1) : "—" }}
            </div>
            <div class="muted small">computed (mph ÷ gph)</div>
          </div>
          <div class="card tile">
            <h3>STFT B1</h3>
            <div class="big" :class="trimClass(stftB1)">{{ fmtTrim(stftB1) }}</div>
          </div>
          <div class="card tile">
            <h3>LTFT B1</h3>
            <div class="big" :class="trimClass(ltftB1)">{{ fmtTrim(ltftB1) }}</div>
          </div>
          <div class="card tile">
            <h3>STFT B2</h3>
            <div class="big" :class="trimClass(stftB2)">{{ fmtTrim(stftB2) }}</div>
          </div>
          <div class="card tile">
            <h3>LTFT B2</h3>
            <div class="big" :class="trimClass(ltftB2)">{{ fmtTrim(ltftB2) }}</div>
          </div>
        </div>
      </section>

      <!-- Air / atmosphere -->
      <section>
        <h3 class="section-title">Air &amp; atmosphere</h3>
        <div class="tiles">
          <div class="card tile">
            <h3>Intake air</h3>
            <div class="big">{{ fmtTemp(iat) }}</div>
          </div>
          <div class="card tile">
            <h3>Ambient</h3>
            <div class="big">{{ fmtTemp(ambient) }}</div>
          </div>
          <div class="card tile">
            <h3>MAP</h3>
            <div class="big">
              {{ map != null ? map.toFixed(0) + " kPa" : "—" }}
            </div>
          </div>
          <div class="card tile">
            <h3>Baro</h3>
            <div class="big">
              {{ baro != null ? baro.toFixed(0) + " kPa" : "—" }}
            </div>
          </div>
          <div class="card tile">
            <h3>Timing</h3>
            <div class="big">
              {{ timing != null ? timing.toFixed(1) + "°" : "—" }}
            </div>
          </div>
        </div>
      </section>

      <!-- Trip context -->
      <section>
        <h3 class="section-title">Trip context</h3>
        <div class="tiles">
          <div class="card tile">
            <h3>Odometer</h3>
            <div class="big">{{ fmtInt(odometer) }}</div>
          </div>
          <div class="card tile">
            <h3>Run time</h3>
            <div class="big">{{ fmtRunTime(timeSinceStart) }}</div>
          </div>
          <div class="card tile">
            <h3>Distance since DTC clear</h3>
            <div class="big">{{ fmtInt(distSinceClear) }}</div>
          </div>
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
.section-title {
  margin: 0 0 0.5rem 0;
  font-size: 0.78rem;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  color: var(--c-muted);
  font-weight: 600;
}
.tiles {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 0.7rem;
}
.tile .big {
  font-size: 1.55rem;
  font-weight: 600;
  letter-spacing: -0.02em;
}
.tile .big.ok {
  color: var(--c-success);
}
.tile .big.warn {
  color: var(--c-warn);
}
.tile .big.danger {
  color: var(--c-danger);
}
.tile .unit {
  font-size: 0.85rem;
  color: var(--c-muted);
  font-weight: 500;
}
.tile.highlight {
  border-color: rgba(63, 185, 80, 0.35);
  box-shadow: 0 0 0 1px rgba(63, 185, 80, 0.2);
}
.tile .small {
  font-size: 0.72rem;
  margin-top: 0.15rem;
}
</style>
