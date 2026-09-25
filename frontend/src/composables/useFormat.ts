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
const KPA_TO_PSI = (kpa: number) => kpa * 0.145038;
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

// ─── Intl number formatting ───────────────────────────────────────────────
// Every grouped / fixed-precision number goes through one cached
// Intl.NumberFormat so "12,345 mi" and "$1,234.00" look the same on every
// screen. Temperatures / pressures keep toFixed() (no grouping) — their
// tests pin that shape and a thousands separator on "1040 °F" is noise.
const nfCache = new Map<string, Intl.NumberFormat>();
export function nf(minDigits: number, maxDigits = minDigits, grouping = true): Intl.NumberFormat {
  const key = `${minDigits}:${maxDigits}:${grouping}`;
  let f = nfCache.get(key);
  if (!f) {
    f = new Intl.NumberFormat("en-US", {
      minimumFractionDigits: minDigits,
      maximumFractionDigits: maxDigits,
      useGrouping: grouping,
    });
    nfCache.set(key, f);
  }
  return f;
}
const moneyCache = new Map<number, Intl.NumberFormat>();
function moneyNf(digits: number): Intl.NumberFormat {
  let f = moneyCache.get(digits);
  if (!f) {
    f = new Intl.NumberFormat("en-US", {
      style: "currency",
      currency: "USD",
      minimumFractionDigits: digits,
      maximumFractionDigits: digits,
    });
    moneyCache.set(digits, f);
  }
  return f;
}

/** Coerce Decimal-as-string OR number OR null to a finite number, or null. */
export function toNum(v: unknown): number | null {
  if (typeof v === "number") return Number.isFinite(v) ? v : null;
  if (typeof v === "string" && v.trim().length > 0) {
    const n = Number(v);
    return Number.isFinite(n) ? n : null;
  }
  return null;
}

function bad(v: unknown): boolean {
  return v == null || (typeof v === "number" && Number.isNaN(v));
}

// ─── Source units ─────────────────────────────────────────────────────────
// Fillup / expense rows keep the unit they were entered in (the vehicle's
// Fuelio dist_unit / fuel_unit). OBD-derived values are canonical metric.
export type DistUnit = "km" | "mi";
export type VolUnit = "L" | "gal";

export function vehicleDistUnit(v?: { dist_unit?: number | null } | null): DistUnit {
  return v?.dist_unit === 0 ? "km" : "mi";
}
export function vehicleVolUnit(v?: { fuel_unit?: number | null } | null): VolUnit {
  return v?.fuel_unit === 0 ? "L" : "gal";
}

// ─── Unit labels (headers, axis titles, input suffixes) ──────────────────
export function distUnitLabel(system?: ResolvedUnitSystem): string {
  return resolved(system) === "imperial" ? "mi" : "km";
}
export function volUnitLabel(system?: ResolvedUnitSystem): string {
  return resolved(system) === "imperial" ? "gal" : "L";
}
export function speedUnitLabel(system?: ResolvedUnitSystem): string {
  return resolved(system) === "imperial" ? "mph" : "km/h";
}
export function tempUnitLabel(system?: ResolvedUnitSystem): string {
  return resolved(system) === "imperial" ? "°F" : "°C";
}
export function economyUnitLabel(system?: ResolvedUnitSystem): string {
  return resolved(system) === "imperial" ? "mpg" : "L/100km";
}
export function elevUnitLabel(system?: ResolvedUnitSystem): string {
  return resolved(system) === "imperial" ? "ft" : "m";
}

// ─── Numeric converters (for charts, inputs, arithmetic) ─────────────────
/** Distance in `src` units → display units. */
export function convDistance(v: number, src: DistUnit = "km", system?: ResolvedUnitSystem): number {
  const imperial = resolved(system) === "imperial";
  if (src === "km") return imperial ? KM_TO_MI(v) : v;
  return imperial ? v : v / 0.621371;
}
/** Volume in `src` units → display units. */
export function convVolume(v: number, src: VolUnit = "L", system?: ResolvedUnitSystem): number {
  const imperial = resolved(system) === "imperial";
  if (src === "L") return imperial ? L_TO_USGAL(v) : v;
  return imperial ? v : v / 0.264172;
}
/** Price per `src` volume unit → price per display volume unit. */
export function convPricePerVolume(v: number, src: VolUnit = "gal", system?: ResolvedUnitSystem): number {
  // $/gal → $/L divides by 3.785; i.e. price scales inversely to volume.
  const oneSrcInDisplay = convVolume(1, src, system);
  return oneSrcInDisplay > 0 ? v / oneSrcInDisplay : v;
}
export function convSpeedKph(kph: number, system?: ResolvedUnitSystem): number {
  return resolved(system) === "imperial" ? KPH_TO_MPH(kph) : kph;
}
export function convTempC(c: number, system?: ResolvedUnitSystem): number {
  return resolved(system) === "imperial" ? C_TO_F(c) : c;
}
/** US mpg → display economy (mpg or L/100km). */
export function convEconomyMpg(mpg: number, system?: ResolvedUnitSystem): number {
  if (resolved(system) === "imperial") return mpg;
  return mpg > 0 ? 235.214583 / mpg : 0;
}
export function convElevationM(m: number, system?: ResolvedUnitSystem): number {
  return resolved(system) === "imperial" ? m * 3.28084 : m;
}

