package com.pitstop.design

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.pitstop.http.TripDetailDto
import com.pitstop.http.TripSampleDto
import com.pitstop.ui.history.detail.Loaded
import com.pitstop.ui.history.detail.StoredSeries
import com.pitstop.ui.theme.PitstopTheme
import kotlin.math.PI
import kotlin.math.sin

/**
 * DEBUG-ONLY harness that renders the real trip-detail screen against
 * synthetic data, with no backend, no login and no ViewModel.
 *
 * Why it exists: trip detail is the densest screen in the app and the
 * only way to see it was to have a configured phone, a real trip and a
 * reachable server. That made every layout change unverifiable on an
 * emulator, which is how the Timeline's chip row grew to five wrapping
 * rows without anyone seeing the cost. This renders the SHIPPING
 * composable — not a mock of it — so what you screenshot is what users
 * get, including the picker sheet and the empty-group skipping.
 *
 *   adb shell am start -n com.pitstop/com.pitstop.design.TimelineGalleryActivity
 *
 * Synthetic data only, deliberately: it keeps real GPS, odometer and VIN
 * values out of any screenshot that ends up in a commit or an issue.
 *
 * Not in the release variant and not in the nav graph — the debug source
 * set's AndroidManifest.xml declares the activity.
 */
class TimelineGalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PitstopTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Loaded(
                        trip = syntheticTrip(),
                        route = emptyList(),
                        unitSystem = "imperial",
                        // Simulates a first run: nothing persisted, so the
                        // screen falls back to the defaultVisible metrics.
                        storedSeries = StoredSeries(loaded = true, metrics = null),
                        onPersistSeries = {},
                        onTowingChange = {},
                        onCategoryChange = {},
                        onOpenDtc = { _, _ -> },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/**
 * A drive-shaped trip: speed rises, cruises and falls, with the other
 * series derived from it so the chart reads as a drive rather than
 * noise. Deliberately partial — only 7 of the 18 chartable metrics are
 * present — because that exercises the "skip groups with no data" path
 * in the series picker, which a fully-populated fixture would not.
 */
private fun syntheticTrip(): TripDetailDto {
    val samples = mutableListOf<TripSampleDto>()
    val n = 200
    for (i in 0 until n) {
        val f = i / n.toDouble()
        val t = "2026-01-01T12:%02d:%02dZ".format((i * 5) / 60, (i * 5) % 60)
        fun add(metric: String, v: Double) = samples.add(TripSampleDto(t, metric, v))
        add("vehicle_speed", 100.0 * sin(PI * f) + 5 * sin(i / 4.0))
        add("engine_rpm", 1800.0 + 900 * sin(PI * f))
        add("coolant_temp", 60.0 + 30 * f)
        add("engine_fuel_rate", 0.4 + 0.9 * sin(PI * f))
        add("catalyst_temp_b1", 520.0 + 60 * sin(PI * f))
        add("catalyst_temp_b2", 524.0 + 60 * sin(PI * f))
        // Monotonic, and five orders of magnitude above everything else —
        // the case that proves LineChart's per-series auto-fit works.
        add("odometer", 204_318.0 + f * 11.4)
    }
    return TripDetailDto(
        id = "00000000-0000-0000-0000-000000000000",
        vehicleId = "00000000-0000-0000-0000-000000000001",
        startedAt = "2026-01-01T12:00:00Z",
        endedAt = "2026-01-01T12:16:40Z",
        durationS = 1000,
        distanceKm = 18.3,
        maxRpm = 2700.0,
        maxSpeedKph = 100.0,
        avgSpeedKph = 66.0,
        avgCoolantC = 88.0,
        fuelUsedL = 1.9,
        idleS = 74,
        odoStartKm = 204_318.0,
        odoEndKm = 204_329.4,
        fuelLevelStartPct = 62.0,
        fuelLevelEndPct = 58.0,
        weatherTempC = 21.0,
        weatherCode = 1,
        samples = samples,
    )
}
