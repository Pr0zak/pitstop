<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useRoute, RouterLink } from "vue-router";
import { useAsync } from "@/composables/useAsync";
import * as api from "@/api/endpoints";
import type uPlot from "uplot";
import UPlotChart from "@/components/charts/UPlotChart.vue";
import MapLibreMap from "@/components/charts/MapLibreMap.vue";
import {
  fmtDateTime,
  fmtDuration,
  fmtDistanceKm,
  fmtSpeedKph,
  fmtRpm,
  fmtVolumeL,
  fmtTemp,
  fmtTempC,
} from "@/composables/useFormat";
import { WMO_CODE } from "@/api/types";
import { useUnitsStore } from "@/stores/units";

function weatherEmoji(code: number | null | undefined): string {
  if (code == null) return "—";
  return WMO_CODE[code]?.icon ?? "·";
}
import { ChevronLeft, RefreshCw } from "lucide-vue-next";

const route = useRoute();
const tripId = computed(() => String(route.params.id ?? ""));

const { data: trip, loading, error, reload } = useAsync(
  () => api.getTrip(tripId.value),
  [tripId],
);
// Route polyline now comes from /trips/{id}/route which queries the
// dedicated gps_points hypertable. The legacy `samples.gps_lat/gps_lon`
// path is kept for backwards-compat with trips ingested before v0.1.78.
const { data: routeData } = useAsync(
  () => api.getTripRoute(tripId.value),
  [tripId],
);

// "This trip vs your average" baseline (Task #112). Fetches the
// vehicle's mean stats for trips in the same distance bucket. The
// computed flips active once `trip` resolves so we only fire once
// per detail page open. Backend returns `sufficient: false` when
// the bucket has < 5 samples and we hide the panel in that case.
const baselineKey = computed(() => {
  if (!trip.value || !trip.value.vehicle_id || trip.value.distance_km == null) {
    return null;
  }
  return `${trip.value.vehicle_id}:${trip.value.distance_km}`;
});
const { data: baseline } = useAsync(
  async () => {
    if (!trip.value || !trip.value.vehicle_id || trip.value.distance_km == null) {
      return null;
    }
    return api.getTripBaseline(trip.value.vehicle_id, trip.value.distance_km);
  },
  [baselineKey],
);

interface BaselineRow { label: string; thisTrip: string; avg: string; deltaPct: number | null }
const baselineRows = computed<BaselineRow[]>(() => {
  if (!trip.value || !baseline.value || !baseline.value.sufficient) return [];
  const t = trip.value;
  const b = baseline.value;
  const rows: BaselineRow[] = [];

  if (b.avg_duration_s != null && t.duration_s != null) {
    const tMin = Math.round(t.duration_s / 60);
    const aMin = Math.round(b.avg_duration_s / 60);
    rows.push({
      label: "Duration",
      thisTrip: `${tMin} min`,
      avg: `${aMin} min`,
      deltaPct: aMin > 0 ? ((tMin - aMin) / aMin) * 100 : null,
    });
  }
  if (b.avg_speed_kph != null && t.avg_speed_kph != null) {
    const tMph = t.avg_speed_kph * 0.621371;
    const aMph = b.avg_speed_kph * 0.621371;
    rows.push({
      label: "Avg speed",
      thisTrip: `${tMph.toFixed(0)} mph`,
      avg: `${aMph.toFixed(0)} mph`,
      deltaPct: aMph > 0 ? ((tMph - aMph) / aMph) * 100 : null,
    });
  }
  if (b.avg_max_speed_kph != null && t.max_speed_kph != null) {
    const tMph = t.max_speed_kph * 0.621371;
    const aMph = b.avg_max_speed_kph * 0.621371;
    rows.push({
      label: "Top speed",
      thisTrip: `${tMph.toFixed(0)} mph`,
      avg: `${aMph.toFixed(0)} mph`,
      deltaPct: aMph > 0 ? ((tMph - aMph) / aMph) * 100 : null,
    });
  }
  if (b.avg_mpg != null && t.distance_km != null && t.fuel_used_l != null && t.fuel_used_l > 0.4) {
    const mi = t.distance_km * 0.621371;
    const gal = t.fuel_used_l * 0.264172;
    if (gal > 0) {
      const tMpg = mi / gal;
      rows.push({
        label: "MPG",
        thisTrip: tMpg.toFixed(1),
        avg: b.avg_mpg.toFixed(1),
        deltaPct: b.avg_mpg > 0 ? ((tMpg - b.avg_mpg) / b.avg_mpg) * 100 : null,
      });
    }
  }
  return rows;
});

// Helper for color tone on the delta cell. MPG / speed: positive
// = green ("better than average"). Duration: positive = red
// ("slower than average"). Top speed: positive = neutral-yellow
// (faster, but not necessarily better).
function deltaTone(label: string, pct: number): string {
  if (Math.abs(pct) < 3) return "neutral";
  const goodIfPositive = label === "MPG" || label === "Avg speed";
  const goodIfNegative = label === "Duration";
  if (goodIfPositive) return pct > 0 ? "good" : "bad";
  if (goodIfNegative) return pct < 0 ? "good" : "bad";
  return "neutral";
}

// Build uPlot data + options from samples. See chartData / chartOpts below —
// data and options are split so a Smooth-chip toggle (data-only) hits
// UPlotChart's setData() path instead of a full rebuild.

// Display-unit resolution for the chart. Everything else on this page already
// renders through useFormat (which reads the units store), but the series table
// below used to hard-code the imperial branch — a metric user got °F values
// under a "°F" axis and mph under "mph". That's the same class of bug
// AnalyticsView already fixed for its coolant chart, and it's fixed here the
// same way: derive the table from `imperial` rather than assuming it. For an
// imperial vehicle every existing row evaluates to exactly what it did before,
// so this is a no-op for the current fleet.
const units = useUnitsStore();
const imperial = computed(() => units.resolved === "imperial");

// Series definitions keyed on metric name. Each defines its display
// label, stroke color, axis scale, and a transform from canonical SI
// units to the display unit (kph→mph, °C→°F, etc).
interface TripSeries {
  metric: string;
  label: string;
  stroke: string;
  scale: string;
  axisLabel: string;
  transform: (v: number) => number;
  defaultVisible: boolean;
  /** Fixed decimal places for axis ticks and the legend readout. Omit to let
   *  uPlot pick its own precision. Needed by the near-1.0 ratio series
   *  (commanded_afr_ratio / o2_s1_lambda live in 0.98–1.02), which otherwise
   *  render as a column of identical "1"s on both the axis and the legend. */
  decimals?: number;
}