// ─── Quantity formatters ─────────────────────────────────────────────────
/** Money, Intl currency. `digits` defaults to cents. */
export function fmtMoney(v: number | string | null | undefined, digits = 2): string {
  const n = toNum(v);
  if (n == null) return "—";
  return moneyNf(digits).format(n);
}
/** Fuel economy from a US-mpg value, rendered in the display system. */
export function fmtMpg(v: number | null | undefined, system?: ResolvedUnitSystem): string {
  if (bad(v) || (v as number) <= 0) return "—";
  return `${nf(1).format(convEconomyMpg(v as number, system))} ${economyUnitLabel(system)}`;
}
/** Distance. `src` is the unit the value is stored in. */
export function fmtDistance(
  v: number | string | null | undefined,
  src: DistUnit = "km",
  digits = 1,
  system?: ResolvedUnitSystem,
): string {
  const n = toNum(v);
  if (n == null) return "—";
  return `${nf(digits).format(convDistance(n, src, system))} ${distUnitLabel(system)}`;
}
/** @deprecated prefer fmtDistance(v, "mi"). Value in miles. */
export function fmtMiles(v: number | null | undefined): string {
  return fmtDistance(v, "mi");
}
/** Whole-unit odometer reading, grouped ("48,210 mi"). */
export function fmtOdo(
  v: number | string | null | undefined,
  src: DistUnit = "mi",
  system?: ResolvedUnitSystem,
): string {
  const n = toNum(v);
  if (n == null) return "—";
  return `${nf(0).format(convDistance(n, src, system))} ${distUnitLabel(system)}`;
}
/** Odometer from canonical km. */
export function fmtOdoKm(km: number | null | undefined, system?: ResolvedUnitSystem): string {
  return fmtOdo(km, "km", system);
}
/** Volume. `src` is the unit the value is stored in. */
export function fmtVolume(
  v: number | string | null | undefined,
  src: VolUnit = "L",
  digits = 2,
  system?: ResolvedUnitSystem,
): string {
  const n = toNum(v);
  if (n == null) return "—";
  return `${nf(digits).format(convVolume(n, src, system))} ${volUnitLabel(system)}`;
}
/** @deprecated prefer fmtVolume(v, "gal"). Value in US gallons. */
export function fmtGallons(v: number | null | undefined): string {
  return fmtVolume(v, "gal");
}
/** Price per volume ("$3.459/gal"). `src` is the volume unit the price is per. */
export function fmtPricePerVolume(
  v: number | string | null | undefined,
  src: VolUnit = "gal",
  digits = 3,
  system?: ResolvedUnitSystem,
): string {
  const n = toNum(v);
  if (n == null) return "—";
  return `${moneyNf(digits).format(convPricePerVolume(n, src, system))}/${volUnitLabel(system)}`;
}
/** Elevation / altitude from metres. */
export function fmtElevationM(m: number | null | undefined, system?: ResolvedUnitSystem): string {
  if (bad(m)) return "—";
  return `${nf(0).format(convElevationM(m as number, system))} ${elevUnitLabel(system)}`;
}
/** Money per distance ("$0.142/mi"). Value is $ per `src` unit. */
export function fmtMoneyPerDistance(
  v: number | null | undefined,
  src: DistUnit = "mi",
  digits = 3,
  system?: ResolvedUnitSystem,
): string {
  if (bad(v)) return "—";
  const oneSrc = convDistance(1, src, system);
  return `${moneyNf(digits).format((v as number) / (oneSrc || 1))}/${distUnitLabel(system)}`;
}
/** Plain grouped integer / decimal ("1,234"). */
export function fmtInt(v: number | null | undefined): string {
  if (bad(v)) return "—";
  return nf(0).format(v as number);
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

/** Wind speed from km/h — same conversion as vehicle speed. */
export function fmtWindKph(kph: number | null | undefined, system?: ResolvedUnitSystem): string {
  return fmtSpeedKph(kph, system);
}

export function fmtDistanceKm(km: number | null | undefined, system?: ResolvedUnitSystem): string {
  return fmtDistance(km, "km", 1, system);
}

export function fmtVolumeL(l: number | null | undefined, system?: ResolvedUnitSystem): string {
  return fmtVolume(l, "L", 2, system);
}

export function fmtFuelRateLh(lh: number | null | undefined, system?: ResolvedUnitSystem): string {
  if (lh == null || Number.isNaN(lh)) return "—";
  return resolved(system) === "imperial"
    ? `${L_TO_USGAL(lh).toFixed(2)} gph`
    : `${lh.toFixed(2)} L/h`;
}

/**
 * OBD pressures (MAP, barometric, fuel rail) are stored in kPa and render as
 * psi for an imperial user — the same rule the Android app has always applied
 * via `UnitFormat.Quantity.PressureKpa`, and the same ×0.145038 factor.
 *
 * This exists because the web had no pressure formatter at all: LiveView
 * hardcoded `" kPa"` at three call sites, so an imperial user read the fuel
 * rail as "3500 kPa" on Live while the trip-detail chart drew the identical
 * metric as "508 psi". That is the unit-as-a-literal-string bug class the
 * phone side was just root-caused for; the fix is the same one — one place
 * that knows which unit belongs to which system.
 *
 * `digits` is caller-chosen because the sensible precision differs by metric
 * (MAP/baro read 0–105 kPa, the fuel rail ~3500 kPa).
 */
export function fmtPressureKpa(
  kpa: number | null | undefined,
  system?: ResolvedUnitSystem,
  digits = 0,
): string {
  if (kpa == null || Number.isNaN(kpa)) return "—";
  return resolved(system) === "imperial"
    ? `${KPA_TO_PSI(kpa).toFixed(digits)} psi`
    : `${kpa.toFixed(digits)} kPa`;
}

export function fmtConsumptionLp100(
  lp100: number | null | undefined,
  system?: ResolvedUnitSystem,
): string {
  if (lp100 == null || Number.isNaN(lp100) || lp100 <= 0) return "—";
  return resolved(system) === "imperial"
    ? `${LP100_TO_MPG(lp100).toFixed(1)} mpg`
    : `${lp100.toFixed(1)} L/100km`;
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
