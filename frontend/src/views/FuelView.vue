<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { RouterLink } from "vue-router";
import { useVehiclesStore } from "@/stores/vehicles";
import { useSettingsStore } from "@/stores/settings";
import { useAsync } from "@/composables/useAsync";
import * as api from "@/api/endpoints";
import type { Fillup } from "@/api/types";
import type uPlot from "uplot";
import {
  fmtDate,
  fmtMpg,
  fmtMoney,
  fmtMiles,
  fmtOdo,
  fmtGallons,
  fmtNumber,
} from "@/composables/useFormat";
import { Plus, Pencil, X, Upload } from "lucide-vue-next";
import FillupModal from "@/components/FillupModal.vue";
import UPlotChart from "@/components/charts/UPlotChart.vue";
import MapLibreMap from "@/components/charts/MapLibreMap.vue";
import { WMO_CODE } from "@/api/types";

function wxIcon(code: number | null | undefined): string {
  if (code == null) return "";
  return WMO_CODE[code]?.icon ?? "·";
}
function weatherTitle(f: Fillup): string {
  if (f.weather_temp_c == null) return "";
  const parts: string[] = [];
  const lbl = f.weather_code != null ? WMO_CODE[f.weather_code]?.label : null;
  if (lbl) parts.push(lbl);
  if (f.weather_humidity_pct != null) parts.push(`${f.weather_humidity_pct}% rh`);
  if (f.weather_wind_kph != null) {
    const mph = Math.round(f.weather_wind_kph * 0.621371);
    parts.push(`wind ${mph} mph`);
  }
  if (f.weather_precip_mm != null && f.weather_precip_mm > 0) {
    parts.push(`${f.weather_precip_mm.toFixed(1)} mm precip`);
  }
  return parts.join(" · ");
}

const vehicles = useVehiclesStore();
const settings = useSettingsStore();
const vehicleId = computed(() => vehicles.selectedVehicleId);
const tab = ref<"fillups" | "map" | "stats">("fillups");

onMounted(() => {
  if (!settings.settings) void settings.fetchSettings();
});

// Map center: prefer the live "current location" override the user
// can set with the button, fall back to the home location from
// settings, fall back to NYC (MapLibreMap's own default) so the map
// still renders something.
const liveCenter = ref<[number, number] | null>(null);
const mapCenter = computed<[number, number] | null>(() => {
  if (liveCenter.value) return liveCenter.value;
  const home = settings.settings?.home;
  if (home && home.lat != null && home.lon != null) {
    return [home.lon, home.lat];
  }
  return null;
});

function useCurrentLocation() {
  if (!("geolocation" in navigator)) return;
  navigator.geolocation.getCurrentPosition(
    (pos) => {
      liveCenter.value = [pos.coords.longitude, pos.coords.latitude];
    },
    () => {
      /* permission denied / failed — keep falling back to home */
    },
    { enableHighAccuracy: false, timeout: 5_000, maximumAge: 60_000 },
  );
}

const limit = ref(50);
const offset = ref(0);

// Stats-tab user controls — persisted to localStorage so the
// view survives reload. Window applies to spend / cost-per-mile /
// fillup-history charts. Chart-visibility toggles are per-chart.
type StatsWindow = "30d" | "3m" | "12m" | "all";
const STATS_WINDOW_KEY = "pitstop_stats_window";
const STATS_CHARTS_KEY = "pitstop_stats_charts";

function loadStatsWindow(): StatsWindow {
  try {
    const v = localStorage.getItem(STATS_WINDOW_KEY) as StatsWindow | null;
    if (v === "30d" || v === "3m" || v === "12m" || v === "all") return v;
  } catch {
    /* ignore */
  }
  return "12m";
}
function loadVisible(): Record<string, boolean> {
  try {
    const raw = localStorage.getItem(STATS_CHARTS_KEY);
    if (raw) return { ...DEFAULT_VISIBLE, ...JSON.parse(raw) };
  } catch {
    /* ignore */
  }
  return { ...DEFAULT_VISIBLE };
}
const DEFAULT_VISIBLE: Record<string, boolean> = {
  kpis: true,
  monthly: true,
  cpm: true,
  overlay: true,
  range: true,
  ppg: true,
  freq: true,
  vol: true,
  mpgVsTemp: true,
};
const statsWindow = ref<StatsWindow>(loadStatsWindow());
const chartVisible = ref<Record<string, boolean>>(loadVisible());

