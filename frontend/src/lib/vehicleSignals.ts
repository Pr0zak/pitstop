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
 *
 * When the corner's door placard is known, a tire at or above it is never low,
 * whatever the spread: on 2026-11-11 the set read 38/38/37/35 psi on a 35 psi
 * placard and RR showed amber for being 2.8 psi under FR, though every tire
 * was fully inflated. Mirrors backend tires.low_tire_corners.
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
  /** Placard for this corner, kPa; null when none is set. */
  placardKpa: number | null;
  /** psi under placard (positive = below); null without placard/reading. */
  belowPlacardPsi: number | null;
  underInflated: boolean;
  belowSpec: boolean;
}

export function tpmsReadings(
  pressureKpa: Partial<Record<TireCorner, number | null>>,
  tempC: Partial<Record<TireCorner, number | null>> = {},
  placard: TirePlacard | null = null,
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
      low:
        p != null &&
        max != null &&
        max - p >= threshold - 1e-6 &&
        !atOrAbovePlacard(p, placardKpa(c, placard)),
      placardKpa: placardKpa(c, placard),
      belowPlacardPsi: belowPlacardPsi(p, placardKpa(c, placard)),
      underInflated: isUnderInflated(p, placardKpa(c, placard)),
      belowSpec: isBelowSpec(p, placardKpa(c, placard)),
    };
  });
}

// ─── Door-placard baseline ────────────────────────────────────────────────
// vehicles.tire_placard_{front,rear}_kpa: the manufacturer's recommended COLD
// pressure. Mirrors backend/src/pitstop/tires.py (placard_kpa /
// below_placard_psi / is_under_inflated / is_below_spec); the trip summary and
// /analytics/tires carry the server's verdict, these are for the live/Home
// grid and for formatting. Every placard visual hides when none is set.

/** "Under-inflated" at ≥ 25 % below placard — the FMVSS 138 TPMS warning
 *  threshold Honda's system uses (the dash lights at ~26 psi on a 35 placard),
 *  so our flag agrees with the car's light. */
export const TIRE_UNDER_INFLATED_FRACTION = 0.25;
/** Softer "below spec" note at ≥ 3 psi under placard, judged cold: a warm
 *  tire gains 2–4 psi, so a hot reading 3 under is further under cold. */
export const TIRE_BELOW_SPEC_PSI = 3;

export interface TirePlacard {
  frontKpa?: number | null;
  rearKpa?: number | null;
}

/** The placard pair off a Vehicle row (null when neither axle is set). */
export function vehiclePlacard(
  v: { tire_placard_front_kpa?: number | null; tire_placard_rear_kpa?: number | null } | null | undefined,
): TirePlacard | null {
  if (!v || (v.tire_placard_front_kpa == null && v.tire_placard_rear_kpa == null)) return null;
  return { frontKpa: v.tire_placard_front_kpa ?? null, rearKpa: v.tire_placard_rear_kpa ?? null };
}

/** Placard for a corner: fl/fr → front, rl/rr → rear. */
export function placardKpa(corner: TireCorner, placard: TirePlacard | null | undefined): number | null {
  if (!placard) return null;
  const v = corner === "fl" || corner === "fr" ? placard.frontKpa : placard.rearKpa;
  return v != null && Number.isFinite(v) && v > 0 ? v : null;
}

/** psi UNDER the placard: positive = below, negative = above; null if either
 *  side is unknown. */
export function belowPlacardPsi(
  pressureKpa: number | null | undefined,
  placard: number | null | undefined,
): number | null {
  if (pressureKpa == null || placard == null || !Number.isFinite(pressureKpa) || !(placard > 0)) {
    return null;
  }
  return (placard - pressureKpa) * PSI_PER_KPA;
}

/** True only when a placard is set and the reading is at or above it. */
function atOrAbovePlacard(pressureKpa: number, placard: number | null): boolean {
  return placard != null && placard > 0 && pressureKpa >= placard;
}

export function isUnderInflated(pressureKpa: number | null | undefined, placard: number | null | undefined): boolean {
  if (pressureKpa == null || placard == null || !Number.isFinite(pressureKpa) || !(placard > 0)) return false;
  return pressureKpa <= placard * (1 - TIRE_UNDER_INFLATED_FRACTION) + 1e-6;
}

