package com.pitstop.screenshots

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.pitstop.http.TripSampleDto
import com.pitstop.ui.history.detail.SeriesPickerContent
import com.pitstop.ui.history.detail.StoredSeries
import com.pitstop.ui.history.detail.TRIP_METRICS
import com.pitstop.ui.history.detail.TripDetailContent
import com.pitstop.ui.theme.LocalUnitSystem
import com.pitstop.ui.theme.PitstopTheme
import java.time.Instant
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Rule
import org.junit.Test

/**
 * Trip-chart series picker + the web-parity series (gear, cylinders, trims,
 * dash fuel, outside temp, ATF, misfires, accel). Its own class so it doesn't
 * collide with edits to [ScreenShots]. Synthetic data only.
 */
class TripChartShots {
    @get:Rule val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6.copy(screenHeight = 3000, softButtons = false),
        useDeviceResolution = true,
    )

    private fun shot(name: String, units: String = "imperial", content: @Composable () -> Unit) =
        paparazzi.snapshot(name) {
            PitstopTheme(useDarkTheme = true) {
                CompositionLocalProvider(
                    LocalUnitSystem provides units,
                    LocalInspectionMode provides true,
                ) {
                    Surface(color = MaterialTheme.colorScheme.background) { content() }
                }
            }
        }

    /** Every series the chart can draw, as the picker sheet lists them. */
    @Test fun tripSeriesPicker() = shot("trip_series_picker") {
        SeriesPickerContent(
            available = TRIP_METRICS,
            visible = setOf("vehicle_speed", "engine_rpm", "gear_position", "stft_b1", "stft_b2"),
            unitSystem = "imperial",
            onToggle = {},
            onReset = {},
        )
    }

    @Test fun tripSeriesPickerMetric() = shot("trip_series_picker_metric", units = "metric") {
        SeriesPickerContent(
            available = TRIP_METRICS,
            visible = setOf("vehicle_speed", "fuel_level_l", "ambient_air_temp_f"),
            unitSystem = "metric",
            onToggle = {},
            onReset = {},
        )
    }

    /** Gear (stepped, R/P/N/D ticks) alone so its axis labels draw. */
    @Test fun tripChartGear() = shot("trip_chart_gear") { detail(setOf("gear_position")) }

    /** Speed + cylinders + both STFT banks on a shared range. */
    @Test fun tripChartParity() = shot("trip_chart_parity") {
        detail(setOf("vehicle_speed", "active_cylinders", "stft_b1", "stft_b2"))
    }

    @Composable
    private fun detail(series: Set<String>) = TripDetailContent(
        trip = Fixtures.tripDetail.copy(samples = Fixtures.tripDetail.samples + paritySamples),
        route = emptyList(), baseline = null, unitSystem = "imperial",
        storedSeries = StoredSeries(loaded = true, metrics = series),
        onPersistSeries = {}, onOpenDtc = { _, _ -> }, onOpenMap = {}, onEdit = {},
    )

    private val paritySamples: List<TripSampleDto> = run {
        val out = mutableListOf<TripSampleDto>()
        val start = Instant.parse("2026-09-23T17:42:00Z")
        val n = 200
        for (i in 0 until n) {
            val f = i / n.toDouble()
            val t = start.plusSeconds(i * 9L).toString()
            fun add(metric: String, v: Double) = out.add(TripSampleDto(t, metric, v))
            val gear = when {
                i < 3 -> 15.0
                i < 6 -> 0.0
                else -> (2 + (8 * sin(PI * f)).toInt()).coerceIn(2, 9).toDouble()
            }
            add("gear_position", gear)
            add("active_cylinders", if (f in 0.35..0.6) 3.0 else 6.0)
            add("stft_b1", 2.0 * sin(i / 5.0))
            add("stft_b2", 4.0 + 2.0 * sin(i / 6.0))
            if (i % 10 == 0) {
                add("ltft_b1", 0.8)
                add("ltft_b2", 2.3)
                add("atf_temp", 40.0 + 30 * f)
                add("fuel_level_l", 39.4 - 2.5 * f)
                add("ambient_air_temp_f", 54.0 + 2 * f)
                add("commanded_evap_purge", if (f > 0.3) 40.0 else 0.0)
                for (c in 1..6) add("misfire_count_$c", if (c == 4 && f > 0.7) 1.0 else 0.0)
            }
        }
        out
    }
}
