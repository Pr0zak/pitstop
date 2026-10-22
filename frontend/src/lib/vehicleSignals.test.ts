import { describe, expect, it } from "vitest";
import {
  TIRE_SLOW_LEAK_PSI_PER_WEEK,
  fmtBrakeBar,
  fmtPsiPerWeek,
  fmtTempRiseC,
  isSlowLeak,
  tireSpreadPsi,
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

describe("tire trend helpers", () => {
  it("formats psi/week with a true minus", () => {
    expect(fmtPsiPerWeek(-0.42)).toBe("−0.4 psi/week");
    expect(fmtPsiPerWeek(0.26)).toBe("+0.3 psi/week");
    expect(fmtPsiPerWeek(0.01)).toBe("0.0 psi/week");
    expect(fmtPsiPerWeek(null)).toBe("—");
  });
  it("flags a slow leak only on enough samples", () => {
    expect(isSlowLeak(TIRE_SLOW_LEAK_PSI_PER_WEEK, 4)).toBe(true);
    expect(isSlowLeak(-0.8, 3)).toBe(false);
    expect(isSlowLeak(-0.4, 10)).toBe(false);
    expect(isSlowLeak(null, 10)).toBe(false);
  });
  it("formats a temperature rise as a delta, not a reading", () => {
    expect(fmtTempRiseC(10, true)).toBe("+18 °F");
    expect(fmtTempRiseC(10, false)).toBe("+10 °C");
    expect(fmtTempRiseC(-2, false)).toBe("−2 °C");
    expect(fmtTempRiseC(0, true)).toBe("±0 °F");
    expect(fmtTempRiseC(null, true)).toBe("—");
  });
  it("measures spread below the highest tire", () => {
    const p = { fl: 213, fr: 196, rl: 209, rr: 205 };
    expect(Math.round(tireSpreadPsi(p, "fr")!)).toBe(-2);
    expect(tireSpreadPsi(p, "fl")).toBe(0);
    expect(tireSpreadPsi({ fl: 213 }, "fl")).toBeNull();
  });
});

describe("fmtBrakeBar", () => {
  it("is whole bar in every unit system", () => {
    expect(fmtBrakeBar(87.6)).toBe("88 bar");
    expect(fmtBrakeBar(-0.2)).toBe("0 bar");
    expect(fmtBrakeBar(null)).toBe("—");
  });
});