// Rebuilt when the resolved unit system flips. `visibleSeries` → `chartOpts`
// depend on this, so a units change triggers a chart rebuild — correct, and
// rare enough not to matter.
const TRIP_SERIES = computed<TripSeries[]>(() => {
  const imp = imperial.value;
  const cToDisp = (v: number) => (imp ? (v * 9) / 5 + 32 : v);
  const degUnit = imp ? "°F" : "°C";
  // Pressures are stored in kPa and read psi for an imperial user — ONE policy
  // for every pressure series. MAP used to be pinned to kPa while the fuel rail
  // converted, so a single chart showed two pressures under two unit systems
  // and disagreed with the Live view (which hardcoded kPa) about the very same
  // fuel-rail metric. Matches Android's UnitFormat.Quantity.PressureKpa and the
  // web's fmtPressureKpa.
  const kpaToDisp = (v: number) => (imp ? v * 0.145038 : v);
  const presUnit = imp ? "psi" : "kPa";
  // engine_fuel_rate arrives in g/s (the ECU's own burn figure, PID 0x9D).
  // g/s → L/h is ×3600 ÷ 749.9 g/L (gasoline density); L → US gal ×0.264172.
  const GASOLINE_G_PER_L = 749.9;
  const L_PER_US_GAL = 0.264172;
  const fuelRate = (gPerS: number) => {
    const litresPerHour = (gPerS * 3600) / GASOLINE_G_PER_L;
    return imp ? litresPerHour * L_PER_US_GAL : litresPerHour;
  };
  return [
    { metric: "vehicle_speed",          label: imp ? "Speed (mph)" : "Speed (km/h)", stroke: "#2f81f7", scale: "speed",   axisLabel: imp ? "mph" : "km/h",   transform: (v) => (imp ? v * 0.621371 : v), defaultVisible: true },
    { metric: "engine_rpm",             label: "RPM",                                stroke: "#3fb950", scale: "rpm",     axisLabel: "rpm",                  transform: (v) => v,                       defaultVisible: true },
    { metric: "coolant_temp",           label: `Coolant (${degUnit})`,               stroke: "#d29922", scale: "temp",    axisLabel: degUnit,                transform: cToDisp,                        defaultVisible: true },
    { metric: "throttle_position",      label: "Throttle (%)",                       stroke: "#a78bfa", scale: "pct",     axisLabel: "%",                    transform: (v) => v,                       defaultVisible: false },
    { metric: "engine_load",            label: "Load (%)",                           stroke: "#ec4899", scale: "pct",     axisLabel: "%",                    transform: (v) => v,                       defaultVisible: false },
    { metric: "manifold_pressure",      label: `MAP (${presUnit})`,                  stroke: "#06b6d4", scale: "kpa",     axisLabel: presUnit,               transform: kpaToDisp,                      defaultVisible: false },
    { metric: "maf_air_flow",           label: "MAF (g/s)",                          stroke: "#14b8a6", scale: "maf",     axisLabel: "g/s",                  transform: (v) => v,                       defaultVisible: false },
    { metric: "fuel_level",             label: "Fuel (%)",                           stroke: "#f97316", scale: "pct",     axisLabel: "%",                    transform: (v) => v,                       defaultVisible: false },
    { metric: "control_module_voltage", label: "Battery (V)",                        stroke: "#facc15", scale: "volt",    axisLabel: "V",                    transform: (v) => v,                       defaultVisible: false },
    { metric: "intake_air_temp",        label: `Intake (${degUnit})`,                stroke: "#94a3b8", scale: "temp",    axisLabel: degUnit,                transform: cToDisp,                        defaultVisible: false },

    // --- Fuel + exhaust -------------------------------------------------
    // All default-hidden: the chart is already busy with the three
    // always-on series, and these are diagnostic add-ons the user opts
    // into. Anyone opening a trip today sees exactly what they saw before.
    //
    // Fuel rate gets its own scale rather than sharing "maf" — both are
    // mass/volume flows but they're an order of magnitude apart, so
    // sharing would flatten one of them.
    { metric: "engine_fuel_rate",       label: imp ? "Fuel rate (gal/h)" : "Fuel rate (L/h)", stroke: "#e11d48", scale: "fuelrate", axisLabel: imp ? "gal/h" : "L/h", transform: fuelRate,                       defaultVisible: false },
    // Deliberately NOT converted to lb/h — MAF above is likewise left in
    // its native g/s, and kg/h is how the PID is quoted. Flip both together
    // if imperial mass flow is ever wanted.
    { metric: "engine_exhaust_flow",    label: "Exhaust (kg/h)",                     stroke: "#a3e635", scale: "exflow",  axisLabel: "kg/h",                 transform: (v) => v,                       defaultVisible: false },
    // Catalyst temps get a scale of their own instead of joining "temp":
    // they run ~1040 °F against a ~195 °F coolant line, so sharing an axis
    // would squash coolant and intake into a flat band at the bottom.
    // B1/B2 share one scale with each other because their *divergence* is
    // the diagnostic — on separate axes a split would be invisible.
    { metric: "catalyst_temp_b1",       label: `Cat B1 (${degUnit})`,                stroke: "#f0abfc", scale: "cattemp", axisLabel: `${degUnit} cat`,       transform: cToDisp,                        defaultVisible: false },
    { metric: "catalyst_temp_b2",       label: `Cat B2 (${degUnit})`,                stroke: "#d946ef", scale: "cattemp", axisLabel: `${degUnit} cat`,       transform: cToDisp,                        defaultVisible: false },
    // Commanded vs measured: one shared "lambda" scale is mandatory here —
    // the whole point of the pair is reading the gap between them, which
    // only means anything when both sit on the same axis. Unitless, so no
    // imperial branch; 3 decimals because the interesting range is
    // 0.98–1.02 and 2 decimals quantises the signal away.
    { metric: "commanded_afr_ratio",    label: "AFR cmd (λ)",                        stroke: "#0d9488", scale: "lambda",  axisLabel: "λ",                    transform: (v) => v,                       defaultVisible: false, decimals: 3 },
    { metric: "o2_s1_lambda",           label: "O2 lambda (λ)",                      stroke: "#5eead4", scale: "lambda",  axisLabel: "λ",                    transform: (v) => v,                       defaultVisible: false, decimals: 3 },
    // ~3500 kPa on this fleet. Same pressure policy as MAP above — both
    // convert, so the chart never mixes unit systems within one dimension.
    { metric: "fuel_rail_pressure",     label: `Rail press (${presUnit})`,           stroke: "#fb7185", scale: "railp",   axisLabel: presUnit,               transform: kpaToDisp,                      defaultVisible: false },

    // --- Distance -------------------------------------------------------
    // Absolute odometer, already offset-corrected by the API (get_trip
    // subtracts vehicles.odometer_offset_km), so these values match the
    // dash. Its own scale, necessarily: ~200 000 on one axis with anything
    // else would flatten every other series into the baseline.
    // Published by the WiCAN only — absent on a cellular-only trip, where
    // the chip dims like any other unpolled metric.
    { metric: "odometer",               label: imp ? "Odometer (mi)" : "Odometer (km)", stroke: "#8b949e", scale: "odo", axisLabel: imp ? "mi" : "km",   transform: (v) => (imp ? v * 0.621371 : v), defaultVisible: false, decimals: 0 },

    // Derived series — computed below from successive vehicle_speed
    // samples (dv/dt → m/s², converted to g). Won't be found in
    // pid_readings; the chart loop synthesises the column.
    { metric: "acceleration",           label: "Accel (g)",                          stroke: "#fb923c", scale: "accel",   axisLabel: "g",                    transform: (v) => v,                       defaultVisible: false },
  ];
});

// Metrics in TRIP_SERIES that are computed in the frontend rather
// than fetched from pid_readings.
const DERIVED_METRICS = new Set(["acceleration"]);

// Set of metric names that actually have at least one non-null
// sample in THIS trip. Powers the chip-dimming so the user can
// tell at a glance which toggles are useful — flipping on a
// metric the WiCAN profile doesn't poll just shows an empty line.
const metricsWithData = computed<Set<string>>(() => {
  const out = new Set<string>();
  const samples = trip.value?.samples ?? [];
  for (const s of samples as Array<{ metric?: string; value_num?: number | null }>) {
    if (s.metric && s.value_num != null) out.add(s.metric);
  }
  // Derived metrics have data if their inputs do.
  if (out.has("vehicle_speed")) out.add("acceleration");
  return out;
});

// Persisted visibility selection — survives reload + revisit.
const SERIES_VIS_KEY = "pitstop_trip_series_visible";
/** Resolve the persisted visibility map, merged OVER the current defaults.
 *
 *  The stored blob is a snapshot of whatever series existed the last time the
 *  user touched a chip, so an entry written before a new metric shipped has no
 *  key for it. This used to `return JSON.parse(raw)` verbatim, which left the
 *  new metrics `undefined`: survivable for the ones added here (undefined is
 *  falsy, so the series stays hidden and the first chip click flips it true —
 *  which happens to match defaultVisible:false), but it silently ignored
 *  `defaultVisible` altogether, so any series ever added with defaultVisible
 *  true would stay invisible forever for every existing user. Seeding from the
 *  defaults and overlaying only the keys actually stored fixes that.
 *
 *  It also (a) drops stale keys for metrics that no longer exist, so the blob
 *  can't accrete forever, and (b) survives a corrupt payload — a stored `null`
 *  or `[]` previously became `seriesVisible.value` itself and made every
 *  `seriesVisible[s.metric]` read in the template throw. */
function loadSeriesVis(): Record<string, boolean> {
  const vis: Record<string, boolean> = Object.fromEntries(
    TRIP_SERIES.value.map((s) => [s.metric, s.defaultVisible]),
  );
  try {
    const raw = localStorage.getItem(SERIES_VIS_KEY);
    if (raw) {
      const parsed: unknown = JSON.parse(raw);
      if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) {
        const stored = parsed as Record<string, unknown>;
        for (const s of TRIP_SERIES.value) {
          if (typeof stored[s.metric] === "boolean") vis[s.metric] = stored[s.metric] as boolean;
        }
      }
    }
  } catch { /* ignore */ }
  return vis;
}
const seriesVisible = ref<Record<string, boolean>>(loadSeriesVis());
watch(seriesVisible, (v) => {
  try { localStorage.setItem(SERIES_VIS_KEY, JSON.stringify(v)); } catch { /* ignore */ }
}, { deep: true });

// Per-trip chart smoothing (Task #127). Applies a rolling median
// filter to every visible metric BEFORE the forward-fill + wide-form
// pivot step. Generalisation of the old fuel-only smoother — fuel
// level was the original motivator (tank-float slosh ±20% per second)
// but speed, throttle, RPM etc. all benefit from a light filter for
// readability on long zoomed-out timelines.
//
// Four discrete levels keyed on a window size; "off" returns raw
// values unchanged. Window sizes operate on array indices, not time,
// so denser series (1 Hz vehicle_speed) get more aggressive temporal
// smoothing than sparser ones (~1-per-30s fuel_level) at the same
// level — that's the intent, since dense series have more noise to
// suppress.
type SmoothLevel = "off" | "light" | "medium" | "heavy";
const SMOOTH_LEVELS: Record<SmoothLevel, number> = {
  off: 1,
  light: 3,
  medium: 7,
  heavy: 15,
};
const SMOOTH_LEVEL_ORDER: SmoothLevel[] = ["off", "light", "medium", "heavy"];
const SMOOTH_LEVEL_LABEL: Record<SmoothLevel, string> = {
  off: "Off",
  light: "Light",
  medium: "Medium",
  heavy: "Heavy",
};
const SMOOTH_LEVEL_KEY = "pitstop_trip_smooth_level";
const SMOOTH_LEGACY_KEY = "pitstop_trip_smooth";

