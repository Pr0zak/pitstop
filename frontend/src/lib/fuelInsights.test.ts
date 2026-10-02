import { describe, expect, it } from "vitest";
import {
  compareSpend,
  batteryBand,
  batterySummary,
  compareToMarket,
  eiaLookup,
  groupByMonth,
  cumulativeSpend,
  dayOfYear,
  median,
  mpgOverSpan,
  niceTicks,
  quarterTicks,
  rollingMedian,
  totalAt,
} from "./fuelInsights";

describe("median / rollingMedian", () => {
  it("handles odd and even lengths", () => {
    expect(median([3, 1, 2])).toBe(2);
    expect(median([4, 1, 3, 2])).toBe(2.5);
    expect(Number.isNaN(median([]))).toBe(true);
  });
  it("is trailing with a short head", () => {
    expect(rollingMedian([10, 20, 15, 30, 5])).toEqual([10, 15, 15, 20, 15]);
  });
});

describe("niceTicks", () => {
  it("uses 1/2/5 steps, never 2.5", () => {
    expect(niceTicks(14.2, 22.9, 4)).toEqual([15, 20]);
    expect(niceTicks(14.2, 21.9, 4)).toEqual([16, 18, 20]);
    expect(niceTicks(0, 1500, 4)).toEqual([0, 500, 1000, 1500]);
    expect(niceTicks(3.1, 4.6, 4)).toEqual([3.5, 4, 4.5]);
    for (const t of [niceTicks(0, 10, 4), niceTicks(0, 25, 4)]) {
      const step = t[1] - t[0];
      const mant = step / 10 ** Math.floor(Math.log10(step));
      expect([1, 2, 5]).toContain(+mant.toFixed(6));
    }
  });
});

describe("mpgOverSpan", () => {
  const raw = [
    { period: "2023-01", mpg: 16, fillup_count: 2 },
    { period: "2025-12", mpg: null, fillup_count: 0 },
    { period: "2026-07", mpg: 18, fillup_count: 1 },
    { period: "2026-08", mpg: 20, fillup_count: 3 },
    { period: "2026-09", mpg: 19, fillup_count: 3 },
  ];
  it("weights the average by fillup count and drops null months", () => {
    const all = mpgOverSpan(raw, null);
    expect(all.points.map((p) => p.period)).toEqual(["2023-01", "2026-07", "2026-08", "2026-09"]);
    expect(all.average).toBeCloseTo((16 * 2 + 18 + 20 * 3 + 19 * 3) / 9, 6);
  });
  it("cuts the span back from the last month and medians within it", () => {
    const yr = mpgOverSpan(raw, 12);
    expect(yr.points.map((p) => p.period)).toEqual(["2026-07", "2026-08", "2026-09"]);
    expect(yr.points.map((p) => p.median)).toEqual([18, 19, 19]);
    expect(yr.average).toBeCloseTo((18 + 60 + 57) / 7, 6);
  });
  it("empty in, empty out", () => {
    expect(mpgOverSpan([], 36)).toEqual({ points: [], average: null });
  });
});

describe("compareToMarket", () => {
  // Newest-first like the API.
  const eia = [
    { week_of: "2026-09-28", price: 4.465 },
    { week_of: "2026-09-21", price: 4.478 },
    { week_of: "2026-09-14", price: 4.319 },
  ];
  it("matches each fill to the week starting on or before it", () => {
    const r = compareToMarket(eia, [
      { day: "2026-09-29", ppg: 4.299, gallons: 17.25 }, // week of 09-28
      { day: "2026-09-21", ppg: 4.578, gallons: 10 }, // same day as week start
      { day: "2026-09-20", ppg: 4.219, gallons: 12 }, // week of 09-14
      { day: "2026-09-01", ppg: 3.9, gallons: 15 }, // before the EIA window
    ]);
    expect(r.fills.map((f) => f.eia)).toEqual([4.319, 4.478, 4.465]);
    expect(r.fills.map((f) => +f.diff.toFixed(3))).toEqual([-0.1, 0.1, -0.166]);
    expect(r.meanDiff).toBeCloseTo(-0.166 / 3, 6);
    expect(r.gallons).toBeCloseTo(39.25, 6);
    expect(r.dollars).toBeCloseTo((0.166 / 3) * 39.25, 6);
  });
  it("keeps a fill up to 3 days before the first week (matched to it)", () => {
    const r = compareToMarket(eia, [{ day: "2026-09-11", ppg: 4.0, gallons: null }]);
    expect(r.fills).toHaveLength(1);
    expect(r.fills[0].eia).toBe(4.319);
    expect(r.gallons).toBe(0);
  });
  it("no EIA → nothing to compare", () => {
    expect(compareToMarket([], [{ day: "2026-09-29", ppg: 4, gallons: 1 }]).meanDiff).toBeNull();
  });
});

