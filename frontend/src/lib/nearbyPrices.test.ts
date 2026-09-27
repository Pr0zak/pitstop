import { describe, expect, it } from "vitest";
import {
  agoText,
  emptyLine,
  fmtFuelPrice,
  gradeLabel,
  isFuelGrade,
  myLastLine,
  originLine,
  otherPricesLine,
  priceDigits,
  usageLine,
} from "./nearbyPrices";

// Fixed clock + locale so the shared date formatter is deterministic.
const NOW = new Date(2026, 8, 26, 21, 0); // Sat Sep 26 2026, 9 PM local
const OPTS = { now: NOW, locale: "en-US" };

describe("fmtFuelPrice", () => {
  it("renders a missing price as an em dash, never $0", () => {
    expect(fmtFuelPrice(null)).toBe("—");
    expect(fmtFuelPrice(undefined)).toBe("—");
    expect(fmtFuelPrice(Number.NaN)).toBe("—");
  });
  it("keeps 2 decimals for cent prices", () => {
    expect(fmtFuelPrice(2.99, "USD")).toBe("$2.99");
    expect(fmtFuelPrice(3.5)).toBe("$3.50");
  });
  it("keeps the third decimal when Google returns one", () => {
    expect(fmtFuelPrice(3.459, "USD")).toBe("$3.459");
    expect(priceDigits(3.459)).toBe(3);
    expect(priceDigits(3.49)).toBe(2);
  });
  it("survives an unknown currency code", () => {
    expect(fmtFuelPrice(1.5, "NOPE")).toBe("1.50");
  });
});

describe("grades", () => {
  it("labels the four selectable grades", () => {
    expect(gradeLabel("REGULAR_UNLEADED")).toBe("Regular");
    expect(gradeLabel("MIDGRADE")).toBe("Mid");
    expect(gradeLabel("PREMIUM")).toBe("Premium");
    expect(gradeLabel("DIESEL")).toBe("Diesel");
  });
  it("passes through grades outside the picker", () => {
    expect(gradeLabel("E85")).toBe("E85");
    expect(gradeLabel("SP95_E10")).toBe("SP95 E10");
    expect(gradeLabel("DIESEL_PLUS")).toBe("Diesel+");
  });
  it("validates stored grade values", () => {
    expect(isFuelGrade("PREMIUM")).toBe(true);
    expect(isFuelGrade("E85")).toBe(false);
    expect(isFuelGrade(null)).toBe(false);
  });
  it("joins other grades in server order", () => {
    expect(
      otherPricesLine([
        { grade: "DIESEL", price: 3.79, currency: "USD", updated_at: null },
        { grade: "PREMIUM", price: 4.099, currency: "USD", updated_at: null },
      ]),
    ).toBe("Diesel $3.79 · Premium $4.099");
    expect(otherPricesLine([])).toBe("");
  });
});

describe("copy", () => {
  it("shows own history only when there is a price", () => {
    expect(myLastLine(null, "2026-09-12")).toBeNull();
    expect(myLastLine(3.29, "2026-09-12", OPTS)).toBe("You paid $3.29 · Sep 12");
    expect(myLastLine(3.29, null)).toBe("You paid $3.29");
  });
  it("names where the search was centred", () => {
    const asOf = new Date(2026, 8, 26, 18, 5).toISOString();
    expect(
      originLine({ lat: 0, lon: 0, source: "vehicle", as_of: asOf }, "Pilot", OPTS),
    ).toBe("Near where Pilot was parked · 2 h ago");
    expect(originLine({ lat: 0, lon: 0, source: "vehicle", as_of: null }, null)).toBe(
      "Near where the car was parked",
    );
    expect(originLine({ lat: 0, lon: 0, source: "home", as_of: null }, "Pilot")).toBe("Near home");
    expect(originLine(null, "Pilot")).toBeNull();
  });
  it("formats usage and the empty state", () => {
    expect(usageLine({ month_calls: 1, monthly_cap: 900 })).toBe(
      "Prices from Google · 1/900 lookups this month",
    );
    expect(emptyLine("3.1 mi")).toBe("No stations within 3.1 mi");
  });
  it("words ages like the Android card", () => {
    const at = (minsAgo: number) => new Date(NOW.getTime() - minsAgo * 60_000).toISOString();
    const now = NOW.getTime();
    expect(agoText(at(0), now)).toBe("just now");
    expect(agoText(at(12), now)).toBe("12 min ago");
    expect(agoText(at(3 * 60 + 5), now)).toBe("3 h ago");
    expect(agoText(at(47 * 60), now)).toBe("47 h ago");
    expect(agoText(at(4 * 24 * 60), now)).toBe("4 d ago");
    expect(agoText(null, now)).toBeNull();
    expect(agoText("not a date", now)).toBeNull();
  });
});
