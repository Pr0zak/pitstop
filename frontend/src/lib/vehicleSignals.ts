// Display rules for the Honda Mode 22 signals the WiCAN polls since its
// firmware-6 profile (2026-10-21): gear, ATF temp, cylinder deactivation,
// per-cylinder misfires, cluster odometer / fuel litres, ambient temp and the
// four-corner TPMS. Pure functions so the Live view and Overview render them
// identically, and so the Android app has one written spec to match.
import { fmtPressureKpa, fmtTempC } from "@/composables/useFormat";
import type { ResolvedUnitSystem } from "@/stores/units";

/**
 * ZF 9HP `gear_position`: 0 = Park OR Neutral (the PCM reports them the same
 * way, so the label names both), 1–9 = forward gear, 15 = Reverse.
 * Stopped in Drive reads 2 — the 9HP launches in second — and that is shown
 * as-is ("D2"), not corrected to D1.
 */
export function gearLabel(v: number | null | undefined): string {
  if (v == null || !Number.isFinite(v)) return "—";
  const g = Math.round(v);
  if (g === 0) return "P/N";
  if (g === 15) return "R";
  if (g >= 1 && g <= 9) return `D${g}`;
  return String(g); // an undocumented code: show it raw rather than guess
}

/** `active_cylinders`: 6 normally, 3 while Variable Cylinder Management has a
 *  bank shut off. Anything below 6 is a VCM state. */
export function vcmActive(v: number | null | undefined): boolean {
  return v != null && Number.isFinite(v) && v > 0 && Math.round(v) < 6;
}
export function cylindersLabel(v: number | null | undefined): string {
  if (v == null || !Number.isFinite(v)) return "—";
  const n = Math.round(v);
  return vcmActive(n) ? `${n} (VCM)` : String(n);
}

/** `ambient_air_temp_f` arrives in °F (the cluster's own reading); convert to
 *  °C so it goes through the same unit-aware formatter as every other temp. */
export function fToC(f: number): number {
  return ((f - 32) * 5) / 9;
}
export function fmtTempF(f: number | null | undefined, system?: ResolvedUnitSystem): string {
  if (f == null || !Number.isFinite(f)) return "—";
  return fmtTempC(fToC(f), system);
}

// ─── TPMS ─────────────────────────────────────────────────────────────────
export type TireCorner = "fl" | "fr" | "rl" | "rr";
export const TIRE_CORNERS: TireCorner[] = ["fl", "fr", "rl", "rr"];
export const TIRE_CORNER_LABEL: Record<TireCorner, string> = {
  fl: "FL",
  fr: "FR",
  rl: "RL",
  rr: "RR",
};

/**
 * Low-tire rule, deliberately simple: a tire is "low" when it reads at least
 * 2 psi below the HIGHEST of the four. Honda's TPMS logic wants the door-placard
 * pressure, which we don't store; a relative spread needs no per-vehicle config
 * and can't fire on a uniform cold-morning drop. 2 psi rather than 3 because on
 * 2026-10-21 the Pilot's dash lit its TPMS warning for FR at 28.4 psi against
 * a 30.9 psi high (a 2.5 psi spread), and 3 psi would have missed the very tire
 * the car itself was flagging. The threshold is in psi regardless of the
 * display unit.
 */
export const TPMS_LOW_DELTA_PSI = 2;
const KPA_PER_PSI = 1 / 0.145038;

export interface TireReading {
  corner: TireCorner;
  label: string;
  /** kPa as stored. */
  pressureKpa: number | null;
  /** °C as stored. */
  tempC: number | null;
  low: boolean;
}

export function tpmsReadings(
  pressureKpa: Partial<Record<TireCorner, number | null>>,
  tempC: Partial<Record<TireCorner, number | null>> = {},
): TireReading[] {
  const known = TIRE_CORNERS.map((c) => pressureKpa[c]).filter(
    (p): p is number => p != null && Number.isFinite(p),
  );
  // Needs at least two tires to compare; one reading can't be "below" itself.
  const max = known.length >= 2 ? Math.max(...known) : null;
  const threshold = TPMS_LOW_DELTA_PSI * KPA_PER_PSI;
  return TIRE_CORNERS.map((c) => {
    const p = pressureKpa[c] ?? null;
    const t = tempC[c] ?? null;
    return {
      corner: c,
      label: TIRE_CORNER_LABEL[c],
      pressureKpa: p,
      tempC: t,
      // 1e-6 slack so exactly 2.0 psi (after the kPa round-trip) counts as low.
      low: p != null && max != null && max - p >= threshold - 1e-6,
    };
  });
}

