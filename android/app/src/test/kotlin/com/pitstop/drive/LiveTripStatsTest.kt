package com.pitstop.drive

import com.pitstop.car.LINK_TILE_KEY
import com.pitstop.car.TRIP_DISTANCE_KEY
import com.pitstop.car.TRIP_ECONOMY_KEY
import com.pitstop.car.TRIP_FUEL_KEY
import com.pitstop.car.withTripTiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTripStatsTest {

    private val t0 = 1_000_000L

    @Test
    fun `speed integration gives distance and skips gaps of a minute or more`() {
        val s = LiveTripStats(t0)
        // 36 km/h for 100 s = 1 km, sampled every second.
        for (i in 0..100) s.onPid(t0 + i * 1000L, "vehicle_speed", 36.0)
        // A 2-minute hole at the same speed must add nothing.
        s.onPid(t0 + 220_000L, "vehicle_speed", 36.0)
        assertEquals(1.0, s.snapshot(t0 + 220_000L).distanceKm, 1e-9)
    }

    @Test
    fun `gps jumps faster than 250 kmh are dropped`() {
        val s = LiveTripStats(t0)
        s.onGps(t0, 40.0, -75.0)
        s.onGps(t0 + 10_000L, 40.001, -75.0)   // ~0.11 km in 10 s: fine
        s.onGps(t0 + 11_000L, 40.5, -75.0)     // ~55 km in 1 s: a fix jump
        val km = s.snapshot(t0 + 11_000L).distanceKm
        assertTrue("got $km", km in 0.10..0.12)
    }

    @Test
    fun `idle counts only time spent under 1 kmh`() {
        val s = LiveTripStats(t0)
        for (i in 0..30) s.onPid(t0 + i * 1000L, "vehicle_speed", 0.0)
        for (i in 31..60) s.onPid(t0 + i * 1000L, "vehicle_speed", 50.0)
        assertEquals(31L, s.snapshot(t0 + 60_000L).idleS)
    }

    @Test
    fun `fuel rate is preferred and converted by density only`() {
        val s = LiveTripStats(t0)
        // 1.5 g/s for 500 s = 750 g of fuel = 1.0001 L at 749.9 g/L.
        for (i in 0..500) s.onPid(t0 + i * 1000L, "engine_fuel_rate", 1.5)
        for (i in 0..500) s.onPid(t0 + i * 1000L, "maf_sensor_a", 99.0)
        assertEquals(750.0 / 749.9, s.snapshot(t0 + 500_000L).fuelL!!, 1e-6)
    }

    @Test
    fun `a source that covered under half the drive is not believed`() {
        val s = LiveTripStats(t0)
        for (i in 0..100) s.onPid(t0 + i * 1000L, "engine_fuel_rate", 5.0)
        for (i in 0..400) s.onPid(t0 + i * 1000L, "vehicle_speed", 40.0)
        assertNull(s.snapshot(t0 + 400_000L).fuelL)
    }

    @Test
    fun `trip tiles stay empty when no drive is open`() {
        val out = withTripTiles(emptyMap(), null, t0)
        assertFalse(TRIP_DISTANCE_KEY in out)
        assertFalse(LINK_TILE_KEY in out)
    }

    @Test
    fun `trip economy waits for a credible distance and fuel figure`() {
        val short = withTripTiles(emptyMap(), LiveTripStats.Snapshot(60, 0.4, 0.05, 0), t0)
        assertFalse(TRIP_ECONOMY_KEY in short)
        assertTrue(TRIP_FUEL_KEY in short)
        // 32.187 km = 20 mi on 1 US gal (3.785 L) = 20 mpg.
        val real = withTripTiles(emptyMap(), LiveTripStats.Snapshot(1800, 32.18688, 3.785411784, 0), t0)
        assertEquals(20.0, real.getValue(TRIP_ECONOMY_KEY).value, 0.01)
    }
}
