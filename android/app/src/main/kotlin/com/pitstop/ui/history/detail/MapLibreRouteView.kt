package com.pitstop.ui.history.detail

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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

/**
 * Compose wrapper for MapLibre's native MapView, rendering a trip's
 * GPS polyline. The line is drawn as multiple short LineString
 * features, each colored by the speed at the segment's start —
 * mirrors `routeSegments` in the web TripDetailView.
 *
 * Style URL: Carto Voyager (matches the web's default basemap). The
 * style is fetched on first display, then cached by the MapLibre
 * runtime.
 *
 * MapLibre's `getMapAsync` callback must outlive recompositions; we
 * key the AndroidView so the same MapView instance is reused and only
 * the polyline GeoJSON is swapped on point change.
 */
@Composable
fun MapLibreRouteView(
    points: List<RoutePointDto>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // MapLibre's static init pulls the renderer + storage paths up.
    // Safe to call multiple times — the SDK no-ops after the first.
    LaunchedEffect(Unit) {
        MapLibre.getInstance(context)
    }

    val mapView = remember {
        // We construct the MapView before the lifecycle is attached so
        // we can drive it manually below — the AndroidView factory hook
        // doesn't dispatch onCreate/onStart/onResume for us.
        MapView(context).apply {
            id = android.R.id.content // unique enough for our single
            // map instance — avoids the "duplicate id" issue when the
            // same composable is re-entered after backstack pop.
        }
    }

    // Mirror the host composable's lifecycle into the MapView so it
    // pauses tile downloads when the user switches tabs / backgrounds.
    DisposableEffect(lifecycleOwner, mapView) {
        mapView.onCreate(null)
        mapView.onStart()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { mapView },
            update = { view ->
                view.getMapAsync { map ->
                    setupMap(map, points, context)
                }
            },
        )
    }
}

private const val SOURCE_ROUTE = "pitstop-route"
private const val LAYER_ROUTE = "pitstop-route-layer"
// Carto's Voyager style — matches the web frontend's default base
// map. Same vector tiles + glyphs MapLibre's GL renderer expects.
private const val STYLE_URL =
    "https://basemaps.cartocdn.com/gl/voyager-gl-style/style.json"

private fun setupMap(
    map: MapLibreMap,
    points: List<RoutePointDto>,
    context: Context,
) {
    map.setStyle(STYLE_URL) { style ->
        renderRoute(map, style, points)
    }
    // Don't let map drags eat parent scroll on a phone — the user
    // expects vertical scroll to keep working when they swipe down
    // over the map area. MapLibre's default gesture set captures
    // verticals; turn off scroll, keep pinch + tap to engage if the
    // user explicitly wants to explore the route.
    map.uiSettings.isLogoEnabled = false
    map.uiSettings.isAttributionEnabled = true
    map.uiSettings.isCompassEnabled = false
}

private fun renderRoute(
    map: MapLibreMap,
    style: Style,
    points: List<RoutePointDto>,
) {
    if (points.size < 2) return

    // Build a FeatureCollection of 2-point LineStrings, one per
    // segment, with a `color` property set from the speed at the
    // segment's start. We pre-render the color string so the style
    // can use "case" on the `color` literal — simpler than the web's
    // continuous expression, which doesn't translate 1:1 to the
    // MapLibre Android style spec without hex arithmetic.
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
    // Remove any prior source/layer when reusing the same MapView for
    // a different trip. style.getSource() returns null if absent.
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

    // Frame the camera around the polyline bounding box.
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
