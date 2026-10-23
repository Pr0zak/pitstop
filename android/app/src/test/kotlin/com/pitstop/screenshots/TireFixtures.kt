package com.pitstop.screenshots

import com.pitstop.http.TireAmbientDayDto
import com.pitstop.http.TireAnalyticsDto
import com.pitstop.http.TireColdReadingDto
import com.pitstop.http.TireCornerHistoryDto
import com.pitstop.http.TireDayDto
import com.pitstop.ui.tires.TireDetailUi
import java.time.LocalDate
import kotlin.math.sin

/**
 * Synthetic /analytics/tires bodies for the Tire detail screen: 30 days, FR
 * slowly leaking, a cold snap mid-month, placard 35 psi. No real data.
 */
object TireFixtures {
    private const val KPA_PER_PSI = 1 / 0.145038
    private val start = LocalDate.of(2026, 1, 1)
    private val days = (0 until 30).map { start.plusDays(it.toLong()).toString() }

    /** Outside °F per day: a cold snap around day 12. */
    private fun outsideF(i: Int) = 48.0 + 10 * sin(i / 5.0) - if (i in 10..14) 14.0 else 0.0

    private fun corner(id: String, heat: Double, basePsi: Double, leakPerDay: Double, belowPlacard: Double?, flags: Triple<Boolean, Boolean, Boolean>): TireCornerHistoryDto {
        val daily = days.mapIndexed { i, d ->
            // Sensor silent a few days (car away from home, phone not polling).
            if (i % 9 == 4) return@mapIndexed TireDayDto(day = d)
            val cold = basePsi - leakPerDay * i + (outsideF(i) - 48.0) / 10.0
            val hasCold = i % 2 == 0
            TireDayDto(
                day = d,
                pressureMinKpa = cold * KPA_PER_PSI,
                pressureMeanKpa = (cold + 1.6) * KPA_PER_PSI,
                pressureMaxKpa = (cold + 3.1) * KPA_PER_PSI,
                tempMeanC = 12.0 + (outsideF(i) - 48.0) / 3,
                tempMaxC = 24.0 + heat + (outsideF(i) - 48.0) / 2 + (i % 3),
                coldPressureKpa = if (hasCold) cold * KPA_PER_PSI else null,
                warmupPsi = if (hasCold) 3.1 else null,
            )
        }
        val colds = daily.filter { it.coldPressureKpa != null }
        val last = colds.last()
        return TireCornerHistoryDto(
            corner = id,
            daily = daily,
            coldReadings = colds.map { TireColdReadingDto("${it.day}T12:30:00+00:00", it.coldPressureKpa, 8.0) },
            currentColdKpa = last.coldPressureKpa,
            currentColdAt = "${last.day}T12:30:00+00:00",
            slopePsiPerWeek = -leakPerDay * 7,
            slopeSamples = colds.size.toDouble(),
            possibleSlowLeak = flags.first,
            warmupMedianPsi = 3.1,
            placardKpa = 241.3,
            belowPlacardPsi = belowPlacard,
            underInflated = flags.third,
            belowSpec = flags.second,
        )
    }

    val analytics = TireAnalyticsDto(
        hasData = true,
        days = days,
        ambient = days.mapIndexed { i, d -> TireAmbientDayDto(d, if (i % 9 == 4) null else outsideF(i)) },
        corners = listOf(
            corner("fl", 0.0, 35.4, 0.01, -0.1, Triple(false, false, false)),
            corner("fr", 2.5, 35.0, 0.16, 4.5, Triple(true, true, false)),
            corner("rl", -3.0, 35.6, 0.0, -0.4, Triple(false, false, false)),
            corner("rr", -1.5, 34.9, 0.02, 0.5, Triple(false, false, false)),
        ),
        lowCorners = listOf("fr"),
        lowDeltaPsi = 2.0,
        slowLeakPsiPerWeek = -0.5,
        slowLeakMinSamples = 4.0,
        coldParkHours = 3.0,
        placardFrontKpa = 241.3,
        placardRearKpa = 241.3,
        underInflatedFraction = 0.25,
        belowSpecPsi = 3.0,
        windowDays = 30.0,
    )

    /** A 7-day window with two cold readings so far: trends say "needs 4 cold readings". */
    val young = analytics.copy(
        days = days.takeLast(7),
        ambient = analytics.ambient.takeLast(7),
        corners = analytics.corners.map { c ->
            c.copy(daily = c.daily.takeLast(7), slopeSamples = 2.0, slopePsiPerWeek = null, possibleSlowLeak = false, belowSpec = false, belowPlacardPsi = 1.0)
        },
        placardFrontKpa = null,
        placardRearKpa = null,
    )

    val ui = TireDetailUi(days = 30, data = analytics)
    val uiYoung = TireDetailUi(days = 7, data = young)
    val uiEmpty = TireDetailUi(days = 7, data = TireAnalyticsDto(hasData = false, days = days.take(7)))
    val uiLoading = TireDetailUi(loading = true)
    val uiError = TireDetailUi(error = "Couldn't reach the server")
}
