<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useRouter } from "vue-router";
import { useVehiclesStore } from "@/stores/vehicles";
import { useAsync } from "@/composables/useAsync";
import * as api from "@/api/endpoints";
import type { Trip } from "@/api/types";
import {
  fmtDateTime,
  fmtDuration,
  fmtDistanceKm,
  fmtSpeedKph,
  fmtRpm,
  fmtVolumeL,
} from "@/composables/useFormat";

const vehicles = useVehiclesStore();
const router = useRouter();
// Date-range presets (TRIPS, C16). Chips compute LOCAL boundaries so the
// server window matches what the user means by a calendar day; "custom"
// reveals the two date inputs. This also sidesteps the date-only-as-UTC bug:
// `new Date("2026-06-13")` parses as UTC midnight, which in US TZs lops ~6h
// off the "To" day. We build local 00:00 boundaries explicitly instead.
type DatePreset = "7d" | "30d" | "90d" | "all" | "custom";
const datePreset = ref<DatePreset>("all");
const fromDate = ref<string>("");
const toDate = ref<string>("");
const limit = ref(50);
const offset = ref(0);

/** Local 00:00 of a "yyyy-MM-dd" string, as an ISO UTC instant. */
function localDayStartIso(ymd: string): string | undefined {
  if (!ymd) return undefined;
  const [y, m, d] = ymd.split("-").map(Number);
  if (!y || !m || !d) return undefined;
  return new Date(y, m - 1, d, 0, 0, 0, 0).toISOString();
}
/** Exclusive end: local 00:00 of (ymd + 1 day), as an ISO UTC instant, so the
 *  whole "To" calendar day is included. */
function localDayEndExclusiveIso(ymd: string): string | undefined {
  if (!ymd) return undefined;
  const [y, m, d] = ymd.split("-").map(Number);
  if (!y || !m || !d) return undefined;
  return new Date(y, m - 1, d + 1, 0, 0, 0, 0).toISOString();
}

// Resolved from/to instants the query actually uses. Presets win; for
// "custom" we read the date inputs (with local-boundary construction).
const fromIso = computed<string | undefined>(() => {
  if (datePreset.value === "all") return undefined;
  if (datePreset.value === "custom") return localDayStartIso(fromDate.value);
  const days = { "7d": 7, "30d": 30, "90d": 90 }[datePreset.value];
  return new Date(Date.now() - days * 86_400_000).toISOString();
});
const toIso = computed<string | undefined>(() =>
  datePreset.value === "custom" ? localDayEndExclusiveIso(toDate.value) : undefined,
);

const vehicleId = computed(() => vehicles.selectedVehicleId);

// ── Selection mode (mirror phone) ───────────────────────────────────
// Tap a row in selection mode → toggle in the set. The action bar
// shows count + Merge (any ≥ 2) + Delete (any ≥ 1) + Cancel.
// "Select" button in the toolbar enters the mode; entering selection
// suppresses the open-detail behavior on row click.
const selectMode = ref(false);
const selectedIds = ref<Set<string>>(new Set());
type ActionState =
  | { kind: "idle" }
  | { kind: "in_progress"; verb: "merge" | "delete" }
  | { kind: "done"; verb: "merge" | "delete"; message: string }
  | { kind: "failed"; verb: "merge" | "delete"; message: string };
const action = ref<ActionState>({ kind: "idle" });
const confirmDelete = ref(false);

function enterSelectMode() {
  selectMode.value = true;
}
function exitSelectMode() {
  selectMode.value = false;
  selectedIds.value = new Set();
  action.value = { kind: "idle" };
}
function toggleSelected(id: string) {
  const next = new Set(selectedIds.value);
  if (next.has(id)) next.delete(id);
  else next.add(id);
  selectedIds.value = next;
}

// Client-side sort + source filter (TRIPS-2). Apply over the
// currently-loaded page; for narrow date windows the server-side
// from/to handles bigger ranges efficiently.
type SortOrder = "recent" | "distance" | "speed" | "duration";
type SrcFilter = "all" | "phone_batch" | "manual_merge" | "other";
const sort = ref<SortOrder>("recent");
const srcFilter = ref<SrcFilter>("all");
// Towing is a load condition, so it filters independently of source.
const towingOnly = ref(false);

// Relative-date bucket for group headers.
type GroupKey =
  | "today" | "yesterday" | "past7" | "past30" | "thisYear" | "older";
