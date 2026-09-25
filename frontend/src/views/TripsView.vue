<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { RouterLink, useRouter } from "vue-router";
import ConfirmDialog from "@/components/ConfirmDialog.vue";
import StateCard from "@/components/StateCard.vue";
import { useQueryParam } from "@/composables/useQueryParam";
import { useToastStore } from "@/stores/toast";
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
  fmtMpg,
  fmtWhen,
  nf,
  convDistance,
  convVolume,
  distUnitLabel,
  volUnitLabel,
  economyUnitLabel,
  dateGroupFor,
  DATE_GROUP_LABEL,
  DATE_GROUP_ORDER,
  type DateGroupKey,
} from "@/composables/useFormat";
import { foldShortHops, groupTotals, tripMpg, type TripRow } from "@/lib/tripList";
import { ChevronRight } from "lucide-vue-next";

const vehicles = useVehiclesStore();
const router = useRouter();
const toast = useToastStore();
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

// Sort / source / towing are SERVER-side now (GET /trips sort, source,
// towing) so a "longest" ranking covers every trip, not the loaded page.
// The same filter + sort is re-applied client-side to the returned page:
// idempotent against a backend that honoured the params, and a correct
// per-page fallback against one that predates them. All three round-trip
// through the URL.
type SortOrder = api.TripSort;
type SrcFilter = "all" | api.TripSourceFilter;
const SORTS = ["recent", "distance", "duration", "top_speed", "max_rpm", "fuel"] as const;
const SORT_LABEL: Record<SortOrder, string> = {
  recent: "Most recent",
  distance: "Longest distance",
  duration: "Longest duration",
  top_speed: "Fastest top speed",
  max_rpm: "Highest RPM",
  fuel: "Most fuel used",
};
const sort = useQueryParam<SortOrder>("sort", "recent", SORTS);
const srcFilter = useQueryParam<SrcFilter>("source", "all", ["all", "phone_batch", "manual_merge", "other"]);
// Towing is a load condition, so it filters independently of source.
const towingParam = useQueryParam<"0" | "1">("towing", "0", ["0", "1"]);
const towingOnly = computed<boolean>({
  get: () => towingParam.value === "1",
  set: (v) => (towingParam.value = v ? "1" : "0"),
});
const SRC_LABEL: Record<SrcFilter, string> = {
  all: "All sources",
  phone_batch: "Phone",
  manual_merge: "Merged",
  other: "Other",
};

const { data, loading, error, reload } = useAsync(
  () =>
    vehicleId.value
      ? api.listTrips({
          vehicle_id: vehicleId.value,
          from: fromIso.value,
          to: toIso.value,
          limit: limit.value,
          offset: offset.value,
          ...(sort.value !== "recent" ? { sort: sort.value } : {}),
          ...(srcFilter.value !== "all" ? { source: srcFilter.value } : {}),
          ...(towingOnly.value ? { towing: true } : {}),
        })
      : Promise.resolve({ items: [], total: 0 }),
  [vehicleId, fromIso, toIso, limit, offset, sort, srcFilter, towingOnly],
);

