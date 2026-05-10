import { apiQuery, apiIngest } from "./index";
import type {
  Vehicle,
  Profile,
  Reading,
  AggregateBucket,
  Trip,
  TripDetail,
  Dtc,
  Settings,
  Fillup,
  Expense,
  Category,
  ReminderGroup,
  MpgPoint,
  CostPerMiPoint,
  MonthlySpend,
  StationCluster,
  MpgOverlay,
  AnalyticsWindow,
  FuelioImportPreview,
  LogEntry,
  LogIngestEntry,
  LogRecentParams,
} from "./types";

// ─── vehicles ─────────────────────────────────────────────────────────

export async function listVehicles(): Promise<Vehicle[]> {
  const r = await apiQuery.get<Vehicle[]>("/vehicles");
  return r.data;
}
export async function getVehicle(id: string): Promise<Vehicle> {
  const r = await apiQuery.get<Vehicle>(`/vehicles/${id}`);
  return r.data;
}
export async function createVehicle(payload: Partial<Vehicle>): Promise<Vehicle> {
  const r = await apiIngest.post<Vehicle>("/vehicles", payload);
  return r.data;
}
export async function updateVehicle(id: string, payload: Partial<Vehicle>): Promise<Vehicle> {
  const r = await apiIngest.patch<Vehicle>(`/vehicles/${id}`, payload);
  return r.data;
}
export async function deleteVehicle(id: string): Promise<void> {
  await apiIngest.delete(`/vehicles/${id}`);
}

// ─── profiles ─────────────────────────────────────────────────────────

export async function listProfiles(): Promise<Profile[]> {
  const r = await apiQuery.get<Profile[]>("/profiles");
  return r.data;
}
export async function getProfile(id: string): Promise<Profile> {
  const r = await apiQuery.get<Profile>(`/profiles/${id}`);
  return r.data;
}
export async function createProfile(payload: Partial<Profile>): Promise<Profile> {
  const r = await apiIngest.post<Profile>("/profiles", payload);
  return r.data;
}
export async function updateProfile(id: string, payload: Partial<Profile>): Promise<Profile> {
  const r = await apiIngest.put<Profile>(`/profiles/${id}`, payload);
  return r.data;
}
export async function deleteProfile(id: string): Promise<void> {
  await apiIngest.delete(`/profiles/${id}`);
}

// ─── readings ─────────────────────────────────────────────────────────

export interface ReadingsParams {
  vehicle_id: string;
  metric: string;
  from?: string;
  to?: string;
  limit?: number;
}
export async function listReadings(p: ReadingsParams): Promise<Reading[]> {
  const r = await apiQuery.get<Reading[]>("/readings", { params: p });
  return r.data;
}

export interface AggregateParams extends ReadingsParams {
  bucket: "hour" | "day";
}
export async function aggregateReadings(p: AggregateParams): Promise<AggregateBucket[]> {
  const r = await apiQuery.get<AggregateBucket[]>("/readings/aggregate", { params: p });
  return r.data;
}

// ─── trips ────────────────────────────────────────────────────────────

export interface TripListParams {
  vehicle_id?: string;
  from?: string;
  to?: string;
  limit?: number;
  offset?: number;
}
export async function listTrips(
  p: TripListParams,
): Promise<{ items: Trip[]; total: number }> {
  const r = await apiQuery.get<Trip[]>("/trips", { params: p });
  const total = parseInt(r.headers["x-total-count"] ?? "0", 10) || r.data.length;
  return { items: r.data, total };
}
export interface ServerVersion {
  version: string;
  git_sha: string;
  build_time: string;
}
export async function getVersion(): Promise<ServerVersion> {
  // /version is unauthenticated — include skipAuth via raw axios.
  const r = await apiQuery.get<ServerVersion>("/version");
  return r.data;
}

export async function getTrip(id: string): Promise<TripDetail> {
  const r = await apiQuery.get<TripDetail>(`/trips/${id}`);
  return r.data;
}
export interface TripRoutePoint {
  t: string;
  lat: number;
  lon: number;
  alt_m: number | null;
  speed_mps: number | null;
  heading_deg: number | null;
  accuracy_m: number | null;
}
export async function getTripRoute(id: string): Promise<{ trip_id: string; points: TripRoutePoint[] }> {
  const r = await apiQuery.get<{ trip_id: string; points: TripRoutePoint[] }>(`/trips/${id}/route`);
  return r.data;
}
export async function updateTrip(id: string, payload: Partial<Trip>): Promise<Trip> {
  const r = await apiIngest.patch<Trip>(`/trips/${id}`, payload);
  return r.data;
}
export async function deleteTrip(id: string): Promise<void> {
  await apiIngest.delete(`/trips/${id}`);
}

// ─── DTCs ─────────────────────────────────────────────────────────────

