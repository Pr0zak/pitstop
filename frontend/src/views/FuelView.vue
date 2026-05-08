<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { RouterLink } from "vue-router";
import { useVehiclesStore } from "@/stores/vehicles";
import { useAsync } from "@/composables/useAsync";
import * as api from "@/api/endpoints";
import type { Fillup } from "@/api/types";
import type uPlot from "uplot";
import {
  fmtDate,
  fmtMpg,
  fmtMoney,
  fmtMiles,
  fmtGallons,
  fmtNumber,
} from "@/composables/useFormat";
import { Plus, Pencil, X, Upload } from "lucide-vue-next";
import FillupModal from "@/components/FillupModal.vue";
import UPlotChart from "@/components/charts/UPlotChart.vue";
import MapLibreMap from "@/components/charts/MapLibreMap.vue";

const vehicles = useVehiclesStore();
const vehicleId = computed(() => vehicles.selectedVehicleId);
const tab = ref<"fillups" | "map" | "stats">("fillups");

const limit = ref(50);
const offset = ref(0);

const fillupsQ = useAsync(
  () =>
    vehicleId.value
      ? api.listFillups({ vehicle_id: vehicleId.value, limit: limit.value, offset: offset.value })
      : Promise.resolve({ items: [], total: 0 }),
  [vehicleId, limit, offset],
);

const stationsQ = useAsync(
  () =>
    vehicleId.value && tab.value === "map"
      ? api.stationsCluster(vehicleId.value)
      : Promise.resolve([]),
  [vehicleId, tab],
);

const monthlyQ = useAsync(
  () =>
    vehicleId.value && tab.value === "stats"
      ? api.monthlySpend(vehicleId.value, 12)
      : Promise.resolve({ months: [] }),
  [vehicleId, tab],
);

const cpmQ = useAsync(
  () =>
    vehicleId.value && tab.value === "stats"
      ? api.costPerMile(vehicleId.value, "year")
      : Promise.resolve({ points: [] }),
  [vehicleId, tab],
);

const overlayQ = useAsync(
  () =>
    vehicleId.value && tab.value === "stats"
      ? api.mpgOverlay(vehicleId.value)
      : Promise.resolve({ obd: [], fillup: [] }),
  [vehicleId, tab],
);

watch([vehicleId], () => {
  offset.value = 0;
});

// Sorting
type SortKey = "fillup_date" | "odometer" | "volume" | "total_price" | "mpg_recomputed";
const sortKey = ref<SortKey>("fillup_date");
const sortDir = ref<"asc" | "desc">("desc");

const sortedFillups = computed<Fillup[]>(() => {
  const items = [...(fillupsQ.data.value?.items ?? [])];
  items.sort((a, b) => {
    const av = a[sortKey.value] ?? 0;
    const bv = b[sortKey.value] ?? 0;
    if (typeof av === "string" && typeof bv === "string") {
      return sortDir.value === "asc"
        ? av.localeCompare(bv)
        : bv.localeCompare(av);
    }
    const ax = Number(av);
    const bx = Number(bv);
    return sortDir.value === "asc" ? ax - bx : bx - ax;
  });
  return items;
});

function changeSort(k: SortKey) {
  if (sortKey.value === k) {
    sortDir.value = sortDir.value === "asc" ? "desc" : "asc";
  } else {
    sortKey.value = k;
    sortDir.value = "desc";
  }
}

// Modal
const showModal = ref(false);
const editing = ref<Partial<Fillup> | null>(null);
function openCreate() {
  editing.value = null;
  showModal.value = true;
}
function openEdit(f: Fillup) {
  editing.value = f;
  showModal.value = true;
}
function onSaved() {
  void fillupsQ.reload();
}
async function remove(f: Fillup) {
  if (!window.confirm("Delete this fillup?")) return;
  try {
    await api.deleteFillup(f.id);
    await fillupsQ.reload();
  } catch (e: unknown) {
    alert(e instanceof Error ? e.message : "delete failed");
  }
}

// Station autocomplete corpus from existing fillups (deduped, non-empty)
const stationSuggestions = computed<string[]>(() => {
  const set = new Set<string>();
  for (const f of fillupsQ.data.value?.items ?? []) {
    if (f.station_name) set.add(f.station_name);
  }
  return Array.from(set).sort();
});