watch(statsWindow, (v) => {
  try { localStorage.setItem(STATS_WINDOW_KEY, v); } catch { /* ignore */ }
});
watch(
  chartVisible,
  (v) => {
    try { localStorage.setItem(STATS_CHARTS_KEY, JSON.stringify(v)); } catch { /* ignore */ }
  },
  { deep: true },
);

const chartChoices = [
  { key: "kpis", label: "KPIs" },
  { key: "monthly", label: "Monthly spend" },
  { key: "cpm", label: "$/mile" },
  { key: "overlay", label: "OBD vs fillup" },
  { key: "range", label: "Range" },
  { key: "ppg", label: "$/gal trend" },
  { key: "freq", label: "Frequency" },
  { key: "vol", label: "Volume" },
  { key: "mpgVsTemp", label: "MPG vs temp" },
] as const;

const statsWindowMonths = computed<number>(() => {
  switch (statsWindow.value) {
    case "30d": return 1;
    case "3m": return 3;
    case "12m": return 12;
    // Backend monthlySpend caps months at 120 (10 years), which is
    // plenty for the user's Fuelio history. Anything above triggers a
    // 422 and the chart goes empty.
    case "all": return 120;
  }
});
const statsCutoffMs = computed<number | null>(() => {
  if (statsWindow.value === "all") return null;
  const days = { "30d": 30, "3m": 90, "12m": 365 }[statsWindow.value];
  return Date.now() - days * 86_400_000;
});

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

const stationPricesQ = useAsync(
  () =>
    vehicleId.value && tab.value === "map"
      ? api.stationPrices(vehicleId.value, 5)
      : Promise.resolve([]),
  [vehicleId, tab],
);

const monthlyQ = useAsync(
  () =>
    vehicleId.value && tab.value === "stats"
      ? api.monthlySpend(vehicleId.value, statsWindowMonths.value)
      : Promise.resolve({ months: [] }),
  [vehicleId, tab, statsWindow],
);

const cpmQ = useAsync(
  () =>
    vehicleId.value && tab.value === "stats"
      ? api.costPerMile(
          vehicleId.value,
          statsWindow.value === "30d"
            ? "month"
            : statsWindow.value === "3m"
              ? "3m"
              : statsWindow.value === "12m"
                ? "year"
                : "all",
        )
      : Promise.resolve({ points: [] }),
  [vehicleId, tab, statsWindow],
);

const overlayQ = useAsync(
  () =>
    vehicleId.value && tab.value === "stats"
      ? api.mpgOverlay(vehicleId.value)
      : Promise.resolve({ obd_mpg: [], fillup_mpg: [] }),
  [vehicleId, tab],
);

// Pull a wider window of fillups when the stats tab opens so the new
// $/gal trend and the fillup-frequency histogram have enough history
// to be meaningful even when the main fillups table is paginated.
const statsFillupsQ = useAsync(
  () =>
    vehicleId.value && tab.value === "stats"
      ? api.listFillups({ vehicle_id: vehicleId.value, limit: 500 })
      : Promise.resolve({ items: [], total: 0 }),
  [vehicleId, tab],
);

// Time-window-filtered slice of statsFillupsQ. Each chart consumes
// this rather than the raw list so the user's window selection
// applies uniformly without re-fetching.
const statsFillupsFiltered = computed<Fillup[]>(() => {
  const items = (statsFillupsQ.data.value?.items ?? []) as Fillup[];
  const cutoff = statsCutoffMs.value;
  if (cutoff == null) return items;
  return items.filter((f) => {
    const t = Date.parse(f.fillup_date ?? "");
    return Number.isFinite(t) && t >= cutoff;
  });
});

