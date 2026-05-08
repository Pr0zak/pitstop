/**
 * Parse a latitude/longitude pair from common share formats.
 * Returns { lat, lon } on success, or null if the input doesn't yield coords.
 *
 * Supported:
 *   - Plain pair:   "39.012, -94.654"  /  "39.012 -94.654"  /  "39.012,-94.654"
 *   - Google Maps:  ".../@LAT,LON,zoomZ/..."          (most common share)
 *   - Google Maps:  "?q=LAT,LON"  /  "&q=LAT,LON"     (older format)
 *   - Google Maps:  "!3dLAT!4dLON"                    (place-detail format)
 *   - Apple Maps:   "?ll=LAT,LON"
 *   - OpenStreetMap "?mlat=LAT&mlon=LON" (or vice versa)
 *
 * Not supported (need server-side redirect resolution):
 *   - Google short links: "https://maps.app.goo.gl/..."
 *     The user must open the link first and paste the resolved long URL.
 */
export function parseLatLon(input: string): { lat: number; lon: number } | null {
  const s = input.trim();
  if (!s) return null;

  // Plain "LAT,LON" or "LAT LON" (with optional spaces).
  const plain = s.match(/^(-?\d+(?:\.\d+)?)[,\s]+(-?\d+(?:\.\d+)?)$/);
  if (plain) {
    const lat = parseFloat(plain[1]);
    const lon = parseFloat(plain[2]);
    if (validCoords(lat, lon)) return { lat, lon };
  }

  // Google Maps "@LAT,LON" anywhere in the URL — most common share format.
  const at = s.match(/@(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)/);
  if (at) {
    const lat = parseFloat(at[1]);
    const lon = parseFloat(at[2]);
    if (validCoords(lat, lon)) return { lat, lon };
  }

  // Google Maps "!3dLAT!4dLON" — appears in place-detail URLs.
  const bang = s.match(/!3d(-?\d+(?:\.\d+)?)!4d(-?\d+(?:\.\d+)?)/);
  if (bang) {
    const lat = parseFloat(bang[1]);
    const lon = parseFloat(bang[2]);
    if (validCoords(lat, lon)) return { lat, lon };
  }

  // ?q=LAT,LON or &q=LAT,LON
  const q = s.match(/[?&]q=(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)/);
  if (q) {
    const lat = parseFloat(q[1]);
    const lon = parseFloat(q[2]);
    if (validCoords(lat, lon)) return { lat, lon };
  }

  // Apple Maps ?ll=LAT,LON
  const ll = s.match(/[?&]ll=(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)/);
  if (ll) {
    const lat = parseFloat(ll[1]);
    const lon = parseFloat(ll[2]);
    if (validCoords(lat, lon)) return { lat, lon };
  }

  // OpenStreetMap ?mlat=LAT&mlon=LON (order independent)
  const mlat = s.match(/[?&]mlat=(-?\d+(?:\.\d+)?)/);
  const mlon = s.match(/[?&]mlon=(-?\d+(?:\.\d+)?)/);
  if (mlat && mlon) {
    const lat = parseFloat(mlat[1]);
    const lon = parseFloat(mlon[1]);
    if (validCoords(lat, lon)) return { lat, lon };
  }

  return null;
}

export function validCoords(lat: number, lon: number): boolean {
  return (
    Number.isFinite(lat) &&
    Number.isFinite(lon) &&
    lat >= -90 &&
    lat <= 90 &&
    lon >= -180 &&
    lon <= 180 &&
    !(lat === 0 && lon === 0) // null-island filter
  );
}

/** Round to 5 decimals (~1.1 m precision) — same convention as the geolocate path. */
export function roundCoords(lat: number, lon: number): { lat: number; lon: number } {
  return {
    lat: Math.round(lat * 1e5) / 1e5,
    lon: Math.round(lon * 1e5) / 1e5,
  };
}
