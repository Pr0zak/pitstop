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
