/**
 * Geometry helpers for the Insights page charts (components/insights/*).
 *
 * The /analytics/insights payload is already in display units and every
 * sentence is server-written, so nothing here touches the data itself —
 * these only decide where things go on screen: domains, tick positions,
 * which labels fit, and how a number is printed on an axis.
 */
import { niceTicks, padRange } from "./fuelInsights";

export { niceTicks };

const MINUS = "−";

/** Map [d0, d1] → [r0, r1]. A zero-width domain maps to the middle. */
export function linearScale(d0: number, d1: number, r0: number, r1: number) {
  const span = d1 - d0;
  if (span === 0) return () => (r0 + r1) / 2;
  return (v: number) => r0 + ((v - d0) / span) * (r1 - r0);
}

interface Extras {
  band?: { lo: number; hi: number } | null;
  ref?: { value: number } | null;
}

/** Bar domain: always contains zero (bars grow from a zero baseline), the
 *  band and the reference line. */
export function barDomain(values: readonly number[], extras: Extras = {}): [number, number] {
  const all = [0, ...values.filter(Number.isFinite)];
  if (extras.band) all.push(extras.band.lo, extras.band.hi);
  if (extras.ref) all.push(extras.ref.value);
  const lo = Math.min(...all);
  const hi = Math.max(...all);
  return lo === hi ? [lo, lo + 1] : [lo, hi];
}

/** Line / scatter domain: data plus band and reference, lightly padded. */
export function valueDomain(values: readonly number[], extras: Extras = {}): [number, number] {
  const all = values.filter(Number.isFinite);
  if (extras.band) all.push(extras.band.lo, extras.band.hi);
  if (extras.ref) all.push(extras.ref.value);
  if (all.length === 0) return [0, 1];
  const [lo, hi] = padRange(all, 0.08);
  return lo === hi ? [lo - 1, hi + 1] : [lo, hi];
}

/** Show every k-th label so each one gets at least `minPx`. */
export function labelStride(count: number, slotPx: number, minPx: number): number {
  if (count <= 1 || slotPx <= 0) return 1;
  return Math.max(1, Math.ceil(minPx / slotPx));
}

/** Rough rendered width of `text` at an ~10.5 px UI font. Good enough to
 *  decide whether labels collide; the SVG never relies on it for layout. */
export function textWidth(text: string, pxPerChar = 6): number {
  return text.length * pxPerChar;
}

/** Split a category label onto at most two lines of `maxChars`; overflow
 *  gets an ellipsis. Breaks at spaces only, so "Short (<10 min)" becomes
 *  ["Short", "(<10 min)"] rather than cutting a word. */
export function wrapLabel(label: string, maxChars: number): string[] {
  const max = Math.max(3, Math.floor(maxChars));
  if (label.length <= max) return [label];
  const words = label.split(" ");
  const lines: string[] = [];
  let cur = "";
  for (const w of words) {
    const next = cur ? `${cur} ${w}` : w;
    if (next.length <= max || !cur) cur = next;
    else {
      lines.push(cur);
      cur = w;
    }
  }
  if (cur) lines.push(cur);
  const out = lines.slice(0, 2);
  if (lines.length > 2) out[1] = `${out[1]} ${lines.slice(2).join(" ")}`;
  return out.map((l) => (l.length > max ? `${l.slice(0, max - 1)}…` : l));
}

/** Which bars carry a value label. Highlighted bars always do; the rest
 *  follow `stride`, skipping any that would sit next to a highlighted one
 *  when labels are being thinned. */
export function valueLabelMask(highlights: readonly boolean[], stride: number): boolean[] {
  if (stride <= 1) return highlights.map(() => true);
  return highlights.map((h, i) => {
    if (h) return true;
    if (i % stride !== 0) return false;
    for (let d = 1; d < stride; d++) {
      if (highlights[i - d] || highlights[i + d]) return false;
    }
    return true;
  });
}

const nfCache = new Map<number, Intl.NumberFormat>();
function nfMax(digits: number): Intl.NumberFormat {
  let f = nfCache.get(digits);
  if (!f) {
    f = new Intl.NumberFormat(undefined, { maximumFractionDigits: digits });
    nfCache.set(digits, f);
  }
  return f;
}

/** A server value as printed: up to `maxDigits` decimals, true minus sign. */
export function fmtInsightNumber(v: number, maxDigits = 2): string {
  const s = nfMax(maxDigits).format(Math.abs(v));
  return v < 0 && s !== "0" ? `${MINUS}${s}` : s;
}

/** Axis tick label: as many decimals as the tick step needs. */
export function fmtTick(v: number, step: number): string {
  const digits = step > 0 ? Math.max(0, -Math.floor(Math.log10(step) + 1e-9)) : 0;
  return fmtInsightNumber(v, Math.min(digits, 3));
}

/** Value with its unit. "$" leads ("$65"); everything else follows
 *  ("22 % worse", "12.28 V", "−4.6 psi/day"). */
export function valueWithUnit(v: number, unit: string | null | undefined, maxDigits = 2) {
  const n = fmtInsightNumber(v, maxDigits);
  if (!unit) return { prefix: "", number: n, suffix: "" };
  if (unit === "$") {
    return n.startsWith(MINUS)
      ? { prefix: `${MINUS}$`, number: n.slice(1), suffix: "" }
      : { prefix: "$", number: n, suffix: "" };
  }
  return { prefix: "", number: n, suffix: unit };
}

// ─── time axis ──────────────────────────────────────────────────────────