const GROUP_LABEL: Record<GroupKey, string> = {
  today: "Today",
  yesterday: "Yesterday",
  past7: "Past 7 days",
  past30: "Past 30 days",
  thisYear: "This year",
  older: "Older",
};
function bucketFor(iso: string): GroupKey {
  const t = new Date(iso);
  if (Number.isNaN(t.getTime())) return "older";
  const now = new Date();
  const msPerDay = 24 * 3600 * 1000;
  const dayStart = (d: Date) => {
    const x = new Date(d);
    x.setHours(0, 0, 0, 0);
    return x.getTime();
  };
  const daysAgo = Math.floor((dayStart(now) - dayStart(t)) / msPerDay);
  if (daysAgo <= 0) return "today";
  if (daysAgo === 1) return "yesterday";
  if (daysAgo <= 7) return "past7";
  if (daysAgo <= 30) return "past30";
  if (t.getFullYear() === now.getFullYear()) return "thisYear";
  return "older";
}

const { data, loading, error, reload } = useAsync(
  () =>
    vehicleId.value
      ? api.listTrips({
          vehicle_id: vehicleId.value,
          from: fromIso.value,
          to: toIso.value,
          limit: limit.value,
          offset: offset.value,
        })
      : Promise.resolve({ items: [], total: 0 }),
  [vehicleId, fromIso, toIso, limit, offset],
);

watch([vehicleId, fromIso, toIso], () => {
  offset.value = 0;
});

function open(tripId: string) {
  if (selectMode.value) {
    toggleSelected(tripId);
    return;
  }
  router.push(`/trips/${tripId}`);
}

// ── Merge ───────────────────────────────────────────────────────────
// Backend picks the earliest trip as the kept row regardless of which
// id we pass as the path parameter, so we pass the first one as the
// path trip and the rest as the fold-in list (MERGE-N).
async function doMerge() {
  if (selectedIds.value.size < 2) return;
  if (action.value.kind === "in_progress") return;
  const ids = Array.from(selectedIds.value);
  action.value = { kind: "in_progress", verb: "merge" };
  try {
    await api.mergeTrips(ids[0], ids.slice(1));
    action.value = { kind: "done", verb: "merge", message: "Trips merged" };
    selectMode.value = false;
    selectedIds.value = new Set();
    await reload();
    setTimeout(() => {
      if (action.value.kind === "done" && action.value.verb === "merge") {
        action.value = { kind: "idle" };
      }
    }, 3000);
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    action.value = { kind: "failed", verb: "merge", message: msg };
    setTimeout(() => {
      if (action.value.kind === "failed" && action.value.verb === "merge") {
        action.value = { kind: "idle" };
      }
    }, 4000);
  }
}

// ── Delete ──────────────────────────────────────────────────────────
function requestDelete() {
  if (selectedIds.value.size === 0) return;
  confirmDelete.value = true;
}
function cancelDelete() {
  confirmDelete.value = false;
}
async function doDelete() {
  confirmDelete.value = false;
  if (selectedIds.value.size === 0) return;
  if (action.value.kind === "in_progress") return;
  const ids = Array.from(selectedIds.value);
  action.value = { kind: "in_progress", verb: "delete" };
  try {
    // Serial deletes — the count is small (typically 1–3) and serial
    // gives a deterministic error message if one fails. The successful
    // ones are already gone server-side; reload reconciles.
    for (const id of ids) {
      await api.deleteTrip(id);
    }
    action.value = {
      kind: "done",
      verb: "delete",
      message: `Deleted ${ids.length} trip${ids.length === 1 ? "" : "s"}`,
    };
    selectMode.value = false;
    selectedIds.value = new Set();
    await reload();
    setTimeout(() => {
      if (action.value.kind === "done" && action.value.verb === "delete") {
        action.value = { kind: "idle" };
      }
    }, 3000);
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    action.value = { kind: "failed", verb: "delete", message: msg };
    // Partial failure is possible — reload so the list reflects
    // whatever did delete.
    await reload();
    setTimeout(() => {
      if (action.value.kind === "failed" && action.value.verb === "delete") {
        action.value = { kind: "idle" };
      }
    }, 4000);
  }
}

// Pre-compute a label for the confirm dialog so it can name what's
// being deleted without doing template-side lookups.
const deleteTargets = computed<Trip[]>(() => {
  if (!data.value?.items) return [];
  return data.value.items.filter((t) => selectedIds.value.has(t.id));
});

