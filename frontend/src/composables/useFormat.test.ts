import { describe, expect, it } from "vitest";
import {
  fmtElevationM,
  fmtFuelRateLh,
  fmtMoney,
  fmtMpg,
  fmtOdo,
  fmtOdoKm,
  fmtPressureKpa,
  fmtPricePerVolume,
  fmtTempC,
  fmtVolume,
  fmtWhen,
  fmtTripTitle,
  dateGroupFor,
} from "./useFormat";

/**
 * Unit conversions are the recurring defect class in this repo: a value gets
 * rendered with a hardcoded canonical-unit string, so an imperial user reads a
 * metric number under a metric label on one screen and the converted number on
 * another. These tests pin the web side of the contract to the same arithmetic
 * the Android app asserts in `UnitFormatTest.kt`, so the two clients cannot
 * drift without one of the suites going red.
 *
 * Every case passes `system` explicitly — the formatters fall back to reading
 * the Pinia units store, which isn't active in a bare unit test.
 */
describe("fmtPressureKpa", () => {
  it("converts kPa to psi for imperial", () => {
    // Fuel rail sits near 3500 kPa on the Pilot -> 507.6 psi.
    expect(fmtPressureKpa(3500, "imperial")).toBe("508 psi");
    expect(fmtPressureKpa(3500, "metric")).toBe("3500 kPa");
  });

  it("converts MAP the same way it converts the fuel rail", () => {
    // The bug this guards: MAP pinned to kPa while the fuel rail converted,
    // putting two pressures under two unit systems in one chart.
    expect(fmtPressureKpa(101, "imperial")).toBe("15 psi");
    expect(fmtPressureKpa(101, "metric")).toBe("101 kPa");
  });

  it("honours the caller's precision", () => {
    expect(fmtPressureKpa(101.3, "imperial", 2)).toBe("14.69 psi");
  });

  it("renders an em dash for null / NaN", () => {
    expect(fmtPressureKpa(null, "imperial")).toBe("—");
    expect(fmtPressureKpa(undefined, "metric")).toBe("—");
    expect(fmtPressureKpa(Number.NaN, "imperial")).toBe("—");
  });
});

describe("fmtFuelRateLh", () => {
  // engine_fuel_rate is GRAMS PER SECOND on the wire; callers convert to L/h
  // with x3600/749.9 before formatting. 0.34 g/s idle burn -> 1.632 L/h.
  const idleLh = (0.34 * 3600) / 749.9;

  it("converts L/h to US gal/h for imperial", () => {
    expect(fmtFuelRateLh(idleLh, "imperial")).toBe("0.43 gph");
    expect(fmtFuelRateLh(idleLh, "metric")).toBe("1.63 L/h");
  });

  it("does not relabel the metric number as imperial", () => {
    expect(fmtFuelRateLh(idleLh, "imperial")).not.toBe(
      fmtFuelRateLh(idleLh, "metric"),
    );
  });
});

describe("fmtTempC", () => {
  it("converts catalyst-range temperatures without double-converting", () => {
    // 560 C cat -> 1040 F, the value the trip chart draws on the shared
    // catalyst scale.
    expect(fmtTempC(560, "imperial")).toBe("1040 °F");
    expect(fmtTempC(560, "metric")).toBe("560 °C");
  });
});

describe("Intl quantity formatters", () => {
  it("groups odometer readings and converts source units", () => {
    expect(fmtOdo(48210.4, "mi", "imperial")).toBe("48,210 mi");
    expect(fmtOdo(100, "km", "imperial")).toBe("62 mi");
    expect(fmtOdoKm(100, "metric")).toBe("100 km");
  });

  it("formats money with Intl currency", () => {
    expect(fmtMoney(1234.5)).toBe("$1,234.50");
    expect(fmtMoney("41.07")).toBe("$41.07");
    expect(fmtMoney(null)).toBe("—");
  });

  it("converts price per volume inversely to volume", () => {
    expect(fmtPricePerVolume(3.785, "gal", 3, "imperial")).toBe("$3.785/gal");
    expect(fmtPricePerVolume(3.785, "gal", 2, "metric")).toBe("$1.00/L");
  });

  it("converts volume and elevation", () => {
    expect(fmtVolume(10, "gal", 1, "metric")).toBe("37.9 L");
    expect(fmtElevationM(100, "imperial")).toBe("328 ft");
  });

  it("renders economy per system", () => {
    expect(fmtMpg(23.5, "imperial")).toBe("23.5 mpg");
    expect(fmtMpg(23.5, "metric")).toBe("10.0 L/100km");
  });
});

