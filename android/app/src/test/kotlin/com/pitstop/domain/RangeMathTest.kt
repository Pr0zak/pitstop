package com.pitstop.domain

import com.pitstop.http.FillupDto
import com.pitstop.http.LatestReadingDto
import com.pitstop.http.TripDto
import com.pitstop.http.VehicleDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RangeMathTest {
    private val now = Instant.parse("2026-09-25T12:00:00Z").toEpochMilli()
    private fun daysAgo(d: Long) = Instant.ofEpochMilli(now - d * 86_400_000L).toString()

    private fun trip(id: String, km: Double?, litres: Double?, daysAgo: Long = 1) =
        TripDto(id = id, vehicleId = "v", startedAt = daysAgo(daysAgo), distanceKm = km, fuelUsedL = litres)

    private fun vehicle(
        tank1: Double? = 19.5,
        fuelUnit: Int? = 1,
        tankL: Double? = 73.8,
        estimateL: Double? = null,
        sensorPct: Double? = null,
    ) = VehicleDto(
        id = "v", slug = "demo", name = "Demo",
        tank1Capacity = tank1, fuelUnit = fuelUnit, tankCapacityL = tankL,
        fuelLevelEstimateL = estimateL, fuelLevelEstimateUpdatedAt = if (estimateL != null) daysAgo(0) else null,
        latest = sensorPct?.let { mapOf("fuel_level" to LatestReadingDto(time = daysAgo(0), source = "bridge", valueNum = it)) } ?: emptyMap(),
    )

    @Test fun `trip basis is total distance over total fuel, not a mean of trip mpg`() {
        // 100 km on 8 L and 10 km on 2 L: pooled = 110 km / 10 L.
        val mpg = RangeMath.tripBasisMpg(listOf(trip("a", 100.0, 8.0), trip("b", 10.0, 2.0)), now)!!
        val expected = (110.0 * 0.621371) / (10.0 * 0.264172)
        assertEquals(expected, mpg, 1e-6)
    }

    @Test fun `trip basis ignores old trips, trips without fuel and needs enough miles`() {
        val old = trip("old", 500.0, 40.0, daysAgo = 45)
        val noFuel = trip("nf", 80.0, null)
        val zeroFuel = trip("zf", 80.0, 0.0)
        assertNull(RangeMath.tripBasisMpg(listOf(old, noFuel, zeroFuel, trip("a", 10.0, 1.0)), now))
        // Two short trips under 20 mi total: too little to trust.
        assertNull(RangeMath.tripBasisMpg(listOf(trip("a", 10.0, 1.0), trip("b", 10.0, 1.0)), now))
        assertNotNull(RangeMath.tripBasisMpg(listOf(trip("a", 20.0, 2.0), trip("b", 20.0, 2.0)), now))
    }

    @Test fun `implausible basis is rejected`() {
        // 100 km on 0.1 L would be ~2,300 mpg.
        assertNull(RangeMath.tripBasisMpg(listOf(trip("a", 100.0, 0.05), trip("b", 100.0, 0.05)), now))
    }

    @Test fun `fillup fallback averages the last three full unbroken fills`() {
        fun f(id: String, date: String, mpg: Double?, full: Boolean = true, missed: Boolean = false) =
            FillupDto(id = id, vehicleId = "v", fillupDate = date, odo = 0.0, mpg = mpg, isFull = full, isMissed = missed)
        val fills = listOf(
            f("1", "2026-09-20", 20.0),
            f("2", "2026-09-10", 30.0, missed = true),
            f("3", "2026-09-01", 22.0),
            f("4", "2026-08-20", null, full = false),
            f("5", "2026-08-10", 24.0),
            f("6", "2026-08-01", 99.0), // beyond the three most recent
        )
        assertEquals(22.0, RangeMath.fillupBasisMpg(fills)!!, 1e-9)
    }

    @Test fun `basis prefers trips and falls back to fillups`() {
        val trips = listOf(trip("a", 40.0, 4.0), trip("b", 40.0, 4.0))
        val fills = listOf(FillupDto(id = "1", vehicleId = "v", fillupDate = "2026-09-20", odo = 0.0, mpg = 21.0))
        assertEquals(RangeBasisSource.RecentTrips, RangeMath.basis(trips, fills, now)!!.source)
        assertEquals(RangeBasisSource.RecentFillups, RangeMath.basis(emptyList(), fills, now)!!.source)
        assertNull(RangeMath.basis(null, null, now))
    }

    @Test fun `tank capacity honours the fuel unit`() {
        assertEquals(19.5, RangeMath.tankUsGallons(19.5, 1, 73.8)!!, 1e-9)
        assertEquals(60.0 * 0.264172, RangeMath.tankUsGallons(60.0, 0, null)!!, 1e-9)
        assertEquals(15.0 * 1.20095, RangeMath.tankUsGallons(15.0, 2, null)!!, 1e-9)
        assertEquals(80.0 * 0.264172, RangeMath.tankUsGallons(null, 1, 80.0)!!, 1e-9)
        assertNull(RangeMath.tankUsGallons(null, 1, null))
    }

    @Test fun `estimator litres win over the sensor and give gallons directly`() {
        val s = RangeMath.fuelSnapshot(vehicle(estimateL = 36.9, sensorPct = 90.0))
        assertTrue(s.isEstimate)
        assertEquals(50.0, s.pct!!, 1e-6)
        assertEquals(36.9 * 0.264172, s.usGallons!!, 1e-9)
    }

    @Test fun `sensor path uses percent times tank`() {
        val s = RangeMath.fuelSnapshot(vehicle(sensorPct = 62.0))
        assertFalse(s.isEstimate)
        assertEquals(19.5 * 0.62, s.usGallons!!, 1e-9)
    }

    @Test fun `range is gallons times basis and flags low under 50 miles`() {
        val snap = FuelSnapshot(pct = 62.0, usGallons = 12.2, tankUsGallons = 19.5, readingAtMs = now)
        val est = RangeMath.estimate(snap, RangeBasis(20.4, RangeBasisSource.RecentTrips))
        assertEquals(248.88, est.rangeMi!!, 1e-9)
        assertFalse(est.low)
        val low = RangeMath.estimate(snap.copy(usGallons = 2.0), RangeBasis(20.0, RangeBasisSource.RecentTrips))
        assertTrue(low.low)
    }

    @Test fun `without a basis there is no range, only gallons`() {
        val snap = FuelSnapshot(pct = 62.0, usGallons = 12.2, tankUsGallons = 19.5, readingAtMs = now)
        val est = RangeMath.estimate(snap, null)
        assertNull(est.rangeMi)
        assertEquals(12.2, est.fuel.usGallons!!, 1e-9)
    }

    @Test fun `live level overlays only a newer sensor reading, never the estimator`() {
        val sensor = FuelSnapshot(pct = 60.0, usGallons = 11.7, tankUsGallons = 19.5, readingAtMs = 1_000)
        assertEquals(50.0, RangeMath.withLiveLevel(sensor, 50.0, 2_000).pct!!, 1e-9)
        assertEquals(60.0, RangeMath.withLiveLevel(sensor, 50.0, 500).pct!!, 1e-9)
        val est = sensor.copy(isEstimate = true)
        assertEquals(60.0, RangeMath.withLiveLevel(est, 50.0, 2_000).pct!!, 1e-9)
    }

    @Test fun `range from percent for the car tile`() {
        assertEquals(19.5 * 0.5 * 20.0, RangeMath.rangeFromPct(50.0, 19.5, 20.0)!!, 1e-9)
        assertNull(RangeMath.rangeFromPct(50.0, null, 20.0))
    }
}