export async function listDtcs(vehicleId: string, activeOnly = true): Promise<Dtc[]> {
  const r = await apiQuery.get<Dtc[]>("/dtcs", {
    params: { vehicle_id: vehicleId, active_only: activeOnly },
  });
  return r.data;
}
export async function clearDtc(id: string): Promise<void> {
  await apiIngest.post(`/dtcs/clear/${id}`);
}
export interface DtcTimelineCode {
  code: string;
  description: string | null;
  count: number;
  first_seen: string;
  last_seen: string;
  active: boolean;
  events: { id: string; seen_at: string }[];
}
export interface DtcTimeline {
  codes: DtcTimelineCode[];
  window_days: number;
}
export async function getDtcsTimeline(
  vehicleId: string,
  days = 365,
): Promise<DtcTimeline> {
  const r = await apiQuery.get<DtcTimeline>("/dtcs/timeline", {
    params: { vehicle_id: vehicleId, days },
  });
  return r.data;
}

// ─── settings ─────────────────────────────────────────────────────────

export async function getSettings(): Promise<Settings> {
  const r = await apiQuery.get<Settings>("/settings");
  return r.data;
}
export async function updateSettings(payload: Partial<Settings>): Promise<Settings> {
  const r = await apiIngest.patch<Settings>("/settings", payload);
  return r.data;
}
export async function testHa(): Promise<{ ok: boolean; status_code?: number }> {
  const r = await apiIngest.post<{ ok: boolean; status_code?: number }>("/settings/ha/test");
  return r.data;
}

// ─── fillups ──────────────────────────────────────────────────────────

export interface FillupListParams {
  vehicle_id?: string;
  from?: string;
  to?: string;
  limit?: number;
  offset?: number;
}
export async function listFillups(
  p: FillupListParams,
): Promise<{ items: Fillup[]; total: number }> {
  const r = await apiQuery.get<Fillup[]>("/fillups", { params: p });
  const total = parseInt(r.headers["x-total-count"] ?? "0", 10) || r.data.length;
  return { items: r.data, total };
}
export async function createFillup(payload: Partial<Fillup>): Promise<Fillup> {
  const r = await apiIngest.post<Fillup>("/fillups", payload);
  return r.data;
}
export async function updateFillup(id: string, payload: Partial<Fillup>): Promise<Fillup> {
  const r = await apiIngest.patch<Fillup>(`/fillups/${id}`, payload);
  return r.data;
}
export async function deleteFillup(id: string): Promise<void> {
  await apiIngest.delete(`/fillups/${id}`);
}

// ─── expenses ─────────────────────────────────────────────────────────

export interface ExpenseListParams {
  vehicle_id?: string;
  category_id?: string;
  from?: string;
  to?: string;
}
export async function listExpenses(p: ExpenseListParams): Promise<Expense[]> {
  const r = await apiQuery.get<Expense[]>("/expenses", { params: p });
  return r.data;
}
export async function createExpense(payload: Partial<Expense>): Promise<Expense> {
  const r = await apiIngest.post<Expense>("/expenses", payload);
  return r.data;
}
export async function updateExpense(id: string, payload: Partial<Expense>): Promise<Expense> {
  const r = await apiIngest.patch<Expense>(`/expenses/${id}`, payload);
  return r.data;
}
export async function deleteExpense(id: string): Promise<void> {
  await apiIngest.delete(`/expenses/${id}`);
}
export async function listCategories(): Promise<Category[]> {
  const r = await apiQuery.get<Category[]>("/expense-categories");
  return r.data;
}

// ─── analytics ────────────────────────────────────────────────────────

export async function mpgTrend(
  vehicleId: string,
  window: AnalyticsWindow,
): Promise<{ points: MpgPoint[] }> {
  const r = await apiQuery.get<{ points: MpgPoint[] }>("/analytics/mpg", {
    params: { vehicle_id: vehicleId, window },
  });
  return r.data;
}
export async function costPerMile(
  vehicleId: string,
  window: AnalyticsWindow,
): Promise<{ points: CostPerMiPoint[] }> {
  const r = await apiQuery.get<{ points: CostPerMiPoint[] }>("/analytics/cost-per-mi", {
    params: { vehicle_id: vehicleId, window },
  });
  return r.data;
}
export async function monthlySpend(
  vehicleId: string,
  months = 12,
): Promise<{ months: MonthlySpend[] }> {
  const r = await apiQuery.get<{ months: MonthlySpend[] }>("/analytics/monthly-spend", {
    params: { vehicle_id: vehicleId, months },
  });
  return r.data;
}
export async function stationsCluster(vehicleId: string): Promise<StationCluster[]> {
  const r = await apiQuery.get<StationCluster[]>("/analytics/stations", {
    params: { vehicle_id: vehicleId },
  });
  return r.data;
}

