import { describe, expect, it } from "vitest";
import type { TireAnalytics } from "@/api/endpoints";
import { coldPressureChart, dayLabel, dayTicks, dayToMs, populatedDays, tireTempChart } from "./tireAnalytics";

function fixture(): TireAnalytics {
  const days = ["2026-10-20", "2026-10-21", "2026-10-22"];
  const empty = (day: string) => ({
    day,
    pressure_min_kpa: null,
    pressure_mean_kpa: null,
    pressure_max_kpa: null,
    temp_mean_c: null,
    temp_max_c: null,
    cold_pressure_kpa: null,
    warmup_psi: null,
  });
  const corner = (c: "fl" | "fr" | "rl" | "rr") => ({
    corner: c,
    daily: days.map(empty),
    cold_readings: [],
    current_cold_kpa: null,
    current_cold_at: null,
    slope_psi_per_week: null,
    slope_samples: 0,
    possible_slow_leak: false,
    warmup_median_psi: null,
  });
  const a: TireAnalytics = {
    has_data: true,
    window_days: 3,
    days,
    ambient: days.map((day) => ({ day, ambient_mean_f: day === "2026-10-21" ? 76 : null })),
    corners: [corner("fl"), corner("fr"), corner("rl"), corner("rr")],
    low_corners: [],
    low_delta_psi: 2,
    slow_leak_psi_per_week: -0.5,
    slow_leak_min_samples: 4,
    cold_park_hours: 3,
  };
  a.corners[1].daily[1] = { ...a.corners[1].daily[1], cold_pressure_kpa: 196, temp_max_c: 27 };
  return a;
}

describe("day helpers", () => {
  it("places a UTC day at noon UTC", () => {
    expect(dayToMs("2026-10-21")).toBe(Date.UTC(2026, 9, 21, 12));
    expect(dayLabel("2026-10-21")).toBe("Oct 21");
  });
  it("thins ticks and keeps the last day", () => {
    const days = Array.from({ length: 30 }, (_, i) => `2026-10-${String(i + 1).padStart(2, "0")}`);
    const t = dayTicks(days, 6);
    expect(t.length).toBeLessThanOrEqual(6);
    expect(t[t.length - 1].label).toBe("Oct 30");
    expect(dayTicks([])).toEqual([]);
  });
});

describe("coldPressureChart", () => {
  it("psi per corner, nulls (not zeros) on empty days", () => {
    const ch = coldPressureChart(fixture(), (f) => f);
    expect(ch.corners.fr.map((v) => (v == null ? null : Math.round(v)))).toEqual([null, 28, null]);
    expect(ch.corners.fl).toEqual([null, null, null]);
    expect(ch.outside).toEqual([null, 76, null]);
    expect(ch.hasCorner).toBe(true);
    expect(ch.hasOutside).toBe(true);
    expect(populatedDays(ch)).toEqual([1]);
  });
});

describe("tireTempChart", () => {
  it("converts both tire and outside temps to the display unit", () => {
    const ch = tireTempChart(fixture(), (c) => c, (f) => ((f - 32) * 5) / 9);
    expect(ch.corners.fr[1]).toBe(27);
    expect(Math.round(ch.outside[1]!)).toBe(24);
  });
  it("reports no corner data when the window is empty", () => {
    const a = fixture();
    a.corners[1].daily[1].temp_max_c = null;
    expect(tireTempChart(a, (c) => c, (f) => f).hasCorner).toBe(false);
  });
});