/** Initial smoothing level. Resolution order:
 *  1. New key (pitstop_trip_smooth_level) if present.
 *  2. Migration: old boolean key — true → "medium", false → "off".
 *  3. Auto: "medium" when any per-metric raw sample count > 300,
 *     otherwise "off" (short drives default to raw).
 */
function initialSmoothLevel(samples: Array<{ metric?: string; value_num?: number | null }>): SmoothLevel {
  try {
    const stored = localStorage.getItem(SMOOTH_LEVEL_KEY);
    if (stored && stored in SMOOTH_LEVELS) return stored as SmoothLevel;
    const legacy = localStorage.getItem(SMOOTH_LEGACY_KEY);
    if (legacy != null) {
      const migrated: SmoothLevel = legacy === "false" ? "off" : "medium";
      localStorage.setItem(SMOOTH_LEVEL_KEY, migrated);
      localStorage.removeItem(SMOOTH_LEGACY_KEY);
      return migrated;
    }
  } catch { /* ignore */ }
  // Auto-pick based on density.
  const counts = new Map<string, number>();
  for (const s of samples) {
    if (s.metric && s.value_num != null) {
      counts.set(s.metric, (counts.get(s.metric) ?? 0) + 1);
    }
  }
  for (const n of counts.values()) {
    if (n > 300) return "medium";
  }
  return "off";
}

// Resolve persisted level synchronously up-front (covers cases 1 + 2
// from initialSmoothLevel — stored new key, or legacy migration). If
// nothing is stored, leave it "off" and let the trip-loaded watcher
// auto-pick a default once we know the sample density.
function storedSmoothLevel(): SmoothLevel | null {
  try {
    const stored = localStorage.getItem(SMOOTH_LEVEL_KEY);
    if (stored && stored in SMOOTH_LEVELS) return stored as SmoothLevel;
    const legacy = localStorage.getItem(SMOOTH_LEGACY_KEY);
    if (legacy != null) {
      const migrated: SmoothLevel = legacy === "false" ? "off" : "medium";
      localStorage.setItem(SMOOTH_LEVEL_KEY, migrated);
      localStorage.removeItem(SMOOTH_LEGACY_KEY);
      return migrated;
    }
  } catch { /* ignore */ }
  return null;
}
const initialStored = storedSmoothLevel();
const smoothLevel = ref<SmoothLevel>(initialStored ?? "off");
// `smoothInitialized` flips once either (a) a stored level was found
// at setup, or (b) the trip has resolved and we've auto-picked. After
// that, every change is treated as user intent and persisted.
let smoothInitialized = initialStored !== null;
watch(trip, (t) => {
  if (smoothInitialized || !t) return;
  smoothLevel.value = initialSmoothLevel((t.samples ?? []) as Array<{ metric?: string; value_num?: number | null }>);
  smoothInitialized = true;
}, { immediate: true });
watch(smoothLevel, (v) => {
  if (!smoothInitialized) return;
  try { localStorage.setItem(SMOOTH_LEVEL_KEY, v); } catch { /* ignore */ }
});
function cycleSmoothLevel() {
  const idx = SMOOTH_LEVEL_ORDER.indexOf(smoothLevel.value);
  smoothLevel.value = SMOOTH_LEVEL_ORDER[(idx + 1) % SMOOTH_LEVEL_ORDER.length];
}

/** Rolling median with a centered window of size `windowSize`. Null
 *  values are excluded from the window; if all values in the window
 *  are null, output is null. `windowSize` of 1 short-circuits and
 *  returns the input untouched. Operates on array indices (not time
 *  gaps), so cadence determines how much temporal smoothing a given
 *  window gives a series. */
function medianFilter(arr: (number | null)[], windowSize: number): (number | null)[] {
  if (windowSize <= 1) return arr.slice();
  const half = Math.floor(windowSize / 2);
  const out: (number | null)[] = new Array(arr.length).fill(null);
  for (let i = 0; i < arr.length; i++) {
    const win: number[] = [];
    for (let j = Math.max(0, i - half); j <= Math.min(arr.length - 1, i + half); j++) {
      const v = arr[j];
      if (v != null) win.push(v);
    }
    if (win.length === 0) continue;
    win.sort((a, b) => a - b);
    out[i] = win[Math.floor(win.length / 2)];
  }
  return out;
}

// The visible-series set drives BOTH the aligned-data column layout and the
// opts (series / scales / axes). Sharing one computed keeps chartData and
// chartOpts structurally in lock-step so they can never desync on a series
// toggle. Neither depends on smoothLevel, so a Smooth-chip toggle only
// re-derives chartData — chartOpts identity stays stable and UPlotChart takes
// the cheap setData() path instead of a full destroy()+rebuild.
const visibleSeries = computed(() => TRIP_SERIES.value.filter((s) => seriesVisible.value[s.metric]));