export interface StationPrice {
  cluster_id: string;
  name: string | null;
  lat: number | null;
  lon: number | null;
  fillup_count: number;
  latest_price: number;
  latest_date: string;
  avg_price: number;
  delta_pct: number | null;
  recent: { date: string; price_per_unit: number; fuel_volume: number }[];
}

export async function stationPrices(
  vehicleId?: string,
  limitPerStation = 5,
): Promise<StationPrice[]> {
  const r = await apiQuery.get<StationPrice[]>("/analytics/station-prices", {
    params: {
      ...(vehicleId ? { vehicle_id: vehicleId } : {}),
      limit_per_station: limitPerStation,
    },
  });
  return r.data;
}
export interface EiaWeeklyPoint {
  week_of: string;
  price: number;
}
export interface EiaWeekly {
  region: string;
  points: EiaWeeklyPoint[];
}

export async function eiaWeekly(
  region = "midwest",
  weeks = 52,
): Promise<EiaWeekly> {
  const r = await apiQuery.get<EiaWeekly>("/analytics/eia-weekly", {
    params: { region, weeks },
  });
  return r.data;
}

export async function mpgOverlay(
  vehicleId: string,
  from?: string,
  to?: string,
): Promise<MpgOverlay> {
  const r = await apiQuery.get<MpgOverlay>("/analytics/mpg-overlay", {
    params: { vehicle_id: vehicleId, from, to },
  });
  return r.data;
}

export interface CostBreakdownMonth {
  month: string;
  total: number;
  categories: Record<string, number>;
}
export interface CostBreakdown {
  months: CostBreakdownMonth[];
  summary: Record<string, number>;
  category_order: string[];
}
export async function getCostBreakdown(
  vehicleId: string,
  months = 12,
): Promise<CostBreakdown> {
  const r = await apiQuery.get<CostBreakdown>("/analytics/cost-breakdown", {
    params: { vehicle_id: vehicleId, months },
  });
  return r.data;
}

export interface CostOfOwnership {
  purchase_price: number | null;
  purchase_date: string | null;
  fuel_total: number;
  maintenance_total: number;
  total: number;
  lifetime_mi: number | null;
  cost_per_mi: number | null;
}
export async function getCostOfOwnership(vehicleId: string): Promise<CostOfOwnership> {
  const r = await apiQuery.get<CostOfOwnership>("/analytics/cost-of-ownership", {
    params: { vehicle_id: vehicleId },
  });
  return r.data;
}

export interface FuelGradeRow {
  grade: number;
  fillup_count: number;
  avg_mpg: number | null;
  avg_price_per_unit: number | null;
  total_volume: number;
  total_cost: number;
  first_fillup: string;
  last_fillup: string;
}
export async function getFuelGradeBreakdown(
  vehicleId: string,
): Promise<{ grades: FuelGradeRow[] }> {
  const r = await apiQuery.get<{ grades: FuelGradeRow[] }>("/analytics/fuel-grade", {
    params: { vehicle_id: vehicleId },
  });
  return r.data;
}

/**
 * Fuelio's `fuel_type` numeric codes — best-effort label table.
 * Codes vary by region/version of the Fuelio export. Any code not
 * in this table is shown as "Grade <N>". 100 is the most common
 * default in modern exports.
 */
export const FUEL_GRADE_LABELS: Record<number, string> = {
  0: "Diesel",
  1: "Regular (87)",
  2: "Mid-grade (89)",
  3: "Premium (91)",
  4: "Premium (93)",
  5: "E85",
  6: "LPG",
  7: "CNG",
  100: "Regular",
  101: "Mid-grade",
  102: "Premium",
};

export interface AnomalyItem {
  type: string;
  severity: "warn" | "danger";
  headline: string;
  detail: string;
  deep_link: string;
  fingerprint: string;
}
export interface AnomaliesResponse { anomalies: AnomalyItem[] }
export async function getAnomalies(vehicleId: string): Promise<AnomaliesResponse> {
  const r = await apiQuery.get<AnomaliesResponse>("/analytics/anomalies", {
    params: { vehicle_id: vehicleId },
  });
  return r.data;
}

export interface OdometerHistoryPoint { time: string; odo_km: number }
export interface OdometerHistorySummary {
  window: string;
  n_points: number;
  delta_km?: number;
  delta_mi?: number;
  miles_per_day?: number;
  current_km?: number;
  current_mi?: number;
}
export interface OdometerHistory {
  points: OdometerHistoryPoint[];
  summary: OdometerHistorySummary;
}
export async function getOdometerHistory(
  vehicleId: string,
  window: "year" | "3y" | "all" = "all",
): Promise<OdometerHistory> {
  const r = await apiQuery.get<OdometerHistory>("/analytics/odometer", {
    params: { vehicle_id: vehicleId, window },
  });
  return r.data;
}