// Stations markers
const stationMarkers = computed(() => {
  const cs = stationsQ.data.value ?? [];
  return cs
    .filter((c) => c.latitude != null && c.longitude != null)
    .map((c) => ({
      id: c.id,
      lng: c.longitude,
      lat: c.latitude,
      properties: {
        label: c.name ?? "Unnamed station",
        fillup_count: c.fillup_count,
        total_volume: c.total_volume,
        last_visit: c.last_visit,
      },
    }));
});

const selectedStation = ref<{ id: string; properties: Record<string, unknown> } | null>(null);
function onMarkerClick(id: string, properties: Record<string, unknown>) {
  selectedStation.value = { id, properties };
}

// Stats charts
const monthlyChart = computed(() => {
  const months = monthlyQ.data.value?.months ?? [];
  if (months.length === 0) return null;
  const t = months.map((m) => Math.round((Date.parse(m.month) || 0) / 1000));
  const fuel = months.map((m) => m.fuel ?? 0);
  const service = months.map((m) => m.service ?? 0);
  const aligned: uPlot.AlignedData = [t, fuel, service];
  const opts: uPlot.Options = {
    width: 600,
    height: 220,
    scales: { x: { time: true } },
    axes: [{ stroke: "var(--c-muted)" }, { stroke: "var(--c-muted)", label: "$" }],
    series: [
      {},
      { label: "Fuel", stroke: "#2f81f7", fill: "rgba(47,129,247,0.18)", width: 1.5 },
      { label: "Service", stroke: "#d29922", fill: "rgba(210,153,34,0.18)", width: 1.5 },
    ],
  };
  return { aligned, opts };
});

const cpmChart = computed(() => {
  const points = cpmQ.data.value?.points ?? [];
  if (points.length === 0) return null;
  const t = points.map((p) => Math.round((Date.parse(p.period) || 0) / 1000));
  const y = points.map((p) => p.cost_per_mi ?? null);
  const aligned: uPlot.AlignedData = [t, y];
  const opts: uPlot.Options = {
    width: 600,
    height: 200,
    scales: { x: { time: true } },
    axes: [{ stroke: "var(--c-muted)" }, { stroke: "var(--c-muted)", label: "$/mi" }],
    series: [{}, { label: "$/mi", stroke: "#3fb950", width: 2 }],
  };
  return { aligned, opts };
});

const overlayChart = computed(() => {
  const obd = overlayQ.data.value?.obd ?? [];
  const fillup = overlayQ.data.value?.fillup ?? [];
  if (obd.length === 0 && fillup.length === 0) return null;
  const tsSet = new Set<number>();
  for (const p of obd) tsSet.add(Math.round((Date.parse(p.period) || 0) / 1000));
  for (const p of fillup) tsSet.add(Math.round((Date.parse(p.period) || 0) / 1000));
  const ts = Array.from(tsSet).sort((a, b) => a - b);
  const idx = new Map<number, number>();
  ts.forEach((t, i) => idx.set(t, i));
  const obdCol: (number | null)[] = new Array(ts.length).fill(null);
  const fillCol: (number | null)[] = new Array(ts.length).fill(null);
  for (const p of obd) {
    const i = idx.get(Math.round((Date.parse(p.period) || 0) / 1000));
    if (i != null) obdCol[i] = p.mpg ?? null;
  }
  for (const p of fillup) {
    const i = idx.get(Math.round((Date.parse(p.period) || 0) / 1000));
    if (i != null) fillCol[i] = p.mpg ?? null;
  }
  const aligned: uPlot.AlignedData = [ts, obdCol, fillCol];
  const opts: uPlot.Options = {
    width: 600,
    height: 220,
    scales: { x: { time: true } },
    axes: [{ stroke: "var(--c-muted)" }, { stroke: "var(--c-muted)", label: "mpg" }],
    series: [
      {},
      { label: "OBD MPG", stroke: "#3fb950", width: 1.5 },
      { label: "Fillup MPG", stroke: "#2f81f7", width: 1.5, dash: [4, 3] },
    ],
  };
  return { aligned, opts };
});

const summary = computed(() => {
  const items = fillupsQ.data.value?.items ?? [];
  const recent = items.slice(0, 6);
  const mpgs = recent
    .map((f) => f.mpg_recomputed)
    .filter((v): v is number => typeof v === "number");
  const avgMpg = mpgs.length ? mpgs.reduce((a, b) => a + b, 0) / mpgs.length : null;
  const totalSpend12m = (monthlyQ.data.value?.months ?? []).reduce(
    (sum, m) => sum + (m.total ?? 0),
    0,
  );
  const totalMiles = (cpmQ.data.value?.points ?? []).reduce(
    (sum, p) => sum + (p.miles ?? 0),
    0,
  );
  return {
    avgMpg,
    totalSpend12m,
    totalMiles,
  };
});
</script>