// Aligned data: pivots + smooths + forward-fills the trip samples into the
// column layout implied by visibleSeries. Recomputes on trip load, series
// toggle, and Smooth-level change.
const chartData = computed<uPlot.AlignedData | null>(() => {
  if (!trip.value || !trip.value.samples || trip.value.samples.length === 0) return null;
  type Sample = {
    time: string;
    metric?: string;
    value_num?: number | null;
    vehicle_speed?: number | null;
    engine_rpm?: number | null;
    coolant_temp?: number | null;
  };
  const allSamples = trip.value.samples as Sample[];

  // Pre-pass: take each metric's raw sparse sample sequence and apply
  // a rolling median filter BEFORE the wide-form pivot. Doing it on
  // the raw signal (not the forward-filled wide table) is the whole
  // point — forward-fill creates plateau steps, and the median of
  // identical values is the same value. Smoothing the raw sequence
  // first means the plateaus themselves are calmer, which is what
  // actually shows up on the chart at zoom-out.
  //
  // Window comes from the user's smoothing level. Off = window 1 =
  // identity (the per-metric loop below short-circuits because
  // medianFilter just slices when window <= 1, so the substitution
  // map ends up matching the raw values exactly).
  const window = SMOOTH_LEVELS[smoothLevel.value];
  const smoothedByTs = new Map<string, Map<number, number>>();
  if (window > 1) {
    // Collect every metric that actually has raw long-form samples
    // in this trip; smooth each one independently.
    const seqsByMetric = new Map<string, Array<{ ts: number; v: number }>>();
    for (const s of allSamples) {
      if (!s.metric || s.value_num == null) continue;
      const ts = Math.round((Date.parse(s.time) || 0) / 1000);
      if (!Number.isFinite(ts)) continue;
      const list = seqsByMetric.get(s.metric);
      const entry = { ts, v: s.value_num as number };
      if (list) list.push(entry);
      else seqsByMetric.set(s.metric, [entry]);
    }
    // Also fold in wide-form values (older API responses include
    // vehicle_speed / engine_rpm / coolant_temp directly on each
    // sample row alongside the long-form rows). Without this, the
    // wide-form columns would overwrite the smoothed long-form ones
    // in the pivot loop below.
    const wideMetrics = ["vehicle_speed", "engine_rpm", "coolant_temp"] as const;
    for (const wm of wideMetrics) {
      if (seqsByMetric.has(wm)) continue;
      const seq: Array<{ ts: number; v: number }> = [];
      for (const s of allSamples as Array<{ time: string } & Record<string, unknown>>) {
        const raw = s[wm];
        if (typeof raw !== "number") continue;
        const ts = Math.round((Date.parse(s.time) || 0) / 1000);
        if (!Number.isFinite(ts)) continue;
        seq.push({ ts, v: raw });
      }
      if (seq.length >= 2) seqsByMetric.set(wm, seq);
    }
    for (const [metric, seq] of seqsByMetric) {
      if (seq.length < 2) continue;
      seq.sort((a, b) => a.ts - b.ts);
      const vals = seq.map((x) => x.v);
      const filtered = medianFilter(vals, window);
      const map = new Map<number, number>();
      seq.forEach((x, idx) => {
        const v = filtered[idx];
        if (v != null) map.set(x.ts, v);
      });
      smoothedByTs.set(metric, map);
    }
  }

  // Pivot long-form samples ({time, metric, value_num}) to wide form
  // keyed on bucket time. Backwards-compat: handle the old wide-form
  // shape too in case an older API response sneaks in.
  const buckets = new Map<number, Record<string, number | null>>();
  for (const s of allSamples) {
    const ts = Math.round((Date.parse(s.time) || 0) / 1000);
    const slot = buckets.get(ts) ?? {};
    if (s.metric && s.value_num !== undefined && s.value_num !== null) {
      // Substitute the smoothed value when we built one for this metric.
      const smoothMap = smoothedByTs.get(s.metric);
      const smoothed = smoothMap?.get(ts);
      slot[s.metric] = smoothed ?? s.value_num;
    }
    if (s.vehicle_speed != null) {
      const sm = smoothedByTs.get("vehicle_speed")?.get(ts);
      slot["vehicle_speed"] = sm ?? s.vehicle_speed;
    }
    if (s.engine_rpm != null) {
      const sm = smoothedByTs.get("engine_rpm")?.get(ts);
      slot["engine_rpm"] = sm ?? s.engine_rpm;
    }
    if (s.coolant_temp != null) {
      const sm = smoothedByTs.get("coolant_temp")?.get(ts);
      slot["coolant_temp"] = sm ?? s.coolant_temp;
    }
    buckets.set(ts, slot);
  }
  const sortedTs = Array.from(buckets.keys()).sort((a, b) => a - b);
  const visible = visibleSeries.value;
  if (visible.length === 0) return null;

  // Pre-compute the acceleration column when it's visible. dv/dt
  // from successive vehicle_speed samples (km/h → m/s, then divided
  // by 9.81 to render as g — intuitive for "0.4g hard brake" cues).
  // Null when either neighbouring speed sample is missing.
  const accelByTs = new Map<number, number | null>();
  if (visible.some((s) => s.metric === "acceleration")) {
    const G = 9.80665;
    const KPH_TO_MPS = 1 / 3.6;
    let prevTs: number | null = null;
    let prevSpeed: number | null = null;
    for (const ts of sortedTs) {
      const speedKph = buckets.get(ts)?.["vehicle_speed"];
      if (speedKph == null || prevTs == null || prevSpeed == null) {
        accelByTs.set(ts, null);
      } else {
        const dt = ts - prevTs;
        if (dt <= 0) {
          accelByTs.set(ts, null);
        } else {
          const dv = (Number(speedKph) - prevSpeed) * KPH_TO_MPS;
          accelByTs.set(ts, dv / dt / G);
        }
      }
      if (speedKph != null) {
        prevTs = ts;
        prevSpeed = Number(speedKph);
      }
    }
  }

  const t: number[] = [];
  const arrays = visible.map(() => [] as (number | null)[]);
  for (const ts of sortedTs) {
    const slot = buckets.get(ts)!;
    t.push(ts);
    visible.forEach((s, i) => {
      if (s.metric === "acceleration") {
        arrays[i].push(accelByTs.get(ts) ?? null);
        return;
      }
      const v = slot[s.metric];
      arrays[i].push(v == null ? null : s.transform(v));
    });
  }
  // Forward-fill sparse series so they render as step lines instead
  // of disappearing into the gaps between samples. uPlot breaks
  // lines at nulls by default; a metric like fuel_level with one
  // reading per 30s amongst 1Hz vehicle_speed timestamps would have
  // ~96% null values and draw essentially nothing. Carrying the
  // previous value forward gives a horizontal-step visualisation
  // that's right for slow-changing metrics (fuel, coolant, oil,
  // battery, ATF). Speed / RPM / throttle have a sample at almost
  // every timestamp anyway so the no-op fill is harmless for them.
  visible.forEach((s, i) => {
    if (s.metric === "acceleration") return; // already null where expected
    let prev: number | null = null;
    const arr = arrays[i];
    for (let k = 0; k < arr.length; k++) {
      if (arr[k] == null) arr[k] = prev;
      else prev = arr[k];
    }
  });

  // (Smoothing happens up-front on each metric's raw sparse signal —
  // see the smoothedByTs build above. Doing it here on the
  // forward-filled arrays would no-op because the fill produces
  // plateaus and a median of N identical values is the same value.)
  return [t, ...arrays] as uPlot.AlignedData;
});

// Chart options: scales / axes / series / plugins, derived purely from the
// visible-series set + the trip's DTC markers. Deliberately independent of
// smoothLevel so the opts object identity is stable across Smooth-chip
// toggles (the whole point of the split). Returns null when nothing is
// visible / no trip so the template can gate on it in parallel with chartData.
const chartOpts = computed<uPlot.Options | null>(() => {
  if (!trip.value) return null;
  const visible = visibleSeries.value;
  if (visible.length === 0) return null;
  // Build the scales object: every distinct scale used by visible series.
  const scales: Record<string, { time?: boolean }> = { x: { time: true } };
  for (const s of visible) scales[s.scale] = {};
  // Axes — first is x; then one per *unique* scale, alternating sides.
  const axisScalesSeen = new Set<string>();
  const axes: uPlot.Axis[] = [{ stroke: "#9aa0aa" }];
  let side = 0; // 0 = left (3 for top, but we want bottom-default), 1 = right
  for (const s of visible) {
    if (axisScalesSeen.has(s.scale)) continue;
    axisScalesSeen.add(s.scale);
    // Capture into a local so the closure below doesn't have to re-narrow
    // `s.decimals` (TS drops property narrowing across a function boundary).
    const dp = s.decimals;
    axes.push({
      scale: s.scale,
      stroke: "#9aa0aa",
      label: s.axisLabel,
      side: side === 0 ? 3 : 1,
      grid: { show: side === 0 },
      // Fixed precision for the ratio scales. uPlot's automatic tick
      // formatting derives decimals from the tick increment, which on a
      // 0.98–1.02 range rounds every split to "1".
      ...(dp != null ? { values: (_u: uPlot, splits: number[]) => splits.map((v) => v.toFixed(dp)) } : {}),
    });
    side = 1 - side;
  }
  // Vertical rules for DTC fire events (#110).
  const dtcMarkers: { ts: number; code: string }[] = (trip.value.dtcs ?? [])
    .map((d) => ({ ts: Math.round(Date.parse(d.seen_at) / 1000), code: d.code }))
    .filter((d) => Number.isFinite(d.ts));
  const hasMarkers = dtcMarkers.length > 0;
  const markerPlugin: uPlot.Plugin | null = hasMarkers
    ? {
        hooks: {
          draw: (u) => {
            const ctx = u.ctx;
            ctx.save();
            ctx.font = "11px ui-sans-serif";
            // DTC rules — solid red, code label above
            ctx.strokeStyle = "#ef4444";
            ctx.fillStyle = "#ef4444";
            ctx.lineWidth = 1;
            for (const m of dtcMarkers) {
              const x = u.valToPos(m.ts, "x", true);
              if (x < u.bbox.left || x > u.bbox.left + u.bbox.width) continue;
              ctx.setLineDash([]);
              ctx.beginPath();
              ctx.moveTo(x, u.bbox.top);
              ctx.lineTo(x, u.bbox.top + u.bbox.height);
              ctx.stroke();
              ctx.fillText(m.code, x + 4, u.bbox.top + 12);
            }
            ctx.setLineDash([]);
            ctx.restore();
          },
        },
      }
    : null;
  return {
    width: 800,
    height: 320,
    cursor: { drag: { x: true, y: false, setScale: true } },
    scales,
    series: [
      {},
      ...visible.map((s) => {
        const dp = s.decimals;
        return {
          label: s.label,
          stroke: s.stroke,
          scale: s.scale,
          width: 1.4,
          // Same reason as the axis `values` above — without this the
          // legend readout for a λ series shows a flat "1" under the cursor.
          ...(dp != null
            ? { value: (_u: uPlot, v: number | null) => (v == null ? "--" : v.toFixed(dp)) }
            : {}),
        };
      }),
    ],
    axes,
    ...(markerPlugin || cursorMapSyncPlugin ? {
      plugins: [
        ...(markerPlugin ? [markerPlugin] : []),
        cursorMapSyncPlugin,
      ],
    } : {}),
  };
});

// Hover marker for chart ↔ map sync. The cursor plugin below
// updates this on every uPlot setCursor; MapLibreMap watches it
// and renders a distinct marker at the matching GPS position.
const hoverGps = ref<{ lat: number; lon: number } | null>(null);

// uPlot plugin: on every cursor move, find the nearest GPS point
// by time and update hoverGps. The map's prop watcher then moves
// the marker. Throttled by uPlot's natural mousemove rate.
const cursorMapSyncPlugin: uPlot.Plugin = {
  hooks: {
    setCursor: (u) => {
      const idx = u.cursor.idx;
      if (idx == null) {
        hoverGps.value = null;
        return;
      }
      const ts = (u.data[0] as number[] | undefined)?.[idx];
      if (ts == null) {
        hoverGps.value = null;
        return;
      }
      const points = routeData.value?.points;
      if (!points || points.length === 0) {
        hoverGps.value = null;
        return;
      }
      // Binary search would be O(log n) but points are sorted and
      // capped at a few thousand — linear scan is fast enough and
      // simpler. Tolerance: within ±15s counts as "on the route".
      let best: typeof points[number] | null = null;
      let bestDelta = Infinity;
      for (const p of points) {
        const ptTs = Math.round((Date.parse(p.t) || 0) / 1000);
        const d = Math.abs(ptTs - ts);
        if (d < bestDelta) {
          bestDelta = d;
          best = p;
        }
      }
      hoverGps.value = best && bestDelta < 15
        ? { lat: best.lat, lon: best.lon }
        : null;
    },
  },
};

