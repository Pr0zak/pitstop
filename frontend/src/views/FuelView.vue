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
  fmtWhen,
  dateGroupFor,
  DATE_GROUP_LABEL,
  DATE_GROUP_ORDER,
  type DateGroupKey,
  fmtMpg,
  fmtMoney,
  fmtOdo,
  fmtVolume,
  fmtPricePerVolume,
  fmtDistance,
  fmtTempC,
  fmtWindKph,
  nf,
  toNum,
  convDistance,
  convVolume,
  convPricePerVolume,
  convEconomyMpg,
  convTempC,
  economyUnitLabel,
  volUnitLabel,
  distUnitLabel,
  tempUnitLabel,
  vehicleDistUnit,
  vehicleVolUnit,
} from "@/composables/useFormat";
import { Plus, Pencil, X, Upload } from "lucide-vue-next";
import FillupModal from "@/components/FillupModal.vue";
import ConfirmDialog from "@/components/ConfirmDialog.vue";
import StateCard from "@/components/StateCard.vue";
import WindowChips from "@/components/WindowChips.vue";
import { useQueryParam } from "@/composables/useQueryParam";
import { chartColors, chartPalette, withAlpha } from "@/lib/chartTheme";
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
    parts.push(`wind ${fmtWindKph(f.weather_wind_kph)}`);
  }
  if (f.weather_precip_mm != null && f.weather_precip_mm > 0) {
    parts.push(`${f.weather_precip_mm.toFixed(1)} mm precip`);
  }
  return parts.join(" · ");
}

const vehicles = useVehiclesStore();
const settings = useSettingsStore();
const vehicleId = computed(() => vehicles.selectedVehicleId);

// uPlot draws to <canvas>, which can't resolve CSS custom properties, so the
// chart colours come from lib/chartTheme (tokens read via getComputedStyle).
const C = chartColors();
const PAL = chartPalette();
// Chart series colours come from the categorical palette — the coral accent
// is reserved for primary actions / selection state.
const PRICE_COLOR = PAL[2];
const HIST_COLOR = PAL[5];
void C;
// Tab lives in ?tab= so a link / reload lands on the same view.
const tab = useQueryParam<"fillups" | "map" | "stats">("tab", "fillups", ["fillups", "map", "stats"]);

// Fillup numbers are stored in the vehicle's own units.
const distSrc = computed(() => vehicleDistUnit(vehicles.selectedVehicle));
const volSrc = computed(() => vehicleVolUnit(vehicles.selectedVehicle));

/** Per-fillup total — price_total, or unit price × volume when missing. */
function fillupTotal(f: Fillup): number | null {
  const t = toNum(f.price_total);
  if (t != null) return t;
  const p = toNum(f.price_per_unit);
  const v = toNum(f.fuel_volume);
  return p != null && v != null ? p * v : null;
}
/** Per-fillup unit price — price_per_unit, or total ÷ volume when missing. */
function fillupPpu(f: Fillup): number | null {
  const p = toNum(f.price_per_unit);
  if (p != null && p > 0) return p;
  const t = toNum(f.price_total);
  const v = toNum(f.fuel_volume);
  return t != null && v != null && v > 0 ? t / v : null;
}

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
const STATS_WINDOWS = ["30d", "3m", "12m", "all"] as const;
const STATS_WINDOW_OPTIONS = [
  { value: "30d" as const, label: "30 days" },
  { value: "3m" as const, label: "3 months" },
  { value: "12m" as const, label: "12 months" },
  { value: "all" as const, label: "All time" },
];
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
// ?window= wins; otherwise the last choice from localStorage.
const statsWindowParam = useQueryParam<StatsWindow>("window", loadStatsWindow(), STATS_WINDOWS);
const statsWindow = statsWindowParam;
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
  { key: "cpm", label: "Cost / distance" },
  { key: "overlay", label: "OBD vs fillup" },
  { key: "range", label: "Range" },
  { key: "ppg", label: "Price trend" },
  { key: "freq", label: "Frequency" },
  { key: "vol", label: "Volume" },
  { key: "mpgVsTemp", label: "Economy vs temp" },
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