<template>
  <div class="fuel">
    <header class="head">
      <h1>Fuel</h1>
      <div class="actions">
        <RouterLink to="/fuel/import" class="link">
          <Upload :size="14" /> Import
        </RouterLink>
        <button class="primary" type="button" @click="openCreate" :disabled="!vehicleId">
          <Plus :size="14" /> New fillup
        </button>
      </div>
    </header>

    <div v-if="!vehicleId" class="card">
      <p class="muted">Select a vehicle.</p>
    </div>
    <template v-else>
      <nav class="tabs">
        <button
          type="button"
          :class="{ active: tab === 'fillups' }"
          @click="tab = 'fillups'"
        >Fillups</button>
        <button
          type="button"
          :class="{ active: tab === 'map' }"
          @click="tab = 'map'"
        >Stations map</button>
        <button
          type="button"
          :class="{ active: tab === 'stats' }"
          @click="tab = 'stats'"
        >Stats</button>
      </nav>

      <!-- Fillups -->
      <template v-if="tab === 'fillups'">
        <div v-if="fillupsQ.loading.value && !fillupsQ.data.value" class="card">
          <p class="muted">Loading…</p>
        </div>
        <div v-else-if="!fillupsQ.data.value || fillupsQ.data.value.items.length === 0" class="card">
          <p class="muted">No fillups yet. Import your Fuelio history or add one manually.</p>
        </div>
        <div v-else class="card no-pad">
          <table class="data">
            <thead>
              <tr>
                <th class="sortable" @click="changeSort('fillup_date')">Date</th>
                <th class="sortable" @click="changeSort('odometer')">Odo</th>
                <th class="sortable" @click="changeSort('volume')">Volume</th>
                <th class="sortable" @click="changeSort('total_price')">Total</th>
                <th>Station</th>
                <th class="sortable" @click="changeSort('mpg_recomputed')">
                  MPG <small class="muted">(reported)</small>
                </th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="f in sortedFillups" :key="f.id">
                <td>{{ fmtDate(f.fillup_date) }}</td>
                <td>{{ fmtMiles(f.odometer) }}</td>
                <td>
                  {{ fmtGallons(f.volume) }}
                  <span v-if="f.partial" class="badge warn" title="Partial fill">P</span>
                  <span v-if="f.missed" class="badge danger" title="Missed">M</span>
                </td>
                <td>{{ fmtMoney(f.total_price) }}</td>
                <td>{{ f.station_name ?? "—" }}</td>
                <td>
                  <strong>{{ fmtMpg(f.mpg_recomputed) }}</strong>
                  <span v-if="f.mpg_reported != null" class="muted small">
                    ({{ fmtNumber(f.mpg_reported, { digits: 1 }) }})
                  </span>
                </td>
                <td class="row-actions">
                  <button class="ghost" type="button" @click="openEdit(f)" title="Edit">
                    <Pencil :size="14" />
                  </button>
                  <button class="ghost" type="button" @click="remove(f)" title="Delete">
                    <X :size="14" />
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <footer v-if="fillupsQ.data.value" class="pager">
          <span class="muted">
            {{ offset + 1 }}–{{
              Math.min(offset + fillupsQ.data.value.items.length, fillupsQ.data.value.total)
            }}
            of {{ fillupsQ.data.value.total }}
          </span>
          <button
            type="button"
            :disabled="offset === 0"
            @click="offset = Math.max(0, offset - limit)"
          >Prev</button>
          <button
            type="button"
            :disabled="offset + limit >= fillupsQ.data.value.total"
            @click="offset = offset + limit"
          >Next</button>
        </footer>
      </template>

      <!-- Stations map -->
      <template v-else-if="tab === 'map'">
        <div v-if="stationsQ.loading.value" class="card">
          <p class="muted">Loading stations…</p>
        </div>
        <div v-else-if="stationMarkers.length === 0" class="card">
          <p class="muted">
            No stations with GPS coordinates yet. Add fillups with location set, or import
            from Fuelio.
          </p>
        </div>
        <template v-else>
          <div class="card no-pad map-card">
            <MapLibreMap :markers="stationMarkers" :height="480" @marker-click="onMarkerClick" />
          </div>
          <div v-if="selectedStation" class="card">
            <h3>{{ String(selectedStation.properties.label ?? "Station") }}</h3>
            <dl class="kv">
              <dt>Fillup count</dt>
              <dd>{{ selectedStation.properties.fillup_count }}</dd>
              <dt>Total volume</dt>
              <dd>{{ fmtGallons(Number(selectedStation.properties.total_volume) || null) }}</dd>
              <dt>Last visit</dt>
              <dd>
                {{
                  selectedStation.properties.last_visit
                    ? fmtDate(String(selectedStation.properties.last_visit))
                    : "—"
                }}
              </dd>
            </dl>
          </div>
        </template>
      </template>

      <!-- Stats -->
      <template v-else>
        <div class="grid stats">
          <div class="card kpi">
            <h3>Avg MPG (recent)</h3>
            <div class="big">{{ fmtMpg(summary.avgMpg) }}</div>
          </div>
          <div class="card kpi">
            <h3>Total spend (12m)</h3>
            <div class="big">{{ fmtMoney(summary.totalSpend12m) }}</div>
          </div>
          <div class="card kpi">
            <h3>Miles tracked</h3>
            <div class="big">{{ fmtMiles(summary.totalMiles) }}</div>
          </div>
          <div class="card chart-card">
            <h3>Monthly spend</h3>
            <div v-if="monthlyQ.loading.value" class="muted">Loading…</div>
            <div v-else-if="!monthlyChart" class="muted">No data.</div>
            <UPlotChart v-else :data="monthlyChart.aligned" :options="monthlyChart.opts" />
          </div>
          <div class="card chart-card">
            <h3>$/mile</h3>
            <div v-if="cpmQ.loading.value" class="muted">Loading…</div>
            <div v-else-if="!cpmChart" class="muted">No data.</div>
            <UPlotChart v-else :data="cpmChart.aligned" :options="cpmChart.opts" />
          </div>
          <div class="card chart-card wide">
            <h3>OBD vs fillup MPG</h3>
            <div v-if="overlayQ.loading.value" class="muted">Loading…</div>
            <div v-else-if="!overlayChart" class="muted">
              No OBD data yet — drive with the WiCAN connected to populate this chart.
            </div>
            <UPlotChart v-else :data="overlayChart.aligned" :options="overlayChart.opts" />
          </div>
        </div>
      </template>
    </template>

    <FillupModal
      v-if="showModal && vehicles.selectedVehicle"
      :vehicle="vehicles.selectedVehicle"
      :initial="editing"
      :station-suggestions="stationSuggestions"
      @close="showModal = false"
      @saved="onSaved"
    />
  </div>