const odoDelta = computed<number | null>(() => {
  const t = trip.value;
  if (!t || t.odo_start_km == null || t.odo_end_km == null) return null;
  const d = t.odo_end_km - t.odo_start_km;
  // Reject obviously-wrong deltas (engine-off readings can return the
  // *previous* trip's value when the ECU hasn't logged yet, producing
  // a negative or 100x-too-large delta).
  if (d < 0 || d > 1000) return null;
  return d;
});

function fmtOdoMi(km: number | null): string {
  if (km == null) return "—";
  return (km * 0.621371).toFixed(0);
}
function fmtOdoDeltaMi(km: number | null): string {
  if (km == null) return "—";
  return `+${(km * 0.621371).toFixed(1)} mi`;
}

const route2D = computed<[number, number][]>(() => {
  // Prefer gps_points (richer + accurate); fall back to legacy
  // samples.gps_lat/gps_lon for trips from before v0.1.78.
  if (routeData.value?.points?.length) {
    return routeData.value.points.map((p) => [p.lon, p.lat] as [number, number]);
  }
  if (!trip.value?.samples) return [];
  return trip.value.samples
    .filter((s) => s.gps_lat != null && s.gps_lon != null)
    .map((s) => [s.gps_lon as number, s.gps_lat as number]);
});

/**
 * Speed-bucketed polyline segments. Each segment is a 2-point
 * LineString colored by the speed at the segment's start.
 *   stopped (<1 m/s)         red    — traffic / parked / lights
 *   city (1-10 m/s, ~2-22mph) amber — surface streets
 *   suburban (10-24.6, ~22-55) green — main arterials
 *   highway (24.6+, 55+ mph)  blue  — interstate (matches /analytics/mpg-by-speed-class cutoff)
 * Falls back to a single blue segment when speed data is absent
 * (legacy trips with no /route endpoint coverage).
 */
/**
 * Continuous speed → color heatmap. Maps speeds 0 m/s (red, stopped)
 * → 36 m/s (≈80 mph, magenta) through the HSL spectrum red →
 * orange → yellow → green → cyan → blue → magenta. Anchored at a
 * fixed 0–36 m/s range so colors mean the same thing across trips
 * (a city-speed segment is always orange/yellow regardless of the
 * trip's max speed).
 */
const HEATMAP_MAX_MPS = 36;
function speedColor(speedMps: number | null): string {
  if (speedMps == null) return "#2f81f7";
  const clamped = Math.max(0, Math.min(speedMps, HEATMAP_MAX_MPS));
  const t = clamped / HEATMAP_MAX_MPS;
  // HSL hue: red (0°) → magenta (300°). Skip the wrap back to red.
  const hue = t * 300;
  return `hsl(${hue.toFixed(0)}, 80%, 50%)`;
}

// Discrete bucket — kept for the speed-distribution donut card,
// where 4 fixed labels (Stopped / City / Suburban / Highway) make
// sense even though the route line itself uses the continuous map.
function bucketColor(speedMps: number | null): string {
  if (speedMps == null) return "#2f81f7";
  if (speedMps < 1) return "#ef4444";
  if (speedMps < 10) return "#f59e0b";
  if (speedMps < 24.6) return "#22c55e";   // <55 mph
  return "#2f81f7";                        // ≥55 mph highway
}

// User preference for the route map: heatmap (continuous color
// per segment) vs plain (single accent line). Persisted across
// reloads / revisits.
const HEATMAP_KEY = "pitstop_route_heatmap";
const heatmapEnabled = ref<boolean>(localStorage.getItem(HEATMAP_KEY) !== "false");
watch(heatmapEnabled, (v) => {
  try { localStorage.setItem(HEATMAP_KEY, String(v)); } catch { /* ignore */ }
});

const routeSegments = computed<{ coords: [number, number][]; color: string }[]>(() => {
  const points = routeData.value?.points;
  if (!points || points.length < 2) return [];
  if (!heatmapEnabled.value) return [];   // fall back to plain `route` line
  const out: { coords: [number, number][]; color: string }[] = [];
  for (let i = 0; i < points.length - 1; i++) {
    out.push({
      coords: [
        [points[i].lon, points[i].lat],
        [points[i + 1].lon, points[i + 1].lat],
      ],
      color: speedColor(points[i].speed_mps),
    });
  }
  return out;
});

/** Speed distribution: seconds spent in each bucket. Inferred from
 *  consecutive GPS points; assumes ~5s cadence per fix. */
interface SpeedBucket { label: string; color: string; seconds: number }
const speedDistribution = computed<SpeedBucket[]>(() => {
  const points = routeData.value?.points;
  if (!points || points.length < 2) return [];
  // Bucket thresholds in m/s: <1, <10, <24.6, ≥24.6.
  // mph: <2, <22, <55, ≥55. The 55-mph highway cutoff matches the
  // backend's /analytics/mpg-by-speed-class boundary.
  const buckets: SpeedBucket[] = [
    { label: "Stopped (<2 mph)",     color: "#ef4444", seconds: 0 },
    { label: "City (2–22 mph)",      color: "#f59e0b", seconds: 0 },
    { label: "Suburban (22–55 mph)", color: "#22c55e", seconds: 0 },
    { label: "Highway (≥55 mph)",    color: "#2f81f7", seconds: 0 },
  ];
  for (let i = 1; i < points.length; i++) {
    const dt = (Date.parse(points[i].t) - Date.parse(points[i - 1].t)) / 1000;
    if (!Number.isFinite(dt) || dt <= 0 || dt > 60) continue;
    const sp = points[i - 1].speed_mps ?? 0;
    const idx = sp < 1 ? 0 : sp < 10 ? 1 : sp < 24.6 ? 2 : 3;
    buckets[idx].seconds += dt;
  }
  return buckets;
});
const speedTotalSeconds = computed(() =>
  speedDistribution.value.reduce((s, b) => s + b.seconds, 0),
);

/** Elevation profile: list of [cumulative_km, alt_m] pairs from
 *  gps_points. Skip if fewer than 4 points have altitude data. */
interface ElevationPoint { km: number; alt_m: number }
const elevationProfile = computed<ElevationPoint[]>(() => {
  const points = routeData.value?.points;
  if (!points || points.length < 4) return [];
  const out: ElevationPoint[] = [];
  let cumKm = 0;
  let prev: typeof points[0] | null = null;
  for (const p of points) {
    if (prev != null) {
      cumKm += haversineKm(prev.lat, prev.lon, p.lat, p.lon);
    }
    if (p.alt_m != null) out.push({ km: cumKm, alt_m: p.alt_m });
    prev = p;
  }
  return out.length >= 4 ? out : [];
});
const elevationStats = computed(() => {
  const pts = elevationProfile.value;
  if (pts.length === 0) return null;
  const alts = pts.map((p) => p.alt_m);
  const min = Math.min(...alts);
  const max = Math.max(...alts);
  let climb = 0;
  for (let i = 1; i < pts.length; i++) {
    const d = pts[i].alt_m - pts[i - 1].alt_m;
    if (d > 0) climb += d;
  }
  return { min, max, climb };
});

function haversineKm(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const r = 6371;
  const dLat = ((lat2 - lat1) * Math.PI) / 180;
  const dLon = ((lon2 - lon1) * Math.PI) / 180;
  const a = Math.sin(dLat / 2) ** 2 +
    Math.cos((lat1 * Math.PI) / 180) * Math.cos((lat2 * Math.PI) / 180) *
    Math.sin(dLon / 2) ** 2;
  return 2 * r * Math.asin(Math.sqrt(a));
}

function fmtBucketSeconds(s: number): string {
  if (s < 60) return `${Math.round(s)}s`;
  const m = Math.round(s / 60);
  return m >= 60 ? `${Math.floor(m / 60)}h ${m % 60}m` : `${m}m`;
}

// Stop list (Task #113): consecutive GPS points where speed_mps < 1
// for ≥30 s. Reveals red-light wait times, brief errands, garage
// stops. Centroid is the median of the run's lat/lon.
interface TripStop { started_at: string; duration_s: number; lat: number; lon: number }
const stops = computed<TripStop[]>(() => {
  const points = routeData.value?.points;
  if (!points || points.length < 2) return [];
  const out: TripStop[] = [];
  let runStart = -1;
  for (let i = 0; i <= points.length; i++) {
    const stopped = i < points.length && (points[i].speed_mps ?? 0) < 1;
    if (stopped && runStart < 0) {
      runStart = i;
    } else if (!stopped && runStart >= 0) {
      const startP = points[runStart];
      const endP = points[i - 1];
      const dur = (Date.parse(endP.t) - Date.parse(startP.t)) / 1000;
      if (dur >= 30) {
        const mid = points[runStart + Math.floor((i - 1 - runStart) / 2)];
        out.push({
          started_at: startP.t,
          duration_s: Math.round(dur),
          lat: mid.lat,
          lon: mid.lon,
        });
      }
      runStart = -1;
    }
  }
  return out;
});

