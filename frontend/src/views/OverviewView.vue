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
  fmtMoney,
  fmtDate,
  fmtDistanceKm,
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
// 30 most-recent fillups feed the hero cards (latest $/gal, this-month
// cost). One query covers both — server caps at 30 by default. The
// recent-fillups table below shows the first 5 of these, so no separate
// limit-5 call is needed (the limit-5 list is a strict prefix of this
// list — same vehicle, same default ordering).
const heroFillupsQ = useAsync(
  () =>
    vehicleId.value
      ? api.listFillups({ vehicle_id: vehicleId.value, limit: 30 })
      : Promise.resolve({ items: [], total: 0 }),
  [vehicleId],
);

// Recent-fillups table: first 5 of the hero list. Derived, not fetched.
const recentFillups = computed(() =>
  (heroFillupsQ.data.value?.items ?? []).slice(0, 5),
);

// Rolling-90d MPG trend feeds the consumption tile.
const mpgTrendQ = useAsync(
  () =>
    vehicleId.value
      ? api.mpgTrend(vehicleId.value, "3m")
      : Promise.resolve({ points: [] }),
  [vehicleId],
);

// Long-range MPG trend (yearly buckets) for the MPG-trend hero card.
const mpgAllQ = useAsync(
  () =>
    vehicleId.value
      ? api.mpgTrend(vehicleId.value, "all")
      : Promise.resolve({ points: [] }),
  [vehicleId],
);

