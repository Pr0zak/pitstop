import { describe, expect, it } from "vitest";
import type { Trip } from "@/api/types";
import { foldShortHops, groupTotals, tripMpg } from "./tripList";

let seq = 0;
function trip(distance_km: number | null, fuel_used_l: number | null = null): Trip {
  seq += 1;
  return {
    id: `t${seq}`,
    vehicle_id: "v",
    started_at: new Date(2026, 8, 25, 8, seq).toISOString(),
    distance_km,
    fuel_used_l,
  };
}

describe("tripMpg", () => {
  it("computes US mpg from km and litres", () => {
    // 40 km on 4 L = 10 L/100km = 23.5 mpg
    expect(tripMpg(trip(40, 4))!.toFixed(1)).toBe("23.5");
  });

  it("returns null (rendered as —) when fuel is absent or zero", () => {
    expect(tripMpg(trip(40, null))).toBeNull();
    expect(tripMpg(trip(40, 0))).toBeNull();
  });

  it("returns null for trips under ~0.1 mi", () => {
    expect(tripMpg(trip(0.1, 0.01))).toBeNull();
    expect(tripMpg(trip(null, 1))).toBeNull();
  });

  it("rejects physically implausible results", () => {
    expect(tripMpg(trip(5, 0.001))).toBeNull();
  });
});

describe("foldShortHops", () => {
  it("folds runs of two or more consecutive short trips", () => {
    const rows = foldShortHops([trip(12), trip(0.2), trip(0.1), trip(0.3), trip(8)]);
    expect(rows.map((r) => r.kind)).toEqual(["trip", "hops", "trip"]);
    const hops = rows[1];
    if (hops.kind !== "hops") throw new Error("expected hops");
    expect(hops.trips).toHaveLength(3);
    expect(hops.distanceKm).toBeCloseTo(0.6);
  });

  it("leaves a lone short trip as a normal row", () => {
    const rows = foldShortHops([trip(12), trip(0.2), trip(8)]);
    expect(rows.map((r) => r.kind)).toEqual(["trip", "trip", "trip"]);
  });

  it("does not treat an unknown distance as a short hop", () => {
    const rows = foldShortHops([trip(0.2), trip(null), trip(0.2)]);
    expect(rows.map((r) => r.kind)).toEqual(["trip", "trip", "trip"]);
  });
});

describe("groupTotals", () => {
  it("sums distance and fuel over the group's trips", () => {
    const t = groupTotals([trip(10, 1), trip(5, null), trip(0.2, 0.05)]);
    expect(t.count).toBe(3);
    expect(t.distanceKm).toBeCloseTo(15.2);
    expect(t.fuelL).toBeCloseTo(1.05);
  });

  it("reports no fuel (not zero) when no trip carried a fuel reading", () => {
    expect(groupTotals([trip(10), trip(5)]).fuelL).toBeNull();
  });
});