watch([vehicleId], () => {
  offset.value = 0;
});

// Sorting. Header click-targets keep the conceptual names (odometer, volume,
// total_price, mpg_recomputed) since the user reads them; the comparator
// translates each to the actual API field name.
type SortKey = "fillup_date" | "odometer" | "volume" | "total_price" | "mpg_recomputed";
const sortKey = ref<SortKey>("fillup_date");
const sortDir = ref<"asc" | "desc">("desc");

const SORT_FIELD: Record<SortKey, keyof Fillup> = {
  fillup_date: "fillup_date",
  odometer: "odo",
  volume: "fuel_volume",
  total_price: "price_total",
  mpg_recomputed: "mpg",
};

const sortedFillups = computed<Fillup[]>(() => {
  const items = [...(fillupsQ.data.value?.items ?? [])];
  const field = SORT_FIELD[sortKey.value];
  items.sort((a, b) => {
    const av = (a as Fillup)[field] ?? 0;
    const bv = (b as Fillup)[field] ?? 0;
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
    if (f.city) set.add(f.city);
  }
  return Array.from(set).sort();
});

// Stations markers
const stationMarkers = computed(() => {
  const cs = stationsQ.data.value ?? [];
  return cs
    .filter((c) => c.lat != null && c.lon != null)
    .map((c) => ({
      id: c.cluster_id,
      lng: c.lon!,
      lat: c.lat!,
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
    axes: [{ stroke: "#9aa0aa" }, { stroke: "#9aa0aa", label: "$" }],
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
    axes: [{ stroke: "#9aa0aa" }, { stroke: "#9aa0aa", label: "$/mi" }],
    series: [{}, { label: "$/mi", stroke: "#3fb950", width: 2 }],
  };
  return { aligned, opts };
});

const overlayChart = computed(() => {
  const obd = overlayQ.data.value?.obd_mpg ?? [];
  const fillup = overlayQ.data.value?.fillup_mpg ?? [];
  if (obd.length === 0 && fillup.length === 0) return null;
  const tsSet = new Set<number>();
  for (const p of obd) tsSet.add(Math.round((Date.parse(p.time) || 0) / 1000));
  for (const p of fillup) tsSet.add(Math.round((Date.parse(p.time) || 0) / 1000));
  const ts = Array.from(tsSet).sort((a, b) => a - b);
  const idx = new Map<number, number>();
  ts.forEach((t, i) => idx.set(t, i));
  const obdCol: (number | null)[] = new Array(ts.length).fill(null);
  const fillCol: (number | null)[] = new Array(ts.length).fill(null);
  for (const p of obd) {
    const i = idx.get(Math.round((Date.parse(p.time) || 0) / 1000));
    if (i != null) obdCol[i] = p.mpg ?? null;
  }
  for (const p of fillup) {
    const i = idx.get(Math.round((Date.parse(p.time) || 0) / 1000));
    if (i != null) fillCol[i] = p.mpg ?? null;
  }
  const aligned: uPlot.AlignedData = [ts, obdCol, fillCol];
  const opts: uPlot.Options = {
    width: 600,
    height: 220,
    scales: { x: { time: true } },
    axes: [{ stroke: "#9aa0aa" }, { stroke: "#9aa0aa", label: "mpg" }],
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
    .map((f) => f.mpg)
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

// ── New stats panels (#46): $/gal trend, fillup-frequency histogram,
//    tank-fill volume distribution, range-to-empty estimator. All four
//    derive client-side from the wide-window fillups query — no new
//    backend endpoints. Empty-data graceful: each computed returns
//    null when there isn't enough history for the chart to be useful.

// $/gal time series — line chart of price_per_unit by fillup date.
//
// price_per_unit is Decimal serialised as a JSON string. Coerce here.
const ppgChart = computed(() => {
  const items = (statsFillupsFiltered.value as Fillup[])
    .map((f) => {
      const ppgRaw = f.price_per_unit;
      const ppg =
        typeof ppgRaw === "number"
          ? ppgRaw
          : typeof ppgRaw === "string" && ppgRaw.length > 0
            ? Number(ppgRaw)
            : null;
      return { ppg, fillup_date: f.fillup_date };
    })
    .filter(
      (x): x is { ppg: number; fillup_date: string } =>
        x.ppg != null && Number.isFinite(x.ppg) && x.ppg > 0 && !!x.fillup_date,
    )
    .sort(
      (a, b) =>
        (Date.parse(a.fillup_date) || 0) - (Date.parse(b.fillup_date) || 0),
    );
  if (items.length < 2) return null;
  const t = items.map((f) => Math.round((Date.parse(f.fillup_date) || 0) / 1000));
  const y = items.map((f) => f.ppg);
  const aligned: uPlot.AlignedData = [t, y];
  const opts: uPlot.Options = {
    width: 600,
    height: 200,
    scales: { x: { time: true } },
    axes: [
      { stroke: "#9aa0aa" },
      { stroke: "#9aa0aa", label: "$/gal" },
    ],
    series: [{}, { label: "$/gal", stroke: "var(--c-accent)", width: 1.5 }],
  };
  return { aligned, opts };
});

// Days-between-fillups histogram. Bins: 0-3, 4-7, 8-14, 15-21, 22-30, 31+
const frequencyChart = computed(() => {
  const items = (statsFillupsFiltered.value as Fillup[])
    .filter((f) => !!f.fillup_date)
    .sort(
      (a, b) =>
        (Date.parse(a.fillup_date!) || 0) - (Date.parse(b.fillup_date!) || 0),
    );
  if (items.length < 2) return null;

  const bins = [0, 0, 0, 0, 0, 0]; // 0-3, 4-7, 8-14, 15-21, 22-30, 31+
  for (let i = 1; i < items.length; i++) {
    const a = Date.parse(items[i - 1].fillup_date!) || 0;
    const b = Date.parse(items[i].fillup_date!) || 0;
    const days = Math.max(0, Math.round((b - a) / 86_400_000));
    const bucket =
      days <= 3 ? 0 :
      days <= 7 ? 1 :
      days <= 14 ? 2 :
      days <= 21 ? 3 :
      days <= 30 ? 4 : 5;
    bins[bucket]++;
  }
  // uPlot bar chart needs paired series; we hand-make it as a step plot.
  const labels = ["0-3d", "4-7d", "8-14d", "15-21d", "22-30d", "31+d"];
  const x = bins.map((_, i) => i);
  const aligned: uPlot.AlignedData = [x, bins];
  const opts: uPlot.Options = {
    width: 600,
    height: 200,
    scales: { x: { time: false } },
    axes: [
      {
        stroke: "#9aa0aa",
        values: (_u, vals) => vals.map((v) => labels[v as number] ?? ""),
      },
      { stroke: "#9aa0aa", label: "fillups" },
    ],
    series: [
      {},
      {
        label: "Fillups",
        stroke: "var(--c-accent)",
        width: 0,
        fill: "rgba(255,91,58,0.55)",
        paths: (_u, _seriesIdx, idx0, idx1) => {
          const path = new Path2D();
          // We render the bar shape ourselves via uPlot's clip; this
          // is a compact approximation good enough for the histogram.
          for (let i = idx0; i <= idx1; i++) {
            const xv = _u.valToPos(i, "x", true);
            const yv = _u.valToPos(bins[i], "y", true);
            const yz = _u.valToPos(0, "y", true);
            const w = 38;
            path.rect(xv - w / 2, yv, w, yz - yv);
          }
          return { stroke: path, fill: path };
        },
      },
    ],
  };
  return { aligned, opts };
});

// Tank-fill volume distribution histogram. Bins are 5L wide.
const volumeDistChart = computed(() => {
  const items = (statsFillupsFiltered.value as Fillup[])
    .map((f) => f.fuel_volume)
    .filter((v): v is number => typeof v === "number" && v > 0);
  if (items.length < 4) return null;

  // Auto-pick bin width from data range.
  const max = Math.max(...items);
  const min = Math.min(...items);
  const span = max - min;
  const binW = span > 30 ? 5 : span > 10 ? 2 : 1;
  const bins: Map<number, number> = new Map();
  for (const v of items) {
    const b = Math.floor(v / binW) * binW;
    bins.set(b, (bins.get(b) ?? 0) + 1);
  }
  const sorted = [...bins.entries()].sort((a, b) => a[0] - b[0]);
  const x = sorted.map(([k]) => k);
  const y = sorted.map(([, v]) => v);
  const aligned: uPlot.AlignedData = [x, y];
  const opts: uPlot.Options = {
    width: 600,
    height: 200,
    scales: { x: { time: false } },
    axes: [
      { stroke: "#9aa0aa", label: "Volume (gal)" },
      { stroke: "#9aa0aa", label: "fillups" },
    ],
    series: [
      {},
      {
        label: "Fillups",
        stroke: "var(--c-accent)",
        width: 0,
        fill: "rgba(255,91,58,0.55)",
        paths: (_u, _seriesIdx, idx0, idx1) => {
          const path = new Path2D();
          for (let i = idx0; i <= idx1; i++) {
            const xv = _u.valToPos(x[i], "x", true);
            const yv = _u.valToPos(y[i], "y", true);
            const yz = _u.valToPos(0, "y", true);
            const w = 24;
            path.rect(xv - w / 2, yv, w, yz - yv);
          }
          return { stroke: path, fill: path };
        },
      },
    ],
  };
  return { aligned, opts };
});

// Range-to-empty estimator. Needs current OBD fuel level (0..100) +
// vehicle.tank1_capacity (litres) + rolling MPG from existing data.
const rangeEstimate = computed<{
  miles: number | null;
  fuelLevel: number | null;
  rollingMpg: number | null;
} | null>(() => {
  const veh = vehicles.selectedVehicle;
  if (!veh) return null;
  const tankL = veh.tank1_capacity;
  if (!tankL || tankL <= 0) return null;
  const latest = (veh.latest ?? {}) as Record<string, unknown>;
  const lvlRaw = latest["fuel_level"];
  const fuelLevel =
    typeof lvlRaw === "number"
      ? lvlRaw
      : typeof lvlRaw === "string"
        ? Number(lvlRaw) || null
        : null;
  if (fuelLevel == null) return { miles: null, fuelLevel: null, rollingMpg: summary.value.avgMpg };
  // MPG over last 6 fillups
  const mpg = summary.value.avgMpg;
  if (mpg == null || mpg <= 0) {
    return { miles: null, fuelLevel, rollingMpg: null };
  }
  const tankGal = tankL * 0.264172;
  const remainingGal = tankGal * (fuelLevel / 100);
  const miles = remainingGal * mpg;
  return { miles, fuelLevel, rollingMpg: mpg };
});

// MPG vs temperature: bin fillups by ambient temp at fillup time
// (10 °F buckets) and chart the average recomputed MPG per bucket.
// Falls out of the v0.1.91 weather plumbing — answers "how much
// does cold weather hurt my MPG" directly. Needs at least 4 fillups
// with both weather_temp_c and a recomputed mpg.
const mpgVsTempChart = computed(() => {
  const items = (statsFillupsFiltered.value as Fillup[])
    .filter((f) => f.weather_temp_c != null && f.mpg != null && (f.mpg as number) > 0);
  if (items.length < 4) return null;
  // Group into 10°F buckets centred on each integer label.
  const C_TO_F = (c: number) => (c * 9) / 5 + 32;
  const buckets = new Map<number, number[]>();
  for (const f of items) {
    const fF = C_TO_F(f.weather_temp_c as number);
    const bucket = Math.round(fF / 10) * 10;
    const arr = buckets.get(bucket) ?? [];
    arr.push(f.mpg as number);
    buckets.set(bucket, arr);
  }
  const bins = Array.from(buckets.keys()).sort((a, b) => a - b);
  const xs = bins.map((b) => b);
  const ys = bins.map((b) => {
    const arr = buckets.get(b)!;
    return arr.reduce((s, v) => s + v, 0) / arr.length;
  });
  const aligned: uPlot.AlignedData = [xs, ys];
  const opts: uPlot.Options = {
    width: 600,
    height: 220,
    cursor: { drag: { x: false, y: false, setScale: false } },
    legend: { show: false },
    scales: { x: { time: false }, y: {} },
    axes: [
      { stroke: "#9aa0aa", label: "°F" },
      { stroke: "#9aa0aa", label: "mpg" },
    ],
    series: [
      {},
      {
        label: "Avg MPG",
        stroke: "#3fb950",
        fill: "rgba(63,185,80,0.45)",
        width: 1.5,
        paths: (_u, sIdx, i0, i1) => {
          const path = new Path2D();
          for (let i = i0; i <= i1; i++) {
            const xv = _u.valToPos(xs[i], "x", true);
            const yv = _u.valToPos(ys[i], "y", true);
            const yz = _u.valToPos(0, "y", true);
            const w = 28;
            path.rect(xv - w / 2, yv, w, yz - yv);
          }
          return { stroke: path, fill: path };
        },
      },
    ],
  };
  return { aligned, opts, bucketCount: bins.length };
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
                <th title="Conditions at fillup time">Wx</th>
                <th class="sortable" @click="changeSort('mpg_recomputed')">
                  MPG <small class="muted">(reported)</small>
                </th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="f in sortedFillups" :key="f.id">
                <td>{{ fmtDate(f.fillup_date) }}</td>
                <td>{{ fmtOdo(f.odo) }}</td>
                <td>
                  {{ fmtGallons(f.fuel_volume) }}
                  <span v-if="f.is_missed" class="badge warn" title="Missed">M</span>
                  <span v-if="f.is_full === false" class="badge warn" title="Partial fill">P</span>
                </td>
                <td>
                  {{ f.price_total != null
                      ? fmtMoney(Number(f.price_total))
                      : fmtMoney(
                          (Number(f.price_per_unit) || 0) * (f.fuel_volume ?? 0)
                        )
                  }}
                </td>
                <td>{{ f.station_id ?? "—" }}</td>
                <td :title="weatherTitle(f)">
                  <span v-if="f.weather_temp_c != null">
                    {{ wxIcon(f.weather_code) }}
                    {{ Math.round(f.weather_temp_c * 9 / 5 + 32) }}°
                  </span>
                  <span v-else class="muted">—</span>
                </td>
                <td>
                  <!--
                    MPG cell: prefer the API's recomputed value (handles
                    chain rule across partial/missed fillups). When that
                    is null (first row of a chain, partial preceded by a
                    missed, exclude_distance set, etc.), fall back to
                    the user-reported value Fuelio carried in the CSV
                    so the user always sees a number when one exists.
                    Fallback is rendered italic + muted so the user can
                    tell which source the cell is from.
                  -->
                  <strong v-if="f.mpg != null">{{ fmtMpg(f.mpg) }}</strong>
                  <span
                    v-else-if="f.mpg_reported != null && f.mpg_reported > 0"
                    class="mpg-fallback"
                    title="Recomputed value unavailable; showing the value Fuelio recorded"
                  >
                    {{ fmtMpg(f.mpg_reported) }}
                  </span>
                  <span v-else class="muted">—</span>
                  <span
                    v-if="f.mpg != null && f.mpg_reported != null && f.mpg_reported > 0"
                    class="muted small"
                  >
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
          <div class="map-actions">
            <button class="btn ghost" @click="useCurrentLocation">
              Use current location
            </button>
            <span v-if="liveCenter" class="muted small">
              Centered on your live position
            </span>
            <span v-else-if="mapCenter" class="muted small">
              Centered on home location
            </span>
          </div>
          <div class="card no-pad map-card">
            <MapLibreMap
              :markers="stationMarkers"
              :height="480"
              :initial-center="mapCenter ?? undefined"
              :initial-zoom="11"
              @marker-click="onMarkerClick"
            />
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

        <!-- Per-station price intelligence — derived from your own
             fillup history. Sortable by latest price, last visit, or
             fillup count. Map shows only stations with GPS; this list
             shows every cluster (including station_id-only entries
             that lack coords). -->
        <section
          v-if="stationPricesQ.data.value && stationPricesQ.data.value.length > 0"
          class="card"
        >
          <h3>Recent station prices</h3>
          <p class="muted small">
            Last {{ Math.max(...stationPricesQ.data.value.map(s => s.recent.length)) }}
            visits per station. Delta column compares the most recent
            price against the average of the others before it.
          </p>
          <table class="data station-prices">
            <thead>
              <tr>
                <th>Station</th>
                <th class="num">Latest $/gal</th>
                <th class="num">Δ vs prev avg</th>
                <th class="num">Avg</th>
                <th class="num">Visits</th>
                <th>Last visit</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="s in stationPricesQ.data.value" :key="s.cluster_id">
                <td>{{ s.name ?? "Unnamed station" }}</td>
                <td class="num">${{ s.latest_price.toFixed(3) }}</td>
                <td
                  class="num"
                  :class="{
                    'delta-up': (s.delta_pct ?? 0) > 0.5,
                    'delta-down': (s.delta_pct ?? 0) < -0.5,
                  }"
                >
                  <template v-if="s.delta_pct != null">
                    {{ s.delta_pct > 0 ? '▲' : s.delta_pct < 0 ? '▼' : '·' }}
                    {{ Math.abs(s.delta_pct).toFixed(1) }}%
                  </template>
                  <template v-else>—</template>
                </td>
                <td class="num">${{ s.avg_price.toFixed(3) }}</td>
                <td class="num">{{ s.fillup_count }}</td>
                <td>{{ fmtDate(s.latest_date) }}</td>
              </tr>
            </tbody>
          </table>
        </section>
      </template>

      <!-- Stats -->
      <template v-else>
        <div class="stats-controls">
          <div class="control-row">
            <span class="muted small">Window:</span>
            <button
              v-for="w in (['30d', '3m', '12m', 'all'] as const)"
              :key="w"
              class="chip"
              :class="{ active: statsWindow === w }"
              @click="statsWindow = w"
            >{{ { '30d': '30 days', '3m': '3 months', '12m': '12 months', 'all': 'All time' }[w] }}</button>
          </div>
          <div class="control-row">
            <span class="muted small">Charts:</span>
            <button
              v-for="c in chartChoices"
              :key="c.key"
              class="chip"
              :class="{ active: chartVisible[c.key] }"
              @click="chartVisible[c.key] = !chartVisible[c.key]"
            >{{ c.label }}</button>
          </div>
        </div>
        <div class="grid stats">
          <template v-if="chartVisible.kpis">
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
          </template>
          <div v-if="chartVisible.monthly" class="card chart-card">
            <h3>Monthly spend</h3>
            <div v-if="monthlyQ.loading.value" class="muted">Loading…</div>
            <div v-else-if="!monthlyChart" class="muted">No data.</div>
            <UPlotChart v-else :data="monthlyChart.aligned" :options="monthlyChart.opts" />
          </div>
          <div v-if="chartVisible.cpm" class="card chart-card">
            <h3>$/mile</h3>
            <div v-if="cpmQ.loading.value" class="muted">Loading…</div>
            <div v-else-if="!cpmChart" class="muted">No data.</div>
            <UPlotChart v-else :data="cpmChart.aligned" :options="cpmChart.opts" />
          </div>
          <div v-if="chartVisible.overlay" class="card chart-card wide">
            <h3>OBD vs fillup MPG</h3>
            <div v-if="overlayQ.loading.value" class="muted">Loading…</div>
            <div v-else-if="!overlayChart" class="muted">
              No OBD data yet — drive with the WiCAN connected to populate this chart.
            </div>
            <UPlotChart v-else :data="overlayChart.aligned" :options="overlayChart.opts" />
          </div>

          <!-- Range-to-empty KPI: depends on tank1_capacity + live fuel
               level. When either is missing, surfaces a hint. -->
          <div v-if="chartVisible.range" class="card kpi">
            <h3>Range to empty</h3>
            <div v-if="!rangeEstimate" class="muted small">
              Set tank capacity on the vehicle first.
            </div>
            <template v-else-if="rangeEstimate.miles != null">
              <div class="big">{{ rangeEstimate.miles.toFixed(0) }} mi</div>
              <div class="muted small">
                {{ rangeEstimate.fuelLevel?.toFixed(0) }}% fuel ·
                {{ rangeEstimate.rollingMpg?.toFixed(1) }} mpg avg
              </div>
            </template>
            <template v-else>
              <div class="big">—</div>
              <div class="muted small">
                {{ rangeEstimate.fuelLevel == null
                  ? 'No live fuel reading yet'
                  : 'Need more fillups for MPG estimate' }}
              </div>
            </template>
          </div>

          <div v-if="chartVisible.ppg" class="card chart-card">
            <h3>$/gallon trend</h3>
            <div v-if="statsFillupsQ.loading.value" class="muted">Loading…</div>
            <div v-else-if="!ppgChart" class="muted">No price data.</div>
            <UPlotChart v-else :data="ppgChart.aligned" :options="ppgChart.opts" />
          </div>

          <div v-if="chartVisible.freq" class="card chart-card">
            <h3>Fillup frequency</h3>
            <div v-if="statsFillupsQ.loading.value" class="muted">Loading…</div>
            <div v-else-if="!frequencyChart" class="muted">
              Need at least 2 fillups for a frequency histogram.
            </div>
            <UPlotChart v-else :data="frequencyChart.aligned" :options="frequencyChart.opts" />
          </div>

          <div v-if="chartVisible.vol" class="card chart-card">
            <h3>Volume per fill</h3>
            <div v-if="statsFillupsQ.loading.value" class="muted">Loading…</div>
            <div v-else-if="!volumeDistChart" class="muted">
              Need at least 4 fillups for a distribution.
            </div>
            <UPlotChart v-else :data="volumeDistChart.aligned" :options="volumeDistChart.opts" />
          </div>

          <div v-if="chartVisible.mpgVsTemp" class="card chart-card">
            <h3>MPG by temperature</h3>
            <div v-if="statsFillupsQ.loading.value" class="muted">Loading…</div>
            <div v-else-if="!mpgVsTempChart" class="muted">
              Need at least 4 fillups with weather data — backfill is in progress.
            </div>
            <template v-else>
              <UPlotChart :data="mpgVsTempChart.aligned" :options="mpgVsTempChart.opts" />
              <p class="muted small">
                Average recomputed MPG grouped into 10°F buckets across
                {{ mpgVsTempChart.bucketCount }} temperature ranges.
              </p>
            </template>
          </div>
        </div>
      </template>
    </template>

    <FillupModal
      v-if="showModal && vehicles.selectedVehicle"
      :vehicle="vehicles.selectedVehicle"
      :initial="editing"
      :station-suggestions="stationSuggestions"
      :last-fillup-odo="fillupsQ.data.value?.items?.[0]?.odo ?? null"
      :last-fillup-date="fillupsQ.data.value?.items?.[0]?.fillup_date ?? null"
      :all-vehicles="vehicles.vehicles"
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
.mpg-fallback {
  font-style: italic;
  color: var(--c-ink2);
}
.station-prices .delta-up {
  color: var(--c-danger);
}
.station-prices .delta-down {
  color: var(--c-success);
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
.stats-controls {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  margin-bottom: 0.6rem;
}
.control-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.4rem;
}
.chip {
  padding: 0.32rem 0.7rem;
  font-size: 0.78rem;
  border-radius: 999px;
  border: 1px solid var(--c-border-soft);
  background: var(--c-surface);
  color: var(--c-muted);
  cursor: pointer;
}
.chip:hover {
  background: var(--c-surface-2);
}
.chip.active {
  background: var(--c-accent-tint);
  color: var(--c-ink0);
  border-color: var(--c-accent);
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
  color: #9aa0aa;
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