// Time-of-day badge (Task #113). Driven entirely by trip.started_at —
// hour-of-week categorisation. Useful context for MPG analysis ("rush
// hour drives average X mpg vs off-peak Y").
function todBadge(startedAt?: string | null): { label: string; tone: string } | null {
  if (!startedAt) return null;
  const d = new Date(startedAt);
  if (Number.isNaN(d.getTime())) return null;
  const dow = d.getDay();      // 0 Sun .. 6 Sat
  const hr = d.getHours();
  const isWeekend = dow === 0 || dow === 6;
  if (hr >= 22 || hr < 5) return { label: "Late night", tone: "tone-night" };
  if (isWeekend) {
    if (hr < 11) return { label: "Weekend morning", tone: "tone-weekend" };
    if (hr < 17) return { label: "Weekend afternoon", tone: "tone-weekend" };
    return { label: "Weekend evening", tone: "tone-weekend" };
  }
  if (hr >= 7 && hr < 9) return { label: "Morning rush", tone: "tone-rush" };
  if (hr >= 16 && hr < 19) return { label: "Evening rush", tone: "tone-rush" };
  return { label: "Off-peak", tone: "tone-offpeak" };
}
const tripBadge = computed(() => (trip.value ? todBadge(trip.value.started_at) : null));

function fmtClock(iso?: string | null): string {
  if (!iso) return "—";
  const d = new Date(iso);
  return d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
}

// Auto-generated narrative sentence (Task #103). Synthesises headline
// numbers into one human-readable line at the top of the page. Each
// clause is independent — skipped when its inputs are missing — so a
// stub trip ("just started, no GPS yet") still produces a sane line.
const summarySentence = computed<string>(() => {
  const t = trip.value;
  if (!t) return "";
  const parts: string[] = [];

  // 1) "13-min morning drive" / "13-min drive"
  const minutes = t.duration_s ? Math.round(t.duration_s / 60) : null;
  const todLabel = (() => {
    if (!t.started_at) return null;
    const h = new Date(t.started_at).getHours();
    if (h >= 22 || h < 5) return "late-night";
    if (h < 11) return "morning";
    if (h < 17) return "afternoon";
    return "evening";
  })();
  if (minutes != null) {
    if (minutes < 1) parts.push("Brief drive");
    else parts.push(`${minutes}-min ${todLabel ? todLabel + " " : ""}drive`);
  }

  // 2) Weather. Suffix: "in 67°F clear conditions"
  if (t.weather_temp_c != null) {
    const f = Math.round(t.weather_temp_c * 9 / 5 + 32);
    const wmo = t.weather_code != null ? WMO_CODE[t.weather_code]?.label : null;
    parts.push(`in ${f}°F${wmo ? ` ${wmo}` : ""} conditions`);
  }

  // 3) Speed split. Use bucket distribution if it produced totals.
  if (speedTotalSeconds.value > 0) {
    const total = speedTotalSeconds.value;
    const hwy = speedDistribution.value.find((b) => b.label.startsWith("Highway"));
    const sub = speedDistribution.value.find((b) => b.label.startsWith("Suburban"));
    const city = speedDistribution.value.find((b) => b.label.startsWith("City"));
    const hwyPct = hwy ? Math.round((hwy.seconds / total) * 100) : 0;
    const subPct = sub ? Math.round((sub.seconds / total) * 100) : 0;
    const cityPct = city ? Math.round((city.seconds / total) * 100) : 0;
    if (hwyPct >= 50) parts.push(`mostly highway (${hwyPct}%)`);
    else if (cityPct >= 50) parts.push(`mostly city (${cityPct}%)`);
    else if (hwyPct + subPct + cityPct > 0)
      parts.push(`mixed: ${hwyPct}% hwy, ${subPct}% suburban, ${cityPct}% city`);
  }

  // 4) Stops & distance.
  if (t.distance_km != null && t.distance_km > 0) {
    const mi = (t.distance_km * 0.621371).toFixed(1);
    parts.push(`${mi} mi covered`);
  }
  if (stops.value.length >= 2) {
    parts.push(`${stops.value.length} stops`);
  }

  // 5) MPG. distance_km / fuel_used_l → mi/gal_us. Only when fuel reading
  // is meaningful (>0.1 gal).
  if (t.distance_km != null && t.fuel_used_l != null && t.fuel_used_l > 0.4) {
    const mi = t.distance_km * 0.621371;
    const gal = t.fuel_used_l * 0.264172;
    if (gal > 0) {
      const mpg = mi / gal;
      if (mpg > 1 && mpg < 100) parts.push(`averaged ${mpg.toFixed(1)} mpg`);
    }
  }

  // 6) Top speed.
  if (t.max_speed_kph != null && t.max_speed_kph > 0) {
    parts.push(`peaked at ${Math.round(t.max_speed_kph * 0.621371)} mph`);
  }

  // 7) DTCs.
  if ((t.dtcs?.length ?? 0) > 0) {
    parts.push(`${t.dtcs!.length} DTC${t.dtcs!.length === 1 ? "" : "s"} fired`);
  }

  if (parts.length === 0) return "";
  // Join with commas, terminate. Keep it conversational by joining
  // the first weather suffix with the duration without a comma.
  const head = parts[0] + (parts[1]?.startsWith("in ") ? " " + parts[1] : "");
  const tail = parts.slice(parts[1]?.startsWith("in ") ? 2 : 1).join(", ");
  return tail ? `${head}, ${tail}.` : `${head}.`;
});

let chartRef: uPlot | null = null;
function onChartReady(c: uPlot) {
  chartRef = c;
}
function resetZoom() {
  if (!chartRef) return;
  const [t] = chartRef.data;
  if (!t || t.length === 0) return;
  chartRef.setScale("x", { min: t[0] as number, max: t[t.length - 1] as number });
}

// Editable inline notes/category.
const editingNotes = ref(false);
const notesDraft = ref("");
const categoryDraft = ref("");
const towingDraft = ref(false);
const savingMeta = ref(false);
const metaError = ref<string | null>(null);

watch(trip, (t) => {
  if (t) {
    notesDraft.value = t.notes ?? "";
    categoryDraft.value = t.category ?? "";
    towingDraft.value = t.is_towing ?? false;
  }
});

async function saveMeta() {
  if (!trip.value) return;
  savingMeta.value = true;
  metaError.value = null;
  try {
    await api.updateTrip(trip.value.id, {
      notes: notesDraft.value.trim() || null,
      category: categoryDraft.value.trim() || null,
      is_towing: towingDraft.value,
    });
    editingNotes.value = false;
    await reload();
  } catch (e: unknown) {
    metaError.value = e instanceof Error ? e.message : "save failed";
  } finally {
    savingMeta.value = false;
  }
}
</script>