export function isBelowSpec(pressureKpa: number | null | undefined, placard: number | null | undefined): boolean {
  const below = belowPlacardPsi(pressureKpa, placard);
  return below != null && below >= TIRE_BELOW_SPEC_PSI - 1e-6;
}

/** "−4" / "+1" / "±0": whole-psi delta vs placard (reading − placard). */
export function fmtPlacardDelta(belowPsi: number | null | undefined): string | null {
  if (belowPsi == null || !Number.isFinite(belowPsi)) return null;
  const d = -Math.round(belowPsi);
  if (d === 0) return "±0";
  return d > 0 ? `+${d}` : `−${Math.abs(d)}`;
}

/** Whole psi of a placard, for "of 35" / "placard 35 psi". */
export function placardPsiText(placard: number | null | undefined): string | null {
  return placard != null && placard > 0 ? String(Math.round(placard * PSI_PER_KPA)) : null;
}

/** Chart baseline label: "placard 35 psi", or "placard F 35 / R 38 psi" when
 *  the axles differ; null when no placard is set. */
export function placardLabel(placard: TirePlacard | null | undefined): string | null {
  const f = placardPsiText(placardKpa("fl", placard));
  const r = placardPsiText(placardKpa("rl", placard));
  if (f == null && r == null) return null;
  if (f === r || r == null) return `placard ${f} psi`;
  if (f == null) return `placard R ${r} psi`;
  return `placard F ${f} / R ${r} psi`;
}

/** Distinct placard values in psi for chart baselines (one line per value). */
export function placardBaselinesPsi(placard: TirePlacard | null | undefined): number[] {
  const vals = [placardKpa("fl", placard), placardKpa("rl", placard)]
    .filter((v): v is number => v != null)
    .map((v) => v * PSI_PER_KPA);
  return [...new Set(vals.map((v) => Math.round(v * 10) / 10))];
}

/** Tire pressure, always whole psi/** Tire pressure, always whole psi — what the dash's TPMS page shows — even
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
/** `brake_pressure` arrives (and is stored, and served) in bar — what the VSA
 *  modulator reports — but is DISPLAYED in whole psi in every unit system,
 *  like tire pressure: the user reads pressures in psi (2026-10-21 request).
 *  Convert at display only: psi = bar × 14.5038. The sensor's useful range is
 *  0–~100 bar (0–~1,450 psi), so whole psi is plenty. */
export const PSI_PER_BAR = 14.5038;
export function barToPsi(bar: number): number {
  return bar * PSI_PER_BAR;
}
export function fmtBrakePsi(bar: number | null | undefined): string {
  if (bar == null || !Number.isFinite(bar)) return "—";
  return `${Math.round(Math.max(0, bar) * PSI_PER_BAR)} psi`;
}
/** Server's provisional hard-press threshold (backend trips._HARD_BRAKE_BAR,
 *  kept in bar there). Mirrored only to label it, as ≈ 870 psi. */
export const HARD_BRAKE_BAR = 60;

// ─── Misfires ─────────────────────────────────────────────────────────────
export const MISFIRE_CYLINDERS = [1, 2, 3, 4, 5, 6] as const;
export function misfireMetric(cyl: number): string {
  return `misfire_count_${cyl}`;
}

// ─── Tire status + one-line verdict (TpmsGrid; Android VehicleSignals.tireStatus/tireHeadline) ──

/** How a tire is drawn. "low" (≥ 2 psi below the highest tire, the dash's own
 *  rule) is the one to act on: full amber. "placard" (only ≥ 3 psi under the
 *  door placard, often just a cold morning) is a dim amber so the low tire
 *  still stands out. "under" (≥ 25 % under placard, the car's warning light)
 *  is red. "none" = no reading. Ordered least to most severe. */
export type TireStatus = "none" | "good" | "placard" | "low" | "under";
const TIRE_STATUS_ORDER: TireStatus[] = ["none", "good", "placard", "low", "under"];

export function tireStatus(t: TireReading | null | undefined): TireStatus {
  if (!t || t.pressureKpa == null || !Number.isFinite(t.pressureKpa)) return "none";
  if (t.underInflated) return "under";
  if (t.low) return "low";
  if (t.belowSpec) return "placard";
  return "good";
}

