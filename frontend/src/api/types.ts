// Types mirror the API contract documented in CLAUDE thread for Phase B.
// If the backend OpenAPI is wired up later, swap to openapi-typescript-generated types.

export interface Vehicle {
  id: string;
  name: string;
  slug: string;
  description?: string | null;
  make?: string | null;
  model?: string | null;
  year?: number | null;
  vin?: string | null;
  fuelio_guid?: string | null;
  pid_profile_id?: string | null;
  pid_profile?: { id: string; name: string; description?: string | null } | null;
  tank_count?: number | null;
  tank1_capacity?: number | null;
  tank2_capacity?: number | null;
  // Fuelio unit codes (used by the Auto units toggle):
  //   dist_unit:        0=km, 1=mi
  //   fuel_unit:        0=l, 1=us_gal, 2=uk_gal
  //   consumption_unit: 0=l/100km, 1=mpg_us, 2=mpg_uk, 3=km/l
  dist_unit?: number | null;
  fuel_unit?: number | null;
  consumption_unit?: number | null;
  active?: boolean;
  // server-augmented fields (if available)
  last_seen_at?: string | null;
  last_metric?: string | null;
  latest?: Record<string, number | string | null> | null;
  // Lifetime odometer reading from the freshest pid_readings entry
  // (or fillup history when no live data). Stored canonically in km;
  // convert at render time per the user's preferred units.
  latest_odo_km?: number | null;
  latest_odo_at?: string | null;
  // Lifetime cost-of-ownership inputs (Task #98). Both nullable
  // — the COO card hides the headline number until the user
  // enters a purchase price.
  purchase_price?: number | null;
  purchase_date?: string | null;
  // EPA combined MPG sticker. Reference line on the MPG chart; null
  // hides the overlay. (Task #90)
  epa_mpg_combined?: number | null;
}

export interface Profile {
  id: string;
  name: string;
  description?: string | null;
  body?: unknown;
}

export interface Reading {
  time: string;
  vehicle_id: string;
  metric: string;
  value_num?: number | null;
  value_text?: string | null;
  source?: string | null;
}

export interface AggregateBucket {
  bucket: string;
  avg: number | null;
  min: number | null;
  max: number | null;
  count: number;
}

export interface Trip {
  id: string;
  vehicle_id: string;
  started_at: string;
  ended_at?: string | null;
  duration_s?: number | null;
  // Backend stores SI canonical units (km, kph, litres); frontend
  // converts at render time per the user's preferred system. Old
  // mi/mph/gal aliases kept optional so any in-flight responses with
  // the old shape don't error out.
  distance_km?: number | null;
  max_speed_kph?: number | null;
  avg_speed_kph?: number | null;
  avg_coolant_c?: number | null;
  max_rpm?: number | null;
  fuel_used_l?: number | null;
  dtc_count?: number | null;
  // Seconds engine-on but stopped (Task #91). Null on legacy trips
  // until the deriver re-runs.
  idle_s?: number | null;
  category?: string | null;
  notes?: string | null;
  // Per-trip weather observation captured at trip-open via
  // services/weather.py (Task #78). Sampled at the first GPS point
  // in the trip window — accurate to a city block + 1 hour.
  weather_temp_c?: number | null;
  weather_humidity_pct?: number | null;
  weather_precip_mm?: number | null;
  weather_wind_kph?: number | null;
  weather_code?: number | null;
  /** @deprecated use distance_km */ distance_mi?: number | null;
  /** @deprecated use max_speed_kph */ max_speed?: number | null;
  /** @deprecated use fuel_used_l */ fuel_used?: number | null;
}

/**
 * Lookup table for WMO weather codes 0..99 → short label + emoji
 * the UI can show alongside the numeric reading. Values follow
 * Open-Meteo's interpretation of the WMO standard. Unknown codes
 * fall back to "—".
 * Reference: https://open-meteo.com/en/docs (search "WMO Weather code")
 */
