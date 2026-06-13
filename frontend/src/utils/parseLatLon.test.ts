import { describe, it, expect } from "vitest";
import { parseLatLon, validCoords, roundCoords } from "./parseLatLon";

describe("parseLatLon", () => {
  it("parses a plain comma pair", () => {
    expect(parseLatLon("40.7128, -74.0060")).toEqual({ lat: 40.7128, lon: -74.006 });
  });
  it("parses a plain space pair", () => {
    expect(parseLatLon("40.7128 -74.0060")).toEqual({ lat: 40.7128, lon: -74.006 });
  });
  it("parses a Google Maps @lat,lon URL", () => {
    const got = parseLatLon(
      "https://www.google.com/maps/@40.7128,-74.0060,15z/data=abc",
    );
    expect(got).toEqual({ lat: 40.7128, lon: -74.006 });
  });
  it("parses a Google Maps !3d!4d place URL", () => {
    expect(parseLatLon("foo!3d40.7128!4d-74.0060bar")).toEqual({
      lat: 40.7128,
      lon: -74.006,
    });
  });
  it("parses ?q=lat,lon", () => {
    expect(parseLatLon("https://maps.example/?q=40.7128,-74.0060")).toEqual({
      lat: 40.7128,
      lon: -74.006,
    });
  });
  it("parses Apple ?ll=lat,lon", () => {
    expect(parseLatLon("maps://?ll=40.7128,-74.0060")).toEqual({
      lat: 40.7128,
      lon: -74.006,
    });
  });
  it("parses OSM mlat/mlon in either order", () => {
    expect(parseLatLon("https://osm/?mlon=-74.0060&mlat=40.7128")).toEqual({
      lat: 40.7128,
      lon: -74.006,
    });
  });
  it("rejects empty input", () => {
    expect(parseLatLon("")).toBeNull();
    expect(parseLatLon("   ")).toBeNull();
  });
  it("rejects null-island 0,0", () => {
    expect(parseLatLon("0, 0")).toBeNull();
  });
  it("rejects out-of-range coords", () => {
    expect(parseLatLon("200, 500")).toBeNull();
  });
  it("rejects non-coordinate text", () => {
    expect(parseLatLon("https://maps.app.goo.gl/abcdef")).toBeNull();
  });
});

describe("validCoords", () => {
  it("accepts an in-range pair", () => {
    expect(validCoords(40.7, -74)).toBe(true);
  });
  it("rejects NaN", () => {
    expect(validCoords(NaN, -74)).toBe(false);
  });
  it("rejects 0,0", () => {
    expect(validCoords(0, 0)).toBe(false);
  });
  it("rejects out of bounds", () => {
    expect(validCoords(91, 0)).toBe(false);
    expect(validCoords(0, 181)).toBe(false);
  });
});

describe("roundCoords", () => {
  it("rounds to 5 decimals", () => {
    expect(roundCoords(40.712812345, -74.006012345)).toEqual({
      lat: 40.71281,
      lon: -74.00601,
    });
  });
});