describe("spend by day of year", () => {
  const fills = [
    { day: "2025-01-10", cost: 50 },
    { day: "2025-03-01", cost: 60 },
    { day: "2025-12-30", cost: 70 },
    { day: "2026-01-05", cost: 40 },
    { day: "2026-02-20", cost: 55 },
    { day: "2026-02-21", cost: null },
  ];
  it("computes day-of-year offsets", () => {
    expect(dayOfYear("2026-01-01")).toBe(0);
    expect(dayOfYear("2026-03-01")).toBe(59);
    expect(dayOfYear("2024-03-01")).toBe(60); // leap year
  });
  it("accumulates per year from a (0,0) anchor", () => {
    const s = cumulativeSpend(fills, 2025);
    expect(s.map((p) => [p.doy, p.total])).toEqual([
      [0, 0],
      [9, 50],
      [59, 110],
      [363, 180],
    ]);
    expect(totalAt(s, 58)).toBe(50);
    expect(totalAt(s, 59)).toBe(110);
  });
  it("compares against last year by the day of this year's latest fillup", () => {
    const c = compareSpend(fills, 2026);
    expect(c.total).toBe(95);
    // 2026-02-20 = doy 50 → 2025 had only the Jan 10 fill by then.
    expect(c.previousAtSameDate).toBe(50);
    expect(c.delta).toBe(45);
  });
  it("quarter ticks land on the 1st of Jan/Apr/Jul/Oct", () => {
    expect(quarterTicks(2026)).toEqual([
      { doy: 0, label: "Jan" },
      { doy: 90, label: "Apr" },
      { doy: 181, label: "Jul" },
      { doy: 273, label: "Oct" },
    ]);
  });
});

describe("eiaLookup", () => {
  const look = eiaLookup([
    { week_of: "2026-09-28", price: 4.465 },
    { week_of: "2026-09-14", price: 4.319 },
  ]);
  it("takes the week starting on or before the day", () => {
    expect(look("2026-09-14")).toBe(4.319);
    expect(look("2026-09-27")).toBe(4.319);
    expect(look("2026-09-28")).toBe(4.465);
    expect(look("2026-10-30")).toBe(4.465);
  });
  it("covers a 3-day lead-in, nothing before", () => {
    expect(look("2026-09-11")).toBe(4.319);
    expect(look("2026-09-10")).toBeNull();
    expect(eiaLookup([])("2026-09-28")).toBeNull();
  });
});

describe("groupByMonth", () => {
  it("totals per month and scales the bar to the biggest month", () => {
    const rows = [
      { day: "2026-09-29", cost: 74, volume: 17.2 },
      { day: "2026-09-07", cost: 56, volume: 14.9 },
      { day: "2026-08-20", cost: 65, volume: null },
    ];
    const g = groupByMonth(rows, (r) => r);
    expect(g.map((x) => [x.key, x.fills, +x.volume.toFixed(1), x.total, +x.barFrac.toFixed(3)])).toEqual([
      ["2026-09", 2, 32.1, 130, 1],
      ["2026-08", 1, 0, 65, 0.5],
    ]);
  });
});

describe("batterySummary", () => {
  it("bands the latest reading and names the usual band", () => {
    const s = batterySummary([12.4, 12.15, 12.4, 11.97, 12.2, 12.1, 12.27]);
    expect(s).toMatchObject({ latest: 12.27, band: "fair", days: 7, mostly: "fair" });
    expect(batteryBand(12.4)).toBe("good");
    expect(batteryBand(11.99)).toBe("low");
    expect(batterySummary([])).toBeNull();
  });
});
