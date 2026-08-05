<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from "vue";
import { useVehiclesStore } from "@/stores/vehicles";
import { useAuthStore } from "@/stores/auth";
import { useLive } from "@/composables/useLive";
import ArcGauge from "@/components/charts/ArcGauge.vue";
import Pill from "@/components/Pill.vue";
import {
  fmtPct,
  fmtTempC,
  fmtSpeedKph,
  fmtFuelRateLh,
} from "@/composables/useFormat";
import { useUnitsStore } from "@/stores/units";

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
const units = useUnitsStore();
const useImperial = computed(() => units.resolved === "imperial");

// Lightweight 1 Hz tick so the freshness-derived pills below
// (Stream / Bridge / Broker) re-evaluate even when no new frame
// arrived for a while. Without this they'd only update on a frame.
const tick = ref(Date.now());
let tickInterval: number | undefined;
onMounted(() => {
  tickInterval = window.setInterval(() => {
    tick.value = Date.now();
  }, 1000);
});
onUnmounted(() => {
  if (tickInterval) window.clearInterval(tickInterval);
});

/** Most recent frame timestamp across all metrics in this session. */
const lastFrameMs = computed<number | null>(() => {
  const m = metrics.value ?? {};
  let max = 0;
  for (const k of Object.keys(m)) {
    const t = m[k]?.time ?? 0;
    if (typeof t === "number" && t > max) max = t;
  }
  return max > 0 ? max : null;
});

function num(key: string): number | null {
  const v = metrics.value?.[key]?.value;
  if (typeof v === "number") return v;
  if (typeof v === "string") {
    const n = Number(v);
    return Number.isFinite(n) ? n : null;
  }
  return null;
}

type PillState = "healthy" | "connecting" | "degraded" | "offline" | "neutral";

/** Browser ↔ backend WebSocket. Drives the WS pill. */
const wsLabel = computed<{ text: string; state: PillState }>(() => {
  switch (status.value) {
    case "connecting":
      return { text: "WS …", state: "connecting" };
    case "open":
      return { text: "WS live", state: "healthy" };
    case "stale":
      return { text: "WS stale", state: "degraded" };
    case "disconnected":
      return { text: "WS off", state: "offline" };
    default:
      return { text: "WS idle", state: "neutral" };
  }
});

/**
 * Bridge → broker → backend stream. Derived from the most-recent frame
 * timestamp; healthy if a frame landed in the last 5 s, degraded under
 * 60 s, offline beyond. The frontend can't see the broker / BLE links
 * directly (they're upstream of the backend) but a fresh frame proves
 * both are up.
 */
const streamLabel = computed<{ text: string; state: PillState }>(() => {
  const lf = lastFrameMs.value;
  if (lf == null) return { text: "Stream idle", state: "neutral" };
  const age = (tick.value - lf) / 1000;
  if (age < 5) return { text: "Stream live", state: "healthy" };
  if (age < 60) return { text: `Stream ${age.toFixed(0)}s`, state: "degraded" };
  return { text: "Stream off", state: "offline" };
});

const statusLabel = computed<{
  text: string;
  state: PillState;
}>(() => {
  switch (status.value) {
    case "connecting":
      return { text: "connecting", state: "connecting" };
    case "open":
      return { text: "live", state: "healthy" };
    case "stale":
      return { text: "stale", state: "degraded" };
    case "disconnected":
      return { text: "disconnected", state: "offline" };
    default:
      return { text: "idle", state: "neutral" };
  }
});

// === Live values, by category ============================================

// Hero
const rpm = computed(() => num("engine_rpm"));
const speed = computed(() => num("vehicle_speed"));

// Engine health
const coolant = computed(() => num("coolant_temp"));
const voltage = computed(() => num("control_module_voltage"));
const fuelLvl = computed(() => num("fuel_level"));

// Drivability
const throttle = computed(() => num("throttle_position"));
const load = computed(() => num("engine_load"));
const maf = computed(() => num("maf_air_flow"));
const torque = computed(() => num("engine_torque_pct"));
const refTorque = computed(() => num("engine_reference_torque"));

// Fuel + economy
// Fuel rate. The canonical metric is `engine_fuel_rate` (OBD PID 0x9D) in
// GRAMS PER SECOND; this tile and the economy widget below want l/h.
//
// The legacy `fuel_rate` metric is deliberately only a fallback: the WiCAN's
// built-in 0x9D decoder is broken and published a constant 0.000 for 11,586
// readings before it stopped altogether on 2026-05-23, which is why this tile
// and instant-economy silently showed nothing for months. The live value now
// comes from a custom WiCAN PID (and the phone's own 0x9D poll), both of which
// publish g/s under `engine_fuel_rate`.
const FUEL_G_PER_L = 749.9; // gasoline density ~0.7499 kg/L
const fuelRate = computed(() => {
  const gPerS = num("engine_fuel_rate");
  if (gPerS != null) return (gPerS * 3600) / FUEL_G_PER_L; // g/s -> l/h
  return num("fuel_rate"); // legacy, already l/h
});
// Exhaust mass flow, OBD PID 0x9E, published in KILOGRAMS PER HOUR under
// `engine_exhaust_flow`. Left in kg/h like the other mass-flow tiles (MAF is
// g/s, MAP/baro are kPa) rather than converted per unit system.
const exhaustFlow = computed(() => num("engine_exhaust_flow"));
const stftB1 = computed(() => num("stft_b1"));
const ltftB1 = computed(() => num("ltft_b1"));
const stftB2 = computed(() => num("stft_b2"));
const ltftB2 = computed(() => num("ltft_b2"));

