// Pure helpers for the "Nearby prices" card (GET /fuel-prices/nearby,
// ADR-026). Kept out of the SFC so the copy and number rules are unit-
// tested — the Android card renders the same states with the same strings.

import type { FuelGrade, NearbyOrigin, NearbyOtherPrice } from "@/api/types";
import { fmtWhen } from "@/composables/useFormat";

export const GRADE_OPTIONS: readonly { value: FuelGrade; label: string }[] = [
  { value: "REGULAR_UNLEADED", label: "Regular" },
  { value: "MIDGRADE", label: "Mid" },
  { value: "PREMIUM", label: "Premium" },
  { value: "DIESEL", label: "Diesel" },
];
export const DEFAULT_GRADE: FuelGrade = "REGULAR_UNLEADED";

export function isFuelGrade(v: unknown): v is FuelGrade {
  return GRADE_OPTIONS.some((o) => o.value === v);
}

// Google reports more grades than the four we let the user pick; these
// show up in `other_prices`.
const EXTRA_GRADE_LABEL: Record<string, string> = {
  DIESEL_PLUS: "Diesel+",
  TRUCK_DIESEL: "Truck diesel",
  BIO_DIESEL: "Biodiesel",
  LPG: "LPG",
  METHANE: "Methane",
};

/** "REGULAR_UNLEADED" → "Regular"; unknown codes keep their own spelling
 *  ("SP95_E10" → "SP95 E10", "E85" → "E85"). */
export function gradeLabel(code: string): string {
  const known = GRADE_OPTIONS.find((o) => o.value === code);
  if (known) return known.label;
  return EXTRA_GRADE_LABEL[code] ?? code.replace(/_/g, " ");
}

/** 2 decimals unless the price carries a third ("2.99" vs "3.459"). */
export function priceDigits(price: number): 2 | 3 {
  const cents = price * 100;
  return Math.abs(cents - Math.round(cents)) < 1e-6 ? 2 : 3;
}

const priceNfCache = new Map<string, Intl.NumberFormat>();
/** "$3.49" / "$3.459". A missing price is "—", never "$0.00". */
export function fmtFuelPrice(
  price: number | null | undefined,
  currency?: string | null,
): string {
  if (price == null || !Number.isFinite(price)) return "—";
  const digits = priceDigits(price);
  const cur = (currency || "USD").toUpperCase();
  const key = `${cur}:${digits}`;
  let f = priceNfCache.get(key);
  if (!f) {
    try {
      f = new Intl.NumberFormat("en-US", {
        style: "currency",
        currency: cur,
        minimumFractionDigits: digits,
        maximumFractionDigits: digits,
      });
    } catch {
      // Unknown currency code — plain number, still never 0 for missing.
      return price.toFixed(digits);
    }
    priceNfCache.set(key, f);
  }
  return f.format(price);
}

/** Secondary line: "Diesel $3.79 · Premium $4.09" (server order). */
export function otherPricesLine(prices: NearbyOtherPrice[] | null | undefined): string {
  return (prices ?? [])
    .map((p) => `${gradeLabel(p.grade)} ${fmtFuelPrice(p.price, p.currency)}`)
    .join(" · ");
}

/** "You paid $3.29 · Sep 12", or null when there is no own history. */
export function myLastLine(
  price: number | null | undefined,
  date: string | null | undefined,
  opts: { now?: Date; locale?: string } = {},
): string | null {
  if (price == null) return null;
  const paid = `You paid ${fmtFuelPrice(price)}`;
  return date ? `${paid} · ${fmtWhen(date, opts)}` : paid;
}

/** "just now" / "12 min ago" / "3 h ago" / "4 d ago" — the same buckets
 *  as Android's DateLabel.agoMs, so both cards read identically. A price
 *  is only as good as its age, so the card shows ages, not clock times.
 *  Null when the timestamp is missing or unparseable. */
export function agoText(
  iso: string | null | undefined,
  nowMs: number = Date.now(),
): string | null {
  if (!iso) return null;
  const then = Date.parse(iso);
  if (!Number.isFinite(then)) return null;
  const mins = Math.max(0, Math.floor((nowMs - then) / 60_000));
  if (mins < 1) return "just now";
  if (mins < 60) return `${mins} min ago`;
  if (mins < 48 * 60) return `${Math.floor(mins / 60)} h ago`;
  return `${Math.floor(mins / (24 * 60))} d ago`;
}

/** Where the search was centred:
 *  vehicle → "Near where Pilot was parked · 3 h ago", home →
 *  "Near home", device → "Near this device", none → null. */
export function originLine(
  origin: NearbyOrigin | null | undefined,
  vehicleName: string | null | undefined,
  opts: { now?: Date; locale?: string } = {},
): string | null {
  if (!origin) return null;
  switch (origin.source) {
    case "vehicle": {
      const base = `Near where ${vehicleName || "the car"} was parked`;
      const ago = agoText(origin.as_of, (opts.now ?? new Date()).getTime());
      return ago ? `${base} · ${ago}` : base;
    }
    case "home":
      return "Near home";
    case "device":
      return "Near this device";
    default:
      return null;
  }
}

/** "Prices from Google · 12/900 lookups this month". */
export function usageLine(usage: { month_calls: number; monthly_cap: number }): string {
  return `Prices from Google · ${usage.month_calls}/${usage.monthly_cap} lookups this month`;
}

/** "No stations within 3.1 mi" — `radiusLabel` is the search radius
 *  already in the user's distance unit, as the Android card words it. */
export function emptyLine(radiusLabel: string): string {
  return `No stations within ${radiusLabel}`;
}
