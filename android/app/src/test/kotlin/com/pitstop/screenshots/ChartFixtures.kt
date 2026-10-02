package com.pitstop.screenshots

import com.pitstop.domain.FuelCharts
import com.pitstop.http.EiaWeekDto
import com.pitstop.http.FillupDto
import com.pitstop.http.MpgPointDto
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import kotlin.math.PI
import kotlin.math.sin

/** Synthetic data for the three fuel charts. Nothing here is real. */
object ChartFixtures {
    private val today = LocalDate.of(2026, 9, 30)

    /** 48 months of seasonal mpg with the odd spike and a missing month. */
    val mpgMonths: List<MpgPointDto> = (0 until 48).map { i ->
        val ym = YearMonth.of(2022, 10).plusMonths(i.toLong())
        val seasonal = 18.5 + 1.6 * sin((ym.monthValue - 4) / 12.0 * 2 * PI)
        val mpg = when (i) {
            20 -> null
            29 -> 24.8
            else -> seasonal + (i * 37 % 7 - 3) * 0.25
        }
        MpgPointDto(ym.toString(), mpg, fillupCount = 1 + i % 3)
    }

    private fun fill(id: String, d: LocalDate, ppg: Double, gal: Double) = FillupDto(
        id = id, vehicleId = "v1", fillupDate = d.atTime(17, 0).atOffset(ZoneOffset.UTC).toString(), odo = 0.0,
        fuelVolume = gal, pricePerUnit = ppg, priceTotal = Math.round(ppg * gal * 100) / 100.0,
    )

    private fun market(d: LocalDate) = 3.55 + 0.35 * sin(d.dayOfYear / 365.0 * 2 * PI - 1.2)

    /** EIA's 52 weeks, newest first like the API. */
    val eia: List<EiaWeekDto> = (0 until 52).map { w ->
        val d = LocalDate.of(2026, 9, 28).minusWeeks(w.toLong())
        EiaWeekDto(d.toString(), Math.round(market(d) * 1000) / 1000.0)
    }

    /** A fillup every ~11 days since Jan 1 2025, mostly a little under market. */
    val fills: List<FillupDto> = generateSequence(LocalDate.of(2025, 1, 4)) { it.plusDays(11L + it.dayOfMonth % 3) }
        .takeWhile { !it.isAfter(today) }
        .mapIndexed { i, d -> fill("m$i", d, market(d) - 0.22 + (i % 5) * 0.06, 14.0 + (i % 4) * 1.1) }
        .toList()
        .reversed()

    /** Full tanks with mpg, every 12 days, for MPG per tank. */
    val tankFills: List<FillupDto> = (0 until 24).map { i ->
        val d = LocalDate.of(2025, 11, 1).plusDays(i * 12L)
        fill("k$i", d, 3.5, 15.0).copy(mpg = 18.0 + 1.4 * sin(i / 3.0) + (i % 3) * 0.3, isFull = i % 7 != 3)
    }

    val marketCompare = FuelCharts.marketCompare(fills, eia, ZoneOffset.UTC)!!

    val spendYoy = FuelCharts.spendYoy(fills, today, ZoneOffset.UTC)!!
}
