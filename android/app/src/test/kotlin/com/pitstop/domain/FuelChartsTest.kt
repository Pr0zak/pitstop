package com.pitstop.domain

import com.pitstop.domain.FuelCharts.MpgSpan
import com.pitstop.http.EiaWeekDto
import com.pitstop.http.FillupDto
import com.pitstop.http.MpgPointDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class FuelChartsTest {
    private val chicago = ZoneId.of("America/Chicago")

    private fun fill(id: String, date: String, ppg: Double? = null, gal: Double? = null, total: Double? = null) =
        FillupDto(
            id = id, vehicleId = "v", fillupDate = date, odo = 0.0,
            fuelVolume = gal, pricePerUnit = ppg, priceTotal = total,
        )

    // ── shared ──
    @Test fun `median of odd and even lists`() {
        assertEquals(2.0, FuelCharts.median(listOf(3.0, 1.0, 2.0)), 1e-9)
        assertEquals(2.5, FuelCharts.median(listOf(4.0, 1.0)), 1e-9)
    }

    @Test fun `nice ticks step by 1 2 or 5, never 2_5`() {
        assertEquals(listOf(16.0, 18.0, 20.0, 22.0), FuelCharts.niceTicks(15.3, 22.4))
        // span 10 / 4 = 2.5 raw — must pick 5, not 2.5.
        assertEquals(listOf(10.0, 15.0, 20.0), FuelCharts.niceTicks(10.0, 20.0))
        assertEquals(listOf(4.0, 4.5), FuelCharts.niceTicks(3.55, 4.5))
        assertEquals(listOf(3.6, 3.8, 4.0, 4.2, 4.4), FuelCharts.niceTicks(3.55, 4.5, n = 5))
        assertEquals(1, FuelCharts.tickDecimals(FuelCharts.niceStep(3.55, 4.5)))
        assertEquals(0, FuelCharts.tickDecimals(FuelCharts.niceStep(15.3, 22.4)))
    }

    @Test fun `month ticks label january with the year`() {
        val x0 = FuelCharts.dayX(LocalDate.of(2025, 10, 20))
        val x1 = FuelCharts.dayX(LocalDate.of(2026, 5, 2))
        val t = FuelCharts.monthTicks(x0, x1, 2).map { it.second }
        assertEquals(listOf("Nov", "Jan ’26", "Mar", "May"), t)
    }

    // ── A ──
    private fun months(vararg v: Pair<String, Double?>) = v.map { (p, m) -> MpgPointDto(period = p, mpg = m, fillupCount = 2) }

    @Test fun `rolling median uses this month and up to two before it`() {
        val r = FuelCharts.mpgOverTime(
            months("2026-01" to 18.0, "2026-02" to 30.0, "2026-03" to 19.0, "2026-04" to 20.0),
            MpgSpan.All,
        )!!
        assertEquals(listOf(18.0, 24.0, 19.0, 20.0), r.months.map { it.median3 })
    }

    @Test fun `null months are skipped and average is fill weighted`() {
        val pts = listOf(
            MpgPointDto("2026-01", 20.0, fillupCount = 1),
            MpgPointDto("2026-02", null, fillupCount = 4),
            MpgPointDto("2026-03", 16.0, fillupCount = 3),
        )
        val r = FuelCharts.mpgOverTime(pts, MpgSpan.All)!!
        assertEquals(2, r.months.size)
        assertEquals((20.0 + 16.0 * 3) / 4, r.avgMpg, 1e-9)
        assertEquals("all 2 months", r.spanPhrase)
    }

    @Test fun `span keeps exactly N calendar months`() {
        val pts = (0 until 48).map { i ->
            val ym = java.time.YearMonth.of(2022, 10).plusMonths(i.toLong())
            MpgPointDto(ym.toString(), 18.0, fillupCount = 1)
        } // 2022-10 .. 2026-09
        assertEquals(12, FuelCharts.mpgOverTime(pts, MpgSpan.Year)!!.months.size)
        assertEquals(36, FuelCharts.mpgOverTime(pts, MpgSpan.ThreeYears)!!.months.size)
        assertEquals(48, FuelCharts.mpgOverTime(pts, MpgSpan.All)!!.months.size)
    }

    @Test fun `no usable months is null`() {
        assertNull(FuelCharts.mpgOverTime(months("2026-01" to null), MpgSpan.All))
    }

    // ── B ──
    private val eia = listOf(
        EiaWeekDto("2026-09-28", 4.465),
        EiaWeekDto("2026-09-21", 4.478),
        EiaWeekDto("2026-09-14", 4.319),
    )

    @Test fun `fillup matches the week on or before its local date`() {
        val r = FuelCharts.marketCompare(
            listOf(
                // Sep 29 local → week of Sep 28.
                fill("a", "2026-09-29T11:31:16.932123Z", ppg = 4.299, gal = 17.246),
                // 02:00Z Sep 21 is Sep 20 evening in Chicago → week of Sep 14.
                fill("b", "2026-09-21T02:00:00Z", ppg = 4.0, gal = 10.0),
                // Before the window (more than 3 days ahead of the first week).
                fill("c", "2026-09-01T12:00:00Z", ppg = 3.0, gal = 10.0),
                fill("d", "2026-09-22T12:00:00Z", ppg = null, gal = 10.0),
            ),
            eia,
            chicago,
        )!!
        assertEquals(listOf("b", "a"), r.fills.map { it.fillupId })
        assertEquals(4.319, r.fills[0].marketPrice, 1e-9)
        assertEquals(4.465, r.fills[1].marketPrice, 1e-9)
        val mean = ((4.0 - 4.319) + (4.299 - 4.465)) / 2
        assertEquals(mean, r.meanDiff, 1e-9)
        assertTrue(r.below)
        assertEquals(kotlin.math.abs(mean) * 27.246, r.dollars, 1e-9)
    }

    @Test fun `a fillup just before the first week uses the first week`() {
        val r = FuelCharts.marketCompare(listOf(fill("a", "2026-09-12T18:00:00Z", ppg = 4.0)), eia, chicago)!!
        assertEquals(4.319, r.fills.single().marketPrice, 1e-9)
        assertEquals(0.0, r.gallons, 1e-9)
    }

    @Test fun `no eia data or no fills hides the card`() {
        assertNull(FuelCharts.marketCompare(listOf(fill("a", "2026-09-29T12:00:00Z", ppg = 4.0)), emptyList(), chicago))
        assertNull(FuelCharts.marketCompare(emptyList(), eia, chicago))
    }

    // ── D ──
    @Test fun `cumulative spend compares to last year on the same day`() {
        val r = FuelCharts.spendYoy(
            listOf(
                fill("1", "2026-03-01T15:00:00Z", total = 50.0),
                fill("2", "2026-01-10T15:00:00Z", total = 40.0),
                fill("3", "2025-01-05T15:00:00Z", total = 30.0),
                fill("4", "2025-02-27T15:00:00Z", total = 35.0),
                fill("5", "2025-03-02T15:00:00Z", total = 60.0),
                fill("6", "2025-06-02T15:00:00Z", total = null),
            ),
            today = LocalDate.of(2026, 9, 30),
            zone = chicago,
        )!!
        assertEquals(listOf(0.0, 40.0, 90.0), r.thisYear.map { it.total })
        assertEquals(9.5, r.thisYear[1].x, 1e-9)
        assertEquals(90.0, r.total, 1e-9)
        // 2026-03-01 is day 60; 2025-03-02 is also day 61 → excluded.
        assertEquals(65.0, r.lastYearSameDate, 1e-9)
        assertEquals(25.0, r.delta, 1e-9)
        assertTrue(r.lastYearHasData)
    }

    @Test fun `no fillups this year is null`() {
        assertNull(
            FuelCharts.spendYoy(listOf(fill("1", "2025-03-01T15:00:00Z", total = 50.0)), LocalDate.of(2026, 9, 30), chicago),
        )
    }

    @Test fun `stepped holds each value until the next point`() {
        assertEquals(
            listOf(0.0 to 0.0, 5.0 to 0.0, 5.0 to 10.0, 9.0 to 10.0, 9.0 to 25.0),
            FuelCharts.stepped(listOf(0.0 to 0.0, 5.0 to 10.0, 9.0 to 25.0)),
        )
    }

    @Test fun `quarter ticks sit on the first of the month`() {
        assertEquals(listOf(0.0, 90.0, 181.0, 273.0), FuelCharts.quarterTicks(2026).map { it.first })
    }
}
