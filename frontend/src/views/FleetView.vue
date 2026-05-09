<script setup lang="ts">
/**
 * Fleet view — the "all vehicles at once" page (#47).
 *
 * Per-vehicle health-score tile combining the four signals an owner
 * actually checks between drives:
 *
 *   1. Last-seen freshness  — how long since the bridge published
 *   2. MPG vs all-time avg  — am I getting worse mileage?
 *   3. Active DTC count     — anything broken right now?
 *   4. Service due count    — any maintenance overdue?
 *
 * Each contributes a sub-score (0..100); the tile shows the worst
 * three so the user reads "this vehicle needs attention" at a glance
 * without having to drill in. A tap navigates to that vehicle's
 * Overview.
 *
 * Data sources, all existing endpoints:
 *   /vehicles                  — list with last_seen_at + latest
 *   /fillups (limit 30)        — for rolling MPG vs all-time
 *   /maintenance/reminders     — for service-due count (post v0.1.31
 *                                phantom-filter)
 *   /dtcs?active=true          — for active DTC count
 *   /analytics/mpg?window=year — for the per-vehicle sparkline
 */

import { computed, onMounted, ref } from "vue";
import { RouterLink } from "vue-router";
import { useAuthStore } from "@/stores/auth";
import { useVehiclesStore } from "@/stores/vehicles";
import * as api from "@/api/endpoints";
import type { Vehicle, Fillup, Reminder, Dtc, MpgPoint } from "@/api/types";
import { fmtRelative, fmtMpg } from "@/composables/useFormat";
import Pill from "@/components/Pill.vue";

const auth = useAuthStore();
const vehicles = useVehiclesStore();

interface FleetRow {
  vehicle: Vehicle;
  rollingMpg: number | null;
  allTimeMpg: number | null;
  reminderCount: number;
  dtcCount: number;
  mpgSeries: number[];
  loaded: boolean;
}

const rows = ref<FleetRow[]>([]);
const loading = ref(false);
const error = ref<string | null>(null);

async function loadFleet() {
  if (!auth.hasQueryToken) return;
  loading.value = true;
  error.value = null;
  try {
    await vehicles.ensureLoaded();
    rows.value = vehicles.vehicles.map((v): FleetRow => ({
      vehicle: v,
      rollingMpg: null,
      allTimeMpg: null,
      reminderCount: 0,
      dtcCount: 0,
      mpgSeries: [],
      loaded: false,
    }));

    // Fan out per-vehicle in parallel — each tile is independent.
    await Promise.all(
      rows.value.map(async (row) => {
        try {
          const [fillups, reminders, dtcs, mpg]: [
            { items: Fillup[]; total: number },
            { overdue: Reminder[]; upcoming: Reminder[] },
            Dtc[],
            { points: MpgPoint[] },
          ] = await Promise.all([
            api.listFillups({ vehicle_id: row.vehicle.id, limit: 30 }),
            api.listReminders(row.vehicle.id),
            api.listDtcs(row.vehicle.id, true),
            api.mpgTrend(row.vehicle.id, "year"),
          ]);
          const mpgs = fillups.items
            .map((f) => f.mpg)
            .filter((m): m is number => typeof m === "number" && m > 0);
          const recent = mpgs.slice(0, 6);
          const rolling = recent.length
            ? recent.reduce((a, b) => a + b, 0) / recent.length
            : null;
          const allTime = mpgs.length
            ? mpgs.reduce((a, b) => a + b, 0) / mpgs.length
            : null;
          row.rollingMpg = rolling;
          row.allTimeMpg = allTime;
          row.reminderCount = reminders.overdue.length;
          row.dtcCount = dtcs.length;
          row.mpgSeries = mpg.points
            .map((p) => p.mpg ?? 0)
            .filter((v) => v > 0)
            .slice(-24);
          row.loaded = true;
        } catch {
          // Per-tile failure is silent — the tile renders with whatever
          // data did load. The fleet header surfaces the global error
          // only if the vehicles list itself fails.
        }
      }),
    );
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : "load failed";
  } finally {
    loading.value = false;
  }
}

onMounted(loadFleet);

interface HealthBreakdown {
  score: number;
  signals: { label: string; status: "ok" | "warn" | "bad"; detail: string }[];
}

function freshnessBucket(lastSeenAt: string | null | undefined): {
  status: "ok" | "warn" | "bad";
  detail: string;
} {
  if (!lastSeenAt) return { status: "bad", detail: "never seen" };
  const ageMs = Date.now() - new Date(lastSeenAt).getTime();
  const hours = ageMs / 3_600_000;
  if (hours < 24) return { status: "ok", detail: fmtRelative(lastSeenAt) };
  if (hours < 24 * 7) return { status: "warn", detail: fmtRelative(lastSeenAt) };
  return { status: "bad", detail: fmtRelative(lastSeenAt) };
}

