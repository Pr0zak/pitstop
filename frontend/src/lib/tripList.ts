import type { Trip } from "@/api/types";

/** Below this distance a trip's economy is noise (a 0.05 mi repositioning
 *  burns a rounding error of fuel and "averages" 3 or 300 mpg). ~0.1 mi. */
export const MPG_MIN_DISTANCE_KM = 0.161;

/** Trips shorter than this fold into a "short hops" row. 0.3 mi. */
export const SHORT_HOP_KM = 0.48;

const KM_TO_MI = 0.621371;
const L_TO_GAL = 0.264172;

/**
 * Trip economy in US mpg from the list payload (distance_km / fuel_used_l),
 * or null when it can't honestly be computed: fuel absent or zero, distance
 * absent or under ~0.1 mi, or a result outside a physically plausible band.
 * Null renders as "—" — never as 0, which would read as "measured zero".
 */
export function tripMpg(t: Pick<Trip, "distance_km" | "fuel_used_l">): number | null {
  const km = t.distance_km;
  const l = t.fuel_used_l;
  if (km == null || l == null || !Number.isFinite(km) || !Number.isFinite(l)) return null;
  if (l <= 0 || km < MPG_MIN_DISTANCE_KM) return null;
  const mpg = (km * KM_TO_MI) / (l * L_TO_GAL);
  return mpg > 1 && mpg < 150 ? mpg : null;
}

export type TripRow =
  | { kind: "trip"; trip: Trip }
  | { kind: "hops"; key: string; trips: Trip[]; distanceKm: number };

function isShortHop(t: Trip): boolean {
  return t.distance_km != null && t.distance_km < SHORT_HOP_KM;
}

/**
 * Fold runs of ≥ 2 consecutive short trips (< 0.3 mi) into a single
 * expandable row. A lone short trip stays a normal row — "1 short hop" is
 * just a trip. Order is preserved; the caller decides when folding applies
 * (only for the most-recent sort, where "consecutive" means adjacent in time).
 */
export function foldShortHops(trips: Trip[]): TripRow[] {
  const out: TripRow[] = [];
  let run: Trip[] = [];
  const flush = () => {
    if (run.length >= 2) {
      out.push({
        kind: "hops",
        key: `hops-${run[0].id}`,
        trips: run,
        distanceKm: run.reduce((s, t) => s + (t.distance_km ?? 0), 0),
      });
    } else {
      for (const t of run) out.push({ kind: "trip", trip: t });
    }
    run = [];
  };
  for (const t of trips) {
    if (isShortHop(t)) {
      run.push(t);
    } else {
      flush();
      out.push({ kind: "trip", trip: t });
    }
  }
  flush();
  return out;
}

export interface GroupTotals {
  count: number;
  distanceKm: number;
  /** Null when no trip in the group reported fuel — the header then omits
   *  the volume rather than claiming "0 gal". */
  fuelL: number | null;
}

/** Totals for a group header, over exactly the trips listed in that group. */
export function groupTotals(trips: Trip[]): GroupTotals {
  let distanceKm = 0;
  let fuelL = 0;
  let anyFuel = false;
  for (const t of trips) {
    distanceKm += t.distance_km ?? 0;
    if (t.fuel_used_l != null && t.fuel_used_l > 0) {
      fuelL += t.fuel_used_l;
      anyFuel = true;
    }
  }
  return { count: trips.length, distanceKm, fuelL: anyFuel ? fuelL : null };
}

// ─── Row extras: 30-day economy bar, idle share, weather ────────────────

/** Trips basis window, days — same as the phone's RangeMath.BASIS_WINDOW_DAYS. */
export const BASIS_WINDOW_DAYS = 30;
/** Below this much measured driving the 30-day average is too noisy to show. */
export const MIN_BASIS_MILES = 20;

/**
 * 30-day average economy in US mpg: total distance ÷ total fuel over the
 * trips that measured fuel (a long trip weighs what it burned). Mirrors the
 * phone's RangeMath.tripBasisMpg — null below 20 mi or outside 5–80 mpg.
 */
export function tripBasisMpg(
  trips: readonly Pick<Trip, "started_at" | "distance_km" | "fuel_used_l">[],
  nowMs: number = Date.now(),
): number | null {
  const cutoff = nowMs - BASIS_WINDOW_DAYS * 86_400_000;
  let km = 0;
  let l = 0;
  for (const t of trips) {
    const d = t.distance_km;
    const f = t.fuel_used_l;
    if (d == null || f == null || !(d > 0) || !(f > 0)) continue;
    const at = Date.parse(t.started_at);
    if (!Number.isFinite(at) || at < cutoff || at > nowMs + 60_000) continue;
    km += d;
    l += f;
  }
  const mi = km * KM_TO_MI;
  if (mi < MIN_BASIS_MILES || l <= 0) return null;
  const mpg = mi / (l * L_TO_GAL);
  return mpg >= 5 && mpg <= 80 ? mpg : null;
}

export interface MpgBar {
  /** Bar fill, 0..1. */
  frac: number;
  /** Where the average tick sits, 0..1. */
  avgFrac: number;
  tone: "good" | "warn" | "none";
}

/**
 * Thin economy bar for a trip row. The scale tops out at 1.25 × the average
 * so the tick always sits at 80 % whatever the vehicle (a truck's 14 mpg
 * reads the same as a hybrid's 50). Green at or above the average, amber
 * below, muted (empty) when the trip has no economy.
 */
export function mpgBar(mpg: number | null, avg: number | null): MpgBar | null {
  if (avg == null || !(avg > 0)) return null;
  const max = avg * 1.25;
  if (mpg == null) return { frac: 0, avgFrac: 0.8, tone: "none" };
  return { frac: Math.min(1, mpg / max), avgFrac: 0.8, tone: mpg >= avg ? "good" : "warn" };
}

/** "40s" / "45m" / "1h 5m" — compact duration (under a minute stays in
 *  seconds so a short idle doesn't read "0m"). */
export function shortDuration(s: number): string {
  if (s < 60) return `${Math.round(s)}s`;
  if (s >= 3600) return `${Math.floor(s / 3600)}h ${Math.round((s % 3600) / 60)}m`;
  return `${Math.round(s / 60)}m`;
}

export interface IdleInfo {
  text: string;
  /** Idle over 20 % of the trip. */
  high: boolean;
}

/** "idle 2m · 21 %", or "idle —" when the trip has no idle figure. */
export function idleInfo(t: Pick<Trip, "idle_s" | "duration_s">): IdleInfo {
  const idle = t.idle_s;
  const dur = t.duration_s;
  if (idle == null || dur == null || !(dur > 0)) return { text: "idle —", high: false };
  const share = idle / dur;
  return { text: `idle ${shortDuration(idle)} · ${Math.round(share * 100)} %`, high: share > 0.2 };
}

/** One-word WMO weather label: 0 clear, 1–3 cloudy, 45–48 fog, 51–67 rain,
 *  71–77 snow, 80+ showers (in-between codes fall to the next band down). */
export function wmoShortLabel(code: number | null | undefined): string {
  if (code == null) return "";
  if (code === 0) return "Clear";
  if (code <= 3) return "Cloudy";
  if (code <= 48) return "Fog";
  if (code <= 67) return "Rain";
  if (code <= 77) return "Snow";
  return "Showers";
}