<template>
  <div class="trip-detail">
    <header class="head">
      <div class="left">
        <RouterLink to="/trips" class="back"><ChevronLeft :size="14" /> Trips</RouterLink>
        <h1 v-if="trip">{{ fmtDateTime(trip.started_at) }}</h1>
        <h1 v-else>Trip</h1>
        <span v-if="tripBadge" class="tod-badge" :class="tripBadge.tone">
          {{ tripBadge.label }}
        </span>
      </div>
      <button class="ghost" type="button" @click="reload"><RefreshCw :size="14" /></button>
    </header>

    <div v-if="loading && !trip" class="card">
      <p class="muted">Loading trip…</p>
    </div>
    <div v-else-if="error" class="card">
      <p class="muted">Failed to load: {{ error }}</p>
    </div>
    <template v-else-if="trip">
      <p v-if="summarySentence" class="trip-summary">{{ summarySentence }}</p>
      <div class="layout">
        <section class="main-col">
          <div class="card">
            <header class="chart-head">
              <h3>Timeline</h3>
              <div class="head-tools">
                <button
                  type="button"
                  class="chip toggle-chip smooth-chip"
                  :class="{ active: smoothLevel !== 'off' }"
                  :title="`Rolling median over each series (window ${SMOOTH_LEVELS[smoothLevel]} samples). Click to cycle Off → Light → Medium → Heavy.`"
                  @click="cycleSmoothLevel"
                >
                  Smooth <span class="muted small">({{ SMOOTH_LEVEL_LABEL[smoothLevel] }})</span>
                </button>
                <button class="ghost" type="button" @click="resetZoom">Reset zoom</button>
              </div>
            </header>
            <div class="series-chips">
              <button
                v-for="s in TRIP_SERIES"
                :key="s.metric"
                class="chip"
                :class="{
                  active: seriesVisible[s.metric],
                  empty: !metricsWithData.has(s.metric),
                }"
                :style="seriesVisible[s.metric] ? { borderColor: s.stroke, color: s.stroke } : {}"
                :title="metricsWithData.has(s.metric) ? '' : 'No data for this trip'"
                type="button"
                @click="seriesVisible[s.metric] = !seriesVisible[s.metric]"
              >{{ s.label.replace(/ \(.*\)/, '') }}</button>
            </div>
            <div v-if="!chartData || !chartOpts" class="muted">No metrics selected (or no samples in this trip).</div>
            <UPlotChart
              v-else
              :data="chartData"
              :options="chartOpts"
              @ready="onChartReady"
            />
          </div>

          <div class="card">
            <header class="chart-head">
              <h3>Route</h3>
              <span v-if="routeData?.points?.length" class="muted small">
                {{ routeData.points.length }} GPS points
              </span>
            </header>
            <div v-if="route2D.length === 0" class="muted">
              No GPS data captured for this trip.
            </div>
            <template v-else>
              <MapLibreMap
                :route-segments="routeSegments.length ? routeSegments : undefined"
                :route="routeSegments.length ? undefined : route2D"
                :hover-marker="hoverGps"
                :height="360"
              />
              <div class="speed-legend">
                <label class="toggle">
                  <input type="checkbox" v-model="heatmapEnabled" />
                  Heatmap
                </label>
                <template v-if="heatmapEnabled">
                  <span class="muted small">0</span>
                  <span class="heatmap-gradient" aria-hidden="true" />
                  <span class="muted small">{{ Math.round(HEATMAP_MAX_MPS * 2.237) }} mph</span>
                </template>
              </div>
            </template>
          </div>

          <div v-if="stops.length" class="card">
            <header class="chart-head">
              <h3>Stops</h3>
              <span class="muted small">
                {{ stops.length }} stop{{ stops.length === 1 ? "" : "s" }}
                ≥30s · total
                {{ fmtBucketSeconds(stops.reduce((a, s) => a + s.duration_s, 0)) }}
              </span>
            </header>
            <ul class="stops">
              <li v-for="(s, i) in stops" :key="i">
                <span class="num">{{ fmtClock(s.started_at) }}</span>
                <span class="dot" style="background:#ef4444"></span>
                <span>{{ fmtBucketSeconds(s.duration_s) }} stop</span>
                <a
                  class="muted small"
                  :href="`https://www.openstreetmap.org/?mlat=${s.lat}&mlon=${s.lon}&zoom=17`"
                  target="_blank"
                  rel="noopener"
                >
                  {{ s.lat.toFixed(4) }}, {{ s.lon.toFixed(4) }}
                </a>
              </li>
            </ul>
          </div>

          <div v-if="speedTotalSeconds > 0" class="card">
            <h3>Speed distribution</h3>
            <div class="bucket-bar">
              <div
                v-for="b in speedDistribution"
                :key="b.label"
                class="bucket"
                :style="{
                  background: b.color,
                  width: ((b.seconds / speedTotalSeconds) * 100).toFixed(2) + '%',
                }"
                :title="`${b.label} · ${fmtBucketSeconds(b.seconds)}`"
              />
            </div>
            <dl class="bucket-list">
              <template v-for="b in speedDistribution" :key="b.label">
                <dt>
                  <span class="dot" :style="{ background: b.color }"></span>
                  {{ b.label }}
                </dt>
                <dd>
                  {{ fmtBucketSeconds(b.seconds) }}
                  <span class="muted small">
                    ({{ ((b.seconds / speedTotalSeconds) * 100).toFixed(0) }}%)
                  </span>
                </dd>
              </template>
            </dl>
          </div>

          <div v-if="elevationStats" class="card">
            <header class="chart-head">
              <h3>Elevation</h3>
              <span class="muted small">
                {{ elevationStats.min.toFixed(0) }}–{{ elevationStats.max.toFixed(0) }} m
                · climb {{ elevationStats.climb.toFixed(0) }} m
              </span>
            </header>
            <svg
              :viewBox="`0 0 ${elevationProfile.length} 100`"
              preserveAspectRatio="none"
              class="elev-svg"
            >
              <polyline
                :points="elevationProfile.map((p, i) => {
                  const min = elevationStats!.min;
                  const max = elevationStats!.max;
                  const range = Math.max(1, max - min);
                  const y = 100 - ((p.alt_m - min) / range) * 90 - 5;
                  return `${i},${y}`;
                }).join(' ')"
                fill="none"
                stroke="#22c55e"
                stroke-width="1.4"
                vector-effect="non-scaling-stroke"
              />
            </svg>
          </div>
        </section>

        <aside class="side-col">
          <div class="card">
            <h3>Stats</h3>
            <dl class="stats">
              <dt>Duration</dt>
              <dd>{{ fmtDuration(trip.duration_s) }}</dd>
              <dt>Distance</dt>
              <dd>{{ fmtDistanceKm(trip.distance_km ?? null) }}</dd>
              <dt>Max speed</dt>
              <dd>{{ fmtSpeedKph(trip.max_speed_kph ?? null) }}</dd>
              <dt>Max RPM</dt>
              <dd>{{ fmtRpm(trip.max_rpm) }}</dd>
              <dt>Fuel used</dt>
              <dd>{{ fmtVolumeL(trip.fuel_used_l ?? null) }}</dd>
              <dt v-if="trip.weather_temp_c != null">Weather</dt>
              <dd v-if="trip.weather_temp_c != null">
                <span>{{ weatherEmoji(trip.weather_code) }}</span>
                {{ fmtTempC(trip.weather_temp_c) }}
                <span v-if="trip.weather_wind_kph != null" class="muted small">
                  · wind {{ Math.round(trip.weather_wind_kph * 0.621371) }} mph
                </span>
                <span v-if="trip.weather_humidity_pct != null" class="muted small">
                  · {{ trip.weather_humidity_pct }}% rh
                </span>
              </dd>
              <dt v-if="trip.idle_s != null && trip.idle_s > 0">Idle</dt>
              <dd v-if="trip.idle_s != null && trip.idle_s > 0">
                {{ fmtBucketSeconds(trip.idle_s) }}
                <span class="muted small">
                  · {{ trip.duration_s ? Math.round((trip.idle_s / trip.duration_s) * 100) : 0 }}%
                </span>
              </dd>
              <template v-if="trip.odo_start_km != null && trip.odo_end_km != null">
                <dt>Odo start</dt>
                <dd><span class="num">{{ fmtOdoMi(trip.odo_start_km) }}</span> mi</dd>
                <dt>Odo end</dt>
                <dd><span class="num">{{ fmtOdoMi(trip.odo_end_km) }}</span> mi</dd>
                <dt>Distance (odo Δ)</dt>
                <dd><span class="num">{{ fmtOdoDeltaMi(odoDelta) }}</span></dd>
              </template>
              <template v-if="trip.fuel_level_start_pct != null && trip.fuel_level_end_pct != null">
                <dt>Fuel level</dt>
                <dd>
                  <span class="num">{{ Math.round(trip.fuel_level_start_pct) }}%</span>
                  <span class="muted">→</span>
                  <span class="num">{{ Math.round(trip.fuel_level_end_pct) }}%</span>
                </dd>
              </template>
              <template v-if="trip.fuel_used_l != null && trip.fuel_used_l > 0.01">
                <dt>Gas used (est.)</dt>
                <dd>
                  <span class="num">{{ (trip.fuel_used_l * 0.264172).toFixed(2) }}</span> gal
                </dd>
              </template>
              <dt>DTCs</dt>
              <dd>
                <span>{{ trip.dtc_count ?? trip.dtcs?.length ?? 0 }}</span>
                <ul v-if="trip.dtcs?.length" class="dtc-inline">
                  <li v-for="d in trip.dtcs" :key="d.id">
                    <code>{{ d.code }}</code>
                    <span v-if="d.description" class="muted small">
                      — {{ d.description }}
                    </span>
                  </li>
                </ul>
              </dd>
              <dt>Started</dt>
              <dd>{{ fmtDateTime(trip.started_at) }}</dd>
              <dt>Ended</dt>
              <dd>{{ fmtDateTime(trip.ended_at) }}</dd>
            </dl>
          </div>

          <div v-if="baselineRows.length" class="card">
            <header class="chart-head">
              <h3>vs. your average</h3>
              <span class="muted small">
                {{ baseline?.bucket_label }} · n={{ baseline?.sample_size }}
              </span>
            </header>
            <table class="baseline">
              <thead>
                <tr>
                  <th></th>
                  <th>This</th>
                  <th>Avg</th>
                  <th>Δ</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="r in baselineRows" :key="r.label">
                  <th>{{ r.label }}</th>
                  <td class="num">{{ r.thisTrip }}</td>
                  <td class="num muted">{{ r.avg }}</td>
                  <td
                    v-if="r.deltaPct !== null"
                    class="num"
                    :class="deltaTone(r.label, r.deltaPct)"
                  >
                    {{ r.deltaPct >= 0 ? "+" : "" }}{{ r.deltaPct.toFixed(0) }}%
                  </td>
                  <td v-else class="muted">—</td>
                </tr>
              </tbody>
            </table>
          </div>

          <div class="card">
            <h3>Notes &amp; tags</h3>
            <label class="towing-toggle">
              <input type="checkbox" v-model="towingDraft" />
              <span>
                <strong>Towing</strong>
                <small class="muted">
                  Fuel economy under tow is not comparable to an ordinary
                  trip. Flagging it keeps that obvious when you look back.
                </small>
              </span>
            </label>
            <label>
              Category
              <input
                v-model="categoryDraft"
                list="trip-category-suggestions"
                placeholder="Commute, Errands, Road trip, …"
              />
              <datalist id="trip-category-suggestions">
                <option value="Commute" />
                <option value="Errands" />
                <option value="Road trip" />
                <option value="Work" />
                <option value="Leisure" />
                <option value="Personal" />
              </datalist>
            </label>
            <label>
              Notes
              <textarea v-model="notesDraft" rows="4" />
            </label>
            <p v-if="metaError" class="error">{{ metaError }}</p>
            <button
              class="primary"
              type="button"
              :disabled="savingMeta"
              @click="saveMeta"
            >
              {{ savingMeta ? "Saving…" : "Save" }}
            </button>
          </div>

          <div v-if="trip.samples?.length" class="card">
            <h3>Quick stats</h3>
            <dl class="stats">
              <dt>Samples</dt>
              <dd>{{ trip.samples.length }}</dd>
              <dt>Coolant peak</dt>
              <dd>
                {{
                  fmtTemp(
                    trip.samples.reduce(
                      (m, s) => Math.max(m, s.coolant_temp ?? -Infinity),
                      -Infinity,
                    ) === -Infinity
                      ? null
                      : trip.samples.reduce(
                          (m, s) => Math.max(m, s.coolant_temp ?? -Infinity),
                          -Infinity,
                        ),
                  )
                }}
              </dd>
            </dl>
          </div>
        </aside>
      </div>
    </template>
  </div>