const actionBarLabel = computed(() => {
  switch (action.value.kind) {
    case "in_progress":
      return action.value.verb === "merge" ? "Merging…" : "Deleting…";
    case "done":
      return action.value.message;
    case "failed":
      return `${action.value.verb === "merge" ? "Merge" : "Delete"} failed: ${action.value.message}`;
    default: {
      const n = selectedIds.value.size;
      if (n === 0) return "Tap a trip to select";
      if (n === 1) return "1 selected — pick one more to merge, or Delete";
      return `${n} selected — Merge or Delete`;
    }
  }
});

function nextPage() {
  if (data.value && offset.value + limit.value < data.value.total) {
    offset.value += limit.value;
  }
}
function prevPage() {
  offset.value = Math.max(0, offset.value - limit.value);
}
function reset() {
  datePreset.value = "all";
  fromDate.value = "";
  toDate.value = "";
  offset.value = 0;
  void reload();
}

const DATE_PRESET_LABEL: Record<DatePreset, string> = {
  "7d": "7d",
  "30d": "30d",
  "90d": "90d",
  all: "All",
  custom: "Custom",
};

// Filter + sort + group the current page (TRIPS-2). Always shown
// across whatever from/to range the server delivered.
const groupedTrips = computed<Array<{ key: GroupKey; label: string; items: import("@/api/types").Trip[] }>>(() => {
  const items = data.value?.items ?? [];
  const filtered = items.filter((t) => {
    if (towingOnly.value && !t.is_towing) return false;
    if (srcFilter.value === "all") return true;
    if (srcFilter.value === "phone_batch") return t.source === "phone_batch";
    if (srcFilter.value === "manual_merge") return t.source === "manual_merge";
    return t.source !== "phone_batch" && t.source !== "manual_merge";
  });
  const cmp = (a: import("@/api/types").Trip, b: import("@/api/types").Trip): number => {
    switch (sort.value) {
      case "distance": return (b.distance_km ?? 0) - (a.distance_km ?? 0);
      case "speed": return (b.max_speed_kph ?? 0) - (a.max_speed_kph ?? 0);
      case "duration": return (b.duration_s ?? 0) - (a.duration_s ?? 0);
      default:
        return new Date(b.started_at).getTime() - new Date(a.started_at).getTime();
    }
  };
  const byKey = new Map<GroupKey, import("@/api/types").Trip[]>();
  for (const t of filtered) {
    const k = bucketFor(t.started_at);
    let list = byKey.get(k);
    if (!list) {
      list = [];
      byKey.set(k, list);
    }
    list.push(t);
  }
  const order: GroupKey[] = ["today", "yesterday", "past7", "past30", "thisYear", "older"];
  return order
    .map((k) => ({ key: k, label: GROUP_LABEL[k], items: (byKey.get(k) ?? []).sort(cmp) }))
    .filter((g) => g.items.length > 0);
});

// Per-purpose rollup over the currently visible page (Task #94).
// Untagged trips collapse into a single "—" bucket so the user can
// see how much of their drive history is uncategorised at a glance.
interface PurposeRow { label: string; count: number; km: number; fuel_l: number }
const purposeRollup = computed<PurposeRow[]>(() => {
  if (!data.value?.items) return [];
  const m = new Map<string, PurposeRow>();
  for (const t of data.value.items) {
    const label = (t.category && t.category.trim()) || "—";
    const cur = m.get(label) ?? { label, count: 0, km: 0, fuel_l: 0 };
    cur.count += 1;
    cur.km += t.distance_km ?? 0;
    cur.fuel_l += t.fuel_used_l ?? 0;
    m.set(label, cur);
  }
  return Array.from(m.values()).sort((a, b) => b.km - a.km);
});
</script>

