<script setup lang="ts">
import { computed, ref } from "vue";
import { RouterLink } from "vue-router";
import FillupModal from "@/components/FillupModal.vue";
import StateCard from "@/components/StateCard.vue";
import Sparkline from "@/components/charts/Sparkline.vue";
import type { Fillup } from "@/api/types";
import { useVehiclesStore } from "@/stores/vehicles";
import { useAuthStore } from "@/stores/auth";
import { useAsync } from "@/composables/useAsync";
import * as api from "@/api/endpoints";
import {
  fmtRelative,
  fmtMpg,
  fmtMoney,
  fmtDate,
  fmtWhen,
  fmtDistanceKm,
  fmtVolume,
  fmtPricePerVolume,
  fmtDistance,
  fmtOdoKm,
  fmtInt,
  nf,
  toNum,
  convEconomyMpg,
  convVolume,
  convDistance,
  convPricePerVolume,
  economyUnitLabel,
  volUnitLabel,
  distUnitLabel,
  vehicleDistUnit,
  vehicleVolUnit,
} from "@/composables/useFormat";
import { Fuel, Route, AlertTriangle, ChevronDown } from "lucide-vue-next";

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

// Monthly MPG trend feeds the economy tile (90-day value + 12-month
// sparkline). NOTE: /analytics/mpg does not filter by `window` — every
// non-"all" window returns every month since the first fillup — so the
// 3- and 12-month slices are cut client-side from the period keys.
const mpgTrendQ = useAsync(
  () =>
    vehicleId.value
      ? api.mpgTrend(vehicleId.value, "year")
      : Promise.resolve({ points: [] }),
  [vehicleId],
);

