import { describe, expect, it } from "vitest";
import { fitShape, routeShape } from "./routeThumb";

describe("routeShape", () => {
  it("normalises to the unit box, north up", () => {
    const s = routeShape([
      { lat: 0, lon: 0 },
      { lat: 0.01, lon: 0 },
      { lat: 0.01, lon: 0.005 },
    ])!;
    expect(s.ah).toBeCloseTo(1, 6);
    expect(s.aw).toBeCloseTo(0.5, 3);
    // First point is the southern one → bottom of the box.
    expect(s.pts[0][1]).toBeCloseTo(1, 6);
    expect(s.pts[1][1]).toBeCloseTo(0, 6);
  });
  it("downsamples but keeps the end point", () => {
    const pts = Array.from({ length: 1000 }, (_, i) => ({ lat: i * 1e-4, lon: Math.sin(i / 50) * 1e-3 }));
    const s = routeShape(pts, 80)!;
    expect(s.pts.length).toBeLessThanOrEqual(81);
    expect(s.pts[s.pts.length - 1][1]).toBeCloseTo(0, 6);
  });
  it("needs a real extent", () => {
    expect(routeShape([{ lat: 1, lon: 1 }])).toBeNull();
    expect(routeShape([{ lat: 1, lon: 1 }, { lat: 1, lon: 1 }])).toBeNull();
  });
  it("fits centred inside the padding", () => {
    const s = routeShape([{ lat: 0, lon: 0 }, { lat: 0.01, lon: 0 }])!;
    const p = fitShape(s, 48, 48, 6);
    expect(p[0]).toEqual([24, 42]);
    expect(p[1]).toEqual([24, 6]);
  });
});
