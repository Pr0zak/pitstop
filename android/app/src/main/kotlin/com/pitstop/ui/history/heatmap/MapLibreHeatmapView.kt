package com.pitstop.ui.history.heatmap

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pitstop.ui.history.detail.densityColor
import com.pitstop.ui.history.detail.speedColor
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

private const val TAG = "PitstopHeatmap"

/**
 * Combined-trips heatmap (MAP-2). Renders every (lat, lon, speed,
 * timestamp) tuple from /api/analytics/route-trace as a polyline
 * segment, broken at >30 s gaps. Each segment carries both a
 * speed-color and a density-color (computed from per-cell visit
 * counts on a 4-decimal lat/lon grid). The active mode is selected
 * by the `data-driven get(<attr>)` line-color expression.
 *
 * Mirrors the web `HeatmapView.vue`. Reuses the same dark-matter
 * basemap and discrete density tiers via [densityColor] in
 * TripDetailHelpers.
 */
enum class HeatmapMode { Density, Speed }

@Composable
fun MapLibreHeatmapView(
    points: List<List<Double>>,
    mode: HeatmapMode,
    stations: List<Pair<Double, Double>> = emptyList(),
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val initOk = remember(context.applicationContext) {
        runCatching { MapLibre.getInstance(context.applicationContext) }
            .onFailure { Log.w(TAG, "MapLibre.getInstance failed", it) }
            .isSuccess
    }
    if (!initOk) {
        MapUnavailableFallback(modifier)
        return
    }

    val mapView = remember {
        runCatching { MapView(context) }
            .onFailure { Log.w(TAG, "MapView construction failed", it) }
            .getOrNull()
    }
    if (mapView == null) {
        MapUnavailableFallback(modifier)
        return
    }

    // Per-MapView mutable state: tracks whether we've initialised the
    // style, whether fit-bounds has run once, and the last data
    // reference. Lets us reuse the existing layer for mode toggles
    // (no style reload → camera stays put) and for data refreshes
    // (just swap the GeoJSON source).
    val mapState = remember { HeatmapMapState() }

    DisposableEffect(lifecycleOwner, mapView) {
        runCatching { mapView.onCreate(null) }
        runCatching { mapView.onStart() }
        val observer = LifecycleEventObserver { _, event ->
            runCatching {
                when (event) {
                    Lifecycle.Event.ON_RESUME -> mapView.onResume()
                    Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                    Lifecycle.Event.ON_STOP -> mapView.onStop()
                    else -> Unit
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            runCatching { mapView.onPause() }
            runCatching { mapView.onStop() }
            runCatching { mapView.onDestroy() }
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { mapView },
            update = { view ->
                runCatching {
                    view.getMapAsync { map ->
                        runCatching { applyToMap(map, points, mode, stations, mapState) }
                            .onFailure { Log.w(TAG, "applyToMap failed", it) }
                    }
                }.onFailure { Log.w(TAG, "getMapAsync failed", it) }
            },
        )
    }
}

/** Per-MapView state. Survives recompositions via `remember { ... }`. */
private class HeatmapMapState {
    var styleLoaded: Boolean = false
    var lastPointsRef: List<List<Double>>? = null
    var fittedBounds: Boolean = false
    var lastStationsRef: List<Pair<Double, Double>>? = null
}

@Composable
private fun MapUnavailableFallback(modifier: Modifier) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .wrapContentSize(Alignment.Center),
    ) {
        Text(
            "Map unavailable",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val SOURCE = "pitstop-heatmap"
private const val LAYER = "pitstop-heatmap-layer"
private const val STATIONS_SOURCE = "pitstop-stations"
private const val STATIONS_LAYER = "pitstop-stations-layer"
private const val STATIONS_HALO_LAYER = "pitstop-stations-halo"
private const val STYLE_URL =
    "https://basemaps.cartocdn.com/gl/dark-matter-gl-style/style.json"
private const val MAX_GAP_S = 30L

/**
 * Single entrypoint called on every recomposition. Branches based on
 * whether the style has been loaded and whether the data reference
 * has changed — preserves the user's pan/zoom across mode toggles
 * and data refreshes by avoiding unnecessary style reloads.
 */
private fun applyToMap(
    map: MapLibreMap,
    points: List<List<Double>>,
    mode: HeatmapMode,
    stations: List<Pair<Double, Double>>,
    state: HeatmapMapState,
) {
    map.uiSettings.isLogoEnabled = false
    map.uiSettings.isAttributionEnabled = true
    map.uiSettings.isCompassEnabled = false

    if (!state.styleLoaded) {
        // First time only — load the style asynchronously, then plant
        // the layer in the load callback.
        map.setStyle(STYLE_URL) { style ->
            state.styleLoaded = true
            runCatching { rebuildLayer(map, style, points, mode, state) }
                .onFailure { Log.w(TAG, "rebuildLayer (initial) failed", it) }
            runCatching { applyStations(style, stations, state) }
                .onFailure { Log.w(TAG, "applyStations (initial) failed", it) }
        }
        return
    }

    val style = map.style ?: return  // style being reloaded; skip
    val pointsChanged = points !== state.lastPointsRef
    val hasLayer = style.getLayer(LAYER) != null
    if (pointsChanged || !hasLayer) {
        runCatching { rebuildLayer(map, style, points, mode, state) }
            .onFailure { Log.w(TAG, "rebuildLayer failed", it) }
    } else {
        // Same data, just a mode toggle — swap the line-color
        // expression on the existing layer. Camera + zoom stay put.
        runCatching { applyMode(style, mode) }
            .onFailure { Log.w(TAG, "applyMode failed", it) }
    }
    runCatching { applyStations(style, stations, state) }
        .onFailure { Log.w(TAG, "applyStations failed", it) }
}

/** Add / refresh / clear the fuel-station overlay. Drives the
 *  Stations chip in the controls row. */
private fun applyStations(
    style: Style,
    stations: List<Pair<Double, Double>>,
    state: HeatmapMapState,
) {
    if (stations === state.lastStationsRef) return
    style.getLayer(STATIONS_LAYER)?.let { style.removeLayer(it) }
    style.getLayer(STATIONS_HALO_LAYER)?.let { style.removeLayer(it) }
    style.getSource(STATIONS_SOURCE)?.let { style.removeSource(it) }
    state.lastStationsRef = stations
    if (stations.isEmpty()) return

    val featuresJson = StringBuilder("{\"type\":\"FeatureCollection\",\"features\":[")
    var first = true
    for ((lat, lon) in stations) {
        if (!first) featuresJson.append(',')
        first = false
        featuresJson.append(
            "{\"type\":\"Feature\",\"properties\":{},\"geometry\":{" +
                "\"type\":\"Point\",\"coordinates\":[" + lon + "," + lat + "]}}",
        )
    }
    featuresJson.append("]}")
    style.addSource(GeoJsonSource(STATIONS_SOURCE, featuresJson.toString()))

    // Soft outer halo first, then a solid inner dot — gives the marker
    // enough presence to read over both light and dark map regions
    // without obscuring the route lines underneath.
    style.addLayer(
        CircleLayer(STATIONS_HALO_LAYER, STATIONS_SOURCE).apply {
            setProperties(
                PropertyFactory.circleRadius(11.0f),
                PropertyFactory.circleColor("#22D3EE"),
                PropertyFactory.circleOpacity(0.22f),
                PropertyFactory.circleStrokeWidth(0.0f),
            )
        }
    )
    style.addLayer(
        CircleLayer(STATIONS_LAYER, STATIONS_SOURCE).apply {
            setProperties(
                PropertyFactory.circleRadius(5.5f),
                PropertyFactory.circleColor("#22D3EE"),
                PropertyFactory.circleStrokeWidth(1.5f),
                PropertyFactory.circleStrokeColor("#0E7490"),
                PropertyFactory.circleOpacity(0.95f),
            )
        }
    )
}

private fun applyMode(style: Style, mode: HeatmapMode) {
    val layer = style.getLayer(LAYER) as? LineLayer ?: return
    val attr = if (mode == HeatmapMode.Density) "densityColor" else "speedColor"
    layer.setProperties(PropertyFactory.lineColor(Expression.get(attr)))
}

private fun rebuildLayer(
    map: MapLibreMap,
    style: Style,
    points: List<List<Double>>,
    mode: HeatmapMode,
    state: HeatmapMapState,
) {
    if (points.size < 2) return

    // Visit count per ~11 m cell — drives the density color tier.
    val counts = HashMap<Long, Int>(points.size)
    for (p in points) {
        val key = cellKey(p[0], p[1])
        counts[key] = (counts[key] ?: 0) + 1
    }

    val featuresJson = StringBuilder("{\"type\":\"FeatureCollection\",\"features\":[")
    var first = true
    for (i in 0 until points.size - 1) {
        val a = points[i]
        val b = points[i + 1]
        // Break at >30 s gaps (new trip / engine off / pause).
        if ((b[3] - a[3]).toLong() > MAX_GAP_S) continue
        val visits = counts[cellKey(a[0], a[1])] ?: 1
        val speedHex = colorHex(speedColor(a[2]))
        val densityHex = colorHex(densityColor(visits))
        if (!first) featuresJson.append(',')
        first = false
        featuresJson.append(
            "{\"type\":\"Feature\",\"properties\":{" +
                "\"speedColor\":\"" + speedHex + "\"," +
                "\"densityColor\":\"" + densityHex + "\"" +
                "},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[" +
                a[1] + "," + a[0] + "],[" + b[1] + "," + b[0] + "]]}}",
        )
    }
    featuresJson.append("]}")

    style.getLayer(LAYER)?.let { style.removeLayer(it) }
    style.getSource(SOURCE)?.let { style.removeSource(it) }
    style.addSource(GeoJsonSource(SOURCE, featuresJson.toString()))

    val colorAttr = if (mode == HeatmapMode.Density) "densityColor" else "speedColor"
    val layer = LineLayer(LAYER, SOURCE).apply {
        setProperties(
            PropertyFactory.lineWidth(3.0f),
            PropertyFactory.lineOpacity(0.78f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineColor(Expression.get(colorAttr)),
        )
    }
    style.addLayer(layer)

    state.lastPointsRef = points

    // Only fit-bounds the first time we render non-empty data. After
    // that we leave the camera alone so mode toggles + refreshes
    // don't yank the user's view back.
    if (!state.fittedBounds) {
        val builder = LatLngBounds.Builder()
        for (p in points) builder.include(LatLng(p[0], p[1]))
        val bounds = try {
            builder.build()
        } catch (_: Throwable) {
            map.cameraPosition = CameraPosition.Builder()
                .target(LatLng(points.first()[0], points.first()[1]))
                .zoom(11.0)
                .build()
            state.fittedBounds = true
            return
        }
        map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 60))
        state.fittedBounds = true
    }
}

/** Encode 4-decimal lat/lon into a single Long for fast HashMap keying.
 *  lat ∈ [-90, 90], lon ∈ [-180, 180] → (lat+90)*10000 fits in 21 bits,
 *  (lon+180)*10000 fits in 22 bits; pack into upper/lower halves. */
private fun cellKey(lat: Double, lon: Double): Long {
    val latI = ((lat + 90.0) * 10000.0).toLong().coerceIn(0, 1_800_000)
    val lonI = ((lon + 180.0) * 10000.0).toLong().coerceIn(0, 3_600_000)
    return (latI shl 24) or lonI
}

private fun colorHex(argb: Int): String {
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return "#%02X%02X%02X".format(r, g, b)
}