<template>
  <div class="trips">
    <header class="head">
      <h1>Trips</h1>
      <div class="filters">
        <div class="chip-row" role="tablist" aria-label="Date range">
          <button
            v-for="opt in (['7d','30d','90d','all','custom'] as const)"
            :key="opt"
            type="button"
            class="chip"
            :class="{ active: datePreset === opt }"
            @click="datePreset = opt"
          >
            {{ DATE_PRESET_LABEL[opt] }}
          </button>
        </div>
        <template v-if="datePreset === 'custom'">
          <label>
            <span class="lbl">From</span>
            <input type="date" v-model="fromDate" />
          </label>
          <label>
            <span class="lbl">To</span>
            <input type="date" v-model="toDate" />
          </label>
        </template>
        <div class="chip-row" role="tablist" aria-label="Source filter">
          <button
            v-for="opt in (['all','phone_batch','manual_merge','other'] as const)"
            :key="opt"
            type="button"
            class="chip"
            :class="{ active: srcFilter === opt }"
            @click="srcFilter = opt"
          >
            {{ opt === 'all' ? 'All' : opt === 'phone_batch' ? 'Phone' : opt === 'manual_merge' ? 'Merged' : 'Other' }}
          </button>
        </div>
        <label class="sort">
          <span class="lbl">Sort</span>
          <select v-model="sort">
            <option value="recent">Most recent</option>
            <option value="distance">Longest distance</option>
            <option value="speed">Fastest top speed</option>
            <option value="duration">Longest duration</option>
          </select>
        </label>
        <button type="button" class="ghost" @click="reset">Reset</button>
        <button
          v-if="!selectMode"
          type="button"
          class="ghost"
          @click="enterSelectMode"
        >Select</button>
      </div>
    </header>

    <div v-if="selectMode" class="action-bar" role="toolbar" aria-label="Trip selection actions">
      <span class="action-label">{{ actionBarLabel }}</span>
      <span v-if="action.kind === 'in_progress'" class="spinner" aria-hidden="true"></span>
      <button
        type="button"
        class="ghost"
        :disabled="action.kind === 'in_progress'"
        @click="exitSelectMode"
      >Cancel</button>
      <button
        type="button"
        class="primary"
        :disabled="selectedIds.size < 2 || action.kind === 'in_progress'"
        @click="doMerge"
      >Merge</button>
      <button
        type="button"
        class="danger"
        :disabled="selectedIds.size === 0 || action.kind === 'in_progress'"
        @click="requestDelete"
      >Delete</button>
    </div>

    <div
      v-if="confirmDelete"
      class="modal-overlay"
      role="dialog"
      aria-modal="true"
      aria-labelledby="delete-confirm-title"
      @click.self="cancelDelete"
    >
      <div class="modal">
        <h3 id="delete-confirm-title">Delete {{ deleteTargets.length }} trip{{ deleteTargets.length === 1 ? '' : 's' }}?</h3>
        <p class="muted">This can't be undone.</p>
        <ul v-if="deleteTargets.length <= 5" class="target-list">
          <li v-for="t in deleteTargets" :key="t.id">
            {{ fmtDateTime(t.started_at) }}
            <span class="muted">·</span>
            {{ fmtDistanceKm(t.distance_km ?? null) }}
          </li>
        </ul>
        <div class="modal-actions">
          <button type="button" class="ghost" @click="cancelDelete">Cancel</button>
          <button type="button" class="danger" @click="doDelete">Delete</button>
        </div>
      </div>
    </div>

    <!-- Thin top progress bar during background revalidation. Replaces the
         old full-card "Loading…" blank so stale rows stay visible while the
         next page/sort/window loads (stale-while-revalidate, B9). -->
    <div
      v-if="loading && data"
      class="revalidate-bar"
      role="progressbar"
      aria-label="Loading"
    ></div>

    <div v-if="!vehicleId" class="card">
      <p class="muted">Select a vehicle to view its trips.</p>
    </div>
    <div v-else-if="loading && !data" class="card">
      <p class="muted">Loading trips…</p>
    </div>
    <div v-else-if="error && !data" class="card">
      <p class="muted">Failed to load: {{ error }}</p>
    </div>
    <div v-else-if="!data || data.items.length === 0" class="card">
      <p class="muted">No trips in this range.</p>
    </div>
    <template v-else>
      <div v-if="purposeRollup.length > 1" class="card purposes">
        <h3>By purpose <span class="muted small">— this page</span></h3>
        <ul class="rollup">
          <li v-for="r in purposeRollup" :key="r.label">
            <span class="tag" :class="{ untagged: r.label === '—' }">{{ r.label }}</span>
            <span class="num">{{ r.count }}</span>
            <span class="muted small">trips</span>
            <span class="num">{{ fmtDistanceKm(r.km) }}</span>
            <span class="num muted">{{ fmtVolumeL(r.fuel_l) }}</span>
          </li>
        </ul>
      </div>

      <div v-if="groupedTrips.length === 0" class="card">
        <p class="muted">No trips match the current filter.</p>
      </div>
      <div v-for="group in groupedTrips" :key="group.key" class="card no-pad">
        <header class="group-head">
          <span class="group-label">{{ group.label }}</span>
          <span class="muted small">{{ group.items.length }}</span>
        </header>
        <table class="data">
          <thead>
            <tr>
              <th v-if="selectMode" class="sel-cell"></th>
              <th>Started</th>
              <th>Duration</th>
              <th>Distance</th>
              <th>Max speed</th>
              <th>Max RPM</th>
              <th>Fuel</th>
              <th>Purpose</th>
              <th>DTCs</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="t in group.items"
              :key="t.id"
              class="clickable"
              :class="{ selected: selectedIds.has(t.id) }"
              @click="open(t.id)"
            >
              <td v-if="selectMode" class="sel-cell">
                <span class="checkbox" :class="{ on: selectedIds.has(t.id) }" aria-hidden="true">
                  <span v-if="selectedIds.has(t.id)">✓</span>
                </span>
              </td>
              <td>
                {{ fmtDateTime(t.started_at) }}
                <span
                  v-if="t.is_towing"
                  class="tow-badge"
                  title="Towing — fuel economy on this trip is not comparable"
                >TOW</span>
              </td>
              <td>{{ fmtDuration(t.duration_s) }}</td>
              <td>{{ fmtDistanceKm(t.distance_km ?? null) }}</td>
              <td>{{ fmtSpeedKph(t.max_speed_kph ?? null) }}</td>
              <td>{{ fmtRpm(t.max_rpm) }}</td>
              <td>{{ fmtVolumeL(t.fuel_used_l ?? null) }}</td>
              <td>
                <span v-if="t.category" class="tag">{{ t.category }}</span>
                <span v-else class="muted">—</span>
              </td>
              <td>{{ t.dtc_count ?? 0 }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <footer class="pager">
        <span class="muted">
          {{ offset + 1 }}–{{ Math.min(offset + data.items.length, data.total) }}
          of {{ data.total }}
        </span>
        <button type="button" :disabled="offset === 0" @click="prevPage">Prev</button>
        <button
          type="button"
          :disabled="offset + limit >= data.total"
          @click="nextPage"
        >Next</button>
      </footer>
    </template>
  </div>
</template>

<style scoped>
.trips {
  display: flex;
  flex-direction: column;
  gap: 1rem;
  position: relative;
}
.revalidate-bar {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 2px;
  border-radius: 1px;
  overflow: hidden;
  background: var(--c-accent-soft, rgba(255, 91, 58, 0.18));
  z-index: 20;
}
.revalidate-bar::after {
  content: "";
  position: absolute;
  top: 0;
  bottom: 0;
  width: 40%;
  background: var(--c-accent, #ff5b3a);
  border-radius: 1px;
  animation: revalidate-slide 1s ease-in-out infinite;
}
@keyframes revalidate-slide {
  0% { left: -40%; }
  100% { left: 100%; }
}
.head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 1rem;
  flex-wrap: wrap;
}
.head h1 {
  margin: 0;
}
.filters {
  display: flex;
  align-items: flex-end;
  gap: 0.5rem;
}
.filters label {
  display: flex;
  flex-direction: column;
  gap: 0.2rem;
  font-size: 0.78rem;
  color: var(--c-muted);
}
.no-pad {
  padding: 0;
  overflow: hidden;
}
.pager {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 0.5rem;
}
.purposes h3 {
  margin: 0 0 0.4rem;
  font-size: 0.95rem;
}
.rollup {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}
.rollup li {
  display: grid;
  grid-template-columns: minmax(8rem, 1fr) auto auto auto auto;
  gap: 0.6rem;
  align-items: baseline;
  font-variant-numeric: tabular-nums;
}
.tow-badge {
  display: inline-block;
  margin-left: 6px;
  padding: 1px 5px;
  border-radius: 4px;
  font-size: 0.66rem;
  font-weight: 700;
  letter-spacing: 0.04em;
  color: #ffb020;
  border: 1px solid #ffb020;
  vertical-align: 1px;
}

.tag {
  background: var(--c-surface-soft);
  border: 1px solid var(--c-border-soft);
  border-radius: 999px;
  padding: 0 0.55rem;
  font-size: 0.82rem;
  color: var(--c-text);
}
.tag.untagged {
  color: var(--c-muted);
  font-style: italic;
}
.num {
  font-variant-numeric: tabular-nums;
  text-align: right;
}
.small {
  font-size: 0.78rem;
}
.chip-row {
  display: inline-flex;
  gap: 4px;
}
.chip {
  background: var(--c-surface-soft, #1e1c2a);
  border: 1px solid var(--c-border-soft, #2a2d33);
  border-radius: 999px;
  color: var(--c-text, #e7e9ee);
  padding: 4px 10px;
  font-size: 0.82rem;
  cursor: pointer;
}
.chip.active {
  background: var(--c-accent, #f97316);
  color: white;
  border-color: var(--c-accent, #f97316);
}
.sort {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.sort select {
  background: var(--c-surface-soft, #1e1c2a);
  border: 1px solid var(--c-border-soft, #2a2d33);
  border-radius: 6px;
  color: var(--c-text);
  padding: 4px 8px;
  font-size: 0.85rem;
}
.group-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 14px;
  border-bottom: 1px solid var(--c-border-soft, #2a2d33);
}
.group-label {
  text-transform: uppercase;
  letter-spacing: 0.05em;
  font-size: 0.78rem;
  font-weight: 500;
  color: var(--c-accent, #f97316);
  flex: 1;
}

/* Selection mode + action bar + confirm dialog */
.action-bar {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.6rem 0.9rem;
  background: var(--c-surface-soft, #1e1c2a);
  border: 1px solid var(--c-border-soft, #2a2d33);
  border-radius: 8px;
  position: sticky;
  top: 0;
  z-index: 10;
}
.action-label {
  flex: 1;
  font-size: 0.88rem;
  color: var(--c-text, #e7e9ee);
}
.action-bar button.primary,
.action-bar button.danger,
.action-bar button.ghost {
  font-size: 0.85rem;
  padding: 0.32rem 0.85rem;
  border-radius: 6px;
  cursor: pointer;
  border: 1px solid var(--c-border-soft, #2a2d33);
}
.action-bar button.primary {
  background: var(--c-accent, #f97316);
  color: white;
  border-color: var(--c-accent, #f97316);
}
.action-bar button.danger {
  background: #b91c1c;
  color: white;
  border-color: #b91c1c;
}
.action-bar button.danger:disabled,
.action-bar button.primary:disabled,
.action-bar button.ghost:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.spinner {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  border: 2px solid var(--c-border-soft, #2a2d33);
  border-top-color: var(--c-accent, #f97316);
  animation: spin 0.8s linear infinite;
}
@keyframes spin {
  to { transform: rotate(360deg); }
}
.sel-cell {
  width: 30px;
  padding-right: 0;
}
.checkbox {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  border-radius: 4px;
  border: 1.5px solid var(--c-border-soft, #2a2d33);
  background: transparent;
  color: white;
  font-size: 0.7rem;
  line-height: 1;
}
.checkbox.on {
  background: var(--c-accent, #f97316);
  border-color: var(--c-accent, #f97316);
}
tr.selected {
  background: rgba(249, 115, 22, 0.12);
}
.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.55);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 100;
}
.modal {
  background: var(--c-surface, #14121d);
  border: 1px solid var(--c-border-soft, #2a2d33);
  border-radius: 10px;
  padding: 1.2rem 1.4rem;
  width: min(420px, calc(100% - 2rem));
  display: flex;
  flex-direction: column;
  gap: 0.7rem;
}
.modal h3 {
  margin: 0;
  font-size: 1.05rem;
}
.target-list {
  list-style: none;
  margin: 0;
  padding: 0.4rem 0.6rem;
  background: var(--c-surface-soft, #1e1c2a);
  border-radius: 6px;
  font-size: 0.85rem;
  max-height: 9rem;
  overflow-y: auto;
}
.target-list li {
  padding: 0.15rem 0;
}
.modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 0.5rem;
}
.modal-actions button {
  font-size: 0.9rem;
  padding: 0.4rem 1rem;
  border-radius: 6px;
  cursor: pointer;
  border: 1px solid var(--c-border-soft, #2a2d33);
  background: transparent;
  color: var(--c-text, #e7e9ee);
}
.modal-actions button.danger {
  background: #b91c1c;
  color: white;
  border-color: #b91c1c;
}
</style>
