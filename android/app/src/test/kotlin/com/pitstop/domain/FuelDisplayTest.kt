package com.pitstop.domain

import com.pitstop.http.LatestReadingDto
import com.pitstop.http.VehicleDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The server's fuel_display_* pick, rendered as given (never re-derived). */
class FuelDisplayTest {
    private val now = 1_800_000_000_000L
    private fun iso(msAgo: Long) = java.time.Instant.ofEpochMilli(now - msAgo).toString()

    private fun v(
        displayL: Double? = null, source: String? = null, displayAgoMs: Long = 120_000L,
        estimateL: Double? = 30.0, sensorPct: Double? = 80.0,
    ) = VehicleDto(
        id = "v", slug = "demo", name = "Demo", tank1Capacity = 19.5, fuelUnit = 1, tankCapacityL = 73.8,
        fuelLevelEstimateL = estimateL, fuelLevelEstimateUpdatedAt = iso(3 * 3_600_000L),
        fuelDisplayL = displayL, fuelDisplaySource = source, fuelDisplayAt = iso(displayAgoMs),
        latest = sensorPct?.let { mapOf("fuel_level" to LatestReadingDto(time = iso(60_000L), source = "bridge", valueNum = it)) }
            ?: emptyMap(),
    )

    @Test
    fun `cluster pick drives percent, gallons and the caption`() {
        val s = RangeMath.fuelSnapshot(v(displayL = 46.71, source = "cluster"))
        assertEquals(FuelSource.Cluster, s.source)
        assertEquals(46.71 / 73.8 * 100, s.pct!!, 1e-9)
        assertEquals(12.34, s.usGallons!!, 0.01)
        assertEquals("from cluster · 2m ago", s.caption(now))
        assertTrue(s.isEstimate) // stable: no live slosh overlay
    }

    @Test
    fun `estimate pick says estimated`() {
        val s = RangeMath.fuelSnapshot(v(displayL = 30.0, source = "estimate", displayAgoMs = 3 * 3_600_000L))
        assertEquals(FuelSource.Estimate, s.source)
        assertEquals("estimated · 3h ago", s.caption(now))
    }

    @Test
    fun `older server without fuel_display falls back to the estimator`() {
        val s = RangeMath.fuelSnapshot(v())
        assertEquals(FuelSource.Estimate, s.source)
        assertEquals(30.0 / 73.8 * 100, s.pct!!, 1e-9)
    }

    @Test
    fun `nothing server-picked and no estimate uses the raw sensor`() {
        val s = RangeMath.fuelSnapshot(v(estimateL = null))
        assertEquals(FuelSource.Sensor, s.source)
        assertEquals(80.0, s.pct!!, 1e-9)
        assertEquals("live", s.caption(now)) // 60 s: under the 90 s "live" window
    }

    @Test
    fun `strict decode - new vehicle fields are optional and nullable`() {
        val json = Json { ignoreUnknownKeys = true }
        val full = json.decodeFromString(
            VehicleDto.serializer(),
            """{"id":"v","slug":"p","name":"P","fuel_cluster_l":46.71,"fuel_cluster_at":"2026-10-22T12:00:00Z",
               "fuel_display_l":46.71,"fuel_display_source":"cluster","fuel_display_at":"2026-10-22T12:00:00Z",
               "tire_placard_front_kpa":241.3,"tire_placard_rear_kpa":241.3}""",
        )
        assertEquals(46.71, full.fuelDisplayL!!, 0.0)
        assertEquals("cluster", full.fuelDisplaySource)
        assertEquals(241.3, full.tirePlacardRearKpa!!, 0.0)
        val nulls = json.decodeFromString(
            VehicleDto.serializer(),
            """{"id":"v","slug":"p","name":"P","fuel_cluster_l":null,"fuel_display_l":null,
               "fuel_display_source":null,"fuel_display_at":null,"tire_placard_front_kpa":null}""",
        )
        assertNull(nulls.fuelDisplaySource)
        assertNull(nulls.tirePlacardFrontKpa)
        val omitted = json.decodeFromString(VehicleDto.serializer(), """{"id":"v","slug":"p","name":"P"}""")
        assertNull(omitted.fuelDisplayL)
        assertNull(omitted.fuelClusterAt)
    }
}