/** "YYYY-MM" of the month `back` months before the current one. */
function monthKey(back: number): string {
  const d = new Date();
  d.setDate(1);
  d.setMonth(d.getMonth() - back);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}`;
}
/** Monthly MPG points in the trailing `months` calendar months (incl. this one). */
function recentMpgPoints(months: number) {
  const from = monthKey(months - 1);
  return (mpgTrendQ.data.value?.points ?? []).filter(
    (p) => p.period >= from && p.mpg != null && p.mpg > 0,
  );
}

// Thirteen months of spend for the "This month" bars (same endpoint the
// Android Home uses): 12 bars plus the month before them for the average.
const monthlyQ = useAsync(
  () =>
    vehicleId.value
      ? api.monthlySpend(vehicleId.value, 13)
      : Promise.resolve({ months: [] as { month: string; fuel: number; service: number; total: number }[] }),
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
  // Last three calendar months of monthly MPG averages.
  const mpgPoints = recentMpgPoints(3);

  // 90-day MPG — fillup-weighted mean of the monthly averages in the window.
  const w = mpgPoints.reduce((s, p) => s + (p.fillup_count ?? 1), 0);
  const mpg90 = mpgPoints.length > 0 && w > 0
    ? mpgPoints.reduce((s, p) => s + (p.mpg ?? 0) * (p.fillup_count ?? 1), 0) / w
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
    fuelGallons = convVolume(estimateL, "L");
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
        ? convVolume((tankCapacityGal * fuelLevelPct) / 100, vehicleVolUnit(sv))
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
// ── KPI sparklines ───────────────────────────────────────────────────
// One slot per calendar month, so a month without fillups is a gap in the
// line rather than a fake dip. No data → no sparkline (never a row of zeros).
const econSpark = computed<{ values: (number | null)[]; min: number; max: number } | null>(() => {
  const byMonth = new Map(recentMpgPoints(12).map((p) => [p.period, p.mpg as number]));
  const values = Array.from({ length: 12 }, (_, i) => {
    const v = byMonth.get(monthKey(11 - i));
    return v != null ? convEconomyMpg(v) : null;
  });
  const finite = values.filter((v): v is number => v != null);
  if (finite.length < 2) return null;
  return { values, min: Math.min(...finite), max: Math.max(...finite) };
});

// $/volume of the recent fillups, oldest → newest.
const priceSpark = computed<{ values: number[]; n: number } | null>(() => {
  const vals = [...(heroFillupsQ.data.value?.items ?? [])]
    .sort((a, b) => (a.fillup_date ?? "").localeCompare(b.fillup_date ?? ""))
    .map((f) => toNum(f.price_per_unit))
    .filter((v): v is number => v != null && v > 0)
    .slice(-12)
    .map((v) => convPricePerVolume(v, volSrc.value));
  return vals.length >= 2 ? { values: vals, n: vals.length } : null;
});

// Monthly fuel spend, last 12 calendar months with the current one
// highlighted; the average is over the 12 complete months before it.
const spendBars = computed<{ values: number[]; avg: number | null } | null>(() => {
  const months = monthlyQ.data.value?.months ?? [];
  if (months.length === 0) return null;
  const byMonth = new Map(months.map((m) => [m.month.slice(0, 7), m.fuel ?? 0]));
  const values = Array.from({ length: 12 }, (_, i) => byMonth.get(monthKey(11 - i)) ?? 0);
  const prior = Array.from({ length: 12 }, (_, i) => byMonth.get(monthKey(12 - i)))
    .filter((v): v is number => v != null);
  const avg = prior.length ? prior.reduce((a, b) => a + b, 0) / prior.length : null;
  if (!values.some((v) => v > 0)) return null;
  return { values, avg };
});

const gaugeColor = computed(() => {
  const p = heroData.value.fuelLevelPct;
  if (p == null) return 'var(--c-line0)';
  if (p < 15) return 'var(--c-danger)';
  if (p < 35) return 'var(--c-warn)';
  return 'var(--c-success)';
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

// Odometer hero: current reading + lifetime delta + sparkline. All three
// numbers are canonical km (field names are historical); the template
// converts at render.
interface OdoHero {
  currentMi: number | null;
  deltaMi: number | null;
  milesPerDay: number | null;
  spark: string | null;
}
const odoHero = computed<OdoHero>(() => {
  const summary = odoHistoryQ.data.value?.summary;
  const pts = odoHistoryQ.data.value?.points ?? [];
  // current/delta in km (fall back to the mi fields for older backends).
  const curKm = summary?.current_km ?? (summary?.current_mi != null ? summary.current_mi * 1.609344 : null);
  const deltaKm = summary?.delta_km ?? (summary?.delta_mi != null ? summary.delta_mi * 1.609344 : null);
  const perDayKm = summary?.miles_per_day != null ? summary.miles_per_day * 1.609344 : null;
  return {
    currentMi: curKm,
    deltaMi: deltaKm,
    milesPerDay: perDayKm,
    spark: pts.length >= 2 ? sparkPath(pts.map((p) => p.odo_km ?? null)) : null,
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

// ── Display-unit helpers for the hero numbers ─────────────────────────
// Fillup-derived values are in the vehicle's own units; convert once here.
const volSrc = computed(() => vehicleVolUnit(vehicles.selectedVehicle));
const distSrc = computed(() => vehicleDistUnit(vehicles.selectedVehicle));
function econ(mpg: number | null | undefined): string {
  return mpg != null && mpg > 0 ? nf(1).format(convEconomyMpg(mpg)) : "—";
}
function ppv(v: number | null | undefined): string {
  return v != null ? fmtMoney(convPricePerVolume(v, volSrc.value), 3) : "—";
}

// Lifetime $/volume. Needs the lifetime fillup VOLUME the backend now
// returns (fuel_volume_total); dividing lifetime dollars by the last-30
// fillups' volume (the old code) overstated it several-fold. When an older
// backend omits the field the sub-line is hidden rather than wrong.
const lifetimePpv = computed<number | null>(() => {
  const c = cooQ.data.value;
  const vol = toNum(c?.fuel_volume_total);
  if (!c || vol == null || vol <= 0 || !(c.fuel_total > 0)) return null;
  return c.fuel_total / vol;
});

// Secondary "Ownership" tiles collapse behind a toggle; remember the choice.
const OWN_KEY = "pitstop_overview_ownership_open";
const ownershipOpen = ref<boolean>(
  (() => {
    try {
      return localStorage.getItem(OWN_KEY) === "1";
    } catch {
      return false;
    }
  })(),
);
function toggleOwnership() {
  ownershipOpen.value = !ownershipOpen.value;
  try {
    localStorage.setItem(OWN_KEY, ownershipOpen.value ? "1" : "0");
  } catch {
    /* ignore */
  }
}

// Recent-fillup rows open the shared FillupModal in edit mode.
const editing = ref<Fillup | null>(null);
function onFillupSaved() {
  void heroFillupsQ.reload();
  void cooQ.reload();
  void vehicles.fetchVehicles().catch(() => {});
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
        <AlertTriangle :size="20" aria-hidden="true" />
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

    <StateCard v-else-if="vehicles.loading && !vehicles.loaded" state="loading" title="Loading vehicles…" />
    <StateCard
      v-else-if="vehicles.error && !vehicles.loaded"
      state="error"
      :message="vehicles.error"
      @retry="vehicles.fetchVehicles().catch(() => {})"
    />

    <StateCard v-else-if="vehicles.vehicles.length === 0" state="empty" title="No vehicles yet">
      Add a vehicle on the <RouterLink to="/vehicles">Vehicles</RouterLink> page, or import your
      Fuelio history on the <RouterLink to="/fuel/import">Fuel Import</RouterLink> page.
    </StateCard>

    <template v-else-if="vehicles.selectedVehicle">
      <!-- Four primary tiles. Every tile names the window it covers. -->
      <section class="hero-grid primary" aria-label="Key figures">
        <div class="card hero fuel-gauge" :style="{ '--gauge-accent': gaugeColor }">
          <h3>Fuel level</h3>
          <div class="fuel-gauge-wrap">
            <svg class="fuel-gauge-svg" viewBox="0 0 200 120" preserveAspectRatio="xMidYMax meet" aria-hidden="true">
              <path class="gauge-track" :d="gaugeArcPath(100)" fill="none" stroke-width="14" stroke-linecap="round" />
              <path
                v-if="heroData.fuelLevelPct != null && heroData.fuelLevelPct > 0"
                :d="gaugeArcPath(heroData.fuelLevelPct)"
                fill="none"
                :stroke="gaugeColor"
                stroke-width="14"
                stroke-linecap="round"
              />
              <path class="gauge-tick" :d="gaugeTickPath()" fill="none" stroke-width="1.5" stroke-linecap="round" />
            </svg>
            <span class="fuel-gauge-end e" aria-hidden="true">E</span>
            <span class="fuel-gauge-end f" aria-hidden="true">F</span>
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
            <template v-if="heroData.fuelGallons != null">
              {{ nf(1).format(heroData.fuelGallons) }} {{ volUnitLabel() }}
            </template>
            <template v-if="heroData.fuelGallons != null && heroData.fuelLevelAge"> · </template>
            <template v-if="heroData.fuelLevelAge">now · {{ heroData.fuelLevelAge }}</template>
            <template v-if="heroData.fuelGallons == null && !heroData.fuelLevelAge">no reading yet</template>
          </div>
        </div>

        <div class="card hero">
          <h3>Avg economy</h3>
          <div class="hero-value">
            <span class="big">{{ econ(heroData.mpg90) }}</span>
            <span class="unit">{{ economyUnitLabel() }}</span>
          </div>
          <div class="hero-sub muted">last 90 days · fillup-based</div>
          <div v-if="econSpark" class="hero-trend">
            <Sparkline
              :values="econSpark.values"
              kind="area"
              color="var(--chart-1)"
              :label="`Monthly economy, last 12 months, ${econ(econSpark.min)} to ${econ(econSpark.max)}`"
            />
            <span class="trend-cap">12 mo · {{ nf(1).format(econSpark.min) }}–{{ nf(1).format(econSpark.max) }}</span>
          </div>
        </div>

        <div class="card hero">
          <h3>Gas price</h3>
          <div class="hero-value">
            <span class="big">{{ ppv(heroData.latestPpg) }}</span>
            <span class="unit">/{{ volUnitLabel() }}</span>
          </div>
          <div class="hero-sub muted" v-if="recentFillups[0]">latest fillup · {{ fmtWhen(recentFillups[0].fillup_date) }}</div>
          <div
            v-if="heroData.ppgDelta != null"
            class="hero-sub"
            :class="{ up: heroData.ppgDelta > 0, down: heroData.ppgDelta < 0 }"
          >
            <span aria-hidden="true">{{ heroData.ppgDelta > 0 ? '▲' : heroData.ppgDelta < 0 ? '▼' : '·' }}</span>
            {{ Math.abs(heroData.ppgDelta).toFixed(1) }}% vs last-30-fillup avg
          </div>
          <div
            v-if="heroData.ppgVsRegion != null"
            class="hero-sub"
            :class="{ up: heroData.ppgVsRegion > 0, down: heroData.ppgVsRegion < 0 }"
          >
            <span aria-hidden="true">{{ heroData.ppgVsRegion > 0 ? '▲' : heroData.ppgVsRegion < 0 ? '▼' : '·' }}</span>
            {{ Math.abs(heroData.ppgVsRegion).toFixed(1) }}% vs {{ heroData.eiaRegionLabel }}
            <span class="muted small"> · {{ fmtPricePerVolume(heroData.eiaLatest, "gal") }} this week</span>
          </div>
          <div v-if="priceSpark" class="hero-trend">
            <Sparkline
              :values="priceSpark.values"
              kind="line"
              color="var(--chart-3)"
              :label="`Price per ${volUnitLabel()} over the last ${priceSpark.n} fillups`"
            />
            <span class="trend-cap">last {{ priceSpark.n }} fillups</span>
          </div>
        </div>

        <div class="card hero">
          <h3>This month</h3>
          <div class="hero-value">
            <span class="big">{{ fmtMoney(heroData.monthCost) }}</span>
          </div>
          <div class="hero-sub muted">
            calendar month · {{ heroData.monthCount }} fillup{{ heroData.monthCount === 1 ? '' : 's' }}
          </div>
          <div v-if="spendBars" class="hero-trend">
            <Sparkline
              :values="spendBars.values"
              kind="bars"
              :label="`Monthly fuel spend, last 12 months`"
            />
            <span v-if="spendBars.avg != null" class="trend-cap">12-mo avg {{ fmtMoney(spendBars.avg, 0) }}</span>
          </div>
        </div>
      </section>

      <!-- Ownership row: everything else, collapsed by default. -->
      <section class="ownership">
        <button
          type="button"
          class="own-toggle ghost"
          :aria-expanded="ownershipOpen"
          aria-controls="ownership-grid"
          @click="toggleOwnership"
        >
          <ChevronDown :size="14" class="chev" :class="{ open: ownershipOpen }" aria-hidden="true" />
          Ownership
          <span class="muted own-summary" v-if="!ownershipOpen">
            <template v-if="cooQ.data.value">{{ fmtMoney(cooQ.data.value.fuel_total, 0) }} fuel lifetime</template>
            <template v-if="odoHero.currentMi != null"> · {{ fmtOdoKm(odoHero.currentMi) }}</template>
          </span>
        </button>
        <div v-show="ownershipOpen" id="ownership-grid" class="hero-grid secondary">
          <div class="card hero">
            <h3>Cost / {{ distUnitLabel() }}</h3>
            <div class="hero-value">
              <span class="big sm">{{ heroData.costPerMile != null ? fmtMoney(heroData.costPerMile / convDistance(1, distSrc), 3) : '—' }}</span>
              <span class="unit">/{{ distUnitLabel() }}</span>
            </div>
            <div class="hero-sub muted">
              fuel only · last {{ heroFillupsQ.data.value?.items?.length ?? 0 }} fillups
              <template v-if="heroData.totalMiles != null">
                · {{ fmtDistance(heroData.totalMiles, distSrc, 0) }}
              </template>
            </div>
          </div>

          <div class="card hero">
            <h3>Best month</h3>
            <div class="hero-value">
              <span class="big sm">{{ econ(heroData.bestMpg) }}</span>
              <span class="unit">{{ economyUnitLabel() }}</span>
            </div>
            <div class="hero-sub muted">
              monthly avg · last 90 days<template v-if="heroData.worstMpg != null"> · worst {{ fmtMpg(heroData.worstMpg) }}</template>
            </div>
          </div>

          <div class="card hero">
            <h3>Fuel bought</h3>
            <div class="hero-value">
              <span class="big sm">{{ heroData.totalGallons ? nf(0).format(convVolume(heroData.totalGallons, volSrc)) : '—' }}</span>
              <span class="unit">{{ volUnitLabel() }}</span>
            </div>
            <div class="hero-sub muted">last {{ heroFillupsQ.data.value?.items?.length ?? 0 }} fillups · {{ fmtMoney(heroData.totalCost, 0) }}</div>
          </div>

          <div v-if="mpgTrendHero.latest != null" class="card hero">
            <h3>Economy trend</h3>
            <div class="hero-value">
              <span class="big sm">{{ econ(mpgTrendHero.latest) }}</span>
              <span class="unit">{{ economyUnitLabel() }}</span>
            </div>
            <div
              v-if="mpgTrendHero.deltaPct != null"
              class="hero-sub"
              :class="{ up: mpgTrendHero.deltaPct < 0, down: mpgTrendHero.deltaPct > 0 }"
            >
              <span aria-hidden="true">{{ mpgTrendHero.deltaPct > 0 ? '▲' : mpgTrendHero.deltaPct < 0 ? '▼' : '·' }}</span>
              {{ Math.abs(mpgTrendHero.deltaPct).toFixed(1) }}% this year vs last
            </div>
            <div v-else class="hero-sub muted">this calendar year · yearly avg</div>
            <svg v-if="mpgTrendHero.spark" class="hero-spark" viewBox="0 0 100 30" preserveAspectRatio="none" aria-hidden="true">
              <path :d="mpgTrendHero.spark" stroke="var(--chart-1)" stroke-width="1.4" fill="none" />
            </svg>
          </div>

          <div v-if="cooQ.data.value && cooQ.data.value.fuel_total > 0" class="card hero">
            <h3>Fuel cost</h3>
            <div class="hero-value">
              <span class="big sm">{{ fmtMoney(cooQ.data.value.fuel_total, 0) }}</span>
            </div>
            <div class="hero-sub muted">
              lifetime<template v-if="lifetimePpv != null"> · {{ ppv(lifetimePpv) }}/{{ volUnitLabel() }} avg</template>
            </div>
          </div>

          <div v-if="odoHero.currentMi != null" class="card hero">
            <h3>Odometer</h3>
            <div class="hero-value">
              <span class="big sm">{{ fmtInt(convDistance(odoHero.currentMi, 'km')) }}</span>
              <span class="unit">{{ distUnitLabel() }}</span>
            </div>
            <div v-if="odoHero.deltaMi != null" class="hero-sub muted">
              lifetime · +{{ fmtOdoKm(odoHero.deltaMi) }} tracked
              <template v-if="odoHero.milesPerDay != null"> · {{ fmtDistance(odoHero.milesPerDay, 'km', 1) }}/day</template>
            </div>
            <svg v-if="odoHero.spark" class="hero-spark" viewBox="0 0 100 30" preserveAspectRatio="none" aria-hidden="true">
              <path :d="odoHero.spark" stroke="var(--chart-2)" stroke-width="1.4" fill="none" />
            </svg>
          </div>

          <div v-if="hoursQ.data.value && hoursQ.data.value.total_hours > 0" class="card hero">
            <h3>Engine hours</h3>
            <div class="hero-value">
              <span class="big sm">{{ fmtInt(hoursQ.data.value.total_hours) }}</span>
              <span class="unit">hrs</span>
            </div>
            <div class="hero-sub muted">
              lifetime (tracked)
              <template v-if="hoursQ.data.value.hrs_per_100mi != null">
                · {{ hoursQ.data.value.hrs_per_100mi.toFixed(2) }} hrs/100 mi
              </template>
            </div>
          </div>

          <div v-if="cooQ.data.value" class="card hero coo">
            <h3>Lifetime cost / mi</h3>
            <div class="hero-value">
              <span class="big sm">{{ cooQ.data.value.cost_per_mi != null ? fmtMoney(cooQ.data.value.cost_per_mi, 3) : '—' }}</span>
            </div>
            <div class="hero-sub muted">
              <template v-if="cooQ.data.value.purchase_price != null">
                lifetime · {{ fmtMoney(cooQ.data.value.total, 0) }} total
                <template v-if="cooQ.data.value.lifetime_mi != null">
                  · {{ fmtDistance(cooQ.data.value.lifetime_mi, 'mi', 0) }}
                </template>
              </template>
              <RouterLink
                v-else
                :to="{ path: '/vehicles', query: { edit: vehicles.selectedVehicle.id } }"
                class="link"
              >Add purchase price →</RouterLink>
            </div>
            <div v-if="cooQ.data.value.total > 0" class="coo-bar" aria-hidden="true">
              <span
                class="coo-seg coo-purchase"
                :style="{ width: (((cooQ.data.value.purchase_price ?? 0) / cooQ.data.value.total) * 100).toFixed(1) + '%' }"
                :title="`Purchase ${fmtMoney(cooQ.data.value.purchase_price ?? 0, 0)}`"
              />
              <span
                class="coo-seg coo-fuel"
                :style="{ width: ((cooQ.data.value.fuel_total / cooQ.data.value.total) * 100).toFixed(1) + '%' }"
                :title="`Fuel ${fmtMoney(cooQ.data.value.fuel_total, 0)}`"
              />
              <span
                class="coo-seg coo-maint"
                :style="{ width: ((cooQ.data.value.maintenance_total / cooQ.data.value.total) * 100).toFixed(1) + '%' }"
                :title="`Maintenance ${fmtMoney(cooQ.data.value.maintenance_total, 0)}`"
              />
            </div>
          </div>
        </div>
      </section>

      <div class="row-grid">
        <section class="card">
          <h3>
            <Route :size="14" aria-hidden="true" /> Recent trips
            <RouterLink to="/trips" class="more">all →</RouterLink>
          </h3>
          <StateCard v-if="tripsQ.loading.value && !tripsQ.data.value" state="loading" bare />
          <StateCard v-else-if="tripsQ.error.value" state="error" bare :message="tripsQ.error.value" @retry="tripsQ.reload()" />
          <StateCard v-else-if="!tripsQ.data.value || tripsQ.data.value.items.length === 0" state="empty" bare title="No trips yet." />
          <ul v-else class="recent">
            <li v-for="t in tripsQ.data.value.items" :key="t.id">
              <RouterLink :to="`/trips/${t.id}`">
                <span>{{ fmtWhen(t.started_at, { withTime: true }) }}</span>
                <span class="muted">{{ fmtDistanceKm(t.distance_km ?? null) }}</span>
              </RouterLink>
            </li>
          </ul>
        </section>

        <section class="card">
          <h3>
            <Fuel :size="14" aria-hidden="true" /> Recent fillups
            <RouterLink to="/fuel" class="more">all →</RouterLink>
          </h3>
          <StateCard v-if="heroFillupsQ.loading.value && !heroFillupsQ.data.value" state="loading" bare />
          <StateCard v-else-if="heroFillupsQ.error.value" state="error" bare :message="heroFillupsQ.error.value" @retry="heroFillupsQ.reload()" />
          <StateCard v-else-if="recentFillups.length === 0" state="empty" bare title="No fillups recorded." />
          <ul v-else class="recent">
            <li v-for="f in recentFillups" :key="f.id">
              <button type="button" class="row-btn" :aria-label="`Edit fillup from ${fmtDate(f.fillup_date)}`" @click="editing = f">
                <span>{{ fmtWhen(f.fillup_date) }}</span>
                <span class="muted">
                  {{ fmtVolume(f.fuel_volume, volSrc, 1) }} · {{ fmtMpg(f.mpg) }} · {{ fmtMoney(f.price_total) }}
                </span>
              </button>
            </li>
          </ul>
        </section>

        <section class="card">
          <h3>
            <AlertTriangle :size="14" aria-hidden="true" /> Active DTCs
            <RouterLink to="/dtcs" class="more">all →</RouterLink>
          </h3>
          <StateCard v-if="dtcsQ.loading.value && !dtcsQ.data.value" state="loading" bare />
          <StateCard v-else-if="dtcsQ.error.value" state="error" bare :message="dtcsQ.error.value" @retry="dtcsQ.reload()" />
          <StateCard v-else-if="!dtcsQ.data.value || dtcsQ.data.value.length === 0" state="empty" bare title="No active codes." />
          <ul v-else class="recent">
            <li v-for="d in dtcsQ.data.value" :key="d.id">
              <span><code>{{ d.code }}</code> {{ d.description ?? "" }}</span>
            </li>
          </ul>
        </section>
      </div>

      <FillupModal
        v-if="editing"
        :vehicle="vehicles.selectedVehicle"
        :initial="editing"
        :station-suggestions="[]"
        :all-vehicles="vehicles.vehicles"
        @close="editing = null"
        @saved="onFillupSaved"
      />
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
.hero-grid.primary {
  grid-template-columns: repeat(4, minmax(0, 1fr));
}
@media (max-width: 1000px) {
  .hero-grid.primary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
.hero-grid.secondary {
  margin-top: 0.6rem;
}
.hero-value .big.sm {
  font-size: 1.5rem;
}
.ownership {
  display: flex;
  flex-direction: column;
}
.own-toggle {
  align-self: flex-start;
  display: inline-flex;
  align-items: center;
  gap: 0.4rem;
  padding: 0.35rem 0.5rem;
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: var(--c-ink2);
}
.own-summary {
  text-transform: none;
  letter-spacing: 0;
  font-size: 0.8rem;
  font-family: 'Geist Mono', ui-monospace, monospace;
}
.chev {
  transition: transform 120ms;
  transform: rotate(-90deg);
}
.chev.open {
  transform: none;
}
.row-btn {
  display: flex;
  justify-content: space-between;
  width: 100%;
  background: transparent;
  border: 0;
  padding: 0;
  border-radius: 0;
  font-weight: 400;
  color: var(--c-ink1);
  text-align: left;
  gap: 0.5rem;
}
.row-btn:hover:not(:disabled) {
  background: transparent;
  color: var(--c-ink0);
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
.hero-trend {
  margin-top: auto;
  padding-top: 0.5rem;
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}
.trend-cap {
  font-family: 'Geist Mono', ui-monospace, monospace;
  font-size: 0.72rem;
  color: var(--c-ink3);
  font-variant-numeric: tabular-nums;
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
  color: var(--c-warn);
  background: var(--c-warn-soft);
  border: 1px solid rgba(255, 176, 32, 0.4);
  cursor: help;
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
  color: var(--c-ink1);
}
.recent a:hover {
  color: var(--c-ink0);
}
.card h3 {
  display: flex;
  align-items: center;
  gap: 0.4rem;
}
.more {
  margin-left: auto;
  color: var(--c-ink2);
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
  border-color: rgba(255, 176, 32, 0.4);
  background: linear-gradient(0deg, var(--c-warn-soft), transparent);
}
.anomaly-card.tone-danger {
  border-color: rgba(255, 58, 46, 0.4);
  background: linear-gradient(0deg, var(--c-danger-soft), transparent);
}
.anomaly-icon {
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background: var(--c-bg3);
  color: var(--c-warn);
}
.anomaly-card.tone-danger .anomaly-icon {
  color: var(--c-danger);
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
  background: var(--c-bg3);
}
.coo-seg {
  display: inline-block;
  height: 100%;
}
.coo-purchase { background: var(--chart-4); }
.coo-fuel    { background: var(--chart-1); }
.coo-maint   { background: var(--chart-3); }
</style>