type Unit = "hour" | "day" | "month" | "year";
interface Step {
  unit: Unit;
  n: number;
  ms: number;
}
const H = 3_600_000;
const D = 24 * H;
const STEPS: Step[] = [
  { unit: "hour", n: 1, ms: H },
  { unit: "hour", n: 3, ms: 3 * H },
  { unit: "hour", n: 6, ms: 6 * H },
  { unit: "hour", n: 12, ms: 12 * H },
  { unit: "day", n: 1, ms: D },
  { unit: "day", n: 2, ms: 2 * D },
  { unit: "day", n: 7, ms: 7 * D },
  { unit: "day", n: 14, ms: 14 * D },
  { unit: "month", n: 1, ms: 30.4 * D },
  { unit: "month", n: 2, ms: 60.9 * D },
  { unit: "month", n: 3, ms: 91.3 * D },
  { unit: "month", n: 6, ms: 182.6 * D },
  { unit: "year", n: 1, ms: 365.25 * D },
];

function floorTo(t: number, s: Step): Date {
  const d = new Date(t);
  if (s.unit === "hour") {
    d.setMinutes(0, 0, 0);
    d.setHours(Math.floor(d.getHours() / s.n) * s.n);
  } else if (s.unit === "day") {
    d.setHours(0, 0, 0, 0);
  } else if (s.unit === "month") {
    d.setHours(0, 0, 0, 0);
    d.setDate(1);
    d.setMonth(Math.floor(d.getMonth() / s.n) * s.n);
  } else {
    d.setHours(0, 0, 0, 0);
    d.setMonth(0, 1);
  }
  return d;
}
function advance(d: Date, s: Step): Date {
  const x = new Date(d.getTime());
  if (s.unit === "hour") x.setHours(x.getHours() + s.n);
  else if (s.unit === "day") x.setDate(x.getDate() + s.n);
  else if (s.unit === "month") x.setMonth(x.getMonth() + s.n);
  else x.setFullYear(x.getFullYear() + s.n);
  return x;
}

export interface TimeTick {
  t: number;
  label: string;
}

/** Calendar-aligned ticks (local time) between t0 and t1, at most `max`. */
export function timeTicks(
  t0: number,
  t1: number,
  max = 4,
  opts: { locale?: string; now?: Date } = {},
): TimeTick[] {
  if (!(t1 > t0)) return [{ t: t0, label: fmtTimeTick(t0, "day", opts) }];
  const span = t1 - t0;
  let step = STEPS[STEPS.length - 1];
  let out: TimeTick[] = [];
  for (const s of STEPS) {
    if (span / s.ms > max + 1) continue; // can't fit, skip the walk
    const ticks = ticksFor(t0, t1, s);
    if (ticks.length <= max) {
      step = s;
      out = ticks;
      break;
    }
  }
  if (out.length === 0) out = ticksFor(t0, t1, step);
  // Year-crossing month axes name the year so "Jan" isn't ambiguous.
  const years = new Set(out.map((k) => new Date(k.t).getFullYear()));
  const nowYear = (opts.now ?? new Date()).getFullYear();
  const showYear = years.size > 1 || !years.has(nowYear);
  for (const k of out) k.label = fmtTimeTick(k.t, step.unit, { ...opts, showYear });
  return out;
}

function ticksFor(t0: number, t1: number, step: Step): TimeTick[] {
  const out: TimeTick[] = [];
  let d = floorTo(t0, step);
  while (d.getTime() < t0) d = advance(d, step);
  for (let guard = 0; d.getTime() <= t1 && guard < 60; guard++) {
    out.push({ t: d.getTime(), label: "" });
    d = advance(d, step);
  }
  return out;
}

function fmtTimeTick(
  t: number,
  unit: Unit,
  opts: { locale?: string; showYear?: boolean },
): string {
  const d = new Date(t);
  const { locale } = opts;
  if (unit === "hour") {
    return d.getHours() === 0
      ? new Intl.DateTimeFormat(locale, { month: "short", day: "numeric" }).format(d)
      : new Intl.DateTimeFormat(locale, { hour: "numeric" }).format(d);
  }
  if (unit === "day") {
    return new Intl.DateTimeFormat(locale, { month: "short", day: "numeric" }).format(d);
  }
  if (unit === "month") {
    const m = new Intl.DateTimeFormat(locale, { month: "short" }).format(d);
    return opts.showYear ? `${m} ’${String(d.getFullYear()).slice(2)}` : m;
  }
  return String(d.getFullYear());
}

/** Hover readout date: day precision for long spans, hour for short ones. */
export function fmtHoverTime(t: number, spanMs: number, locale?: string): string {
  const opts: Intl.DateTimeFormatOptions =
    spanMs < 3 * D
      ? { month: "short", day: "numeric", hour: "numeric", minute: "2-digit" }
      : { month: "short", day: "numeric", year: "numeric" };
  return new Intl.DateTimeFormat(locale, opts).format(new Date(t));
}

/** Index of the point whose x is nearest `x` (points sorted by x). */
export function nearestIndex(points: readonly (readonly [number, number])[], x: number): number {
  if (points.length === 0) return -1;
  let lo = 0;
  let hi = points.length - 1;
  while (hi - lo > 1) {
    const mid = (lo + hi) >> 1;
    if (points[mid][0] < x) lo = mid;
    else hi = mid;
  }
  return Math.abs(points[lo][0] - x) <= Math.abs(points[hi][0] - x) ? lo : hi;
}