watch([vehicleId, fromIso, toIso, sort, srcFilter, towingOnly], () => {
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
    toast.success(`Merged ${ids.length} trips`);
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
    toast.success(action.value.message);
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
  void router.replace({ query: {} });
}

const DATE_PRESET_LABEL: Record<DatePreset, string> = {
  "7d": "7d",
  "30d": "30d",
  "90d": "90d",
  all: "All",
  custom: "Custom",
};

// Filter + sort the returned page (see the note on `sort` above).
function matches(t: Trip): boolean {
  if (towingOnly.value && !t.is_towing) return false;
  if (srcFilter.value === "all") return true;
  if (srcFilter.value === "phone_batch") return t.source === "phone_batch";
  if (srcFilter.value === "manual_merge") return t.source === "manual_merge";
  return t.source !== "phone_batch" && t.source !== "manual_merge";
}
function cmp(a: Trip, b: Trip): number {
  switch (sort.value) {
    case "distance": return (b.distance_km ?? 0) - (a.distance_km ?? 0);
    case "top_speed": return (b.max_speed_kph ?? 0) - (a.max_speed_kph ?? 0);
    case "duration": return (b.duration_s ?? 0) - (a.duration_s ?? 0);
    case "max_rpm": return (b.max_rpm ?? 0) - (a.max_rpm ?? 0);
    case "fuel": return (b.fuel_used_l ?? 0) - (a.fuel_used_l ?? 0);
    default:
      return new Date(b.started_at).getTime() - new Date(a.started_at).getTime();
  }
}
const visibleTrips = computed<Trip[]>(() => (data.value?.items ?? []).filter(matches).sort(cmp));

/** Non-recent sorts render as one ranked list — date groups would scatter
 *  the ranking across headers. */
const ranked = computed(() => sort.value !== "recent");

interface TripGroup {
  key: DateGroupKey | "ranked";
  label: string;
  items: Trip[];
  /** What the group renders: short-hop runs folded (most-recent sort only). */
  rows: TripRow[];
  totals: ReturnType<typeof groupTotals>;
}
function makeGroup(key: TripGroup["key"], label: string, items: Trip[]): TripGroup {
  return {
    key,
    label,
    items,
    // Folding is off for ranked sorts and while selecting (every trip must
    // be individually tappable to select it).
    rows:
      ranked.value || selectMode.value
        ? items.map((trip) => ({ kind: "trip" as const, trip }))
        : foldShortHops(items),
    totals: groupTotals(items),
  };
}

const groupedTrips = computed<TripGroup[]>(() => {
  if (ranked.value) {
    return visibleTrips.value.length
      ? [makeGroup("ranked", SORT_LABEL[sort.value], visibleTrips.value)]
      : [];
  }
  const byKey = new Map<DateGroupKey, Trip[]>();
  for (const t of visibleTrips.value) {
    const k = dateGroupFor(t.started_at);
    let list = byKey.get(k);
    if (!list) {
      list = [];
      byKey.set(k, list);
    }
    list.push(t);
  }
  return DATE_GROUP_ORDER.filter((k) => (byKey.get(k)?.length ?? 0) > 0).map((k) =>
    makeGroup(k, DATE_GROUP_LABEL[k], byKey.get(k)!),
  );
});

/** Rank of a trip in the ranked list (only meaningful when `ranked`). */
function rankOf(t: Trip): number {
  return offset.value + visibleTrips.value.indexOf(t) + 1;
}

/** Row date: inside a Today / Yesterday group only the time is shown. */
function rowWhen(t: Trip): string {
  return fmtWhen(t.started_at, { withTime: true, grouped: !ranked.value });
}

// Expanded "short hops" rows, keyed by TripRow.key.
const openHops = ref<Set<string>>(new Set());
function toggleHops(key: string) {
  const next = new Set(openHops.value);
  if (next.has(key)) next.delete(key);
  else next.add(key);
  openHops.value = next;
}
/** Rows actually rendered: an expanded hops row is followed by its trips. */
function renderRows(g: TripGroup): Array<TripRow | { kind: "hop"; trip: Trip }> {
  const out: Array<TripRow | { kind: "hop"; trip: Trip }> = [];
  for (const r of g.rows) {
    out.push(r);
    if (r.kind === "hops" && openHops.value.has(r.key)) {
      for (const trip of r.trips) out.push({ kind: "hop", trip });
    }
  }
  return out;
}
function rowKey(r: TripRow | { kind: "hop"; trip: Trip }): string {
  return r.kind === "hops" ? r.key : r.trip.id;
}

function fmtTotals(g: TripGroup): string {
  const parts = [
    `${g.totals.count} trip${g.totals.count === 1 ? "" : "s"}`,
    `${nf(1).format(convDistance(g.totals.distanceKm, "km"))} ${distUnitLabel()}`,
  ];
  if (g.totals.fuelL != null) {
    parts.push(`${nf(1).format(convVolume(g.totals.fuelL, "L"))} ${volUnitLabel()}`);
  }
  return parts.join(" · ");
}
/** Trip economy cell: "— mpg" when it can't honestly be computed (matches
 *  the Android row). */
function tripEcon(t: Trip): string {
  const v = tripMpg(t);
  return v == null ? `— ${economyUnitLabel()}` : fmtMpg(v);
}
function hopsLabel(r: Extract<TripRow, { kind: "hops" }>): string {
  return `${r.trips.length} short hops · ${nf(1).format(convDistance(r.distanceKm, "km"))} ${distUnitLabel()}`;
}

function onStartedClick(e: MouseEvent, id: string) {
  if (selectMode.value) {
    e.preventDefault();
    toggleSelected(id);
  }
}

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
      <div class="head-actions">
        <button type="button" class="ghost" @click="reset">Reset</button>
        <button v-if="!selectMode" type="button" class="ghost" @click="enterSelectMode">Select</button>
      </div>
    </header>

    <div class="filters">
      <div class="chip-row" role="group" aria-label="Date range">
        <button
          v-for="opt in (['7d','30d','90d','all','custom'] as const)"
          :key="opt"
          type="button"
          class="chip"
          :aria-pressed="datePreset === opt"
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
      <div class="chip-row" role="group" aria-label="Source and load">
        <button
          v-for="opt in (['all','phone_batch','manual_merge','other'] as const)"
          :key="opt"
          type="button"
          class="chip"
          :aria-pressed="srcFilter === opt"
          @click="srcFilter = opt"
        >
          {{ SRC_LABEL[opt] }}
        </button>
        <button
          type="button"
          class="chip"
          :aria-pressed="towingOnly"
          title="Only trips flagged as towing"
          @click="towingOnly = !towingOnly"
        >
          Towing
        </button>
      </div>
      <label class="sort">
        <span class="lbl">Sort</span>
        <select v-model="sort">
          <option v-for="o in SORTS" :key="o" :value="o">{{ SORT_LABEL[o] }}</option>
        </select>
      </label>
    </div>

    <div v-if="selectMode" class="action-bar" role="toolbar" aria-label="Trip selection actions">
      <span class="action-label" role="status">{{ actionBarLabel }}</span>
      <span v-if="action.kind === 'in_progress'" class="spinner" aria-hidden="true"></span>
      <button type="button" class="ghost" :disabled="action.kind === 'in_progress'" @click="exitSelectMode">Cancel</button>
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

    <ConfirmDialog
      :open="confirmDelete"
      :title="`Delete ${deleteTargets.length} trip${deleteTargets.length === 1 ? '' : 's'}?`"
      message="This can't be undone."
      confirm-label="Delete"
      @confirm="doDelete"
      @cancel="cancelDelete"
    >
      <ul v-if="deleteTargets.length <= 5" class="target-list">
        <li v-for="t in deleteTargets" :key="t.id">
          {{ fmtDateTime(t.started_at) }}
          <span class="muted">·</span>
          {{ fmtDistanceKm(t.distance_km ?? null) }}
        </li>
      </ul>
    </ConfirmDialog>

    <!-- Thin top progress bar during background revalidation (stale rows
         stay visible while the next page / sort / filter loads). -->
    <div v-if="loading && data" class="revalidate-bar" role="progressbar" aria-label="Loading"></div>

    <StateCard v-if="!vehicleId" state="empty" title="Select a vehicle to view its trips." />
    <StateCard v-else-if="loading && !data" state="loading" title="Loading trips…" />
    <StateCard v-else-if="error && !data" state="error" :message="error" @retry="reload()" />
    <StateCard v-else-if="!data || data.items.length === 0" state="empty" title="No trips in this range." />
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

      <StateCard v-if="groupedTrips.length === 0" state="empty" title="No trips match the current filter." />
      <div v-for="group in groupedTrips" :key="group.key" class="card no-pad">
        <header class="group-head">
          <span class="group-label">{{ group.label }}</span>
          <span class="muted small num group-totals">{{ fmtTotals(group) }}</span>
        </header>

        <!-- Desktop / tablet: table -->
        <table class="data trip-table">
          <thead>
            <tr>
              <th v-if="selectMode" class="sel-cell"><span class="sr-only">Selected</span></th>
              <th v-if="ranked" class="num">#</th>
              <th class="started-h">Started</th>
              <th class="num">Duration</th>
              <th class="num">Distance</th>
              <th class="num" title="Distance ÷ fuel used for this trip">{{ economyUnitLabel() === 'mpg' ? 'MPG' : economyUnitLabel() }}</th>
              <th class="num">Max speed</th>
              <th class="num">Max RPM</th>
              <th class="num">Fuel</th>
            </tr>
          </thead>
          <tbody>
            <template v-for="r in renderRows(group)" :key="rowKey(r)">
              <tr
                v-if="r.kind === 'hops'"
                class="clickable hops-row"
                :aria-expanded="openHops.has(r.key)"
                @click="toggleHops(r.key)"
              >
                <td v-if="selectMode" class="sel-cell"></td>
                <td :colspan="ranked ? 9 : 8">
                  <button type="button" class="hops-btn" :aria-expanded="openHops.has(r.key)" @click.stop="toggleHops(r.key)">
                    <ChevronRight :size="14" class="hops-chev" :class="{ open: openHops.has(r.key) }" aria-hidden="true" />
                    {{ hopsLabel(r) }}
                  </button>
                </td>
              </tr>
              <tr
                v-else
                class="clickable"
                :class="{ selected: selectedIds.has(r.trip.id), 'hop-child': r.kind === 'hop' }"
                @click="open(r.trip.id)"
              >
                <td v-if="selectMode" class="sel-cell">
                  <span class="checkbox" :class="{ on: selectedIds.has(r.trip.id) }" aria-hidden="true">
                    <span v-if="selectedIds.has(r.trip.id)">✓</span>
                  </span>
                </td>
                <td v-if="ranked" class="num muted">{{ rankOf(r.trip) }}</td>
                <td class="started-cell">
                  <RouterLink
                    :to="`/trips/${r.trip.id}`"
                    class="started-link"
                    :title="fmtDateTime(r.trip.started_at)"
                    :aria-pressed="selectMode ? selectedIds.has(r.trip.id) : undefined"
                    @click.stop="onStartedClick($event, r.trip.id)"
                  >{{ rowWhen(r.trip) }}</RouterLink>
                  <span v-if="r.trip.category" class="row-chip">{{ r.trip.category }}</span>
                  <span v-if="r.trip.is_towing" class="row-chip warn" title="Towing — fuel economy on this trip is not comparable">Tow</span>
                  <span v-if="r.trip.gps_only" class="row-chip" title="No engine data — recorded by the phone alone, so this may not be this vehicle">GPS only</span>
                  <span v-if="(r.trip.dtc_count ?? 0) > 0" class="row-chip danger">{{ r.trip.dtc_count }} DTC</span>
                </td>
                <td class="num">{{ fmtDuration(r.trip.duration_s) }}</td>
                <td class="num">{{ fmtDistanceKm(r.trip.distance_km ?? null) }}</td>
                <td class="num mpg-cell">{{ tripEcon(r.trip) }}</td>
                <td class="num">{{ fmtSpeedKph(r.trip.max_speed_kph ?? null) }}</td>
                <td class="num">{{ fmtRpm(r.trip.max_rpm) }}</td>
                <td class="num">{{ fmtVolumeL(r.trip.fuel_used_l ?? null) }}</td>
              </tr>
            </template>
          </tbody>
        </table>

        <!-- Phone: cards -->
        <ul class="trip-cards">
          <template v-for="r in renderRows(group)" :key="rowKey(r)">
            <li v-if="r.kind === 'hops'" class="hops-card">
              <button type="button" class="hops-btn" :aria-expanded="openHops.has(r.key)" @click="toggleHops(r.key)">
                <ChevronRight :size="14" class="hops-chev" :class="{ open: openHops.has(r.key) }" aria-hidden="true" />
                {{ hopsLabel(r) }}
              </button>
            </li>
            <li v-else :class="{ selected: selectedIds.has(r.trip.id), 'hop-child': r.kind === 'hop' }">
              <RouterLink :to="`/trips/${r.trip.id}`" class="trip-card" @click="onStartedClick($event, r.trip.id)">
                <div class="tc-top">
                  <span v-if="ranked" class="rank num">#{{ rankOf(r.trip) }}</span>
                  <span class="tc-date">{{ rowWhen(r.trip) }}</span>
                  <span class="tc-mpg num">{{ tripEcon(r.trip) }}</span>
                </div>
                <div class="tc-stats num">
                  <span>{{ fmtDistanceKm(r.trip.distance_km ?? null) }}</span>
                  <span>{{ fmtDuration(r.trip.duration_s) }}</span>
                  <span>{{ fmtSpeedKph(r.trip.max_speed_kph ?? null) }} max</span>
                  <span>{{ fmtVolumeL(r.trip.fuel_used_l ?? null) }}</span>
                </div>
                <div
                  v-if="r.trip.category || r.trip.is_towing || r.trip.gps_only || (r.trip.dtc_count ?? 0) > 0"
                  class="tc-chips"
                >
                  <span v-if="r.trip.category" class="row-chip">{{ r.trip.category }}</span>
                  <span v-if="r.trip.is_towing" class="row-chip warn">Tow</span>
                  <span v-if="r.trip.gps_only" class="row-chip">GPS only</span>
                  <span v-if="(r.trip.dtc_count ?? 0) > 0" class="row-chip danger">{{ r.trip.dtc_count }} DTC</span>
                </div>
              </RouterLink>
            </li>
          </template>
        </ul>
      </div>
      <footer class="pager">
        <span class="muted">
          {{ offset + 1 }}–{{ Math.min(offset + data.items.length, data.total) }}
          of {{ data.total }}
        </span>
        <button type="button" :disabled="offset === 0" @click="prevPage">Prev</button>
        <button type="button" :disabled="offset + limit >= data.total" @click="nextPage">Next</button>
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
  background: var(--c-line0);
  z-index: 20;
}
.revalidate-bar::after {
  content: "";
  position: absolute;
  top: 0;
  bottom: 0;
  width: 40%;
  background: var(--c-ink3);
  border-radius: 1px;
  animation: revalidate-slide 1s ease-in-out infinite;
}
@keyframes revalidate-slide {
  0% { left: -40%; }
  100% { left: 100%; }
}
.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  flex-wrap: wrap;
}
.head-actions {
  display: flex;
  gap: 0.4rem;
}
.head h1 {
  margin: 0;
}
.filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.5rem 1rem;
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
.gps-badge {
  display: inline-block;
  margin-left: 6px;
  padding: 1px 5px;
  border-radius: 4px;
  font-size: 0.66rem;
  font-weight: 700;
  letter-spacing: 0.04em;
  color: var(--c-ink2);
  border: 1px solid var(--c-ink3);
  vertical-align: 1px;
}