function mpgBucket(rolling: number | null, allTime: number | null): {
  status: "ok" | "warn" | "bad";
  detail: string;
} {
  if (rolling == null || allTime == null) return { status: "warn", detail: "no data" };
  const delta = (rolling - allTime) / allTime;
  if (delta < -0.10) return { status: "bad", detail: `${(delta * 100).toFixed(0)}% vs avg` };
  if (delta < -0.04) return { status: "warn", detail: `${(delta * 100).toFixed(0)}% vs avg` };
  return { status: "ok", detail: `${delta >= 0 ? "+" : ""}${(delta * 100).toFixed(0)}% vs avg` };
}

function countBucket(n: number, label: string, warnAt = 1, badAt = 3): {
  status: "ok" | "warn" | "bad";
  detail: string;
} {
  if (n === 0) return { status: "ok", detail: `none ${label}` };
  if (n >= badAt) return { status: "bad", detail: `${n} ${label}` };
  if (n >= warnAt) return { status: "warn", detail: `${n} ${label}` };
  return { status: "ok", detail: `${n} ${label}` };
}

function healthFor(row: FleetRow): HealthBreakdown {
  const fresh = freshnessBucket(row.vehicle.last_seen_at);
  const mpg = mpgBucket(row.rollingMpg, row.allTimeMpg);
  const services = countBucket(row.reminderCount, "due", 1, 3);
  const dtcs = countBucket(row.dtcCount, "DTC", 1, 1);

  const signals = [
    { label: "Last seen", ...fresh },
    { label: "MPG", ...mpg },
    { label: "Service", ...services },
    { label: "Codes", ...dtcs },
  ];

  // Score: 25 per ok, 12.5 per warn, 0 per bad. Max 100.
  const score = signals.reduce((s, sig) => {
    return s + (sig.status === "ok" ? 25 : sig.status === "warn" ? 12.5 : 0);
  }, 0);

  return { score: Math.round(score), signals };
}

function pillStateFor(score: number) {
  if (score >= 90) return "healthy";
  if (score >= 60) return "degraded";
  return "offline";
}

function pillStateForSignal(status: "ok" | "warn" | "bad") {
  return status === "ok" ? "healthy" : status === "warn" ? "degraded" : "offline";
}

function sparklinePath(series: number[], width = 80, height = 22): string {
  if (series.length < 2) return "";
  const min = Math.min(...series);
  const max = Math.max(...series);
  const range = max - min || 1;
  const stepX = width / (series.length - 1);
  return series
    .map((v, i) => {
      const x = i * stepX;
      const y = height - ((v - min) / range) * height;
      return `${i === 0 ? "M" : "L"}${x.toFixed(1)},${y.toFixed(1)}`;
    })
    .join(" ");
}

const overall = computed(() => {
  if (rows.value.length === 0) return null;
  const scores = rows.value.map((r) => healthFor(r).score);
  const avg = scores.reduce((a, b) => a + b, 0) / scores.length;
  const worst = Math.min(...scores);
  return { avg: Math.round(avg), worst };
});
</script>

