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

// 30 most-recent fillups feed the hero cards (latest $/gal, this-month
// cost). One query covers both — server caps at 30 by default.
const heroFillupsQ = useAsync(
  () =>
    vehicleId.value
      ? api.listFillups({ vehicle_id: vehicleId.value, limit: 30 })
      : Promise.resolve({ items: [], total: 0 }),
  [vehicleId],
);

// Rolling-90d MPG trend feeds the consumption tile.
const mpgTrendQ = useAsync(
  () =>
    vehicleId.value
      ? api.mpgTrend(vehicleId.value, "3m")
      : Promise.resolve({ points: [] }),
  [vehicleId],
);

// ── Hero card derivations ─────────────────────────────────────────────
//
// All four numbers fall out of two queries (30 most-recent fillups +
// /analytics/mpg windowed to 3 months). No new endpoints needed — the
// data is already on the wire for the existing charts. We compute on
// the client so the cards stay coupled to the same fillup list users
// see further down the page; if a fillup edit changes a value, both
// the card and the table refresh together.

interface HeroFillup {
  fillup_date: string;
  // Backend serializes numeric(10,N) Decimal as JSON string, not number.
  // We coerce defensively below so the type signature reflects reality.
  price_total: number | string | null;
  price_per_unit: number | string | null;
  fuel_volume: number;
  odo: number;
  is_full: boolean;
  is_missed: boolean;
}

/** Coerce Decimal-as-string OR number OR null to a finite number, or null. */
function toNum(v: unknown): number | null {
  if (typeof v === "number") return Number.isFinite(v) ? v : null;
  if (typeof v === "string" && v.length > 0) {
    const n = Number(v);
    return Number.isFinite(n) ? n : null;
  }
  return null;
}

const heroData = computed(() => {
  const fillups = (heroFillupsQ.data.value?.items ?? []) as HeroFillup[];
  const mpgPoints = mpgTrendQ.data.value?.points ?? [];

  // 90-day rolling MPG — average of the points in the trend window.
  const mpg90 = mpgPoints.length > 0
    ? mpgPoints.reduce((s, p) => s + (p.mpg ?? 0), 0) / mpgPoints.length
    : null;

  // Latest fillup price/gal + 30-day average for delta.
  const latest = fillups[0] ?? null;
  const latestPpg = toNum(latest?.price_per_unit ?? null);
  const ppgPoints = fillups
    .map((f) => toNum(f.price_per_unit))
    .filter((v): v is number => v != null && v > 0);
  const avgPpg = ppgPoints.length > 0
    ? ppgPoints.reduce((s, v) => s + v, 0) / ppgPoints.length
    : null;
  const ppgDelta =
    latestPpg != null && avgPpg != null && avgPpg > 0
      ? ((latestPpg - avgPpg) / avgPpg) * 100
      : null;

  // This calendar month's fuel cost (sum of fillups with this YYYY-MM).
  const now = new Date();
  const thisMonth = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}`;
  const monthCost = fillups
    .filter((f) => (f.fillup_date ?? "").startsWith(thisMonth))
    .reduce((s, f) => s + (toNum(f.price_total) ?? 0), 0);
  const monthCount = fillups.filter((f) =>
    (f.fillup_date ?? "").startsWith(thisMonth),
  ).length;

  // Miles-since-last-fill: live OBD odo vs last fillup odo.
  const liveOdoKm = num("odometer");
  const milesSinceFill =
    liveOdoKm != null && latest?.odo != null
      ? Math.max(0, liveOdoKm * 0.621371 - latest.odo)
      : null;

  return {
    mpg90,
    latestPpg,
    ppgDelta,
    monthCost,
    monthCount,
    milesSinceFill,
    hasLatest: !!latest,
  };
});
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
      <!-- Hero cards: fuel consumption + gas price + this-month spend +
           miles since last fill. Sit above the live OBD metric grid so
           the eye lands on long-running averages first; live readings
           come second. -->
      <section v-if="heroData.hasLatest" class="hero-grid">
        <div class="card hero">
          <h3>Avg consumption</h3>
          <div class="hero-value">
            <span class="big">{{ heroData.mpg90 != null ? heroData.mpg90.toFixed(1) : '—' }}</span>
            <span class="unit">mpg</span>
          </div>
          <div class="hero-sub muted">90-day rolling</div>
        </div>
        <div class="card hero">
          <h3>Gas price</h3>
          <div class="hero-value">
            <span class="big">{{ heroData.latestPpg != null ? '$' + heroData.latestPpg.toFixed(3) : '—' }}</span>
            <span class="unit">/gal</span>
          </div>
          <div
            v-if="heroData.ppgDelta != null"
            class="hero-sub"
            :class="{ up: heroData.ppgDelta > 0, down: heroData.ppgDelta < 0 }"
          >
            <span>{{ heroData.ppgDelta > 0 ? '▲' : heroData.ppgDelta < 0 ? '▼' : '·' }}</span>
            {{ Math.abs(heroData.ppgDelta).toFixed(1) }}% vs 30-day avg
          </div>
        </div>
        <div class="card hero">
          <h3>This month</h3>
          <div class="hero-value">
            <span class="big">${{ heroData.monthCost.toFixed(2) }}</span>
          </div>
          <div class="hero-sub muted">
            {{ heroData.monthCount }} fillup{{ heroData.monthCount === 1 ? '' : 's' }}
          </div>
        </div>
        <div class="card hero">
          <h3>Since last fill</h3>
          <div class="hero-value">
            <span class="big">{{ heroData.milesSinceFill != null ? heroData.milesSinceFill.toFixed(0) : '—' }}</span>
            <span class="unit">mi</span>
          </div>
          <div class="hero-sub muted">
            {{ heroData.milesSinceFill != null && heroData.mpg90 != null
              ? '~' + (heroData.mpg90 - heroData.milesSinceFill / 16 * 0.5).toFixed(0) + ' mi range'
              : 'live odo vs last fillup' }}
          </div>
        </div>
      </section>

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
              <span class="muted">{{ fmtMpg(f.mpg) }} · {{ fmtMoney(toNum(f.price_total) ?? 0) }}</span>
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
.hero-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 0.8rem;
}
.hero {
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
}
.hero h3 {
  font-size: 11px;
  font-weight: 500;
  color: var(--c-ink3);
  text-transform: uppercase;
  letter-spacing: 0.08em;
  margin: 0;
}
.hero-value {
  display: flex;
  align-items: baseline;
  gap: 0.35rem;
}
.hero-value .big {
  font-family: 'Geist Mono', ui-monospace, monospace;
  font-size: 2rem;
  font-weight: 500;
  letter-spacing: -0.04em;
  line-height: 1.0;
  font-variant-numeric: tabular-nums;
  color: var(--c-ink0);
}
.hero-value .unit {
  font-family: 'Geist', sans-serif;
  font-size: 0.9rem;
  color: var(--c-ink3);
  font-weight: 500;
}
.hero-sub {
  font-family: 'Geist Mono', ui-monospace, monospace;
  font-size: 0.78rem;
  color: var(--c-ink3);
  letter-spacing: -0.005em;
  font-variant-numeric: tabular-nums;
}
.hero-sub.up {
  color: var(--c-danger);
}
.hero-sub.down {
  color: var(--c-success);
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