export interface TripBaseline {
  bucket_label: string;
  sample_size: number;
  sufficient: boolean;
  avg_distance_km: number | null;
  avg_duration_s: number | null;
  avg_speed_kph: number | null;
  avg_max_speed_kph: number | null;
  avg_mpg: number | null;
}

export async function getTripBaseline(
  vehicleId: string,
  distanceKm: number,
): Promise<TripBaseline> {
  const r = await apiQuery.get<TripBaseline>("/analytics/trip-baseline", {
    params: { vehicle_id: vehicleId, distance_km: distanceKm },
  });
  return r.data;
}

// ─── maintenance ──────────────────────────────────────────────────────

export async function listReminders(vehicleId?: string): Promise<ReminderGroup> {
  const r = await apiQuery.get<ReminderGroup>("/maintenance/reminders", {
    params: vehicleId ? { vehicle_id: vehicleId } : {},
  });
  return r.data;
}
export async function markReminderDone(expenseId: string): Promise<void> {
  await apiIngest.post(`/maintenance/reminders/${expenseId}/done`);
}

// ─── logs ─────────────────────────────────────────────────────────────

export async function listRecentLogs(p: LogRecentParams = {}): Promise<LogEntry[]> {
  const r = await apiQuery.get<LogEntry[]>("/logs/recent", { params: p });
  return r.data;
}

export async function ingestLogs(entries: LogIngestEntry[]): Promise<void> {
  await apiIngest.post("/logs", { entries });
}

export async function deleteLogsOlderThan(days: number): Promise<void> {
  await apiIngest.delete("/logs/older-than", { params: { days } });
}

// ─── admin: storage stats + retention purges ──────────────────────────

export interface StorageTableStats {
  table: string;
  rows: number | null;
  size_bytes: number;
  is_hypertable: boolean;
}
export interface StorageStats {
  total_size_bytes: number;
  tables: StorageTableStats[];
  oldest_reading_at: string | null;
}
export interface PurgePreview {
  preview: boolean;
  older_than_days: number;
  cutoff_iso: string;
  rows_to_delete?: number;
  rows_deleted?: number;
}

export async function getStorageStats(): Promise<StorageStats> {
  const r = await apiQuery.get<StorageStats>("/admin/storage");
  return r.data;
}

export async function purgeReadings(
  olderThanDays: number,
  confirm: boolean,
): Promise<PurgePreview> {
  const r = await apiIngest.post<PurgePreview>(
    `/admin/purge/readings?older_than_days=${olderThanDays}&confirm=${confirm}`,
  );
  return r.data;
}

export async function purgeLogs(
  olderThanDays: number,
  confirm: boolean,
): Promise<PurgePreview> {
  const r = await apiIngest.post<PurgePreview>(
    `/admin/purge/logs?older_than_days=${olderThanDays}&confirm=${confirm}`,
  );
  return r.data;
}

// ── admin: device → vehicle mapping ───────────────────────────────────

export interface DeviceMapping {
  device_id: string;
  kind?: string;
  label?: string | null;
  vehicle_id?: string | null;
  vehicle_name?: string | null;
  vehicle_slug?: string | null;
  first_seen_at?: string | null;
  last_seen_at?: string | null;
  warn_count?: number;
  mapped: boolean;
}

export async function listDevices(): Promise<DeviceMapping[]> {
  const r = await apiQuery.get<DeviceMapping[]>("/admin/devices");
  return r.data;
}

export async function mapDevice(
  deviceId: string,
  vehicleId: string,
  kind = "wican",
  label: string | null = null,
): Promise<DeviceMapping> {
  const r = await apiIngest.post<DeviceMapping>(
    `/admin/devices/${encodeURIComponent(deviceId)}/map`,
    { vehicle_id: vehicleId, kind, label },
  );
  return r.data;
}

export async function unmapDevice(deviceId: string): Promise<void> {
  await apiIngest.delete(
    `/admin/devices/${encodeURIComponent(deviceId)}/map`,
  );
}

// ─── imports ──────────────────────────────────────────────────────────

export async function importFuelio(
  files: File[],
  dryRun: boolean,
  attachVehicleSlug: string | null = null,
): Promise<FuelioImportPreview> {
  const fd = new FormData();
  for (const f of files) fd.append("files", f, f.name);
  const params = new URLSearchParams({ dry_run: dryRun ? "true" : "false" });
  if (attachVehicleSlug) params.set("attach_vehicle_slug", attachVehicleSlug);
  const r = await apiIngest.post<FuelioImportPreview>(
    `/import/fuelio?${params.toString()}`,
    fd,
    { headers: { "Content-Type": "multipart/form-data" } },
  );
  return r.data;
}