// Odometer history for the Odo hero sparkline.
const odoHistoryQ = useAsync(
  () =>
    vehicleId.value
      ? api.getOdometerHistory(vehicleId.value, "all")
      : Promise.resolve({
          points: [] as api.OdometerHistoryPoint[],
          summary: { window: "all", n_points: 0 } as api.OdometerHistorySummary,
        }),
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

/** Convert an ISO 8601 UTC timestamp into a short relative-time string
 *  ("live" / "Nm ago" / "Nh ago" / "Nd ago") for hero-card subtitles.
 *  Mirrors the phone-side helper in StatusViewModel.formatReadingAge. */
function formatReadingAge(isoTime: string): string | null {
  const ms = Date.parse(isoTime);
  if (!Number.isFinite(ms)) return null;
  const ageSec = Math.floor((Date.now() - ms) / 1000);
  if (ageSec < 90) return "live";
  if (ageSec < 3600) return `${Math.floor(ageSec / 60)}m ago`;
  if (ageSec < 86_400) return `${Math.floor(ageSec / 3600)}h ago`;
  return `${Math.floor(ageSec / 86_400)}d ago`;
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

  // Fuel level + estimated gallons + reading age. Two sources, picked by
  // availability:
  //
  // 1. **Hybrid estimator (preferred, post-ADR-019 phase 2):** the backend's
  //    fuel-state worker maintains `vehicles.fuel_level_estimate_l` as a
  //    persisted state mutated by fillups (reset), trips (decrement), and
  //    sensor-snap when engine has been off long enough for the noisy
  //    PID 0x2F sensor to settle. Stable, monotonic between events,
  //    immune to MAF-burst noise.
  //
  // 2. **Raw smoothed sensor (legacy fallback):** when the estimator
  //    hasn't been seeded yet (no fillup logged since the migration, no
  //    truth-up snap fired yet), fall back to the JSONB
  //    `latest.fuel_level` field which goes through the p75 smoothing
  //    in /vehicles. Same behavior as pre-phase-2.
  const sv = vehicles.selectedVehicle;
  const estimateL = sv?.fuel_level_estimate_l ?? null;
  const tankCapacityL = sv?.tank_capacity_l ?? null;
  const tankCapacityGal = sv?.tank1_capacity ?? null;  // user-fuel-unit
  const fuelEntry = sv?.latest?.fuel_level ?? null;

  let fuelLevelPct: number | null;
  let fuelGallons: number | null;
  let fuelLevelAge: string | null;
  if (estimateL != null && tankCapacityL != null && tankCapacityL > 0) {
    fuelLevelPct = Math.max(0, Math.min(100, (estimateL / tankCapacityL) * 100));
    // Show in user's fuel unit. tank1_capacity comes from Fuelio in the
    // user's setting; if that's gallons, estimateL needs the L→gal
    // conversion. If the user has fuel_unit set to L, the "gallons"
    // variable is technically liters — name is historical.
    const isUserUnitGallons =
      tankCapacityGal != null && tankCapacityL > tankCapacityGal * 1.5;
    fuelGallons = isUserUnitGallons ? estimateL * 0.264172 : estimateL;
    fuelLevelAge = sv?.fuel_level_estimate_updated_at
      ? formatReadingAge(sv.fuel_level_estimate_updated_at)
      : null;
  } else {
    fuelLevelPct =
      fuelEntry?.value_num != null && Number.isFinite(fuelEntry.value_num)
        ? fuelEntry.value_num
        : null;
    fuelGallons =
      fuelLevelPct != null && tankCapacityGal != null && tankCapacityGal > 0
        ? (tankCapacityGal * fuelLevelPct) / 100
        : null;
    fuelLevelAge = fuelEntry?.time ? formatReadingAge(fuelEntry.time) : null;
  }

  // Fuel-estimate staleness (FUEL-CONFIDENCE-UI). The hybrid estimator is
  // updated by fillups / trips / engine-off snaps; if none of those have
  // fired in over a week the displayed level is increasingly untrustworthy.
  // Surface a warn badge so the number isn't read as gospel. Mirrored on
  // the phone.
  const STALE_MS = 7 * 86_400_000;
  const estUpdatedAt = sv?.fuel_level_estimate_updated_at;
  const estUpdatedMs = estUpdatedAt ? Date.parse(estUpdatedAt) : NaN;
  const estimateStale =
    estimateL != null &&
    Number.isFinite(estUpdatedMs) &&
    Date.now() - estUpdatedMs > STALE_MS;
  const estimateStaleDays =
    estimateStale && Number.isFinite(estUpdatedMs)
      ? Math.floor((Date.now() - estUpdatedMs) / 86_400_000)
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
    fuelLevelPct,
    fuelGallons,
    fuelLevelAge,
    estimateStale,
    estimateStaleDays,
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
const gaugeColor = computed(() => {
  const p = heroData.value.fuelLevelPct;
  if (p == null) return 'var(--c-line0)';
  if (p < 15) return '#ff3a2e';
  if (p < 35) return '#ffb020';
  return '#4ade80';
});

function gaugeArcPath(pctIn: number): string {
  const pct = Math.max(0, Math.min(100, pctIn));
  if (pct <= 0) return '';
  const cx = 100, cy = 100, r = 78;
  const startAngle = Math.PI;
  const endAngle = Math.PI + (Math.PI * pct) / 100;
  const sx = cx + r * Math.cos(startAngle);
  const sy = cy + r * Math.sin(startAngle);
  const ex = cx + r * Math.cos(endAngle);
  const ey = cy + r * Math.sin(endAngle);
  // Sweep is always 0–180° (top-half gauge), so large-arc-flag is ALWAYS 0.
  // Previously this was `pct > 50 ? 1 : 0`, which told SVG to take the LONGER
  // arc when pct>50 — drawing the wrong way around the circle and leaving
  // only the two rounded-cap endpoints visible.
  return `M ${sx.toFixed(2)} ${sy.toFixed(2)} A ${r} ${r} 0 0 1 ${ex.toFixed(2)} ${ey.toFixed(2)}`;
}

function gaugeTickPath(): string {
  const cx = 100, cy = 100, rOuter = 67, rInner = 61;
  const angles = [180, 225, 270, 315, 360];
  return angles
    .map((deg) => {
      const a = (deg * Math.PI) / 180;
      const x1 = cx + rOuter * Math.cos(a);
      const y1 = cy + rOuter * Math.sin(a);
      const x2 = cx + rInner * Math.cos(a);
      const y2 = cy + rInner * Math.sin(a);
      return `M ${x1.toFixed(2)} ${y1.toFixed(2)} L ${x2.toFixed(2)} ${y2.toFixed(2)}`;
    })
    .join(' ');
}

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

// Build a normalised SVG path "M0,y0 L1,y1 …" from an array of
// numbers, mapped into a 0–100 × 0–30 viewBox so the same SVG
// renders cleanly at any tile width. Returns null when not enough
// data to draw a line.
function sparkPath(values: (number | null)[]): string | null {
  const xs: { i: number; v: number }[] = [];
  values.forEach((v, i) => { if (v != null && Number.isFinite(v)) xs.push({ i, v }); });
  if (xs.length < 2) return null;
  const min = Math.min(...xs.map((p) => p.v));
  const max = Math.max(...xs.map((p) => p.v));
  const range = max - min || 1;
  const stepX = 100 / (values.length - 1 || 1);
  return xs.map((p, k) => {
    const x = (p.i * stepX).toFixed(2);
    const y = (30 - ((p.v - min) / range) * 28 - 1).toFixed(2);
    return (k === 0 ? "M" : "L") + x + "," + y;
  }).join(" ");
}

// MPG long-range trend for the hero card. Pulls from mpgAllQ
// (yearly buckets) and exposes latest, prior, delta-vs-prior, and
// a sparkline path of the per-year averages.
interface MpgTrendHero {
  latest: number | null;
  prior: number | null;
  deltaPct: number | null;
  spark: string | null;
}
const mpgTrendHero = computed<MpgTrendHero>(() => {
  const pts = (mpgAllQ.data.value?.points ?? []).filter((p) => p.mpg != null);
  if (pts.length === 0) {
    return { latest: null, prior: null, deltaPct: null, spark: null };
  }
  const latest = pts[pts.length - 1].mpg!;
  const prior = pts.length >= 2 ? pts[pts.length - 2].mpg! : null;
  const deltaPct = prior != null && prior > 0
    ? ((latest - prior) / prior) * 100
    : null;
  return {
    latest,
    prior,
    deltaPct,
    spark: sparkPath(pts.map((p) => p.mpg)),
  };
});

// Odometer hero: current mi + lifetime delta + sparkline of
// cumulative miles over time.
interface OdoHero {
  currentMi: number | null;
  deltaMi: number | null;
  milesPerDay: number | null;
  spark: string | null;
}
const odoHero = computed<OdoHero>(() => {
  const summary = odoHistoryQ.data.value?.summary;
  const pts = odoHistoryQ.data.value?.points ?? [];
  return {
    currentMi: summary?.current_mi ?? null,
    deltaMi: summary?.delta_mi ?? null,
    milesPerDay: summary?.miles_per_day ?? null,
    spark: pts.length >= 2
      ? sparkPath(pts.map((p) => (p.odo_km != null ? p.odo_km * 0.621371 : null)))
      : null,
  };
});

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
        <div class="card hero fuel-gauge" :style="{ '--gauge-accent': gaugeColor }">
          <h3>Fuel level</h3>
          <div class="fuel-gauge-wrap">
            <svg class="fuel-gauge-svg" viewBox="0 0 200 120" preserveAspectRatio="xMidYMax meet">
              <path
                class="gauge-track"
                :d="gaugeArcPath(100)"
                fill="none"
                stroke-width="14"
                stroke-linecap="round"
              />
              <path
                v-if="heroData.fuelLevelPct != null && heroData.fuelLevelPct > 0"
                :d="gaugeArcPath(heroData.fuelLevelPct)"
                fill="none"
                :stroke="gaugeColor"
                stroke-width="14"
                stroke-linecap="round"
              />
              <path
                class="gauge-tick"
                :d="gaugeTickPath()"
                fill="none"
                stroke-width="1.5"
                stroke-linecap="round"
              />
            </svg>
            <span class="fuel-gauge-end e">E</span>
            <span class="fuel-gauge-end f">F</span>
            <span class="fuel-gauge-pct">
              {{ heroData.fuelLevelPct != null ? heroData.fuelLevelPct.toFixed(0) + '%' : '—' }}
            </span>
            <span
              v-if="heroData.estimateStale"
              class="stale-badge"
              :title="`Estimate hasn't been updated by a fillup, trip, or sensor snap in ${heroData.estimateStaleDays} day${heroData.estimateStaleDays === 1 ? '' : 's'}`"
            >stale</span>
          </div>
          <div class="hero-sub muted">
            {{ heroData.fuelGallons != null && heroData.fuelLevelAge
              ? heroData.fuelGallons.toFixed(1) + ' gal · ' + heroData.fuelLevelAge
              : heroData.fuelGallons != null
                ? heroData.fuelGallons.toFixed(1) + ' gal'
                : heroData.fuelLevelAge ?? '—' }}
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

        <!-- MPG trend over the full history (yearly buckets). -->
        <div v-if="mpgTrendHero.latest != null" class="card hero">
          <h3>MPG trend</h3>
          <div class="hero-value">
            <span class="big">{{ mpgTrendHero.latest!.toFixed(1) }}</span>
            <span class="unit">mpg</span>
          </div>
          <div
            v-if="mpgTrendHero.deltaPct != null"
            class="hero-sub"
            :class="{ up: mpgTrendHero.deltaPct < 0, down: mpgTrendHero.deltaPct > 0 }"
          >
            <span>{{ mpgTrendHero.deltaPct > 0 ? '▲' : mpgTrendHero.deltaPct < 0 ? '▼' : '·' }}</span>
            {{ Math.abs(mpgTrendHero.deltaPct).toFixed(1) }}% vs prior year
          </div>
          <div v-else class="hero-sub muted">yearly average</div>
          <svg
            v-if="mpgTrendHero.spark"
            class="hero-spark"
            viewBox="0 0 100 30"
            preserveAspectRatio="none"
            aria-hidden="true"
          >
            <path :d="mpgTrendHero.spark" stroke="#2f81f7" stroke-width="1.4" fill="none" />
          </svg>
        </div>

        <!-- Lifetime fuel spend from the COO endpoint. -->
        <div v-if="cooQ.data.value && cooQ.data.value.fuel_total > 0" class="card hero">
          <h3>Fuel cost</h3>
          <div class="hero-value">
            <span class="big">${{ Math.round(cooQ.data.value.fuel_total).toLocaleString() }}</span>
          </div>
          <div class="hero-sub muted">
            lifetime
            <span v-if="heroData.totalGallons != null">
              · ${{
                (cooQ.data.value.fuel_total / Math.max(1, heroData.totalGallons)).toFixed(2)
              }}/gal avg
            </span>
          </div>
        </div>

        <!-- Odometer history sparkline. -->
        <div v-if="odoHero.currentMi != null" class="card hero">
          <h3>Odometer</h3>
          <div class="hero-value">
            <span class="big">{{ Math.round(odoHero.currentMi).toLocaleString() }}</span>
            <span class="unit">mi</span>
          </div>
          <div v-if="odoHero.deltaMi != null" class="hero-sub muted">
            +{{ Math.round(odoHero.deltaMi).toLocaleString() }} mi
            <span v-if="odoHero.milesPerDay != null">
              · {{ odoHero.milesPerDay.toFixed(1) }}/day
            </span>
          </div>
          <svg
            v-if="odoHero.spark"
            class="hero-spark"
            viewBox="0 0 100 30"
            preserveAspectRatio="none"
            aria-hidden="true"
          >
            <path :d="odoHero.spark" stroke="#3fb950" stroke-width="1.4" fill="none" />
          </svg>
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
                <span class="muted">{{ fmtDistanceKm(t.distance_km ?? null) }}</span>
              </RouterLink>
            </li>
          </ul>
        </section>

        <section class="card">
          <h3>
            <Fuel :size="14" /> Recent fillups
            <RouterLink to="/fuel" class="more">all →</RouterLink>
          </h3>
          <div v-if="heroFillupsQ.loading.value" class="muted">Loading…</div>
          <div v-else-if="heroFillupsQ.error.value" class="muted">
            Failed to load: {{ heroFillupsQ.error.value }}
          </div>
          <div v-else-if="recentFillups.length === 0" class="muted">
            No fillups recorded.
          </div>
          <ul v-else class="recent">
            <li v-for="f in recentFillups" :key="f.id">
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
.hero-spark {
  display: block;
  width: 100%;
  height: 30px;
  margin-top: 0.4rem;
  opacity: 0.75;
}
.hero.fuel-gauge {
  border: 1.5px solid var(--gauge-accent, var(--c-line0));
  transition: border-color 200ms ease;
}
.fuel-gauge-wrap {
  position: relative;
  width: 100%;
  margin: 0.1rem 0 0.1rem 0;
}
.fuel-gauge-svg {
  display: block;
  width: 100%;
  height: auto;
}
.gauge-track {
  stroke: var(--c-line0);
}
.gauge-tick {
  stroke: var(--c-ink3);
  opacity: 0.7;
}
.fuel-gauge-pct {
  position: absolute;
  left: 50%;
  bottom: 8%;
  transform: translateX(-50%);
  font-family: 'Geist Mono', ui-monospace, monospace;
  font-size: 1.55rem;
  font-weight: 600;
  letter-spacing: -0.04em;
  line-height: 1;
  font-variant-numeric: tabular-nums;
  color: var(--c-ink0);
}
.fuel-gauge-end {
  position: absolute;
  bottom: 2px;
  font-family: 'Geist', sans-serif;
  font-size: 0.72rem;
  font-weight: 700;
  letter-spacing: 0.04em;
  color: var(--c-ink3);
}
.fuel-gauge-end.e {
  left: 2px;
}
.fuel-gauge-end.f {
  right: 2px;
}
.stale-badge {
  position: absolute;
  top: 4px;
  right: 4px;
  font-family: 'Geist', sans-serif;
  font-size: 0.6rem;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  padding: 1px 5px;
  border-radius: 999px;
  color: #f59e0b;
  background: #f59e0b1f;
  border: 1px solid #f59e0b66;
  cursor: help;
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
