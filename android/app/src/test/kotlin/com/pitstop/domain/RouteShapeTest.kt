package com.pitstop.domain

import com.pitstop.http.EiaWeekDto
import com.pitstop.http.FillupDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

class RouteShapeTest {
    @Test fun `shape fits the unit box with north up`() {
        val s = RouteShape.of(listOf(40.0 to -83.0, 40.01 to -83.0, 40.01 to -82.99))!!
        assertEquals(1f, maxOf(s.w, s.h), 1e-6f)
        // Start is the southernmost point → bottom of the box.
        assertEquals(s.h, s.ys[0], 1e-6f)
        assertEquals(0f, s.ys[1], 1e-6f)
        assertNull(s.mph)
    }

    @Test fun `downsampling keeps the last point and maps speeds`() {
        val pts = (0 until 1000).map { 40.0 + it * 1e-4 to -83.0 }
        val speeds = (0 until 1000).map { it.toDouble() / 100 }
        val s = RouteShape.of(pts, speeds, max = 50)!!
        assertEquals(51, s.size)
        assertEquals(0f, s.ys.last(), 1e-6f)
        assertEquals(9.99 * 2.236936, s.mph!!.last().toDouble(), 1e-3)
    }

    @Test fun `one point is no shape`() {
        assertNull(RouteShape.of(listOf(40.0 to -83.0)))
    }

    @Test fun `speed bands use the shared cutoffs`() {
        assertEquals(SpeedBand.Stopped, SpeedBand.of(1.9))
        assertEquals(SpeedBand.City, SpeedBand.of(2.0))
        assertEquals(SpeedBand.Suburban, SpeedBand.of(22.0))
        assertEquals(SpeedBand.Highway, SpeedBand.of(55.0))
    }

    @Test fun `battery status thresholds and guidance`() {
        assertEquals(BatteryHealth.Status.Good, BatteryHealth.status(12.4))
        assertEquals(BatteryHealth.Status.Fair, BatteryHealth.status(12.0))
        assertEquals(BatteryHealth.Status.Low, BatteryHealth.status(11.99))
        assertTrue(BatteryHealth.guidance(listOf(12.1, 12.2, 12.5)).startsWith("Mostly in the fair band."))
    }

    @Test fun `week lookup uses the week on or before, first week within 3 days`() {
        val weeks = FuelCharts.marketWeeks(listOf(EiaWeekDto("2026-09-28", 4.465), EiaWeekDto("2026-09-21", 4.478)))
        assertEquals(4.478, FuelCharts.weekFor(weeks, LocalDate.of(2026, 9, 27))!!.price, 1e-9)
        assertEquals(4.465, FuelCharts.weekFor(weeks, LocalDate.of(2026, 9, 28))!!.price, 1e-9)
        assertEquals(4.478, FuelCharts.weekFor(weeks, LocalDate.of(2026, 9, 18))!!.price, 1e-9)
        assertNull(FuelCharts.weekFor(weeks, LocalDate.of(2026, 9, 17)))
        assertEquals(4.465, FuelCharts.weekFor(weeks, LocalDate.of(2026, 10, 20))!!.price, 1e-9)
    }

    @Test fun `thirty day mpg is total distance over total fuel`() {
        val now = java.time.Instant.parse("2026-10-01T12:00:00Z").toEpochMilli()
        fun t(id: String, start: String, km: Double?, l: Double?) =
            com.pitstop.http.TripDto(id = id, vehicleId = "v", startedAt = start, distanceKm = km, fuelUsedL = l)
        val mpg = FuelCharts.thirtyDayMpg(
            listOf(
                t("a", "2026-09-30T12:00:00Z", 40.0, 4.0),
                t("b", "2026-09-10T12:00:00Z", 60.0, 4.0),
                t("c", "2026-08-01T12:00:00Z", 1000.0, 10.0), // outside 30 days
                t("d", "2026-09-29T12:00:00Z", 5.0, null), // no fuel
            ),
            now,
        )!!
        assertEquals(com.pitstop.util.UnitFormat.mpgFrom(100.0, 8.0)!!, mpg, 1e-9)
        assertNull(FuelCharts.thirtyDayMpg(emptyList(), now))
    }

    @Test fun `fillups group by local month newest first`() {
        fun f(id: String, d: String, gal: Double, total: Double) =
            FillupDto(id = id, vehicleId = "v", fillupDate = d, odo = 0.0, fuelVolume = gal, priceTotal = total)
        val g = FuelCharts.fillupMonths(
            listOf(
                f("a", "2026-08-31T12:00:00Z", 10.0, 40.0),
                f("b", "2026-09-29T12:00:00Z", 17.0, 74.0),
                f("c", "2026-09-07T12:00:00Z", 15.0, 56.0),
            ),
            ZoneOffset.UTC,
        )
        assertEquals(listOf(YearMonth.of(2026, 9), YearMonth.of(2026, 8)), g.map { it.month })
        assertEquals(listOf("b", "c"), g[0].fillups.map { it.id })
        assertEquals(32.0, g[0].gallons, 1e-9)
        assertEquals(130.0, g[0].total, 1e-9)
    }
}
