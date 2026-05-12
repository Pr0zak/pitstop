package com.pitstop.ui.history.detail

import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.pitstop.http.RoutePointDto
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style

private const val TAG = "PitstopMap"

/**
 * Compose wrapper for MapLibre's native MapView, rendering a trip's
 * GPS polyline. The line is drawn as multiple short LineString
 * features, each colored by the speed at the segment's start —
 * mirrors `routeSegments` in the web TripDetailView.
 *
 * Style URL: Carto Voyager (matches the web's default basemap).
 *
 * Two non-obvious things matter here:
 *
 *   1. MapLibre.getInstance(context) must complete BEFORE MapView is
 *      constructed — it pulls up the renderer's storage paths and
 *      worker pool. Originally this was in a LaunchedEffect, which
 *      runs *after* the synchronous `remember { MapView(context) }`
 *      and crashed the trip-detail screen.
 *   2. The MapView's id must not be set to android.R.id.content;
 *      that's the host Activity's content frame and collides at
 *      findViewById time. Letting MapView keep its default
 *      auto-generated id is fine.
 *
 * If MapLibre fails to init or the MapView throws during construction
 * we fall back to a plain "Map unavailable" placeholder so the rest
 * of the trip-detail screen still renders.
 */
@Composable
fun MapLibreRouteView(
    points: List<RoutePointDto>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Synchronous static init — must run before MapView is built.
    // Safe to call multiple times; the SDK no-ops after the first.
    val initOk = remember(context.applicationContext) {
        runCatching { MapLibre.getInstance(context.applicationContext) }
            .onFailure { Log.w(TAG, "MapLibre.getInstance failed", it) }
            .isSuccess
    }

    if (!initOk) {
        MapUnavailableFallback(modifier)
        return
    }

    // remember() persists the MapView across recomposition. Build it
    // inside runCatching so a constructor-time failure doesn't kill
    // the entire detail screen — we just render the fallback.
    val mapView = remember {
        runCatching { MapView(context) }
            .onFailure { Log.w(TAG, "MapView construction failed", it) }
            .getOrNull()
    }

    if (mapView == null) {
        MapUnavailableFallback(modifier)
        return
    }

    // Mirror the host composable's lifecycle into the MapView so it
    // pauses tile downloads on tab switch / background.
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
                        runCatching { setupMap(map, points) }
                            .onFailure { Log.w(TAG, "setupMap failed", it) }
                    }
                }.onFailure { Log.w(TAG, "getMapAsync failed", it) }
            },
        )
    }
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

private const val SOURCE_ROUTE = "pitstop-route"
private const val LAYER_ROUTE = "pitstop-route-layer"
// Carto's Voyager style — matches the web frontend's default basemap.
private const val STYLE_URL =
    "https://basemaps.cartocdn.com/gl/voyager-gl-style/style.json"

private fun setupMap(
    map: MapLibreMap,
    points: List<RoutePointDto>,
) {
    map.uiSettings.isLogoEnabled = false
    map.uiSettings.isAttributionEnabled = true
    map.uiSettings.isCompassEnabled = false

    map.setStyle(STYLE_URL) { style ->
        runCatching { renderRoute(map, style, points) }
            .onFailure { Log.w(TAG, "renderRoute failed", it) }
    }
}

private fun renderRoute(
    map: MapLibreMap,
    style: Style,
    points: List<RoutePointDto>,
) {
    if (points.size < 2) return

    // Build a FeatureCollection of 2-point LineStrings, one per
    // segment, with a `color` property set from the speed at the
    // segment's start.
    val featuresJson = StringBuilder("{\"type\":\"FeatureCollection\",\"features\":[")
    for (i in 0 until points.size - 1) {
        val a = points[i]
        val b = points[i + 1]
        val color = colorHex(speedColor(a.speedMps))
        if (i > 0) featuresJson.append(',')
        featuresJson.append(
            "{\"type\":\"Feature\",\"properties\":{\"color\":\"$color\"},"
                + "\"geometry\":{\"type\":\"LineString\",\"coordinates\":[["
                + a.lon + "," + a.lat + "],[" + b.lon + "," + b.lat + "]]}}",
        )
    }
    featuresJson.append("]}")

    val source = org.maplibre.android.style.sources.GeoJsonSource(
        SOURCE_ROUTE,
        featuresJson.toString(),
    )
    style.getLayer(LAYER_ROUTE)?.let { style.removeLayer(it) }
    style.getSource(SOURCE_ROUTE)?.let { style.removeSource(it) }
    style.addSource(source)

    val layer = org.maplibre.android.style.layers.LineLayer(LAYER_ROUTE, SOURCE_ROUTE).apply {
        setProperties(
            org.maplibre.android.style.layers.PropertyFactory.lineWidth(4.0f),
            org.maplibre.android.style.layers.PropertyFactory.lineCap(
                org.maplibre.android.style.layers.Property.LINE_CAP_ROUND,
            ),
            org.maplibre.android.style.layers.PropertyFactory.lineJoin(
                org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND,
            ),
            org.maplibre.android.style.layers.PropertyFactory.lineColor(
                org.maplibre.android.style.expressions.Expression.get("color"),
            ),
        )
    }
    style.addLayer(layer)

    val builder = LatLngBounds.Builder()
    for (p in points) builder.include(LatLng(p.lat, p.lon))
    val bounds = try {
        builder.build()
    } catch (t: Throwable) {
        // build() throws when all points coincide; fall back to a
        // simple centre + zoom.
        map.cameraPosition = org.maplibre.android.camera.CameraPosition.Builder()
            .target(LatLng(points.first().lat, points.first().lon))
            .zoom(14.0)
            .build()
        return
    }
    map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 60))
}

private fun colorHex(argb: Int): String {
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return "#%02X%02X%02X".format(r, g, b)
}

@Suppress("unused")
private fun unusedKeepImport(c: Color): Color = c
