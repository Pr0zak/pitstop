import { describe, expect, it } from "vitest";
import { fmtFuelRateLh, fmtPressureKpa, fmtTempC } from "./useFormat";

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
