import { describe, expect, it } from "vitest";
import { classifyMonth, fuelSeasonBands, fuelSeasonOn, seasonalMpg } from "./fuelSeasons";

const day = (y: number, m: number, d: number) => new Date(y, m - 1, d);

describe("fuelSeasonOn", () => {
  it("follows the federal retail dates (Jun 1 – Sep 15)", () => {
    expect(fuelSeasonOn(day(2026, 4, 14))).toBe("winter");
    expect(fuelSeasonOn(day(2026, 4, 15))).toBe("transition");
    expect(fuelSeasonOn(day(2026, 5, 31))).toBe("transition");
    expect(fuelSeasonOn(day(2026, 6, 1))).toBe("summer");
    expect(fuelSeasonOn(day(2026, 9, 15))).toBe("summer");
    expect(fuelSeasonOn(day(2026, 9, 16))).toBe("winter");
    expect(fuelSeasonOn(day(2026, 1, 10))).toBe("winter");
  });
  it("ignores the time of day", () => {
    expect(fuelSeasonOn(new Date(2026, 8, 15, 23, 59))).toBe("summer");
  });
});

describe("fuelSeasonBands", () => {
  it("emits a transition and a summer band per year in range", () => {
    const b = fuelSeasonBands(+day(2024, 1, 1), +day(2025, 12, 31));
    expect(b.map((x) => x.kind)).toEqual(["transition", "summer", "transition", "summer"]);
    expect(b[1]).toEqual({ kind: "summer", start: +day(2024, 6, 1), end: +day(2024, 9, 16) });
    expect(b[0]).toEqual({ kind: "transition", start: +day(2024, 4, 15), end: +day(2024, 6, 1) });
  });
  it("drops bands entirely outside the range", () => {
    expect(fuelSeasonBands(+day(2025, 10, 1), +day(2026, 3, 1))).toEqual([]);
    // Range starting mid-summer keeps that summer band, not its transition.
    expect(fuelSeasonBands(+day(2025, 7, 1), +day(2025, 12, 1)).map((x) => x.kind)).toEqual(["summer"]);
  });
  it("is empty for an inverted range", () => {
    expect(fuelSeasonBands(10, 0)).toEqual([]);
  });
});

describe("classifyMonth", () => {
  it("classifies by majority of days, September tie to summer", () => {
    expect(classifyMonth("2025-01")).toBe("winter");
    expect(classifyMonth("2025-03")).toBe("winter");
    expect(classifyMonth("2025-04")).toBe("transition"); // 14 winter, 16 transition
    expect(classifyMonth("2025-05")).toBe("transition");
    expect(classifyMonth("2025-06")).toBe("summer");
    expect(classifyMonth("2025-08")).toBe("summer");
    expect(classifyMonth("2025-09")).toBe("summer"); // 15/15 → season at month start
    expect(classifyMonth("2025-10")).toBe("winter");
    expect(classifyMonth("2025-12")).toBe("winter");
  });
  it("rejects malformed periods", () => {
    expect(classifyMonth("2025-1")).toBeNull();
    expect(classifyMonth("")).toBeNull();
  });
});

describe("seasonalMpg", () => {
  it("weights by fill-ups and leaves transition months out", () => {
    const r = seasonalMpg([
      { period: "2025-01", mpg: 18, fills: 3 },
      { period: "2025-02", mpg: 20, fills: 1 },
      { period: "2025-05", mpg: 99, fills: 4 }, // transition: ignored
      { period: "2025-07", mpg: 22, fills: 2 },
    ]);
    expect(r.winter.mpg).toBeCloseTo((18 * 3 + 20) / 4, 6);
    expect(r.winter.months).toBe(2);
    expect(r.summer.mpg).toBe(22);
    expect(r.summer.months).toBe(1);
  });
  it("returns null (never 0) for a season with no data", () => {
    const r = seasonalMpg([{ period: "2025-12", mpg: 19, fills: 2 }]);
    expect(r.summer.mpg).toBeNull();
    expect(r.summer.months).toBe(0);
    expect(r.winter.mpg).toBe(19);
  });
  it("skips non-positive MPG", () => {
    expect(seasonalMpg([{ period: "2025-07", mpg: 0, fills: 1 }]).summer.mpg).toBeNull();
  });
});
