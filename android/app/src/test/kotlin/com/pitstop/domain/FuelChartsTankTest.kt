package com.pitstop.domain

import com.pitstop.http.FillupDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneOffset

class FuelChartsTankTest {
    private fun f(id: String, day: Int, mpg: Double?, full: Boolean = true, missed: Boolean = false) = FillupDto(
        id = id, vehicleId = "v", fillupDate = "2026-09-%02dT12:00:00Z".format(day), odo = 0.0,
        mpg = mpg, isFull = full, isMissed = missed,
    )

    @Test fun `partial and missed fills are excluded, rolling mean over five`() {
        val s = FuelCharts.tankSeries(
            listOf(
                f("a", 1, 10.0), f("b", 2, 20.0), f("p", 3, 99.0, full = false), f("m", 4, 99.0, missed = true),
                f("c", 5, 30.0), f("d", 6, 40.0), f("e", 7, 50.0), f("g", 8, 60.0), f("n", 9, null),
            ),
            zone = ZoneOffset.UTC,
        )!!
        assertEquals(listOf("a", "b", "c", "d", "e", "g"), s.tanks.map { it.fillupId })
        assertEquals(listOf(10.0, 15.0, 20.0, 25.0, 30.0, 40.0), s.tanks.map { it.roll5 })
        assertEquals(60.0, s.last.mpg, 1e-9)
        assertEquals(35.0, s.avg, 1e-9)
    }

    @Test fun `keeps only the last n tanks`() {
        val s = FuelCharts.tankSeries((1..25).map { f("t$it", it, it.toDouble()) }, n = 20, zone = ZoneOffset.UTC)!!
        assertEquals(20, s.tanks.size)
        assertEquals("t6", s.tanks.first().fillupId)
    }

    @Test fun `fewer than two tanks is null`() {
        assertNull(FuelCharts.tankSeries(listOf(f("a", 1, 20.0)), zone = ZoneOffset.UTC))
    }
}
