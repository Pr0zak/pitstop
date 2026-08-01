package com.pitstop.ui.history

import com.pitstop.http.CostPerMilePointDto
import com.pitstop.http.FillupDto
import com.pitstop.http.MonthlySpendPointDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fillups arrive newest-first from the API — the helpers must not
 *  assume otherwise. */
private fun fillup(
    id: String,
    mpg: Double?,
    isMissed: Boolean = false,
): FillupDto = FillupDto(
    id = id,
    vehicleId = "v1",
    fillupDate = "2026-07-01T00:00:00Z",
    odo = 1000.0,
    mpg = mpg,
    isMissed = isMissed,
)

class FillupStatsTest {

    @Test
    fun `last mpg comes from the newest non-missed fillup`() {
        val stats = computeFillupStats(
            fillups = listOf(fillup("a", 24.0), fillup("b", 20.0), fillup("c", 22.0)),
            costPerMile = emptyList(),
            monthlySpend = emptyList(),
        )
        assertEquals(24.0, stats.lastMpg!!, 1e-9)
        // avg of 24, 20, 22 = 22 → delta = +2
        assertEquals(2.0, stats.mpgDelta!!, 1e-9)
    }

    /** A missed fillup breaks the odometer chain, so its MPG is
     *  meaningless — the same rule the backend applies. */
    @Test
    fun `missed fillups are excluded from mpg stats and the sparkline`() {
        val stats = computeFillupStats(
            fillups = listOf(
                fillup("missed", 99.0, isMissed = true),
                fillup("a", 24.0),
                fillup("b", 20.0),
            ),
            costPerMile = emptyList(),
            monthlySpend = emptyList(),
        )
        assertEquals(24.0, stats.lastMpg!!, 1e-9)
        assertEquals(listOf(20.0, 24.0), stats.spark)
        assertTrue(stats.spark.none { it == 99.0 })
    }

    @Test
    fun `sparkline is oldest-first and capped at twelve tanks`() {
        // 15 fillups newest-first with descending mpg 30..16
        val many = (0 until 15).map { fillup("f$it", 30.0 - it) }
        val stats = computeFillupStats(many, emptyList(), emptyList())
        assertEquals(12, stats.spark.size)
        // Reversed to chronological: the oldest of the kept 12 first.
        assertEquals(19.0, stats.spark.first(), 1e-9)
        assertEquals(30.0, stats.spark.last(), 1e-9)
    }

    @Test
    fun `null and zero mpg rows are ignored`() {
        val stats = computeFillupStats(
            fillups = listOf(fillup("a", null), fillup("b", 0.0), fillup("c", 21.0)),
            costPerMile = emptyList(),
            monthlySpend = emptyList(),
        )
        assertEquals(21.0, stats.lastMpg!!, 1e-9)
        assertEquals(listOf(21.0), stats.spark)
    }

    /** cost_per_mi is null for months with zero miles — those rows must
     *  not become the "latest" tile value. */
    @Test
    fun `cost per mile skips null months and deltas against the prior real month`() {
        val stats = computeFillupStats(
            fillups = emptyList(),
            costPerMile = listOf(
                CostPerMilePointDto(period = "2026-05", costPerMi = 0.20),
                CostPerMilePointDto(period = "2026-06", costPerMi = 0.25),
                CostPerMilePointDto(period = "2026-07", costPerMi = null),
            ),
            monthlySpend = emptyList(),
        )
        assertEquals(0.25, stats.costPerMi!!, 1e-9)
        assertEquals(0.05, stats.costPerMiDelta!!, 1e-9)
    }

    @Test
    fun `monthly spend takes the latest month with fuel and labels it`() {
        val stats = computeFillupStats(
            fillups = emptyList(),
            costPerMile = emptyList(),
            monthlySpend = listOf(
                MonthlySpendPointDto(month = "2026-06", fuel = 120.0, total = 120.0),
                MonthlySpendPointDto(month = "2026-07", fuel = 200.0, total = 200.0),
            ),
        )
        assertEquals(200.0, stats.monthSpend!!, 1e-9)
        assertEquals(80.0, stats.monthSpendDelta!!, 1e-9)
        assertEquals("Jul", stats.monthLabel)
    }

    @Test
    fun `empty input yields an empty strip that the UI hides`() {
        val stats = computeFillupStats(emptyList(), emptyList(), emptyList())
        assertTrue(stats.isEmpty)
        assertNull(stats.lastMpg)
        assertTrue(stats.spark.isEmpty())
    }

    @Test
    fun `month label degrades gracefully on an unexpected format`() {
        assertEquals("Jul", monthShortLabel("2026-07"))
        assertEquals("whatever", monthShortLabel("whatever"))
        assertEquals("2026-99", monthShortLabel("2026-99"))
    }
}