// Fetch 36 months minimum so the YoY ghost-line lookups (Task #84)
// always have prior-year data even when the display window is just
// 30 days. Backend caps at 120; 36 is well within.
const monthlyQ = useAsync(
  () =>
    vehicleId.value && tab.value === "stats"
      ? api.monthlySpend(
          vehicleId.value,
          Math.max(statsWindowMonths.value, 36),
        )
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
type SortKey = "fillup_date" | "odometer" | "volume" | "total_price" | "unit_price" | "mpg_recomputed";
const sortKey = ref<SortKey>("fillup_date");
const sortDir = ref<"asc" | "desc">("desc");

const SORT_FIELD: Record<SortKey, keyof Fillup> = {
  fillup_date: "fillup_date",
  odometer: "odo",
  volume: "fuel_volume",
  total_price: "price_total",
  unit_price: "price_per_unit",
  mpg_recomputed: "mpg",
};

const sortedFillups = computed<Fillup[]>(() => {
  const items = [...(fillupsQ.data.value?.items ?? [])];
  const field = SORT_FIELD[sortKey.value];
  // Only the date column compares as a string (ISO timestamps sort
  // lexicographically). Every other column is numeric — including
  // price_total / fuel_volume, which arrive Decimal-serialised as JSON
  // strings, so "9.80" would lexically sort above "100.00" if compared
  // as text. Coerce to Number before comparing those.
  const asString = sortKey.value === "fillup_date";
  items.sort((a, b) => {
    const av = (a as Fillup)[field] ?? (asString ? "" : 0);
    const bv = (b as Fillup)[field] ?? (asString ? "" : 0);
    if (asString) {
      return sortDir.value === "asc"
        ? String(av).localeCompare(String(bv))
        : String(bv).localeCompare(String(av));
    }
    const ax = sortKey.value === "unit_price" ? (fillupPpu(a) ?? 0) : Number(av);
    const bx = sortKey.value === "unit_price" ? (fillupPpu(b) ?? 0) : Number(bv);
    return sortDir.value === "asc" ? ax - bx : bx - ax;
  });
  return items;
});

function ariaSort(k: SortKey): "ascending" | "descending" | "none" {
  if (sortKey.value !== k) return "none";
  return sortDir.value === "asc" ? "ascending" : "descending";
}
function sortArrow(k: SortKey): string {
  if (sortKey.value !== k) return "";
  return sortDir.value === "asc" ? "▲" : "▼";
}
function changeSort(k: SortKey) {
  if (sortKey.value === k) {
    sortDir.value = sortDir.value === "asc" ? "desc" : "asc";
  } else {
    sortKey.value = k;
    sortDir.value = "desc";
  }
}

// Full/partial filter + date-bucket grouping (FILLUPS-2). Mirrors the
// pattern in TripsView: filter chips above the table, sections per
// relative-date bucket. Sort still controlled by clicking column
// headers — those operations are independent.
type FillupFilter = "all" | "full" | "partial";
const fillupFilter = ref<FillupFilter>("all");

const groupedFillups = computed<Array<{ key: DateGroupKey; label: string; items: Fillup[] }>>(() => {
  const items = sortedFillups.value.filter((f) => {
    if (fillupFilter.value === "all") return true;
    if (fillupFilter.value === "full") return f.is_full !== false;
    return f.is_full === false;
  });
  const byKey = new Map<DateGroupKey, Fillup[]>();
  for (const f of items) {
    const k = dateGroupFor(f.fillup_date);
    let list = byKey.get(k);
    if (!list) {
      list = [];
      byKey.set(k, list);
    }
    list.push(f);
  }
  return DATE_GROUP_ORDER
    .map((k) => ({ key: k, label: DATE_GROUP_LABEL[k], items: byKey.get(k) ?? [] }))
    .filter((g) => g.items.length > 0);
});

/** Row date, group-aware: a fillup under "Today" doesn't repeat "Today". */
function rowDate(f: Fillup): string {
  return fmtWhen(f.fillup_date, { grouped: true });
}

/**
 * Fuelio's own MPG differs from the recomputed chain value (≥ 0.1 in the
 * display unit) — only then is the parenthetical worth showing, and it is
 * styled as a flagged mismatch. Equal values used to render "19.4 mpg (19.4)".
 */
function reportedMismatch(f: Fillup): string | null {
  if (f.mpg == null || f.mpg_reported == null || f.mpg_reported <= 0) return null;
  const a = convEconomyMpg(f.mpg);
  const b = convEconomyMpg(f.mpg_reported);
  return Math.abs(a - b) >= 0.1 ? nf(1).format(b) : null;
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
  void vehicles.fetchVehicles().catch(() => {});
}

// Delete fillup — in-app confirm (ConfirmDialog) instead of native
// window.confirm/alert so it matches the dark theme.
const deleteTarget = ref<Fillup | null>(null);
const deleteBusy = ref(false);
const deleteError = ref<string | null>(null);
function requestRemove(f: Fillup) {
  deleteTarget.value = f;
  deleteError.value = null;
}
async function confirmRemove() {
  const f = deleteTarget.value;
  if (!f) return;
  deleteBusy.value = true;
  deleteError.value = null;
  try {
    await api.deleteFillup(f.id);
    deleteTarget.value = null;
    await fillupsQ.reload();
  } catch (e: unknown) {
    deleteError.value = e instanceof Error ? e.message : "delete failed";
  } finally {
    deleteBusy.value = false;
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

// Stats charts. Monthly chart layers YoY ghost lines (Task #84)
// underneath the primary series — same-calendar-month value from
// 1y / 2y ago, projected onto the primary x-axis so users can see
// "is this worse than last winter, or just normal?" at a glance.
// The monthly chart's series count varies with the data (0–2 YoY ghost
// lines), so opts and data must be built together to stay in sync. A shared
// build computed does that; the exposed opts/data computeds slice out of it.
// Opts identity still changes when the ghost-line set changes (window switch
// that reveals/hides prior-year data) — correct and infrequent — but a pure
// data refresh with the same ghost set keeps opts stable and hits setData().
const monthlyBuild = computed<{ aligned: uPlot.AlignedData; opts: uPlot.Options } | null>(() => {
  const allMonths = monthlyQ.data.value?.months ?? [];
  if (allMonths.length === 0) return null;
  // Primary slice: respect the page's window (statsCutoffMs) when
  // set; otherwise take the most recent 12 so the chart isn't a
  // wall of 36 rows.
  const cutoff = statsCutoffMs.value;
  const primary = cutoff != null
    ? allMonths.filter((m) => Date.parse(m.month) >= cutoff)
    : allMonths.slice(-Math.max(12, statsWindowMonths.value));
  if (primary.length === 0) return null;
  // Build a (year-month) → totals map from the full 36-month
  // history so we can look up prior-year same-month values.
  const byYM = new Map<string, { fuel: number; service: number }>();
  for (const m of allMonths) {
    const d = new Date(m.month);
    byYM.set(
      `${d.getUTCFullYear()}-${d.getUTCMonth()}`,
      { fuel: m.fuel ?? 0, service: m.service ?? 0 },
    );
  }
  const lookupYoY = (m: typeof allMonths[0], yearsBack: number): number | null => {
    const d = new Date(m.month);
    const prev = byYM.get(`${d.getUTCFullYear() - yearsBack}-${d.getUTCMonth()}`);
    return prev ? prev.fuel + prev.service : null;
  };
  const t = primary.map((m) => Math.round((Date.parse(m.month) || 0) / 1000));
  const fuel = primary.map((m) => m.fuel ?? 0);
  const service = primary.map((m) => m.service ?? 0);
  const ghost1 = primary.map((m) => lookupYoY(m, 1));
  const ghost2 = primary.map((m) => lookupYoY(m, 2));
  const hasGhost1 = ghost1.some((v) => v != null);
  const hasGhost2 = ghost2.some((v) => v != null);

  const aligned: uPlot.AlignedData = hasGhost2
    ? [t, fuel, service, ghost1, ghost2]
    : hasGhost1
      ? [t, fuel, service, ghost1]
      : [t, fuel, service];
  const series: uPlot.Series[] = [
    {},
    { label: "Fuel", stroke: PAL[0], fill: withAlpha(PAL[0], 0.18), width: 1.5 },
    { label: "Service", stroke: PAL[2], fill: withAlpha(PAL[2], 0.18), width: 1.5 },
  ];
  if (hasGhost1) {
    series.push({
      label: "1y ago (total)",
      stroke: "rgba(154,160,170,0.55)",
      width: 1,
      dash: [3, 3],
    });
  }
  if (hasGhost2) {
    series.push({
      label: "2y ago (total)",
      stroke: "rgba(154,160,170,0.32)",
      width: 1,
      dash: [3, 3],
    });
  }
  return {
    aligned,
    opts: {
      width: 600,
      height: 220,
      scales: { x: { time: true } },
      axes: [{}, { label: "$", values: (_u, v) => v.map((x) => fmtMoney(x, 0)) }],
      series,
    },
  };
});
const monthlyData = computed<uPlot.AlignedData | null>(() => monthlyBuild.value?.aligned ?? null);
const monthlyOpts = computed<uPlot.Options | null>(() => monthlyBuild.value?.opts ?? null);

const cpmOpts = computed<uPlot.Options>(() => ({
  width: 600,
  height: 200,
  scales: { x: { time: true } },
  axes: [{}, { label: `$/${distUnitLabel()}` }],
  series: [{}, { label: `$/${distUnitLabel()}`, stroke: PAL[1], width: 2 }],
}));
const cpmData = computed<uPlot.AlignedData | null>(() => {
  const points = cpmQ.data.value?.points ?? [];
  if (points.length === 0) return null;
  const t = points.map((p) => Math.round((Date.parse(p.period) || 0) / 1000));
  // cost_per_mi is $ per MILE; per-km for metric = ÷ 1.609.
  const perUnit = convDistance(1, "mi");
  const y = points.map((p) => (p.cost_per_mi != null ? p.cost_per_mi / perUnit : null));
  return [t, y];
});

const overlayOpts = computed<uPlot.Options>(() => ({
  width: 600,
  height: 220,
  scales: { x: { time: true } },
  axes: [{}, { label: economyUnitLabel() }],
  series: [
    {},
    { label: "OBD (per trip)", stroke: PAL[1], width: 1.5 },
    { label: "Fillup", stroke: PAL[0], width: 1.5, dash: [4, 3] },
  ],
}));
const overlayData = computed<uPlot.AlignedData | null>(() => {
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
    if (i != null) obdCol[i] = p.mpg != null ? convEconomyMpg(p.mpg) : null;
  }
  for (const p of fillup) {
    const i = idx.get(Math.round((Date.parse(p.time) || 0) / 1000));
    if (i != null) fillCol[i] = p.mpg != null ? convEconomyMpg(p.mpg) : null;
  }
  return [ts, obdCol, fillCol];
});

const summary = computed(() => {
  const items = fillupsQ.data.value?.items ?? [];
  const recent = items.slice(0, 6);
  const mpgs = recent
    .map((f) => f.mpg)
    .filter((v): v is number => typeof v === "number");
  const avgMpg = mpgs.length ? mpgs.reduce((a, b) => a + b, 0) / mpgs.length : null;
  // monthlyQ deliberately over-fetches (max(window,36), up to 120 for "all")
  // so the YoY ghost lines have prior-year data. The headline spend must only
  // sum the SELECTED window, not the whole over-fetched history. Filter to the
  // stats cutoff; when the window is "all", sum everything.
  const allMonths = monthlyQ.data.value?.months ?? [];
  const cutoff = statsCutoffMs.value;
  const windowMonths =
    cutoff != null
      ? allMonths.filter((m) => (Date.parse(m.month) || 0) >= cutoff)
      : allMonths;
  const totalSpendWindow = windowMonths.reduce((sum, m) => sum + (m.total ?? 0), 0);
  const totalMiles = (cpmQ.data.value?.points ?? []).reduce(
    (sum, p) => sum + (p.miles ?? 0),
    0,
  );
  return {
    avgMpg,
    totalSpendWindow,
    totalMiles,
  };
});

// Label for the spend KPI tracks the selected window.
const spendWindowLabel = computed<string>(
  () =>
    ({ "30d": "30 days", "3m": "3 months", "12m": "12 months", all: "all time" })[
      statsWindow.value
    ],
);

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
  const y = items.map((f) => convPricePerVolume(f.ppg, volSrc.value));
  const aligned: uPlot.AlignedData = [t, y];
  const opts: uPlot.Options = {
    width: 600,
    height: 200,
    scales: { x: { time: true } },
    axes: [{}, { label: `$/${volUnitLabel()}` }],
    series: [{}, { label: `$/${volUnitLabel()}`, stroke: PRICE_COLOR, width: 1.5 }],
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
      { values: (_u, vals) => vals.map((v) => labels[v as number] ?? "") },
      { label: "fillups" },
    ],
    series: [
      {},
      {
        label: "Fillups",
        stroke: HIST_COLOR,
        width: 0,
        fill: withAlpha(HIST_COLOR, 0.55),
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
    .map((f) => toNum(f.fuel_volume))
    .filter((v): v is number => v != null && v > 0)
    .map((v) => convVolume(v, volSrc.value));
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
      { label: `Volume (${volUnitLabel()})` },
      { label: "fillups" },
    ],
    series: [
      {},
      {
        label: "Fillups",
        stroke: HIST_COLOR,
        width: 0,
        fill: withAlpha(HIST_COLOR, 0.55),
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
  // tank1_capacity is in the vehicle's fuel unit; → US gal for mpg math.
  const tankGal = convVolume(convVolume(tankL, volSrc.value, "metric"), "L", "imperial");
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
  // Group into 10° buckets (display unit) centred on each label.
  const buckets = new Map<number, number[]>();
  for (const f of items) {
    const deg = convTempC(f.weather_temp_c as number);
    const bucket = Math.round(deg / 10) * 10;
    const arr = buckets.get(bucket) ?? [];
    arr.push(convEconomyMpg(f.mpg as number));
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
    axes: [{ label: tempUnitLabel() }, { label: economyUnitLabel() }],
    series: [
      {},
      {
        label: "Avg economy",
        stroke: PAL[1],
        fill: withAlpha(PAL[1], 0.45),
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
          <Upload :size="14" aria-hidden="true" /> Import
        </RouterLink>
        <button class="primary" type="button" @click="openCreate" :disabled="!vehicleId">
          <Plus :size="14" aria-hidden="true" /> New fillup
        </button>
      </div>
    </header>

    <StateCard v-if="!vehicleId" state="empty" title="Select a vehicle." />
    <template v-else>
      <nav class="tabs" role="tablist" aria-label="Fuel sections">
        <button
          v-for="t in ([['fillups', 'Fillups'], ['map', 'Stations map'], ['stats', 'Stats']] as const)"
          :key="t[0]"
          type="button"
          role="tab"
          :aria-selected="tab === t[0]"
          :class="{ active: tab === t[0] }"
          @click="tab = t[0]"
        >{{ t[1] }}</button>
      </nav>

      <!-- Fillups -->
      <template v-if="tab === 'fillups'">
        <StateCard v-if="fillupsQ.loading.value && !fillupsQ.data.value" state="loading" title="Loading fillups…" />
        <StateCard
          v-else-if="fillupsQ.error.value && !fillupsQ.data.value"
          state="error"
          :message="fillupsQ.error.value"
          @retry="fillupsQ.reload()"
        />
        <StateCard
          v-else-if="!fillupsQ.data.value || fillupsQ.data.value.items.length === 0"
          state="empty"
          title="No fillups yet."
          message="Import your Fuelio history or add one manually."
        />
        <template v-else>
        <div class="filter-bar">
          <div class="chip-row" role="group" aria-label="Fillup filter">
            <button
              v-for="opt in (['all','full','partial'] as const)"
              :key="opt"
              type="button"
              class="chip"
              :aria-pressed="fillupFilter === opt"
              @click="fillupFilter = opt"
            >
              {{ opt === 'all' ? 'All' : opt === 'full' ? 'Full tanks' : 'Partial fills' }}
            </button>
          </div>
        </div>
        <StateCard v-if="groupedFillups.length === 0" state="empty" title="No fillups match the current filter." />
        <div v-for="group in groupedFillups" :key="group.key" class="card no-pad">
          <header class="group-head">
            <span class="group-label">{{ group.label }}</span>
            <span class="muted small">{{ group.items.length }}</span>
          </header>
          <div class="table-scroll fill-table">
          <table class="data">
            <thead>
              <tr>
                <th
                  v-for="h in ([
                    ['fillup_date', 'Date', false],
                    ['odometer', 'Odo', true],
                    ['volume', 'Volume', true],
                    ['total_price', 'Total', true],
                    ['unit_price', `$/${volUnitLabel()}`, true],
                  ] as const)"
                  :key="h[0]"
                  :class="{ num: h[2] }"
                  :aria-sort="ariaSort(h[0])"
                >
                  <button type="button" class="sort-btn" @click="changeSort(h[0])">
                    {{ h[1] }}<span class="arrow" aria-hidden="true">{{ sortArrow(h[0]) }}</span>
                  </button>
                </th>
                <th>Station</th>
                <th class="num" title="Air temperature at fillup time">Wx</th>
                <th class="num" :aria-sort="ariaSort('mpg_recomputed')">
                  <button
                    type="button"
                    class="sort-btn"
                    :title="`Recomputed from odometer deltas (full-to-full, partials rolled up). A flagged value in parentheses is what Fuelio recorded, shown only when it differs.`"
                    @click="changeSort('mpg_recomputed')"
                  >
                    {{ economyUnitLabel() === 'mpg' ? 'MPG' : economyUnitLabel() }}<span class="arrow" aria-hidden="true">{{ sortArrow('mpg_recomputed') }}</span>
                  </button>
                </th>
                <th><span class="sr-only">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="f in group.items" :key="f.id">
                <td class="date-cell">
                  <span :title="fmtDate(f.fillup_date)">{{ rowDate(f) }}</span>
                  <span v-if="f.is_missed" class="badge warn" title="A fillup before this one wasn't logged; MPG skipped">Missed</span>
                  <span v-if="f.is_full === false" class="badge" title="Partial fill — MPG rolls into the next full tank">Partial</span>
                </td>
                <td class="num">{{ fmtOdo(f.odo, distSrc) }}</td>
                <td class="num">{{ fmtVolume(f.fuel_volume, volSrc) }}</td>
                <td class="num">{{ fmtMoney(fillupTotal(f)) }}</td>
                <td class="num">{{ fmtPricePerVolume(fillupPpu(f), volSrc) }}</td>
                <td>{{ f.city ?? f.station_id ?? "—" }}</td>
                <td class="num" :title="weatherTitle(f)">
                  <span v-if="f.weather_temp_c != null">
                    <span aria-hidden="true">{{ wxIcon(f.weather_code) }}</span>
                    {{ fmtTempC(f.weather_temp_c) }}
                  </span>
                  <span v-else class="muted">—</span>
                </td>
                <td class="num">
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
                    v-if="reportedMismatch(f)"
                    class="mpg-mismatch"
                    :title="`Fuelio recorded ${reportedMismatch(f)} — differs from the recomputed value`"
                  >({{ reportedMismatch(f) }})</span>
                </td>
                <td class="row-actions">
                  <button class="ghost" type="button" @click="openEdit(f)" :aria-label="`Edit fillup from ${fmtDate(f.fillup_date)}`" title="Edit">
                    <Pencil :size="14" />
                  </button>
                  <button class="ghost" type="button" @click="requestRemove(f)" :aria-label="`Delete fillup from ${fmtDate(f.fillup_date)}`" title="Delete">
                    <X :size="14" />
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
          </div>

          <!-- Phone: two-line cards (matches the Android fillup list). Tap a
               card to edit; the trailing button deletes. -->
          <ul class="fill-cards">
            <li v-for="f in group.items" :key="f.id">
              <button
                type="button"
                class="fc"
                :aria-label="`Edit fillup from ${fmtDate(f.fillup_date)}`"
                @click="openEdit(f)"
              >
                <span class="fc-top">
                  <span class="fc-date">{{ rowDate(f) }}</span>
                  <span v-if="f.is_missed" class="badge warn">Missed</span>
                  <span v-if="f.is_full === false" class="badge">Partial</span>
                  <span class="fc-total num">{{ fmtMoney(fillupTotal(f)) }}</span>
                </span>
                <span class="fc-sub num">
                  <span>{{ fmtVolume(f.fuel_volume, volSrc) }}</span>
                  <span>{{ fmtPricePerVolume(fillupPpu(f), volSrc) }}</span>
                  <span v-if="f.mpg != null">{{ fmtMpg(f.mpg) }}<span v-if="reportedMismatch(f)" class="mpg-mismatch"> ({{ reportedMismatch(f) }})</span></span>
                  <span v-else-if="f.mpg_reported != null && f.mpg_reported > 0" class="mpg-fallback">{{ fmtMpg(f.mpg_reported) }}</span>
                  <span>{{ fmtOdo(f.odo, distSrc) }}</span>
                  <span v-if="f.city || f.station_id" class="fc-station">{{ f.city ?? f.station_id }}</span>
                </span>
              </button>
              <button
                class="ghost fc-del"
                type="button"
                :aria-label="`Delete fillup from ${fmtDate(f.fillup_date)}`"
                title="Delete"
                @click="requestRemove(f)"
              >
                <X :size="14" />
              </button>
            </li>
          </ul>
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
      </template>

      <!-- Stations map -->
      <template v-else-if="tab === 'map'">
        <StateCard v-if="stationsQ.loading.value" state="loading" title="Loading stations…" />
        <StateCard v-else-if="stationsQ.error.value" state="error" :message="stationsQ.error.value" @retry="stationsQ.reload()" />
        <StateCard
          v-else-if="stationMarkers.length === 0"
          state="empty"
          title="No stations with GPS coordinates yet."
          message="Add fillups with a location set, or import from Fuelio."
        />
        <template v-else>
          <div class="map-actions">
            <button class="btn ghost" type="button" @click="useCurrentLocation">
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
              <dd>{{ fmtVolume(Number(selectedStation.properties.total_volume) || null, volSrc) }}</dd>
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
          <div class="table-scroll">
          <table class="data station-prices">
            <thead>
              <tr>
                <th>Station</th>
                <th class="num">Latest $/{{ volUnitLabel() }}</th>
                <th class="num">Δ vs prev avg</th>
                <th class="num">Avg</th>
                <th class="num">Visits</th>
                <th>Last visit</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="s in stationPricesQ.data.value" :key="s.cluster_id">
                <td>{{ s.name ?? "Unnamed station" }}</td>
                <td class="num">{{ fmtPricePerVolume(s.latest_price, volSrc) }}</td>
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
                <td class="num">{{ fmtPricePerVolume(s.avg_price, volSrc) }}</td>
                <td class="num">{{ s.fillup_count }}</td>
                <td>{{ fmtWhen(s.latest_date) }}</td>
              </tr>
            </tbody>
          </table>
          </div>
        </section>
      </template>

      <!-- Stats -->
      <template v-else>
        <div class="stats-controls">
          <div class="control-row">
            <span class="muted small">Window</span>
            <WindowChips v-model="statsWindow" :options="STATS_WINDOW_OPTIONS" />
          </div>
          <div class="control-row">
            <span class="muted small">Charts</span>
            <div class="chip-row" role="group" aria-label="Visible charts">
              <button
                v-for="c in chartChoices"
                :key="c.key"
                type="button"
                class="chip"
                :aria-pressed="!!chartVisible[c.key]"
                @click="chartVisible[c.key] = !chartVisible[c.key]"
              >{{ c.label }}</button>
            </div>
          </div>
        </div>
        <div class="grid stats">
          <template v-if="chartVisible.kpis">
          <div class="card kpi">
            <h3>Avg economy <span class="tag-fixed">last 6 fillups</span></h3>
            <div class="big">{{ fmtMpg(summary.avgMpg) }}</div>
          </div>
          <div class="card kpi">
            <h3>Total spend ({{ spendWindowLabel }})</h3>
            <div class="big">{{ fmtMoney(summary.totalSpendWindow) }}</div>
          </div>
          <div class="card kpi">
            <h3>Distance tracked ({{ spendWindowLabel }})</h3>
            <div class="big">{{ fmtDistance(summary.totalMiles, "mi", 0) }}</div>
          </div>
          </template>
          <div v-if="chartVisible.monthly" class="card chart-card">
            <h3>Monthly spend</h3>
            <StateCard v-if="monthlyQ.loading.value" state="loading" bare />
            <StateCard v-else-if="monthlyQ.error.value" state="error" bare :message="monthlyQ.error.value" @retry="monthlyQ.reload()" />
            <StateCard v-else-if="!monthlyData || !monthlyOpts" state="empty" bare title="No data." />
            <UPlotChart v-else :data="monthlyData" :options="monthlyOpts" />
          </div>
          <div v-if="chartVisible.cpm" class="card chart-card">
            <h3>Cost per {{ distUnitLabel() }}</h3>
            <StateCard v-if="cpmQ.loading.value" state="loading" bare />
            <StateCard v-else-if="cpmQ.error.value" state="error" bare :message="cpmQ.error.value" @retry="cpmQ.reload()" />
            <StateCard v-else-if="!cpmData" state="empty" bare title="No data." />
            <UPlotChart v-else :data="cpmData" :options="cpmOpts" />
          </div>
          <div v-if="chartVisible.overlay" class="card chart-card wide">
            <h3>OBD vs fillup economy <span class="tag-fixed">all time</span></h3>
            <StateCard v-if="overlayQ.loading.value" state="loading" bare />
            <StateCard v-else-if="overlayQ.error.value" state="error" bare :message="overlayQ.error.value" @retry="overlayQ.reload()" />
            <StateCard v-else-if="!overlayData" state="empty" bare title="No OBD data yet" message="Drive with the WiCAN connected to populate this chart." />
            <UPlotChart v-else :data="overlayData" :options="overlayOpts" />
          </div>

          <!-- Range-to-empty KPI: depends on tank1_capacity + live fuel
               level. When either is missing, surfaces a hint. -->
          <div v-if="chartVisible.range" class="card kpi">
            <h3>Range to empty <span class="tag-fixed">now</span></h3>
            <div v-if="!rangeEstimate" class="muted small">
              Set tank capacity on the
              <RouterLink to="/vehicles">vehicle</RouterLink> first.
            </div>
            <template v-else-if="rangeEstimate.miles != null">
              <div class="big">{{ fmtDistance(rangeEstimate.miles, "mi", 0) }}</div>
              <div class="muted small">
                {{ rangeEstimate.fuelLevel?.toFixed(0) }}% fuel ·
                {{ fmtMpg(rangeEstimate.rollingMpg) }} avg
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
            <h3>Price per {{ volUnitLabel() === 'gal' ? 'gallon' : 'litre' }}</h3>
            <StateCard v-if="statsFillupsQ.loading.value" state="loading" bare />
            <StateCard v-else-if="statsFillupsQ.error.value" state="error" bare :message="statsFillupsQ.error.value" @retry="statsFillupsQ.reload()" />
            <StateCard v-else-if="!ppgChart" state="empty" bare title="No price data." />
            <UPlotChart v-else :data="ppgChart.aligned" :options="ppgChart.opts" />
          </div>

          <div v-if="chartVisible.freq" class="card chart-card">
            <h3>Fillup frequency</h3>
            <StateCard v-if="statsFillupsQ.loading.value" state="loading" bare />
            <StateCard v-else-if="!frequencyChart" state="empty" bare title="Need at least 2 fillups for a frequency histogram." />
            <UPlotChart v-else :data="frequencyChart.aligned" :options="frequencyChart.opts" />
          </div>

          <div v-if="chartVisible.vol" class="card chart-card">
            <h3>Volume per fill</h3>
            <StateCard v-if="statsFillupsQ.loading.value" state="loading" bare />
            <StateCard v-else-if="!volumeDistChart" state="empty" bare title="Need at least 4 fillups for a distribution." />
            <UPlotChart v-else :data="volumeDistChart.aligned" :options="volumeDistChart.opts" />
          </div>

          <div v-if="chartVisible.mpgVsTemp" class="card chart-card">
            <h3>Economy by temperature</h3>
            <StateCard v-if="statsFillupsQ.loading.value" state="loading" bare />
            <StateCard v-else-if="!mpgVsTempChart" state="empty" bare title="Need at least 4 fillups with weather data." />
            <template v-else>
              <UPlotChart :data="mpgVsTempChart.aligned" :options="mpgVsTempChart.opts" />
              <p class="muted small">
                Average recomputed economy grouped into 10{{ tempUnitLabel() }} buckets across
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

    <ConfirmDialog
      :open="deleteTarget != null"
      title="Delete this fillup?"
      :message="
        deleteError
          ? deleteError
          : deleteTarget
            ? fmtDate(deleteTarget.fillup_date) + ' · ' + fmtVolume(deleteTarget.fuel_volume, volSrc)
            : null
      "
      confirm-label="Delete"
      tone="danger"
      :busy="deleteBusy"
      @confirm="confirmRemove"
      @cancel="deleteTarget = null"
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
  color: var(--c-ink2);
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
.filter-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 4px 0;
  flex-wrap: wrap;
}
.group-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 14px;
  border-bottom: 1px solid var(--c-line0);
}
.group-label {
  text-transform: uppercase;
  letter-spacing: 0.05em;
  font-size: 0.78rem;
  font-weight: 500;
  color: var(--c-ink2);
  flex: 1;
}
.table-scroll {
  overflow-x: auto;
}
.sort-btn {
  background: none;
  border: 0;
  padding: 0;
  font: inherit;
  color: inherit;
  text-transform: inherit;
  letter-spacing: inherit;
  cursor: pointer;
  white-space: nowrap;
}
.sort-btn:hover:not(:disabled) {
  background: none;
  color: var(--c-ink0);
}
.sort-btn .arrow {
  margin-left: 0.25rem;
  font-size: 0.7em;
  color: var(--c-ink2);
}
th[aria-sort="ascending"],
th[aria-sort="descending"] {
  color: var(--c-ink1);
}
td .badge {
  margin-left: 0.3rem;
  font-family: 'Geist', sans-serif;
  font-size: 0.68rem;
}
.date-cell {
  white-space: nowrap;
}
/* Fuelio's value disagrees with the recomputed chain value. */
.mpg-mismatch {
  margin-left: 0.3rem;
  font-size: 0.78rem;
  color: var(--c-warn);
  text-decoration: underline dotted;
  text-underline-offset: 2px;
  cursor: help;
}
.fill-cards {
  display: none;
  list-style: none;
  margin: 0;
  padding: 0;
}
.fill-cards li {
  display: flex;
  align-items: center;
  border-bottom: 1px solid var(--c-line0);
}
.fill-cards li:last-child {
  border-bottom: none;
}
.fc {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 0.3rem;
  padding: 0.65rem 0.4rem 0.65rem 0.9rem;
  background: transparent;
  border: 0;
  border-radius: 0;
  text-align: left;
  font-weight: 400;
  color: var(--c-ink1);
}
.fc:hover:not(:disabled) {
  background: var(--c-bg3);
  border-color: transparent;
}
.fc-top {
  display: flex;
  align-items: center;
  gap: 0.4rem;
}
.fc-top .badge {
  font-size: 0.68rem;
}
.fc-date {
  font-weight: 500;
  color: var(--c-ink0);
}
.fc-total {
  margin-left: auto;
  color: var(--c-ink0);
  font-weight: 500;
}
.fc-sub {
  display: flex;
  flex-wrap: wrap;
  gap: 0.15rem 0;
  font-size: 0.8rem;
  color: var(--c-ink2);
}
.fc-sub > span:not(:last-child)::after {
  content: "·";
  margin: 0 0.4rem;
  color: var(--c-ink4);
}
.fc-station {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 100%;
}
.fc-del {
  flex: none;
  margin-right: 0.4rem;
  color: var(--c-ink3);
}
@media (max-width: 640px) {
  .fill-table {
    display: none;
  }
  .fill-cards {
    display: block;
  }
}
.control-row > .muted {
  min-width: 4rem;
}
@media (max-width: 700px) {
  .head {
    flex-wrap: wrap;
    gap: 0.5rem;
  }
}
</style>