// Mixture + fuel delivery
// `commanded_afr_ratio` (PID 0x44) is the PCM's commanded air-fuel
// EQUIVALENCE ratio — lambda, not the 14.7:1 mass ratio — and `o2_s1_lambda`
// (PID 0x24) is what the upstream wide-range sensor actually measured. Both
// live within a couple of percent of 1.000 in closed loop, so they render at
// three decimals: rounding to the 1 dp the other tiles use would flatten the
// entire meaningful range into a constant "1.0".
//
// There is deliberately NO O2-sensor-1 voltage tile: PID 0x24's voltage field
// is invariant in the stored data (2 distinct values across 29,103 rows, a
// flat 2.000 for the last six weeks), so it is left unaliased at ingest and
// has no canonical metric to render. Lambda is that sensor's real output.
const cmdAfr = computed(() => num("commanded_afr_ratio"));
const o2S1Lambda = computed(() => num("o2_s1_lambda"));
const fuelRail = computed(() => num("fuel_rail_pressure"));

// Emissions + aftertreatment
// Catalyst temps are stored in °C like every other temp metric, so they go
// through fmtTempC and follow the imperial toggle. The two commanded-actuator
// percentages have no imperial variant.
const catB1 = computed(() => num("catalyst_temp_b1"));
const catB2 = computed(() => num("catalyst_temp_b2"));
const cmdEgr = computed(() => num("commanded_egr"));
const cmdEvapPurge = computed(() => num("commanded_evap_purge"));

// Air / atmosphere
const iat = computed(() => num("intake_air_temp"));
const map = computed(() => num("intake_manifold_pressure"));
const baro = computed(() => num("barometric_pressure"));
const timing = computed(() => num("timing_advance"));

// Trip context
// Odometer. The OBD metric is KILOMETRES; this tile used to render it raw and
// unlabelled, so an imperial user read "126959" as miles when it was really
// 126,959 km = 78,889 mi.
//
// `odometer_offset_km` is a per-vehicle correction the user sets on the Vehicles
// page. The PCM's internal distance counter and the instrument cluster are
// separate modules and do not agree — on the Pilot the PID reads ~51 km (32 mi)
// above the dash. That matters because fillup odometers are recorded from the
// DASH, and mixing the two sources into one odo chain corrupts the Δodo that
// recomputed MPG divides by.
//
// The column stores (PCM − dash), so a positive offset means the PCM reads high
// and we SUBTRACT to land on the dash-equivalent number the user expects to see.
const odometerKm = computed(() => {
  const raw = num("odometer");
  if (raw == null) return null;
  return raw - (vehicles.selectedVehicle?.odometer_offset_km ?? 0);
});
const odometer = computed(() => {
  const km = odometerKm.value;
  if (km == null) return null;
  return useImperial.value ? km * 0.621371 : km;
});
const distUnit = computed(() => (useImperial.value ? "mi" : "km"));
// Kept as its own name so the odometer tile's template reads unambiguously;
// both distance tiles in this section share one unit.
const odometerUnit = distUnit;
const timeSinceStart = computed(() => num("time_since_engine_start"));
// PID 0x31, also KILOMETRES — same class of bug as the odometer above, and it
// sits in the same section. No offset applies (it is a since-code-clear
// counter, not the odo chain), but it still needs the conversion and the label.
const distSinceClear = computed(() => {
  const km = num("distance_since_code_clear");
  if (km == null) return null;
  return useImperial.value ? km * 0.621371 : km;
});

// === Derived widgets =====================================================

