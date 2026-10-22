import { describe, expect, it } from "vitest";
import {
  TIRE_MAX_CARRY_S,
  buildTireChart,
  fillForwardWithin,
  hasTireData,
  tireWarmupC,
  validTireValue,
} from "./tripTires";

const T0 = Date.UTC(2026, 9, 21, 22, 41, 0);
const at = (s: number) => new Date(T0 + s * 1000).toISOString();
const ts = (s: number) => Math.round(T0 / 1000) + s;
const id = (v: number) => v;

describe("validTireValue / hasTireData", () => {
  it("drops the 0 kPa and −40 °C sentinels", () => {
    expect(validTireValue("pressure", 0)).toBe(false);
    expect(validTireValue("pressure", 213)).toBe(true);
    expect(validTireValue("temp", -40)).toBe(false);
    expect(validTireValue("temp", 0)).toBe(true);
    expect(validTireValue("temp", null)).toBe(false);
  });
  it("ignores other metrics and sentinel-only tires", () => {
    expect(hasTireData([])).toBe(false);
    expect(hasTireData([{ time: at(0), metric: "engine_rpm", value_num: 800 }])).toBe(false);
    expect(hasTireData([{ time: at(0), metric: "tire_pressure_fl", value_num: 0 }])).toBe(false);
    expect(hasTireData([{ time: at(0), metric: "tire_temp_rr", value_num: 22 }])).toBe(true);
  });
});

describe("fillForwardWithin", () => {
  it("carries within the window and breaks after it", () => {
    const xs = [0, 1, 2, 5, 6];
    expect(fillForwardWithin(xs, [10, null, null, null, 20], 3)).toEqual([10, 10, 10, null, 20]);
  });
  it("never invents a value before the first reading", () => {
    expect(fillForwardWithin([0, 1, 2], [null, 5, null], 10)).toEqual([null, 5, 5]);
  });
});

describe("buildTireChart", () => {
  it("returns null without data of that kind", () => {
    expect(buildTireChart([], "pressure", id)).toBeNull();
    expect(
      buildTireChart([{ time: at(0), metric: "tire_temp_fl", value_num: 20 }], "pressure", id),
    ).toBeNull();
  });

  it("aligns corners, converts, and carries between 30-s readings", () => {
    const samples = [
      { time: at(0), metric: "tire_pressure_fl", value_num: 213 },
      { time: at(0), metric: "tire_pressure_fr", value_num: 196 },
      { time: at(15), metric: "tire_pressure_fr", value_num: 197 },
      { time: at(30), metric: "tire_pressure_fl", value_num: 214 },
      { time: at(30), metric: "vehicle_speed", value_num: 50 },
    ];
    const out = buildTireChart(samples, "pressure", (v) => v * 0.145038)!;
    expect(out.x).toEqual([ts(0), ts(15), ts(30)]);
    expect(out.present).toEqual(["fl", "fr"]);
    expect(out.cols.fl.map((v) => (v == null ? null : Math.round(v)))).toEqual([31, 31, 31]);
    expect(out.cols.fr.map((v) => (v == null ? null : Math.round(v)))).toEqual([28, 29, 29]);
    // Corners with no data are all-null, never zero.
    expect(out.cols.rl).toEqual([null, null, null]);
    expect(out.cols.rr.every((v) => v === null)).toBe(true);
  });

  it("inserts a null break across a dropout instead of joining the line", () => {
    const samples = [
      { time: at(0), metric: "tire_temp_fl", value_num: 20 },
      { time: at(600), metric: "tire_temp_fl", value_num: 30 },
    ];
    const out = buildTireChart(samples, "temp", id)!;
    expect(out.x).toEqual([ts(0), ts(TIRE_MAX_CARRY_S), ts(600)]);
    expect(out.cols.fl).toEqual([20, null, 30]);
  });

  it("breaks one corner that goes quiet while others keep reporting", () => {
    const samples = [0, 30, 60, 90, 120, 150].flatMap((s) => [
      { time: at(s), metric: "tire_pressure_rl", value_num: 209 },
      ...(s === 0 ? [{ time: at(s), metric: "tire_pressure_rr", value_num: 205 }] : []),
    ]);
    const out = buildTireChart(samples, "pressure", id)!;
    expect(out.cols.rr).toEqual([205, 205, 205, null, null, null]);
    expect(out.cols.rl.every((v) => v === 209)).toBe(true);
  });

  it("drops sentinels rather than plotting them", () => {
    const out = buildTireChart(
      [
        { time: at(0), metric: "tire_pressure_fl", value_num: 213 },
        { time: at(30), metric: "tire_pressure_fl", value_num: 0 },
      ],
      "pressure",
      id,
    )!;
    expect(out.x).toEqual([ts(0)]);
    expect(out.cols.fl).toEqual([213]);
  });
});

describe("tireWarmupC", () => {
  it("needs both ends", () => {
    expect(tireWarmupC({ corner: "fl", temp_start_c: 18, temp_end_c: 29 })).toBe(11);
    expect(tireWarmupC({ corner: "fl", temp_start_c: null, temp_end_c: 29 })).toBeNull();
  });
});
