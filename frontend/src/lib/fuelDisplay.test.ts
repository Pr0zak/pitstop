import { describe, expect, it } from "vitest";
import { formatReadingAge, pickFuelDisplay } from "./fuelDisplay";

const base = {
  tank_capacity_l: 73.8,
  fuel_level_estimate_l: 53.1,
  fuel_level_estimate_updated_at: "2026-10-21T17:00:00Z",
};

describe("pickFuelDisplay", () => {
  it("uses the server's cluster pick", () => {
    const d = pickFuelDisplay({
      ...base,
      fuel_display_l: 44.5,
      fuel_display_source: "cluster",
      fuel_display_at: "2026-10-21T17:58:00Z",
    });
    expect(d?.liters).toBe(44.5);
    expect(d?.source).toBe("cluster");
    expect(d?.label).toBe("from cluster");
    expect(d?.pct).toBeCloseTo((44.5 / 73.8) * 100);
  });

  it("captions an estimate pick", () => {
    const d = pickFuelDisplay({
      ...base,
      fuel_display_l: 53.1,
      fuel_display_source: "estimate",
      fuel_display_at: base.fuel_level_estimate_updated_at,
    });
    expect(d?.source).toBe("estimate");
    expect(d?.label).toBe("estimated");
  });

  it("falls back to the estimate on an older backend", () => {
    const d = pickFuelDisplay(base);
    expect(d?.liters).toBe(53.1);
    expect(d?.at).toBe(base.fuel_level_estimate_updated_at);
  });

  it("is null when the server has nothing, or no tank size", () => {
    expect(
      pickFuelDisplay({
        tank_capacity_l: 73.8,
        fuel_level_estimate_l: null,
        fuel_display_l: null,
        fuel_display_source: null,
      }),
    ).toBeNull();
    expect(pickFuelDisplay({ ...base, tank_capacity_l: null })).toBeNull();
    expect(pickFuelDisplay(null)).toBeNull();
  });

  it("clamps the percentage", () => {
    const d = pickFuelDisplay({
      ...base,
      fuel_display_l: 80,
      fuel_display_source: "cluster",
      fuel_display_at: null,
    });
    expect(d?.pct).toBe(100);
  });
});

describe("formatReadingAge", () => {
  const now = Date.parse("2026-10-21T18:00:00Z");
  it("buckets", () => {
    expect(formatReadingAge("2026-10-21T17:59:30Z", now)).toBe("live");
    expect(formatReadingAge("2026-10-21T17:58:00Z", now)).toBe("2m ago");
    expect(formatReadingAge("2026-10-21T15:00:00Z", now)).toBe("3h ago");
    expect(formatReadingAge("2026-10-19T18:00:00Z", now)).toBe("2d ago");
    expect(formatReadingAge("nope", now)).toBeNull();
  });
});