<template>
  <div class="fleet">
    <header class="head">
      <h1>Fleet</h1>
      <p v-if="vehicles.loaded" class="muted">
        {{ vehicles.vehicles.length }} vehicle{{ vehicles.vehicles.length === 1 ? '' : 's' }}
        <template v-if="overall != null">
          · avg health
          <strong>{{ overall.avg }}</strong>
          · worst
          <strong>{{ overall.worst }}</strong>
        </template>
      </p>
    </header>

    <div v-if="!auth.hasQueryToken" class="card">
      <p class="muted">
        Set up your QUERY token in
        <RouterLink to="/settings">Settings</RouterLink>
        to load fleet data.
      </p>
    </div>

    <div v-else-if="loading && rows.length === 0" class="card">
      <p class="muted">Loading fleet…</p>
    </div>

    <div v-else-if="error" class="card">
      <p class="muted">Failed to load: {{ error }}</p>
    </div>

    <div v-else-if="rows.length === 0" class="card">
      <p class="muted">No vehicles yet — add one on the Vehicles page.</p>
    </div>

    <div v-else class="grid">
      <article
        v-for="row in rows"
        :key="row.vehicle.id"
        class="card tile"
        :class="`score-${Math.floor(healthFor(row).score / 30)}`"
      >
        <header class="tile-head">
          <RouterLink
            :to="{ name: 'overview', query: { vehicle: row.vehicle.id } }"
            class="title"
          >
            <span class="name">{{ row.vehicle.name }}</span>
            <span class="meta muted">
              {{ row.vehicle.year ?? '' }} {{ row.vehicle.make ?? '' }}
              {{ row.vehicle.model ?? row.vehicle.slug }}
            </span>
          </RouterLink>
          <Pill
            :state="pillStateFor(healthFor(row).score)"
            :label="`${healthFor(row).score}`"
            mono
          />
        </header>

        <div class="tile-body">
          <div class="signals">
            <div
              v-for="sig in healthFor(row).signals"
              :key="sig.label"
              class="signal"
            >
              <span class="signal-dot" :class="`s-${sig.status}`" />
              <span class="signal-label">{{ sig.label }}</span>
              <span class="signal-detail">{{ sig.detail }}</span>
            </div>
          </div>

          <div class="spark-wrap">
            <span class="t-label">Year MPG</span>
            <svg
              v-if="row.mpgSeries.length >= 2"
              :viewBox="`0 0 80 22`"
              width="80"
              height="22"
              class="spark"
            >
              <path
                :d="sparklinePath(row.mpgSeries)"
                fill="none"
                stroke="var(--c-accent)"
                stroke-width="1.5"
                stroke-linejoin="round"
                stroke-linecap="round"
              />
            </svg>
            <span v-else class="muted small">no data</span>
            <span v-if="row.rollingMpg" class="spark-value tabular">
              {{ fmtMpg(row.rollingMpg) }}
            </span>
          </div>
        </div>
      </article>
    </div>
  </div>
</template>

<style scoped>
.fleet {
  display: flex;
  flex-direction: column;
  gap: 1.2rem;
}
.head h1 {
  margin-bottom: 0.2rem;
}
.head .muted strong {
  font-family: 'Geist Mono', ui-monospace, monospace;
  font-variant-numeric: tabular-nums;
  color: var(--c-ink0);
  font-weight: 500;
}
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 0.85rem;
}
.tile {
  display: flex;
  flex-direction: column;
  gap: 0.85rem;
  padding: 1rem;
  position: relative;
  overflow: hidden;
}
/* Health-score-driven left edge stripe — coral when bad, amber warn,
   no stripe when ok. Mirrors the design's redline semantic so a glance
   at the grid spots the trouble vehicle. */
.tile::before {
  content: "";
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 2px;
}
.tile.score-3::before {
  /* 90+, healthy — no stripe */
}
.tile.score-2::before {
  background: var(--c-warn);
}
.tile.score-1::before,
.tile.score-0::before {
  background: var(--c-danger);
}
.tile-head {
  display: flex;
  align-items: flex-start;
  gap: 0.5rem;
}
.title {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 0.2rem;
  text-decoration: none;
}
.title:hover .name {
  text-decoration: underline;
}
.title .name {
  font-size: 1.05rem;
  font-weight: 600;
  color: var(--c-ink0);
  letter-spacing: -0.012em;
}
.title .meta {
  font-size: 0.78rem;
  font-family: 'Geist Mono', ui-monospace, monospace;
}
.tile-body {
  display: flex;
  flex-direction: column;
  gap: 0.6rem;
}
.signals {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  row-gap: 0.45rem;
  column-gap: 0.6rem;
}
.signal {
  display: grid;
  grid-template-columns: 8px auto 1fr;
  align-items: baseline;
  gap: 0.4rem;
}
.signal-dot {
  width: 8px;
  height: 8px;
  border-radius: 4px;
  align-self: center;
}
.signal-dot.s-ok {
  background: var(--c-success);
}
.signal-dot.s-warn {
  background: var(--c-warn);
}
.signal-dot.s-bad {
  background: var(--c-danger);
}
.signal-label {
  font-size: 0.78rem;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  color: var(--c-ink3);
  font-weight: 500;
}
.signal-detail {
  font-size: 0.82rem;
  font-family: 'Geist Mono', ui-monospace, monospace;
  font-variant-numeric: tabular-nums;
  color: var(--c-ink1);
  text-align: right;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.spark-wrap {
  display: grid;
  grid-template-columns: auto 1fr auto;
  align-items: center;
  gap: 0.6rem;
  border-top: 1px solid var(--c-line0);
  padding-top: 0.6rem;
}
.spark-wrap .t-label {
  font-size: 10px;
}
.spark {
  display: block;
  height: 22px;
  width: 100%;
}
.spark-value {
  font-size: 0.85rem;
  color: var(--c-ink0);
}
.small {
  font-size: 0.78rem;
}
</style>