.tow-badge {
  display: inline-block;
  margin-left: 6px;
  padding: 1px 5px;
  border-radius: 4px;
  font-size: 0.66rem;
  font-weight: 700;
  letter-spacing: 0.04em;
  color: var(--c-warn);
  border: 1px solid var(--c-warn);
  vertical-align: 1px;
}

.tag {
  background: var(--c-bg3);
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
.filters label.sort {
  display: inline-flex;
  flex-direction: row;
  align-items: center;
  gap: 6px;
}
.sort select {
  padding: 4px 8px;
  font-size: 0.85rem;
}
.group-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 14px;
  border-bottom: 1px solid var(--c-line1);
}
.group-label {
  text-transform: uppercase;
  letter-spacing: 0.05em;
  font-size: 0.78rem;
  font-weight: 500;
  color: var(--c-ink2);
  flex: 1;
}

/* Selection mode + action bar + confirm dialog */
.action-bar {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.6rem 0.9rem;
  background: var(--c-bg3);
  border: 1px solid var(--c-line1);
  border-radius: 8px;
  position: sticky;
  top: 0;
  z-index: 10;
}
.action-label {
  flex: 1;
  font-size: 0.88rem;
  color: var(--c-ink1);
}
.action-bar button.primary,
.action-bar button.danger,
.action-bar button.ghost {
  font-size: 0.85rem;
  padding: 0.32rem 0.85rem;
  border-radius: 6px;
  cursor: pointer;
  border: 1px solid var(--c-line1);
}
.action-bar button.primary {
  background: var(--c-accent);
  color: white;
  border-color: var(--c-accent);
}
.action-bar button.danger {
  background: var(--c-danger-deep);
  color: white;
  border-color: var(--c-danger-deep);
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
  border: 2px solid var(--c-line1);
  border-top-color: var(--c-ink1);
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
  border: 1.5px solid var(--c-line1);
  background: transparent;
  color: white;
  font-size: 0.7rem;
  line-height: 1;
}
.checkbox.on {
  background: var(--c-accent);
  border-color: var(--c-accent);
}
tr.selected {
  background: var(--c-accent-soft);
}
.target-list {
  list-style: none;
  margin: 0;
  padding: 0.4rem 0.6rem;
  background: var(--c-bg3);
  border-radius: 6px;
  font-size: 0.85rem;
  max-height: 9rem;
  overflow-y: auto;
}
.target-list li {
  padding: 0.15rem 0;
}
.started-link {
  color: var(--c-ink1);
}
.started-link:hover {
  color: var(--c-ink0);
}
.group-totals {
  font-family: 'Geist Mono', ui-monospace, monospace;
}
.started-cell {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
/* Fixed layout so every group's table lines its columns up the same way. */
.trip-table {
  table-layout: fixed;
}
.trip-table th.started-h {
  width: 30%;
}
.trip-table th.sel-cell {
  width: 40px;
}
.trip-table th:nth-child(1).num {
  width: 3rem;
}
.row-chip {
  display: inline-block;
  margin-left: 6px;
  padding: 0 6px;
  border-radius: 999px;
  border: 1px solid var(--c-line1);
  background: var(--c-bg3);
  color: var(--c-ink2);
  font-size: 0.7rem;
  font-weight: 500;
  line-height: 1.5;
  vertical-align: 1px;
  white-space: nowrap;
}
.row-chip.warn {
  color: var(--c-warn);
  border-color: rgba(255, 176, 32, 0.35);
  background: var(--c-warn-soft);
}
.row-chip.danger {
  color: var(--c-danger);
  border-color: rgba(255, 58, 46, 0.35);
  background: var(--c-danger-soft);
}
.mpg-cell {
  color: var(--c-ink0);
}
.hops-row td {
  padding-top: 0.4rem;
  padding-bottom: 0.4rem;
}
.hops-btn {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
  background: transparent;
  border: 0;
  padding: 0;
  font-size: 0.82rem;
  font-weight: 400;
  color: var(--c-ink2);
}
.hops-btn:hover:not(:disabled) {
  background: transparent;
  color: var(--c-ink0);
}
.hops-chev {
  transition: transform 120ms;
}
.hops-chev.open {
  transform: rotate(90deg);
}
tr.hop-child td:first-child,
.trip-cards li.hop-child .trip-card {
  padding-left: 1.8rem;
}
tr.hop-child {
  background: var(--c-bg1);
}
.hops-card {
  padding: 0.5rem 0.9rem;
}
.tc-mpg {
  color: var(--c-ink0);
  font-size: 0.85rem;
}
.tc-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 0.3rem;
}
.tc-chips .row-chip {
  margin-left: 0;
}
.trip-cards {
  display: none;
  list-style: none;
  margin: 0;
  padding: 0;
}
@media (max-width: 700px) {
  .trip-table {
    display: none;
  }
  .trip-cards {
    display: block;
  }
}
.trip-cards li {
  border-bottom: 1px solid var(--c-line0);
}
.trip-cards li:last-child {
  border-bottom: none;
}
.trip-cards li.selected {
  background: var(--c-accent-soft);
}
.trip-card {
  display: flex;
  flex-direction: column;
  gap: 0.3rem;
  padding: 0.7rem 0.9rem;
  color: var(--c-ink1);
  text-decoration: none;
}
.trip-card:hover {
  text-decoration: none;
  background: var(--c-bg3);
}
.tc-top {
  display: flex;
  align-items: center;
  gap: 0.4rem;
  flex-wrap: wrap;
}
.tc-date {
  font-weight: 500;
  color: var(--c-ink0);
  flex: 1;
}
.rank {
  color: var(--c-ink3);
}
.tc-stats {
  display: flex;
  flex-wrap: wrap;
  gap: 0.4rem 0.9rem;
  font-size: 0.82rem;
  color: var(--c-ink2);
}
</style>
