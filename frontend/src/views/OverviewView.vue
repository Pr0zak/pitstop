<script setup lang="ts">
import { computed } from "vue";
import { RouterLink } from "vue-router";
import { useVehiclesStore } from "@/stores/vehicles";
import { useAuthStore } from "@/stores/auth";
import { useAsync } from "@/composables/useAsync";
import * as api from "@/api/endpoints";
import {
  fmtRelative,
  fmtMpg,
  fmtMiles,
  fmtMoney,
  fmtDate,
} from "@/composables/useFormat";
import { Fuel, Route, AlertTriangle } from "lucide-vue-next";

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

// EIA weekly retail-gasoline averages — feeds the "vs region avg"
// sub-line on the Gas price hero. Region is hard-coded to "midwest"
// for the user's KC-area driving; future iteration could pick
// per-vehicle or per-home-coord. Falls back gracefully to no
// comparison line if the worker hasn't fetched yet.
// "us" = U.S. all-region weekly average. The XLS source EIA still
// publishes only carries the U.S. aggregate for now; future iteration
// can add regional sheets (Midwest, West Coast, etc).
const eiaQ = useAsync(
  () => api.eiaWeekly("us", 13),
  [],
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

  // Miles-since-last-fill: prefer the persisted vehicles.latest_odo_km
  // (refreshed every 5min by the trip deriver) over the per-metric
  // vehicle_state cache, which is often stale or missing the odometer
  // reading. Falls back to the legacy live-OBD path for vehicles that
  // haven't received the v0.1.96 deriver pass yet.
  const liveOdoKm = vehicles.selectedVehicle?.latest_odo_km ?? num("odometer");
  const milesSinceFill =
    liveOdoKm != null && latest?.odo != null
      ? Math.max(0, liveOdoKm * 0.621371 - latest.odo)
      : null;

  // EIA region-avg comparison. Pulls the most-recent week's $/gal for
  // the configured region; computes a % delta vs the user's latest
  // pump price. Null when EIA data isn't available yet.
  const eiaPoints = eiaQ.data.value?.points ?? [];
  const eiaLatest = eiaPoints[0]?.price ?? null;
  const ppgVsRegion =
    latestPpg != null && eiaLatest != null && eiaLatest > 0
      ? ((latestPpg - eiaLatest) / eiaLatest) * 100
      : null;
  const eiaRegionLabel = (() => {
    const r = eiaQ.data.value?.region ?? "us";
    return r === "us" ? "U.S. avg" : r.replace(/_/g, " ") + " avg";
  })();

  // Lifetime + per-fill aggregates from the same fillups window.
  const totalGallons = fillups.reduce((s, f) => s + (toNum(f.fuel_volume) ?? 0), 0);
  const totalCost = fillups.reduce((s, f) => s + (toNum(f.price_total) ?? 0), 0);
  const totalMiles = fillups.length >= 2
    ? Math.max(0, (toNum(fillups[0]?.odo) ?? 0) - (toNum(fillups[fillups.length - 1]?.odo) ?? 0))
    : null;
  const costPerMile = totalMiles && totalMiles > 0 ? totalCost / totalMiles : null;
  // Best / worst MPG across the trend window.
  const mpgVals = mpgPoints.map((p) => p.mpg ?? 0).filter((v) => v > 0);
  const bestMpg = mpgVals.length ? Math.max(...mpgVals) : null;
  const worstMpg = mpgVals.length ? Math.min(...mpgVals) : null;

  return {
    mpg90,
    latestPpg,
    ppgDelta,
    monthCost,
    monthCount,
    milesSinceFill,
    hasLatest: !!latest,
    eiaLatest,
    ppgVsRegion,
    eiaRegionLabel,
    totalGallons,
    totalCost,
    totalMiles,
    costPerMile,
    bestMpg,
    worstMpg,
  };
});
const dtcsQ = useAsync(
  () => (vehicleId.value ? api.listDtcs(vehicleId.value, true) : Promise.resolve([])),
  [vehicleId],
);

// Engine hours (Task #96).
const hoursQ = useAsync(
  () =>
    vehicleId.value
      ? api.getEngineHours(vehicleId.value)
      : Promise.resolve(null as api.EngineHours | null),
  [vehicleId],
);