// Instant economy. fuel_rate from PID 9D is l/h, speed is km/h (WiCAN).
//   metric  : l/100km   = (l/h) ÷ (km/h) × 100
//   imperial: mpg (US)  = (km/h ÷ l/h) × 2.35215
const instantEconomy = computed<{ value: number | null; unit: string }>(() => {
  const s = speed.value; // km/h per WiCAN convention
  const fr = fuelRate.value; // l/h
  if (s == null || fr == null || fr <= 0.05 || s < 1) {
    return { value: null, unit: useImperial.value ? "mpg" : "l/100km" };
  }
  if (useImperial.value) return { value: (s / fr) * 2.35215, unit: "mpg" };
  return { value: (fr / s) * 100, unit: "l/100km" };
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
function fmtSpeedAlt(kph: number | null): string {
  // Always show the *other* unit from what the gauge displays, so the
  // user gets both at a glance. The hero gauge label is whatever the
  // current resolved system shows.
  if (kph == null) return "—";
  return useImperial.value
    ? kph.toFixed(0) // gauge is mph → tile shows kph
    : (kph * 0.62137).toFixed(0); // gauge is kph → tile shows mph
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
        <Pill :state="wsLabel.state" :label="wsLabel.text" />
        <Pill :state="streamLabel.state" :label="streamLabel.text" />
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
          :value="useImperial ? (speed != null ? speed * 0.62137 : null) : speed"
          :max="useImperial ? 120 : 200"
          :warn-at="useImperial ? 75 : 120"
          label="Speed"
          :unit="useImperial ? 'mph' : 'km/h'"
          :size="240"
        />
      </section>

      <!-- Engine health -->
      <section>
        <h3 class="section-title">Engine</h3>
        <div class="tiles">
          <div class="card tile">
            <h3>Coolant</h3>
            <div class="big">{{ fmtTempC(coolant) }}</div>
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
            <h3>{{ useImperial ? "Speed (km/h)" : "Speed (mph)" }}</h3>
            <div class="big">
              {{ fmtSpeedAlt(speed) }}
              <span class="unit">{{ useImperial ? "km/h" : "mph" }}</span>
            </div>
          </div>
        </div>
      </section>

      <!-- Fuel / economy -->
      <section>
        <h3 class="section-title">Fuel &amp; economy</h3>
        <div class="tiles">
          <div class="card tile">
            <h3>Fuel rate</h3>
            <div class="big">{{ fmtFuelRateLh(fuelRate) }}</div>
          </div>
          <div class="card tile">
            <h3>Exhaust flow</h3>
            <div class="big">
              {{ exhaustFlow != null ? exhaustFlow.toFixed(1) + " kg/h" : "—" }}
            </div>
          </div>
          <div class="card tile highlight">
            <h3>{{ useImperial ? "Instant MPG" : "Instant l/100km" }}</h3>
            <div class="big">
              {{ instantEconomy.value != null ? instantEconomy.value.toFixed(1) : "—" }}
            </div>
            <div class="muted small">{{ instantEconomy.unit }} (computed)</div>
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

      <!-- Mixture / fuel delivery -->
      <section>
        <h3 class="section-title">Mixture &amp; fuel delivery</h3>
        <div class="tiles">
          <div class="card tile">
            <h3>Commanded AFR</h3>
            <div class="big">
              {{ cmdAfr != null ? cmdAfr.toFixed(3) : "—" }}
              <span class="unit">λ</span>
            </div>
          </div>
          <div class="card tile">
            <h3>O2 S1 lambda</h3>
            <div class="big">
              {{ o2S1Lambda != null ? o2S1Lambda.toFixed(3) : "—" }}
              <span class="unit">λ</span>
            </div>
            <div class="muted small">upstream wide-range</div>
          </div>
          <div class="card tile">
            <h3>Fuel rail</h3>
            <div class="big">
              {{ fuelRail != null ? fuelRail.toFixed(0) + " kPa" : "—" }}
            </div>
          </div>
        </div>
      </section>

      <!-- Emissions / aftertreatment -->
      <section>
        <h3 class="section-title">Emissions &amp; aftertreatment</h3>
        <div class="tiles">
          <div class="card tile">
            <h3>Catalyst B1</h3>
            <div class="big">{{ fmtTempC(catB1) }}</div>
          </div>
          <div class="card tile">
            <h3>Catalyst B2</h3>
            <div class="big">{{ fmtTempC(catB2) }}</div>
          </div>
          <div class="card tile">
            <h3>Commanded EGR</h3>
            <div class="big">{{ fmtPct(cmdEgr) }}</div>
          </div>
          <div class="card tile">
            <h3>Evap purge</h3>
            <div class="big">{{ fmtPct(cmdEvapPurge) }}</div>
          </div>
        </div>
      </section>

      <!-- Air / atmosphere -->
      <section>
        <h3 class="section-title">Air &amp; atmosphere</h3>
        <div class="tiles">
          <div class="card tile">
            <h3>Intake air</h3>
            <div class="big">{{ fmtTempC(iat) }}</div>
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
            <div class="big">{{ fmtInt(odometer) }} <span class="unit">{{ odometerUnit }}</span></div>
          </div>
          <div class="card tile">
            <h3>Run time</h3>
            <div class="big">{{ fmtRunTime(timeSinceStart) }}</div>
          </div>
          <div class="card tile">
            <h3>Distance since DTC clear</h3>
            <div class="big">
              {{ fmtInt(distSinceClear) }} <span class="unit">{{ distUnit }}</span>
            </div>
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
