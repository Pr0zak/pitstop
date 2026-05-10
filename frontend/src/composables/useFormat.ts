import { format, formatDistanceToNow, parseISO } from "date-fns";
import { useUnitsStore, type ResolvedUnitSystem } from "@/stores/units";

// ============================================================================
// Unit conversions. DB storage is canonical metric for OBD-derived values:
//   speed_kph, temp_c, distance_km, volume_l, consumption_l_per_100km
// Fillup rows from the Fuelio importer keep the user's source units (the
// importer reads the per-export header to know what units a row is in,
// and persists raw — so we may convert in either direction at render time).
// ============================================================================

const C_TO_F = (c: number) => (c * 9) / 5 + 32;
const KPH_TO_MPH = (k: number) => k * 0.621371;
const KM_TO_MI = (k: number) => k * 0.621371;
const L_TO_USGAL = (l: number) => l * 0.264172;
const LP100_TO_MPG = (lp100: number) => (lp100 > 0 ? 235.214583 / lp100 : 0);

/** Resolve unit system. Pinia may not be active in some bootstrap paths; fall back to imperial. */
function resolved(explicit?: ResolvedUnitSystem): ResolvedUnitSystem {
  if (explicit) return explicit;
  try {
    return useUnitsStore().resolved;
  } catch {
    return "imperial";
  }
}

export function fmtNumber(
  value: number | null | undefined,
  opts: { digits?: number; suffix?: string; placeholder?: string } = {},
): string {
  if (value == null || Number.isNaN(value)) return opts.placeholder ?? "—";
  const digits = opts.digits ?? 1;
  const v = Number(value).toFixed(digits);
  return opts.suffix ? `${v} ${opts.suffix}` : v;
}

export function fmtMpg(v: number | null | undefined): string {
  return fmtNumber(v, { digits: 1, suffix: "mpg" });
}
export function fmtMiles(v: number | null | undefined): string {
  return fmtNumber(v, { digits: 1, suffix: "mi" });
}
/** Whole-mile odometer reading (no decimal). Use for any "odo" field
 *  rendered in tables / cards — Fuelio stores it as float but the
 *  real-world value is always a whole mile, so the .0 just clutters. */
export function fmtOdo(v: number | null | undefined): string {
  return fmtNumber(v, { digits: 0, suffix: "mi" });
}
export function fmtGallons(v: number | null | undefined): string {
  return fmtNumber(v, { digits: 2, suffix: "gal" });
}
export function fmtMoney(v: number | null | undefined, currency = "$"): string {
  if (v == null || Number.isNaN(v)) return "—";
  return `${currency}${Number(v).toFixed(2)}`;
}
export function fmtPct(v: number | null | undefined): string {
  return fmtNumber(v, { digits: 1, suffix: "%" });
}
export function fmtTemp(v: number | null | undefined, unit = "°F"): string {
  return fmtNumber(v, { digits: 0, suffix: unit });
}
export function fmtRpm(v: number | null | undefined): string {
  return fmtNumber(v, { digits: 0, suffix: "rpm" });
}
export function fmtSpeed(v: number | null | undefined, unit = "mph"): string {
  return fmtNumber(v, { digits: 0, suffix: unit });
}

// === Unit-aware formatters (read from the units store) ===

export function fmtTempC(c: number | null | undefined, system?: ResolvedUnitSystem): string {
  if (c == null || Number.isNaN(c)) return "—";
  return resolved(system) === "imperial"
    ? `${C_TO_F(c).toFixed(0)} °F`
    : `${c.toFixed(0)} °C`;
}

export function fmtSpeedKph(kph: number | null | undefined, system?: ResolvedUnitSystem): string {
  if (kph == null || Number.isNaN(kph)) return "—";
  return resolved(system) === "imperial"
    ? `${KPH_TO_MPH(kph).toFixed(0)} mph`
    : `${kph.toFixed(0)} km/h`;
}

export function fmtDistanceKm(km: number | null | undefined, system?: ResolvedUnitSystem): string {
  if (km == null || Number.isNaN(km)) return "—";
  return resolved(system) === "imperial"
    ? `${KM_TO_MI(km).toFixed(1)} mi`
    : `${km.toFixed(1)} km`;
}

export function fmtVolumeL(l: number | null | undefined, system?: ResolvedUnitSystem): string {
  if (l == null || Number.isNaN(l)) return "—";
  return resolved(system) === "imperial"
    ? `${L_TO_USGAL(l).toFixed(2)} gal`
    : `${l.toFixed(2)} l`;
}

export function fmtFuelRateLh(lh: number | null | undefined, system?: ResolvedUnitSystem): string {
  if (lh == null || Number.isNaN(lh)) return "—";
  return resolved(system) === "imperial"
    ? `${L_TO_USGAL(lh).toFixed(2)} gph`
    : `${lh.toFixed(2)} l/h`;
}

export function fmtConsumptionLp100(
  lp100: number | null | undefined,
  system?: ResolvedUnitSystem,
): string {
  if (lp100 == null || Number.isNaN(lp100) || lp100 <= 0) return "—";
  return resolved(system) === "imperial"
    ? `${LP100_TO_MPG(lp100).toFixed(1)} mpg`
    : `${lp100.toFixed(1)} l/100km`;
}

export function fmtDuration(seconds: number | null | undefined): string {
  if (seconds == null || Number.isNaN(seconds) || seconds < 0) return "—";
  const s = Math.floor(seconds);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  if (h > 0) return `${h}h ${m}m`;
  if (m > 0) return `${m}m ${sec.toString().padStart(2, "0")}s`;
  return `${sec}s`;
}

export function fmtDate(iso: string | null | undefined, pattern = "yyyy-MM-dd"): string {
  if (!iso) return "—";
  try {
    return format(parseISO(iso), pattern);
  } catch {
    return iso;
  }
}
export function fmtDateTime(iso: string | null | undefined): string {
  return fmtDate(iso, "yyyy-MM-dd HH:mm");
}
export function fmtRelative(iso: string | null | undefined): string {
  if (!iso) return "—";
  try {
    return formatDistanceToNow(parseISO(iso), { addSuffix: true });
  } catch {
    return iso;
  }
}