// Lifetime cost-of-ownership (Task #98).
const cooQ = useAsync(
  () =>
    vehicleId.value
      ? api.getCostOfOwnership(vehicleId.value)
      : Promise.resolve(null as api.CostOfOwnership | null),
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

// Anomaly card (Task #86). Fetches on vehicle change. The single
// surfaced item is filtered through a localStorage dismiss list
// (fingerprint → expiry epoch ms) so users who explicitly waved off
// "MPG dropped" don't get re-nagged for 7 days.
const ANOM_DISMISS_KEY = "pitstop_anomaly_dismiss";
const COOLDOWN_DAYS = 7;
const anomQ = useAsync(
  () =>
    vehicleId.value
      ? api.getAnomalies(vehicleId.value)
      : Promise.resolve({ anomalies: [] as api.AnomalyItem[] }),
  [vehicleId],
);
function readDismissals(): Record<string, number> {
  try {
    const raw = localStorage.getItem(ANOM_DISMISS_KEY);
    if (!raw) return {};
    const parsed = JSON.parse(raw) as Record<string, number>;
    // Drop expired entries on read so the map doesn't grow unbounded.
    const now = Date.now();
    const live: Record<string, number> = {};
    for (const [k, v] of Object.entries(parsed)) {
      if (v > now) live[k] = v;
    }
    return live;
  } catch {
    return {};
  }
}
const visibleAnomaly = computed<api.AnomalyItem | null>(() => {
  const items = anomQ.data.value?.anomalies ?? [];
  if (!items.length) return null;
  const dismissed = readDismissals();
  for (const a of items) {
    if (!(a.fingerprint in dismissed)) return a;
  }
  return null;
});
function dismissAnomaly(fingerprint: string) {
  const dismissed = readDismissals();
  dismissed[fingerprint] = Date.now() + COOLDOWN_DAYS * 86400_000;
  try {
    localStorage.setItem(ANOM_DISMISS_KEY, JSON.stringify(dismissed));
  } catch {
    /* ignore quota / disabled */
  }
  // Force the visibleAnomaly computed to re-evaluate.
  void anomQ.reload();
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

    <div
      v-if="visibleAnomaly"
      class="anomaly-card"
      :class="`tone-${visibleAnomaly.severity}`"
    >
      <div class="anomaly-icon">
        <AlertTriangle :size="20" />
      </div>
      <div class="anomaly-body">
        <div class="anomaly-headline">{{ visibleAnomaly.headline }}</div>
        <div class="anomaly-detail muted">{{ visibleAnomaly.detail }}</div>
      </div>
      <div class="anomaly-actions">
        <RouterLink
          v-if="visibleAnomaly.deep_link"
          :to="visibleAnomaly.deep_link"
          class="link"
        >Details →</RouterLink>
        <button
          type="button"
          class="ghost"
          @click="dismissAnomaly(visibleAnomaly.fingerprint)"
        >Dismiss</button>
      </div>
    </div>

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
          <div
            v-if="heroData.ppgVsRegion != null"
            class="hero-sub"
            :class="{ up: heroData.ppgVsRegion > 0, down: heroData.ppgVsRegion < 0 }"
          >
            <span>{{ heroData.ppgVsRegion > 0 ? '▲' : heroData.ppgVsRegion < 0 ? '▼' : '·' }}</span>
            {{ Math.abs(heroData.ppgVsRegion).toFixed(1) }}% vs {{ heroData.eiaRegionLabel }}
            <span class="muted small"> · ${{ heroData.eiaLatest!.toFixed(3) }}/gal</span>
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
        <div class="card hero">
          <h3>Cost / mile</h3>
          <div class="hero-value">
            <span class="big">{{ heroData.costPerMile != null ? '$' + heroData.costPerMile.toFixed(3) : '—' }}</span>
          </div>
          <div class="hero-sub muted">
            {{ heroData.totalMiles != null
              ? heroData.totalMiles.toFixed(0) + ' mi · $' + heroData.totalCost.toFixed(0) + ' total'
              : 'last 30 fillups' }}
          </div>
        </div>
        <div class="card hero">
          <h3>Best MPG</h3>
          <div class="hero-value">
            <span class="big">{{ heroData.bestMpg != null ? heroData.bestMpg.toFixed(1) : '—' }}</span>
            <span class="unit">mpg</span>
          </div>
          <div class="hero-sub muted">
            {{ heroData.worstMpg != null
              ? 'worst ' + heroData.worstMpg.toFixed(1) + ' mpg'
              : 'this year' }}
          </div>
        </div>
        <div class="card hero">
          <h3>Total fuel</h3>
          <div class="hero-value">
            <span class="big">{{ heroData.totalGallons != null ? heroData.totalGallons.toFixed(0) : '—' }}</span>
            <span class="unit">gal</span>
          </div>
          <div class="hero-sub muted">
            last {{ heroFillupsQ.data.value?.items?.length ?? 0 }} fillups
          </div>
        </div>
        <!--
          Engine hours (Task #96). Total engine-on hours + the
          hrs-per-100-mi idle ratio. Tile hides if we have zero
          time_since_engine_start samples (pre-WiCAN history).
        -->
        <div v-if="hoursQ.data.value && hoursQ.data.value.total_hours > 0" class="card hero">
          <h3>Engine hours</h3>
          <div class="hero-value">
            <span class="big">{{ Math.round(hoursQ.data.value.total_hours).toLocaleString() }}</span>
            <span class="unit">hrs</span>
          </div>
          <div class="hero-sub muted">
            <span v-if="hoursQ.data.value.hrs_per_100mi != null">
              {{ hoursQ.data.value.hrs_per_100mi.toFixed(2) }} hrs/100 mi
            </span>
            <span v-else>cumulative</span>
          </div>
        </div>

        <!--
          Cost of ownership (Task #98). Headline number lights up only
          when the user has set a purchase price; otherwise the card
          falls back to fuel + maintenance lifetime totals so the
          slot doesn't go dark.
        -->
        <div v-if="cooQ.data.value" class="card hero coo">
          <h3>Lifetime $/mi</h3>
          <div class="hero-value">
            <span class="big">
              {{ cooQ.data.value.cost_per_mi != null
                ? '$' + cooQ.data.value.cost_per_mi.toFixed(3)
                : '—' }}
            </span>
          </div>
          <div class="hero-sub muted">
            <template v-if="cooQ.data.value.purchase_price != null">
              ${{ Math.round(cooQ.data.value.total).toLocaleString() }} total
              <span v-if="cooQ.data.value.lifetime_mi != null">
                · {{ Math.round(cooQ.data.value.lifetime_mi).toLocaleString() }} mi
              </span>
            </template>
            <template v-else>
              <RouterLink to="/settings" class="link">
                Add purchase price →
              </RouterLink>
            </template>
          </div>
          <!-- Stacked breakdown bar; only renders when total > 0 -->
          <div v-if="cooQ.data.value.total > 0" class="coo-bar">
            <span
              class="coo-seg coo-purchase"
              :style="{
                width: ((cooQ.data.value.purchase_price ?? 0) /
                  cooQ.data.value.total * 100).toFixed(1) + '%',
              }"
              :title="`Purchase $${(cooQ.data.value.purchase_price ?? 0).toFixed(0)}`"
            />
            <span
              class="coo-seg coo-fuel"
              :style="{
                width: (cooQ.data.value.fuel_total /
                  cooQ.data.value.total * 100).toFixed(1) + '%',
              }"
              :title="`Fuel $${cooQ.data.value.fuel_total.toFixed(0)}`"
            />
            <span
              class="coo-seg coo-maint"
              :style="{
                width: (cooQ.data.value.maintenance_total /
                  cooQ.data.value.total * 100).toFixed(1) + '%',
              }"
              :title="`Maintenance $${cooQ.data.value.maintenance_total.toFixed(0)}`"
            />
          </div>
        </div>
      </section>

      <!-- Brand tape: a thin redline accent under the hero strip. -->
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
.anomaly-card {
  display: grid;
  grid-template-columns: auto 1fr auto;
  gap: 0.85rem;
  align-items: center;
  padding: 0.75rem 1rem;
  border-radius: var(--r-md);
  border: 1px solid;
  background: var(--c-surface);
}
.anomaly-card.tone-warn {
  border-color: #f59e0b66;
  background: linear-gradient(0deg, #f59e0b0e, transparent);
}
.anomaly-card.tone-danger {
  border-color: #ef444466;
  background: linear-gradient(0deg, #ef44440e, transparent);
}
.anomaly-icon {
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background: var(--c-surface-soft);
  color: #f59e0b;
}
.anomaly-card.tone-danger .anomaly-icon {
  color: #ef4444;
}
.anomaly-body {
  display: flex;
  flex-direction: column;
  gap: 0.15rem;
  min-width: 0;
}
.anomaly-headline {
  font-weight: 600;
  font-size: 0.95rem;
}
.anomaly-detail {
  font-size: 0.85rem;
}
.anomaly-actions {
  display: flex;
  gap: 0.5rem;
  align-items: center;
  flex: none;
}
.coo-bar {
  display: flex;
  height: 6px;
  margin-top: 0.5rem;
  border-radius: 3px;
  overflow: hidden;
  background: var(--c-surface-soft);
}
.coo-seg {
  display: inline-block;
  height: 100%;
}
.coo-purchase { background: #6366f1; }
.coo-fuel    { background: #2f81f7; }
.coo-maint   { background: #f59e0b; }
</style>