</template>

<style scoped>
.fuel {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}
.head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.actions {
  display: flex;
  gap: 0.5rem;
  align-items: center;
}
.link {
  display: inline-flex;
  align-items: center;
  gap: 0.3rem;
  padding: 0.4rem 0.7rem;
  background: var(--c-surface-2);
  border: 1px solid var(--c-border-soft);
  border-radius: var(--r-sm);
  color: var(--c-text);
}
.link:hover {
  background: var(--c-surface-3);
  text-decoration: none;
}
.tabs {
  display: inline-flex;
  gap: 0.2rem;
  background: var(--c-surface-2);
  border: 1px solid var(--c-border-soft);
  border-radius: var(--r-sm);
  padding: 2px;
  align-self: flex-start;
}
.tabs button {
  border: none;
  background: transparent;
  font-size: 0.85rem;
  padding: 0.3rem 0.7rem;
}
.tabs button.active {
  background: var(--c-accent-soft);
  color: var(--c-accent);
}
.no-pad {
  padding: 0;
  overflow: hidden;
}
.sortable {
  cursor: pointer;
  user-select: none;
}
.sortable:hover {
  color: var(--c-text);
}
.row-actions {
  display: flex;
  gap: 0.3rem;
  justify-content: flex-end;
}
.pager {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 0.5rem;
}
.small {
  font-size: 0.78rem;
}
.grid.stats {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 1rem;
}
.kpi .big {
  font-size: 1.5rem;
  font-weight: 600;
}
.chart-card {
  grid-column: span 1;
}
.chart-card.wide {
  grid-column: span 3;
}
.kv {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0.4rem 0.6rem;
}
.kv dt {
  color: var(--c-muted);
  font-size: 0.78rem;
  text-transform: uppercase;
  letter-spacing: 0.05em;
}
.kv dd {
  margin: 0;
  font-weight: 500;
}
.map-card {
  padding: 0;
  overflow: hidden;
}
@media (max-width: 900px) {
  .grid.stats {
    grid-template-columns: 1fr;
  }
  .chart-card.wide {
    grid-column: auto;
  }
}
</style>
