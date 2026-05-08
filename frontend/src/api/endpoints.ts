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
export async function getTrip(id: string): Promise<TripDetail> {
  const r = await apiQuery.get<TripDetail>(`/trips/${id}`);
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

// ─── imports ──────────────────────────────────────────────────────────

export async function importFuelio(
  files: File[],
  dryRun: boolean,
): Promise<FuelioImportPreview> {
  const fd = new FormData();
  for (const f of files) fd.append("files", f, f.name);
  const r = await apiIngest.post<FuelioImportPreview>(
    `/import/fuelio?dry_run=${dryRun ? "true" : "false"}`,
    fd,
    { headers: { "Content-Type": "multipart/form-data" } },
  );
  return r.data;
}
