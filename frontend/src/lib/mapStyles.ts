// Shared basemap style URLs for every MapLibre view.
//
// These were three separate inline raster style objects until 2026-09-01,
// when CARTO began stamping an "API KEY REQUIRED" watermark across its
// unauthenticated raster tiles (basemaps.cartocdn.com/dark_all/...) and
// every dark basemap in the app broke at once.
//
// OpenFreeMap replaces them: OpenMapTiles-schema vector tiles served with
// no API key, no signup and no request quota, and it publishes a
// dark/light pair that drops straight onto the existing per-view toggle.
// Vector also renders sharper than the old raster tiles on high-DPI
// displays. Attribution travels in the TileJSON, so MapLibre renders the
// OpenFreeMap / OpenMapTiles / OpenStreetMap credits on its own — do not
// add an AttributionControl for it.
//
// The trade-off is that OpenFreeMap is a free public instance with no
// SLA. If it ever goes away, the migration path is a self-hosted
// Protomaps .pmtiles extract served from the stack's own Caddy container.
export const DARK_STYLE = "https://tiles.openfreemap.org/styles/dark";
export const LIGHT_STYLE = "https://tiles.openfreemap.org/styles/positron";
