// Which fuel number the UI shows, and how it captions it (ADR-025 amendment).
//
// The server picks: `fuel_display_l` is the instrument cluster's own fuel
// quantity (`fuel_level_l`, the dash gauge's figure) while that reading is
// fresh or newer than any estimate update, else the running estimate.
// Clients never re-derive the rule — they render `fuel_display_l / tank`
// and caption it with `fuel_display_source`. The Android Home card and
// widget follow the same contract.
import type { Vehicle } from "@/api/types";

export type FuelDisplaySource = "cluster" | "estimate";

export interface FuelDisplayView {
  liters: number;
  pct: number;
  source: FuelDisplaySource;
  at: string | null;
  /** "from cluster" | "estimated" — the caption prefix. */
  label: string;
}

/** Short relative age ("live" / "Nm ago" / "Nh ago" / "Nd ago").
 *  Mirrors the phone-side StatusViewModel.formatReadingAge. */
export function formatReadingAge(isoTime: string, nowMs: number = Date.now()): string | null {
  const ms = Date.parse(isoTime);
  if (!Number.isFinite(ms)) return null;
  const ageSec = Math.floor((nowMs - ms) / 1000);
  if (ageSec < 90) return "live";
  if (ageSec < 3600) return `${Math.floor(ageSec / 60)}m ago`;
  if (ageSec < 86_400) return `${Math.floor(ageSec / 3600)}h ago`;
  return `${Math.floor(ageSec / 86_400)}d ago`;
}

export function fuelSourceLabel(source: FuelDisplaySource): string {
  return source === "cluster" ? "from cluster" : "estimated";
}

/** The fuel level to show for `v`, or null when neither the cluster nor
 *  the estimator has a figure (the caller may fall back to the raw sensor).
 *  An older backend without `fuel_display_*` falls back to the estimate. */
export function pickFuelDisplay(
  v: Pick<
    Vehicle,
    | "tank_capacity_l"
    | "fuel_display_l"
    | "fuel_display_source"
    | "fuel_display_at"
    | "fuel_level_estimate_l"
    | "fuel_level_estimate_updated_at"
  > | null | undefined,
): FuelDisplayView | null {
  if (!v) return null;
  const tank = v.tank_capacity_l;
  if (tank == null || !(tank > 0)) return null;
  let liters: number | null | undefined;
  let source: FuelDisplaySource;
  let at: string | null | undefined;
  if (v.fuel_display_l != null && v.fuel_display_source != null) {
    liters = v.fuel_display_l;
    source = v.fuel_display_source;
    at = v.fuel_display_at;
  } else if (v.fuel_display_source === undefined && v.fuel_level_estimate_l != null) {
    liters = v.fuel_level_estimate_l;
    source = "estimate";
    at = v.fuel_level_estimate_updated_at;
  } else {
    return null;
  }
  if (liters == null || !Number.isFinite(liters)) return null;
  return {
    liters,
    pct: Math.max(0, Math.min(100, (liters / tank) * 100)),
    source,
    at: at ?? null,
    label: fuelSourceLabel(source),
  };
}