/**
 * Group-aware list dates. `now` is pinned to Fri 2026-09-25 14:00 local and
 * the locale to en-US so the expectations don't depend on the machine.
 * Intl puts a narrow no-break space before "AM"/"PM"; normalise it so the
 * expectations stay readable.
 */
describe("fmtWhen / fmtTripTitle", () => {
  const now = new Date(2026, 8, 25, 14, 0);
  const at = (y: number, mo: number, d: number, h = 6, mi = 33) =>
    new Date(y, mo - 1, d, h, mi).toISOString();
  const norm = (s: string) => s.replace(/[\u202f\u00a0]/g, " ");
  const opts = { now, locale: "en-US" };

  it("shows only the time inside a Today / Yesterday group", () => {
    expect(norm(fmtWhen(at(2026, 9, 25), { ...opts, withTime: true, grouped: true }))).toBe("6:33 AM");
    expect(norm(fmtWhen(at(2026, 9, 24, 18, 5), { ...opts, withTime: true, grouped: true }))).toBe("6:05 PM");
  });

  it("names the day outside a group", () => {
    expect(norm(fmtWhen(at(2026, 9, 25), { ...opts, withTime: true }))).toBe("Today 6:33 AM");
    expect(fmtWhen(at(2026, 9, 24), opts)).toBe("Yesterday");
  });

  it("uses weekday + time within the past week", () => {
    // Tue Sep 22 2026
    expect(norm(fmtWhen(at(2026, 9, 22, 18, 32), { ...opts, withTime: true, grouped: true }))).toBe("Tue 6:32 PM");
    expect(fmtWhen(at(2026, 9, 22), { ...opts, grouped: true })).toBe("Tue");
  });

  it("does not use a weekday for exactly 7 days back (it would read as today's)", () => {
    expect(fmtWhen(at(2026, 9, 18), { ...opts, grouped: true })).toBe("Sep 18");
  });

  it("uses month + day for the same year, adding the year when older", () => {
    expect(fmtWhen(at(2026, 9, 18), opts)).toBe("Sep 18");
    expect(norm(fmtWhen(at(2026, 9, 18), { ...opts, withTime: true }))).toBe("Sep 18, 6:33 AM");
    expect(fmtWhen(at(2025, 9, 7), { ...opts, withTime: true })).toBe("Sep 7, 2025");
  });

  it("reads a bare yyyy-MM-dd as a local calendar day, not UTC midnight", () => {
    expect(fmtWhen("2026-09-18", opts)).toBe("Sep 18");
    expect(fmtWhen("2026-09-24", opts)).toBe("Yesterday");
    expect(dateGroupFor("2026-09-25", now)).toBe("today");
  });

  it("buckets into the same groups the lists use", () => {
    expect(dateGroupFor(at(2026, 9, 24), now)).toBe("yesterday");
    expect(dateGroupFor(at(2026, 9, 20), now)).toBe("past7");
    expect(dateGroupFor(at(2026, 9, 1), now)).toBe("past30");
    expect(dateGroupFor(at(2026, 2, 1), now)).toBe("thisYear");
    expect(dateGroupFor(at(2025, 12, 31), now)).toBe("older");
    expect(dateGroupFor(null, now)).toBe("older");
  });

  it("formats the trip-detail title", () => {
    expect(norm(fmtTripTitle(at(2026, 9, 25), opts))).toBe("Fri, Sep 25 · 6:33 AM");
    expect(norm(fmtTripTitle(at(2025, 9, 25), opts))).toBe("Thu, Sep 25, 2025 · 6:33 AM");
  });

  it("renders an em dash for missing / unparseable input", () => {
    expect(fmtWhen(null, opts)).toBe("—");
    expect(fmtWhen("not a date", opts)).toBe("—");
  });
});
