import { describe, it, expect } from "vitest";
import { compareVersions } from "./endpoints";

describe("compareVersions", () => {
  it("treats equal versions as 0", () => {
    expect(compareVersions("1.2.3", "1.2.3")).toBe(0);
  });
  it("ignores a leading v", () => {
    expect(compareVersions("v1.2.3", "1.2.3")).toBe(0);
    expect(compareVersions("V0.1.180", "v0.1.180")).toBe(0);
  });
  it("orders by numeric patch, not lexical", () => {
    // lexical would put "180" < "9"; numeric must not.
    expect(compareVersions("0.1.180", "0.1.9")).toBeGreaterThan(0);
    expect(compareVersions("0.1.9", "0.1.180")).toBeLessThan(0);
  });
  it("orders by minor then major", () => {
    expect(compareVersions("0.2.0", "0.1.99")).toBeGreaterThan(0);
    expect(compareVersions("1.0.0", "0.99.99")).toBeGreaterThan(0);
  });
  it("treats a missing segment as 0", () => {
    expect(compareVersions("1.2", "1.2.0")).toBe(0);
    expect(compareVersions("1.2.1", "1.2")).toBeGreaterThan(0);
  });
});