function cornerList(labels: string[]): string {
  return labels.length === 1 ? labels[0] : `${labels.slice(0, -1).join(", ")} and ${labels[labels.length - 1]}`;
}

/** The whole set in one sentence, worst status first: "FR is under-inflated:
 *  add air now", "FR is 3 psi lower than the others", "2 tires under the 35
 *  psi placard", "All four tires look good". Null when no tire reports. */
export function tireHeadline(
  tires: TireReading[],
  placard: TirePlacard | null = null,
): { status: TireStatus; text: string } | null {
  const st = tires.map((t) => ({ t, s: tireStatus(t) }));
  const reporting = st.filter((x) => x.s !== "none");
  if (!reporting.length) return null;
  const worst = reporting.reduce<TireStatus>(
    (w, x) => (TIRE_STATUS_ORDER.indexOf(x.s) > TIRE_STATUS_ORDER.indexOf(w) ? x.s : w),
    "none",
  );
  const hit = TIRE_CORNERS.map((c) => st.find((x) => x.t.corner === c)).filter(
    (x): x is { t: TireReading; s: TireStatus } => x != null && x.s === worst,
  );
  const labels = hit.map((x) => x.t.label);
  let text: string;
  if (worst === "under") {
    text = `${cornerList(labels)} ${hit.length === 1 ? "is" : "are"} under-inflated: add air now`;
  } else if (worst === "low") {
    if (hit.length === 1) {
      const highest = Math.max(...reporting.map((x) => x.t.pressureKpa as number));
      // Difference of the whole-psi numbers on screen, so "3 psi lower"
      // matches 31 vs 28 even when the raw gap is 2.5.
      const gap =
        Math.round(highest * PSI_PER_KPA) - Math.round((hit[0].t.pressureKpa as number) * PSI_PER_KPA);
      text = `${labels[0]} is ${gap} psi lower than the others`;
    } else {
      text = `${cornerList(labels)} are low`;
    }
  } else if (worst === "placard") {
    const f = placardPsiText(placardKpa("fl", placard));
    const r = placardPsiText(placardKpa("rl", placard));
    const which = f != null && (r == null || r === f) ? `the ${f} psi placard` : "the door placard";
    text = `${hit.length} ${hit.length === 1 ? "tire" : "tires"} under ${which}`;
  } else {
    text = reporting.length === TIRE_CORNERS.length ? "All four tires look good" : "All reporting tires look good";
  }
  return { status: worst, text };
}

/** "Door placard 35 psi cold" / "… 35 front / 38 rear psi cold"; null without one. */
export function placardCaption(placard: TirePlacard | null | undefined): string | null {
  const f = placardPsiText(placardKpa("fl", placard));
  const r = placardPsiText(placardKpa("rl", placard));
  if (f == null && r == null) return null;
  if (f === r || r == null) return `Door placard ${f} psi cold`;
  if (f == null) return `Door placard ${r} psi cold (rear)`;
  return `Door placard ${f} front / ${r} rear psi cold`;
}

// ── Oil life (Maintenance Minder; VehicleSignals.oilLifeStatus parity) ──

/** Honda's Minder shows "due soon" at 15 % and "due now" at 5 %. */
/** Metric name the phone's 7068 poll publishes. */
export const OIL_LIFE_METRIC = "oil_life_pct";

export const OIL_DUE_SOON_PCT = 15;
export const OIL_DUE_NOW_PCT = 5;

export type OilStatus = "none" | "good" | "due_soon" | "due_now";

export function oilLifeStatus(pct: number | null | undefined): OilStatus {
  if (pct == null || !Number.isFinite(pct)) return "none";
  if (pct <= OIL_DUE_NOW_PCT) return "due_now";
  if (pct <= OIL_DUE_SOON_PCT) return "due_soon";
  return "good";
}

/** One line under the percentage, matching the dash's wording. */
export function oilLifeHeadline(pct: number | null | undefined): string {
  switch (oilLifeStatus(pct)) {
    case "none": return "No reading yet";
    case "due_now": return "Oil change due now";
    case "due_soon": return "Oil change due soon";
    default: return "No service needed yet";
  }
}