export const WMO_CODE: Record<number, { label: string; icon: string }> = {
  0: { label: "clear", icon: "☀️" },
  1: { label: "mainly clear", icon: "🌤" },
  2: { label: "partly cloudy", icon: "⛅" },
  3: { label: "overcast", icon: "☁️" },
  45: { label: "fog", icon: "🌫" },
  48: { label: "rime fog", icon: "🌫" },
  51: { label: "light drizzle", icon: "🌦" },
  53: { label: "drizzle", icon: "🌦" },
  55: { label: "heavy drizzle", icon: "🌧" },
  61: { label: "light rain", icon: "🌧" },
  63: { label: "rain", icon: "🌧" },
  65: { label: "heavy rain", icon: "🌧" },
  71: { label: "light snow", icon: "🌨" },
  73: { label: "snow", icon: "🌨" },
  75: { label: "heavy snow", icon: "❄️" },
  77: { label: "snow grains", icon: "🌨" },
  80: { label: "rain showers", icon: "🌦" },
  81: { label: "rain showers", icon: "🌧" },
  82: { label: "violent showers", icon: "🌧" },
  85: { label: "snow showers", icon: "🌨" },
  86: { label: "snow showers", icon: "❄️" },
  95: { label: "thunderstorm", icon: "⛈" },
  96: { label: "thunder + hail", icon: "⛈" },
  99: { label: "severe thunder", icon: "⛈" },
};

export interface TripSample {
  time: string;
  vehicle_speed?: number | null;
  engine_rpm?: number | null;
  coolant_temp?: number | null;
  throttle_position?: number | null;
  fuel_level?: number | null;
  control_module_voltage?: number | null;
  gps_lat?: number | null;
  gps_lon?: number | null;
}

export interface TripDtcRef {
  id: string;
  code: string;
  seen_at: string;
  description?: string | null;
}

export interface TripImuEvent {
  t: string;
  magnitude: number;
  lat?: number | null;
  lon?: number | null;
}

export interface TripDetail extends Trip {
  samples: TripSample[];
  // Closest pid_readings odometer value within ±15 min of trip
  // start / end. Null when WiCAN didn't publish in that window.
  odo_start_km?: number | null;
  odo_end_km?: number | null;
  // DTCs that fired during the trip's open window (from
  // dtc_events.seen_at, ascending).
  dtcs?: TripDtcRef[];
  // Hard-accel/brake/cornering events: 1s buckets where |linear-accel|
  // exceeded the threshold. Includes the nearest gps_point for map
  // markers. (Task #111)
  imu_events?: TripImuEvent[];
}

export interface Dtc {
  id: string;
  vehicle_id: string;
  code: string;
  description?: string | null;
  detected_at: string;
  cleared_at?: string | null;
  active: boolean;
}

export interface HaSettings {
  enabled: boolean;
  url?: string | null;
  token_set: boolean;
  discovery_prefix?: string | null;
  per_pid_toggles?: Record<string, boolean> | null;
}

export interface HomeLocation {
  lat?: number | null;
  lon?: number | null;
}

export interface Settings {
  ha: HaSettings;
  home: HomeLocation;
  disk_alert_pct?: number | null;
  /** Null = no auto-purge for OBD readings. Positive integer = nightly cron. */
  retention_readings_days?: number | null;
  /** Null = no auto-purge for client logs (warn/info/error). */
  retention_logs_days?: number | null;
  /** Null = no auto-purge for level='debug' rows specifically. */
  retention_logs_debug_days?: number | null;
}

// Field names mirror the backend API exactly (which mirrors Fuelio's CSV
// shape — odo / fuel_volume / price_total / mpg). Renames here would require
// a transform in every endpoint client, which we deliberately avoid.
export interface Fillup {
  id: string;
  vehicle_id: string;
  fillup_date: string;
  odo?: number | null;             // miles or km depending on vehicle.dist_unit
  fuel_volume?: number | null;     // gallons or l depending on vehicle.fuel_unit
  is_full?: boolean;
  is_missed?: boolean;
  // Both NUMERIC(10,N) — backend's Pydantic serialises Decimal as JSON
  // string, so consumers must coerce via Number() / our `toNum` helper
  // before doing arithmetic. The type reflects that reality.
  price_total?: number | string | null;
  price_per_unit?: number | string | null;
  station_id?: number | null;
  city?: string | null;
  lat?: number | null;
  lon?: number | null;
  notes?: string | null;
  tank_number?: number | null;
  fuel_type?: number | null;
  /** Fuelio's free-text weather field (rarely populated). Different
   *  from the structured weather_* columns populated by the backend
   *  Open-Meteo fetcher (Task #78). */
  weather?: string | null;
  weather_temp_c?: number | null;
  weather_humidity_pct?: number | null;
  weather_precip_mm?: number | null;
  weather_wind_kph?: number | null;
  weather_code?: number | null;
  exclude_distance?: boolean;
  // Computed: backend returns `mpg` (recomputed from odo deltas + volume,
  // Fuelio-style chain rule with partial-fill rollup) and `mpg_reported`
  // (the value in the source export, kept for comparison).
  mpg?: number | null;
  mpg_reported?: number | null;
  fuelio_guid?: string | null;
}

