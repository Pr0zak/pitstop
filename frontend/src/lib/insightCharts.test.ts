import { describe, expect, it } from "vitest";
import {
  barDomain,
  fmtInsightNumber,
  fmtTick,
  labelStride,
  linearScale,
  nearestIndex,
  timeTicks,
  valueDomain,
  valueLabelMask,
  valueWithUnit,
  wrapLabel,
} from "./insightCharts";

describe("domains", () => {
  it("bars always include zero, band and ref", () => {
    expect(barDomain([3, 7])).toEqual([0, 7]);
    expect(barDomain([-8.4, 5.1])).toEqual([-8.4, 5.1]);
    expect(barDomain([1, 2], { band: { lo: -5, hi: 5 } })).toEqual([-5, 5]);
    expect(barDomain([1, 2], { ref: { value: 10 } })).toEqual([0, 10]);
    expect(barDomain([0, 0])).toEqual([0, 1]);
  });
  it("line domain pads data plus band / ref", () => {
    const [lo, hi] = valueDomain([29, 30], { ref: { value: 35 } });
    expect(lo).toBeLessThan(29);
    expect(hi).toBeGreaterThan(35);
    expect(valueDomain([])).toEqual([0, 1]);
  });
  it("linear scale maps ends and survives a flat domain", () => {
    const s = linearScale(0, 10, 100, 0);
    expect(s(0)).toBe(100);
    expect(s(10)).toBe(0);
    expect(linearScale(5, 5, 0, 10)(5)).toBe(5);
  });
});

describe("label fitting", () => {
  it("strides labels to keep a minimum width", () => {
    expect(labelStride(16, 20, 36)).toBe(2);
    expect(labelStride(5, 80, 36)).toBe(1);
    expect(labelStride(1, 5, 100)).toBe(1);
  });
  it("wraps at spaces onto two lines with an ellipsis on overflow", () => {
    expect(wrapLabel("City", 8)).toEqual(["City"]);
    expect(wrapLabel("Short (<10 min)", 9)).toEqual(["Short", "(<10 min)"]);
    expect(wrapLabel("a very long label here", 6)).toEqual(["a very", "long …"]);
  });
  it("keeps highlighted value labels and clears their neighbours", () => {
    const hl = [false, false, false, true, false, false];
    expect(valueLabelMask(hl, 1)).toEqual([true, true, true, true, true, true]);
    expect(valueLabelMask(hl, 2)).toEqual([true, false, false, true, false, false]);
  });
});

describe("numbers", () => {
  it("uses a true minus and trims decimals", () => {
    expect(fmtInsightNumber(-4.6)).toBe("−4.6");
    expect(fmtInsightNumber(12.28)).toBe("12.28");
    expect(fmtInsightNumber(22)).toBe("22");
    expect(fmtInsightNumber(-0.001)).toBe("0");
  });
  it("tick decimals follow the step", () => {
    expect(fmtTick(12.5, 0.5)).toBe("12.5");
    expect(fmtTick(1400, 100)).toMatch(/^1,?400$/);
    expect(fmtTick(-2, 1)).toBe("−2");
  });
  it("puts $ in front and other units after", () => {
    expect(valueWithUnit(65, "$")).toEqual({ prefix: "$", number: "65", suffix: "" });
    expect(valueWithUnit(-3, "$")).toEqual({ prefix: "−$", number: "3", suffix: "" });
    expect(valueWithUnit(22, "% worse")).toEqual({ prefix: "", number: "22", suffix: "% worse" });
    expect(valueWithUnit(1, null).suffix).toBe("");
  });
});

describe("timeTicks", () => {
  const now = new Date(2026, 9, 22);
  it("months across a year boundary carry the year", () => {
    const t = timeTicks(new Date(2025, 5, 10).getTime(), new Date(2026, 9, 1).getTime(), 4, {
      locale: "en-US",
      now,
    });
    expect(t.length).toBeGreaterThan(1);
    expect(t.length).toBeLessThanOrEqual(4);
    for (const k of t) expect(k.label).toMatch(/^[A-Z][a-z]{2} ’\d{2}$/);
    for (let i = 1; i < t.length; i++) expect(t[i].t).toBeGreaterThan(t[i - 1].t);
  });
  it("a few days get day ticks", () => {
    const t = timeTicks(new Date(2026, 9, 1, 8).getTime(), new Date(2026, 9, 4, 20).getTime(), 4, {
      locale: "en-US",
      now,
    });
    expect(t.map((k) => k.label)).toEqual(["Oct 2", "Oct 3", "Oct 4"]);
  });
  it("hours within a day get hour ticks", () => {
    const t = timeTicks(new Date(2026, 9, 1, 7).getTime(), new Date(2026, 9, 1, 11).getTime(), 4, {
      locale: "en-US",
      now,
    });
    expect(t[0].label).toBe("9 AM");
  });
  it("stays within range", () => {
    const a = new Date(2026, 0, 1).getTime();
    const b = new Date(2026, 5, 1).getTime();
    for (const k of timeTicks(a, b, 4, { now })) {
      expect(k.t).toBeGreaterThanOrEqual(a);
      expect(k.t).toBeLessThanOrEqual(b);
    }
  });
});

describe("nearestIndex", () => {
  it("finds the closest x", () => {
    const pts: [number, number][] = [
      [0, 1],
      [10, 2],
      [20, 3],
    ];
    expect(nearestIndex(pts, 4)).toBe(0);
    expect(nearestIndex(pts, 6)).toBe(1);
    expect(nearestIndex(pts, 99)).toBe(2);
    expect(nearestIndex([], 1)).toBe(-1);
  });
});