/** Tire pressure, always whole psi — what the dash's TPMS page shows — even
 *  when the rest of the UI is metric (user preference, 2026-10-21). */
export function fmtTirePressure(kpa: number | null | undefined): string {
  return fmtPressureKpa(kpa, "imperial", 0);
}

/** kPa → psi, the one factor every tire figure goes through. */
export const PSI_PER_KPA = 0.145038;
export function kpaToPsi(kpa: number): number {
  return kpa * PSI_PER_KPA;
}

/**
 * Per-corner colours for every multi-tire chart (trip detail, Analytics).
 * Picked to be distinguishable from each other on the dark surface and
 * front/rear-paired by hue family: fronts blue / orange, rears green /
 * magenta. The Android app should use the same four.
 */
export const TIRE_CORNER_COLOR: Record<TireCorner, string> = {
  fl: "#2f81f7",
  fr: "#f97316",
  rl: "#3fb950",
  rr: "#d946ef",
};

/**
 * PROVISIONAL slow-leak flag: a cold-pressure trend at or below this, resting
 * on at least TIRE_SLOW_LEAK_MIN_SAMPLES cold readings. Mirrors
 * backend/src/pitstop/tires.py (which also returns `possible_slow_leak`).
 * Ambient swings cold pressure ~1 psi per 10 °F, so a cooling season can push
 * all four tires down together — a single tire falling alone is the tell.
 */
export const TIRE_SLOW_LEAK_PSI_PER_WEEK = -0.5;
export const TIRE_SLOW_LEAK_MIN_SAMPLES = 4;
export function isSlowLeak(slopePsiPerWeek: number | null | undefined, samples: number): boolean {
  return (
    slopePsiPerWeek != null &&
    Number.isFinite(slopePsiPerWeek) &&
    samples >= TIRE_SLOW_LEAK_MIN_SAMPLES &&
    slopePsiPerWeek <= TIRE_SLOW_LEAK_PSI_PER_WEEK
  );
}

/** "−0.4 psi/week" (true minus sign, always psi). */
export function fmtPsiPerWeek(v: number | null | undefined): string {
  if (v == null || !Number.isFinite(v)) return "—";
  const r = Math.round(v * 10) / 10;
  const sign = r > 0 ? "+" : r < 0 ? "−" : "";
  return `${sign}${Math.abs(r).toFixed(1)} psi/week`;
}

/** Tire-temperature RISE (a delta, not a reading): °C delta → "+12 °F" /
 *  "+7 °C". A delta converts by ×9/5 with no +32 offset. */
export function fmtTempRiseC(deltaC: number | null | undefined, imperial: boolean): string {
  if (deltaC == null || !Number.isFinite(deltaC)) return "—";
  const v = Math.round(imperial ? (deltaC * 9) / 5 : deltaC);
  const sign = v > 0 ? "+" : v < 0 ? "−" : "±";
  return `${sign}${Math.abs(v)} ${imperial ? "°F" : "°C"}`;
}

/** psi below the highest reporting tire (0 for the highest itself, negative
 *  otherwise); null when fewer than two tires report. Same comparison as the
 *  low rule in tpmsReadings, exposed for the "spread" readout. */
export function tireSpreadPsi(
  pressureKpa: Partial<Record<TireCorner, number | null>>,
  corner: TireCorner,
): number | null {
  const known = TIRE_CORNERS.map((c) => pressureKpa[c]).filter(
    (p): p is number => p != null && Number.isFinite(p),
  );
  const p = pressureKpa[corner];
  if (known.length < 2 || p == null) return null;
  return kpaToPsi(p - Math.max(...known));
}

// ─── Brakes ───────────────────────────────────────────────────────────────
/** `brake_pressure` is reported by the ECU in bar and shown in bar for every
 *  unit system — it's a hydraulic line pressure nobody reads off a gauge in
 *  psi, and bar is what the ABS literature and the PCM both use. Whole bar:
 *  the sensor's useful range is 0–~100. */
export function fmtBrakeBar(bar: number | null | undefined): string {
  if (bar == null || !Number.isFinite(bar)) return "—";
  return `${Math.round(Math.max(0, bar))} bar`;
}

// ─── Misfires ─────────────────────────────────────────────────────────────
export const MISFIRE_CYLINDERS = [1, 2, 3, 4, 5, 6] as const;
export function misfireMetric(cyl: number): string {
  return `misfire_count_${cyl}`;
}
