/**
 * Route thumbnail geometry: GPS points → a small scaled outline. Only the
 * shape survives (normalised to the unit box); no coordinates are kept, so a
 * thumbnail can never leak where a trip went beyond its silhouette.
 */

export interface RouteShape {
  /** Points in [0, aw] × [0, ah] with y pointing down (north up). */
  pts: [number, number][];
  /** Aspect box: the longer side is 1. */
  aw: number;
  ah: number;
}

/** Equirectangular projection, downsampled to ≤ `maxPts` points. */
export function routeShape(
  points: readonly { lat: number; lon: number }[],
  maxPts = 80,
): RouteShape | null {
  const good = points.filter((p) => Number.isFinite(p.lat) && Number.isFinite(p.lon));
  if (good.length < 2) return null;
  const k = Math.max(1, Math.ceil(good.length / maxPts));
  const picked = good.filter((_, i) => i % k === 0);
  if (picked[picked.length - 1] !== good[good.length - 1]) picked.push(good[good.length - 1]);
  const lat0 = (picked.reduce((s, p) => s + p.lat, 0) / picked.length) * (Math.PI / 180);
  const xy = picked.map((p) => [p.lon * Math.cos(lat0), -p.lat] as [number, number]);
  const xs = xy.map((p) => p[0]);
  const ys = xy.map((p) => p[1]);
  const x0 = Math.min(...xs);
  const y0 = Math.min(...ys);
  const w = Math.max(...xs) - x0;
  const h = Math.max(...ys) - y0;
  const span = Math.max(w, h);
  if (!(span > 0)) return null;
  return {
    pts: xy.map(([x, y]) => [(x - x0) / span, (y - y0) / span]),
    aw: w / span,
    ah: h / span,
  };
}

/** Fit a shape into a `w`×`h` box with `pad` px margin, centred. */
export function fitShape(s: RouteShape, w: number, h: number, pad = 6): [number, number][] {
  const sc = Math.min((w - 2 * pad) / (s.aw || 1), (h - 2 * pad) / (s.ah || 1));
  const ox = (w - s.aw * sc) / 2;
  const oy = (h - s.ah * sc) / 2;
  return s.pts.map(([x, y]) => [ox + x * sc, oy + y * sc]);
}