</template>

<style scoped>
.trip-detail {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}
.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.head .left {
  display: flex;
  align-items: center;
  gap: 0.7rem;
}
.back {
  display: inline-flex;
  align-items: center;
  gap: 0.2rem;
  color: var(--c-muted);
}
.layout {
  display: grid;
  grid-template-columns: 1fr 320px;
  gap: 1rem;
  align-items: start;
}
.main-col,
.side-col {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}
.chart-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.head-tools {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}
.towing-toggle {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-bottom: 10px;
}
.towing-toggle input {
  width: auto;
  margin-top: 3px;
  flex: none;
}
.towing-toggle small {
  display: block;
  font-size: 0.78rem;
  line-height: 1.35;
}

.toggle-chip {
  padding: 0.2rem 0.6rem;
  font-size: 0.75rem;
  border-radius: 999px;
  border: 1px solid var(--c-border-soft);
  background: var(--c-surface);
  color: var(--c-muted);
  cursor: pointer;
}
.toggle-chip.active {
  border-color: #f97316;
  color: #f97316;
}
.toggle-chip:hover {
  opacity: 0.85;
}
.smooth-chip {
  display: inline-flex;
  align-items: baseline;
  gap: 0.25rem;
}
.smooth-chip .muted.small {
  font-size: 0.7rem;
  opacity: 0.85;
}
.smooth-chip.active .muted.small {
  color: inherit;
  opacity: 1;
}
.stats {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0.4rem 0.6rem;
  margin: 0;
}
.stats dt {
  color: var(--c-muted);
  font-size: 0.78rem;
  text-transform: uppercase;
  letter-spacing: 0.05em;
}
.stats dd {
  margin: 0;
  font-weight: 500;
}
.trip-summary {
  margin: 0;
  padding: 0.7rem 0.95rem;
  background: var(--c-surface-soft);
  border-left: 3px solid var(--c-accent);
  border-radius: var(--r-md);
  color: var(--c-text);
  font-size: 0.95rem;
  line-height: 1.45;
}
.tod-badge {
  display: inline-flex;
  align-items: center;
  padding: 0.05rem 0.55rem;
  border-radius: 999px;
  font-size: 0.78rem;
  font-weight: 500;
  border: 1px solid var(--c-border-soft);
  background: var(--c-surface-soft);
  color: var(--c-muted);
  margin-left: 0.6rem;
}
.tod-badge.tone-rush {
  border-color: #f59e0b66;
  background: #f59e0b22;
  color: #f59e0b;
}
.tod-badge.tone-night {
  border-color: #6366f166;
  background: #6366f122;
  color: #818cf8;
}
.tod-badge.tone-weekend {
  border-color: #22c55e66;
  background: #22c55e22;
  color: #22c55e;
}
.tod-badge.tone-offpeak {
  border-color: #2f81f766;
  background: #2f81f722;
  color: #2f81f7;
}
.stops {
  list-style: none;
  margin: 0.3rem 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 0.3rem;
}
.stops li {
  display: grid;
  grid-template-columns: 4rem auto 1fr auto;
  gap: 0.6rem;
  align-items: center;
  font-size: 0.88rem;
}
.stops a {
  color: var(--c-muted);
  text-decoration: none;
}
.stops a:hover {
  color: var(--c-text);
  text-decoration: underline;
}
.baseline {
  width: 100%;
  border-collapse: collapse;
  margin-top: 0.3rem;
}
.baseline th, .baseline td {
  padding: 0.25rem 0.4rem;
  text-align: left;
  border-bottom: 1px solid var(--c-border-soft);
  font-size: 0.85rem;
}
.baseline thead th {
  color: var(--c-muted);
  font-size: 0.72rem;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  border-bottom-color: var(--c-border);
}
.baseline tbody th {
  font-weight: 500;
  color: var(--c-text);
}
.baseline td.num {
  text-align: right;
  font-variant-numeric: tabular-nums;
}
.baseline td.good { color: #3fb950; }
.baseline td.bad { color: #ef4444; }
.baseline td.neutral { color: var(--c-muted); }
.dtc-inline {
  list-style: none;
  margin: 0.3rem 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 0.15rem;
}
.dtc-inline code {
  background: var(--c-surface-soft);
  padding: 0 0.3rem;
  border-radius: 3px;
  color: #ef4444;
  font-size: 0.78rem;
}
.num {
  font-variant-numeric: tabular-nums;
}
.card label {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  font-size: 0.85rem;
  color: var(--c-muted);
  margin-bottom: 0.6rem;
}
.error {
  color: var(--c-danger);
}
@media (max-width: 900px) {
  .layout {
    grid-template-columns: 1fr;
  }
}
.speed-legend {
  display: flex;
  flex-wrap: wrap;
  gap: 0.6rem;
  margin-top: 0.5rem;
  font-size: 0.78rem;
  color: var(--c-muted);
  align-items: center;
}
.speed-legend .toggle {
  display: inline-flex;
  align-items: center;
  gap: 0.3rem;
  cursor: pointer;
  color: var(--c-text);
}
.heatmap-gradient {
  display: inline-block;
  width: 200px;
  height: 8px;
  border-radius: 4px;
  background: linear-gradient(
    to right,
    hsl(0, 80%, 50%),
    hsl(60, 80%, 50%),
    hsl(120, 80%, 50%),
    hsl(180, 80%, 50%),
    hsl(240, 80%, 50%),
    hsl(300, 80%, 50%)
  );
}
.speed-legend .dot,
.bucket-list .dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-right: 0.4em;
  vertical-align: middle;
}
.bucket-bar {
  display: flex;
  width: 100%;
  height: 14px;
  border-radius: 999px;
  overflow: hidden;
  margin-bottom: 0.6rem;
}
.bucket-bar .bucket {
  height: 100%;
}
.bucket-list {
  display: grid;
  grid-template-columns: max-content auto;
  gap: 0.3rem 0.8rem;
  margin: 0;
  font-size: 0.85rem;
}
.bucket-list dt {
  color: var(--c-ink1);
}
.bucket-list dd {
  margin: 0;
  color: var(--c-muted);
}
.elev-svg {
  width: 100%;
  height: 80px;
}
.series-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 0.4rem;
  margin: 0.4rem 0 0.6rem 0;
}
.series-chips .chip.empty {
  opacity: 0.35;
  cursor: not-allowed;
}
.series-chips .chip.empty:hover {
  opacity: 0.45;
}
.series-chips .chip {
  padding: 0.28rem 0.6rem;
  border-radius: 999px;
  border: 1px solid var(--c-border-soft);
  background: var(--c-surface);
  color: var(--c-muted);
  font-size: 0.78rem;
  cursor: pointer;
}
.series-chips .chip:hover {
  background: var(--c-surface-2);
}
.series-chips .chip.active {
  background: var(--c-surface-2);
  border-width: 1.5px;
}
</style>
