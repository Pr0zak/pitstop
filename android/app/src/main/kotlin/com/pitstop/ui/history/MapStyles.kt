package com.pitstop.ui.history

/**
 * Basemap style URLs shared by the route and heatmap maps.
 *
 * Both maps ran on CARTO's Dark Matter GL style until 2026-09-01, when
 * CARTO began stamping an "API KEY REQUIRED" watermark across its
 * unauthenticated raster tiles. The vector style the phone used was not
 * watermarked at the time, but CARTO is retiring the free tier, so the
 * phone moved to OpenFreeMap alongside the web frontend rather than wait
 * for the same break — see frontend/src/lib/mapStyles.ts, which this file
 * mirrors for web/phone parity.
 *
 * OpenFreeMap serves OpenMapTiles-schema vector tiles with no API key, no
 * signup and no request quota. Attribution travels in the TileJSON, so
 * MapLibre renders the credits on its own once isAttributionEnabled is set.
 */
internal object MapStyles {
    /** Trip-detail and heatmap maps are both fixed dark on the phone. */
    const val DARK = "https://tiles.openfreemap.org/styles/dark"

    /** Light counterpart, for parity with the web toggle. Unused for now. */
    const val LIGHT = "https://tiles.openfreemap.org/styles/positron"
}