export interface Expense {
  id: string;
  vehicle_id: string;
  title: string;
  expense_date: string;
  odometer?: number | null;
  cost: number;
  category_id?: string | null;
  category_name?: string | null;
  notes?: string | null;
  remind_odo?: number | null;
  remind_date?: string | null;
  repeat_odo?: number | null;
  repeat_months?: number | null;
  is_income?: boolean;
  is_template?: boolean;
}

export interface Category {
  id: string;
  name: string;
  priority?: number | null;
  color?: string | null;
}

export interface Reminder {
  expense_id: string;
  vehicle_id: string;
  title: string;
  category_name?: string | null;
  current_odo?: number | null;
  remind_odo?: number | null;
  remind_date?: string | null;
  miles_remaining?: number | null;
  days_remaining?: number | null;
  status: "overdue" | "upcoming";
}

export interface ReminderGroup {
  overdue: Reminder[];
  upcoming: Reminder[];
}

export interface MpgPoint {
  period: string;
  mpg: number | null;
  fillup_count?: number;
}

export interface CostPerMiPoint {
  period: string;
  cost_per_mi: number | null;
  miles?: number | null;
  total_cost?: number | null;
}

export interface MonthlySpend {
  month: string;
  fuel: number;
  service: number;
  total: number;
}

export interface StationCluster {
  cluster_id: string;
  lat: number | null;
  lon: number | null;
  name?: string | null;
  fillup_count: number;
  total_volume?: number | null;
  total_cost?: number | null;
  last_visit?: string | null;
}

/**
 * /analytics/mpg-overlay returns two parallel time-series:
 *   obd_mpg     — derived per-trip from distance / fuel-used-l
 *   fillup_mpg  — recomputed per-fillup from (distance since last full / volume)
 * Both are sparse — each entry is a single sample.
 */
export interface MpgOverlay {
  obd_mpg: { time: string; mpg: number }[];
  fillup_mpg: { time: string; mpg: number }[];
}

export interface LiveMessage {
  vehicle_id: string;
  time: string;
  metric: string;
  value_num?: number | null;
  value_text?: string | null;
  source?: string | null;
}

export type AnalyticsWindow = "month" | "3m" | "year" | "all";

// ─── logs ─────────────────────────────────────────────────────────────

export type LogLevel = "debug" | "info" | "warn" | "error";
export type LogSource = "phone" | "web" | "backend" | "wican" | "test";

export interface LogEntry {
  id: number | string;
  ts: string;
  source: LogSource;
  level: LogLevel;
  message: string;
  vehicle_id?: string | null;
  device_id?: string | null;
  context?: Record<string, unknown> | null;
  client_ts?: string | null;
}

export interface LogIngestEntry {
  ts?: string;
  source: LogSource;
  level: LogLevel;
  message: string;
  vehicle_id?: string | null;
  device_id?: string | null;
  client_ts?: string;
  context?: Record<string, unknown> | null;
}

export interface LogRecentParams {
  source?: string;
  level?: string;
  vehicle_id?: string;
  from?: string;
  to?: string;
  q?: string;
  limit?: number;
}

export interface FuelioImportPreview {
  dry_run: boolean;
  vehicles?: { name: string; guid: string; existing: boolean }[];
  fillups?: { total: number; new: number; updated: number };
  expenses?: { total: number; new: number; updated: number };
  pictures?: { total: number };
  warnings?: string[];
  errors?: string[];
  // backend may include other fields; we accept them as-is
  [key: string]: unknown;
}
