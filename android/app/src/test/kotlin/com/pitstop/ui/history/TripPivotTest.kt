package com.pitstop.ui.history

import com.pitstop.http.TripSampleDto
import com.pitstop.ui.history.detail.ACCELERATION_METRIC
import com.pitstop.ui.history.detail.TimedPoint
import com.pitstop.ui.history.detail.accelerationG
import com.pitstop.ui.history.detail.downsampleLast
import com.pitstop.ui.history.detail.pivotSamples
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Synthetic samples only — no real trip data. */
class TripPivotTest {

    private fun ts(sec: Int) = "2026-01-01T00:%02d:%02dZ".format(sec / 60, sec % 60)

    private fun samples(metric: String, vararg v: Double) =
        v.mapIndexed { i, x -> TripSampleDto(ts(i), metric, x) }

    @Test
    fun `discrete metrics are never median-smoothed`() {
        val gear = samples("gear_position", 2.0, 3.0, 4.0, 5.0)
        val smoothed = pivotSamples(gear, smoothWindow = 3)
        val raw = pivotSamples(gear, smoothWindow = 3, discreteMetrics = setOf("gear_position"))
        // A 2-point edge window averages to 2.5 — a gear that never existed.
        assertEquals(2.5, smoothed.getValue("gear_position").points.first().value, 0.0)
        assertEquals(listOf(2.0, 3.0, 4.0, 5.0), raw.getValue("gear_position").points.map { it.value })
    }

    @Test
    fun `last-value downsample keeps real states`() {
        val pts = listOf(2.0, 3.0, 3.0, 15.0, 0.0, 2.0).mapIndexed { i, v -> TimedPoint(i * 1000L, v) }
        val out = downsampleLast(pts, 3)
        assertEquals(listOf(3.0, 15.0, 2.0), out.map { it.value })
        assertEquals(listOf(1000L, 3000L, 5000L), out.map { it.tMillis })
    }

    @Test
    fun `acceleration is dv over dt in g`() {
        // 0 → 36 km/h in 1 s = 10 m/s² ≈ 1.02 g; then held.
        val speed = listOf(TimedPoint(0, 0.0), TimedPoint(1000, 36.0), TimedPoint(2000, 36.0))
        val a = accelerationG(speed)
        assertEquals(2, a.size)
        assertEquals(10.0 / 9.80665, a[0].value, 1e-9)
        assertEquals(1000L, a[0].tMillis)
        assertEquals(0.0, a[1].value, 0.0)
    }

    @Test
    fun `acceleration is derived only on request and only with speed`() {
        val speed = samples("vehicle_speed", 0.0, 18.0, 36.0)
        assertNull(pivotSamples(speed)[ACCELERATION_METRIC])
        assertEquals(2, pivotSamples(speed, deriveAcceleration = true).getValue(ACCELERATION_METRIC).points.size)
        assertNull(pivotSamples(samples("engine_rpm", 800.0, 900.0), deriveAcceleration = true)[ACCELERATION_METRIC])
    }
}
