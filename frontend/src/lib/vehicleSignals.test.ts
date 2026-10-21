import { describe, expect, it } from "vitest";
import {
  cylindersLabel,
  fmtTempF,
  fmtTirePressure,
  gearLabel,
  tpmsReadings,
  vcmActive,
} from "./vehicleSignals";

describe("gearLabel", () => {
  it("maps the ZF 9HP codes", () => {
    expect(gearLabel(0)).toBe("P/N");
    expect(gearLabel(15)).toBe("R");
    expect(gearLabel(1)).toBe("D1");
    expect(gearLabel(2)).toBe("D2"); // stopped in D: launches in 2nd, shown as-is
    expect(gearLabel(9)).toBe("D9");
  });
  it("shows unknown codes raw and missing as a dash", () => {
    expect(gearLabel(12)).toBe("12");
    expect(gearLabel(null)).toBe("—");
    expect(gearLabel(Number.NaN)).toBe("—");
  });
});

describe("cylindersLabel", () => {
  it("flags VCM below six", () => {
    expect(cylindersLabel(6)).toBe("6");
    expect(cylindersLabel(3)).toBe("3 (VCM)");
    expect(vcmActive(3)).toBe(true);
    expect(vcmActive(6)).toBe(false);
    expect(vcmActive(0)).toBe(false);
    expect(cylindersLabel(null)).toBe("—");
  });
});

describe("fmtTempF", () => {
  it("passes °F through for imperial and converts for metric", () => {
    expect(fmtTempF(68, "imperial")).toBe("68 °F");
    expect(fmtTempF(68, "metric")).toBe("20 °C");
    expect(fmtTempF(null, "imperial")).toBe("—");
  });
});

describe("tpmsReadings", () => {
  const psi = (p: number) => p / 0.145038;
  it("marks a tire 2 psi or more below the highest as low", () => {
    const r = tpmsReadings({ fl: psi(35), fr: psi(35), rl: psi(33), rr: psi(33.5) });
    expect(r.map((t) => t.low)).toEqual([false, false, true, false]);
  });
  it("flags the tire the Pilot's dash warned about (2026-10-21, kPa as stored)", () => {
    const r = tpmsReadings({ fl: 213, fr: 196, rl: 209, rr: 205 });
    expect(r.map((t) => t.low)).toEqual([false, true, false, false]);
  });
  it("does not flag a uniform drop or a single reading", () => {
    expect(tpmsReadings({ fl: psi(30), fr: psi(30), rl: psi(30), rr: psi(30) }).some((t) => t.low)).toBe(false);
    expect(tpmsReadings({ fl: psi(20) }).some((t) => t.low)).toBe(false);
  });
  it("keeps corner order and carries temps", () => {
    const r = tpmsReadings({ rr: 240 }, { rr: 21 });
    expect(r.map((t) => t.label)).toEqual(["FL", "FR", "RL", "RR"]);
    expect(r[3]).toMatchObject({ pressureKpa: 240, tempC: 21, low: false });
    expect(r[0].pressureKpa).toBeNull();
  });
  it("formats whole psi / kPa", () => {
    // Always psi, whatever the unit system.
    expect(fmtTirePressure(241.3)).toBe("35 psi");
    expect(fmtTirePressure(196)).toBe("28 psi");
  });
});
