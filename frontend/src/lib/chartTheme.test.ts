import { describe, expect, it } from "vitest";
import type uPlot from "uplot";
import { applyChartTheme, withAlpha } from "./chartTheme";

describe("applyChartTheme", () => {
  it("fills axis styling under the caller's options (caller wins)", () => {
    const out = applyChartTheme({
      width: 100,
      height: 100,
      series: [{}, {}],
      axes: [{}, { label: "mpg", stroke: "red" }],
    } as uPlot.Options);
    expect(out.axes?.[0].stroke).toBeTruthy();
    expect(out.axes?.[1].stroke).toBe("red");
    expect(out.axes?.[1].label).toBe("mpg");
    expect(out.axes?.[0].grid?.stroke).toBeTruthy();
  });

  it("assigns palette strokes only to series without one", () => {
    const out = applyChartTheme({
      width: 100,
      height: 100,
      series: [{}, {}, { stroke: "#123456" }],
    } as uPlot.Options);
    expect(out.series[1].stroke).toBeTruthy();
    expect(out.series[2].stroke).toBe("#123456");
    expect(out.series[0].stroke).toBeUndefined();
  });
});

describe("withAlpha", () => {
  it("converts hex to rgba and passes other colours through", () => {
    expect(withAlpha("#ff5b3a", 0.5)).toBe("rgba(255, 91, 58, 0.5)");
    expect(withAlpha("hsl(1,2%,3%)", 0.5)).toBe("hsl(1,2%,3%)");
  });
});
