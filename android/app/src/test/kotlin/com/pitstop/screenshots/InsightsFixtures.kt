package com.pitstop.screenshots

import com.pitstop.http.InsightBandDto
import com.pitstop.http.InsightBarDto
import com.pitstop.http.InsightCardDto
import com.pitstop.http.InsightChartDto
import com.pitstop.http.InsightGroupDto
import com.pitstop.http.InsightProgressDto
import com.pitstop.http.InsightRefDto
import com.pitstop.http.InsightSeriesDto
import com.pitstop.http.InsightsResponseDto
import com.pitstop.ui.insights.InsightsUi
import kotlin.math.sin

/** Synthetic Insights payloads for the screenshot tests. Not real driving data. */
object InsightsFixtures {
    private const val DAY = 86_400_000.0
    private const val T0 = 1_767_225_600_000.0 // 2026-01-01T00:00Z

    private fun bar(label: String, v: Double?, hi: Boolean = false) = InsightBarDto(label, v, 10.0, hi)

    private fun daily(n: Int, f: (Int) -> Double) = List(n) { i -> listOf<Double?>(T0 + i * DAY, f(i)) }

    val groups = listOf(
        InsightGroupDto("fuel", "Fuel economy", "What moves your MPG"),
        InsightGroupDto("engine", "Engine health", "Early warnings from the sensors"),
        InsightGroupDto("tires", "Tires & brakes", "Pressure, wear and braking"),
        InsightGroupDto("cost", "Cost", "What you pay and when"),
    )

    val shortTrips = InsightCardDto(
        id = "short_trips", group = "fuel", title = "Short trips", status = "watch",
        headline = "Trips under 10 minutes average 15 mpg, 20% worse than trips over 20 minutes (19).",
        detail = "A cold engine runs rich until it warms up, so short hops cost the most per mile.",
        value = 20.0, valueUnit = "% worse",
        chart = InsightChartDto(
            kind = "bars", yUnit = "mpg",
            bars = listOf(
                bar("Under 5 min", 14.2), bar("5–10 min", 15.6), bar("10–20 min", 17.9),
                bar("20–40 min", 19.1, hi = true), bar("Over 40 min", 18.7),
            ),
        ),
        how = "Each trip's MPG from the engine's fuel-rate sensor, grouped by trip length and weighted by distance.",
        tip = "Combine errands into one trip so the engine stays warm.",
        sample = "Based on 120 trips",
    )

    val bestSpeed = InsightCardDto(
        id = "best_speed", group = "fuel", title = "Most efficient speed", status = "info",
        headline = "Your car is most efficient at 55–60 mph (25 mpg).",
        value = 25.0, valueUnit = "mpg",
        chart = InsightChartDto(
            kind = "bars", yUnit = "mpg",
            bars = List(16) { i ->
                val v = if (i == 0) null else 6.0 + 19.0 * sin(i / 15.0 * Math.PI * 0.85)
                bar("${i * 5}–${i * 5 + 5}", v?.let { Math.round(it * 10) / 10.0 }, hi = i == 11)
            },
        ),
        how = "Seconds of steady driving grouped into 5 mph bands.",
        tip = "On the highway, a few mph slower saves the most.",
    )

    val fillDay = InsightCardDto(
        id = "fill_day", group = "cost", title = "Cheapest day to fill up", status = "info",
        headline = "Tuesday has been your cheapest day, 8¢/gal below your usual.",
        value = 12.0, valueUnit = "¢ spread",
        chart = InsightChartDto(
            kind = "bars", yUnit = "¢ vs usual",
            bars = listOf(
                bar("Mon", 1.9), bar("Tue", -8.0, hi = true), bar("Wed", -2.1), bar("Thu", 3.4),
                bar("Fri", 4.0), bar("Sat", 2.2), bar("Sun", null),
            ),
            ref = InsightRefDto(0.0, "Your usual"),
        ),
        how = "Price per gallon of each fill-up against your average for that month, grouped by weekday.",
        sample = "30 fill-ups",
    )

    val trimSplit = InsightCardDto(
        id = "fuel_trim_split", group = "engine", title = "Fuel correction", status = "good",
        headline = "Both sides of the engine need very little fuel correction. Healthy.",
        value = 3.1, valueUnit = "% max correction",
        chart = InsightChartDto(
            kind = "bars", yUnit = "%",
            bars = listOf(bar("Left idle", -1.6), bar("Left cruise", 1.6), bar("Right idle", 3.1, hi = true), bar("Right cruise", 0.4)),
            band = InsightBandDto(-5.0, 5.0, "Healthy ±5%"),
        ),
        how = "Long-term fuel trim averaged at idle and at cruise for each bank.",
        sample = "Last 30 days",
    )

    val battery = InsightCardDto(
        id = "battery", group = "engine", title = "Battery", status = "watch",
        headline = "Your battery rests at 12.3 V, about half charged.",
        detail = "Resting voltage is read before each start, after the car has sat for a few hours.",
        value = 12.3, valueUnit = "V",
        chart = InsightChartDto(
            kind = "lines", xType = "time", yUnit = "V",
            series = listOf(InsightSeriesDto("Resting voltage", daily(90) { i -> 12.65 - i * 0.004 + 0.05 * sin(i / 3.0) })),
            band = InsightBandDto(12.4, 12.8, "Healthy"),
        ),
        how = "The lowest voltage in the minute before each engine start.",
        tip = "A long drive or a trickle charger will top it up. Test it if it stays under 12.4 V.",
        sample = "90 days",
    )

    val warmup = InsightCardDto(
        id = "warmup", group = "engine", title = "Warm-up", status = "good",
        headline = "The engine warms up in about 4 minutes and runs at 190 °F once warm. Normal.",
        value = 4.0, valueUnit = "min warm-up",
        chart = InsightChartDto(
            kind = "points", xUnit = "°F outside", yUnit = "min to warm",
            points = List(60) { i -> listOf<Double?>(20.0 + i * 1.2, 7.5 - i * 0.07 + 0.6 * sin(i * 1.7)) },
        ),
        how = "Minutes from a cold start until coolant reaches 160 °F, against the outside temperature.",
        sample = "60 cold starts",
    )

    val misfires = InsightCardDto(
        id = "misfires", group = "engine", title = "Misfires", status = "learning",
        headline = "Collecting misfire counts. Needs a few more days of driving.",
        how = "Per-cylinder misfire counters, compared day to day.",
        progress = InsightProgressDto(2.0, 7.0, "days"),
    )

    val tireLeak = InsightCardDto(
        id = "tire_leak", group = "tires", title = "Slow leak", status = "bad",
        headline = "Front right is losing 1.5 psi a day (temperature-corrected). Check it for a nail.",
        value = -1.5, valueUnit = "psi/day",
        chart = InsightChartDto(
            kind = "lines", xType = "time", yUnit = "psi at 68°F",
            series = listOf(
                InsightSeriesDto("FL", daily(6) { 34.8 - it * 0.05 }),
                InsightSeriesDto("FR", daily(6) { 34.5 - it * 1.5 }),
                InsightSeriesDto("RL", daily(6) { 35.1 - it * 0.04 }),
                InsightSeriesDto("RR", daily(6) { 34.9 - it * 0.06 }),
            ),
            ref = InsightRefDto(35.0, "Door placard"),
        ),
        how = "Each tire's pressure corrected to 68 °F, then a straight-line fit over the last week.",
        tip = "Check the tire for a nail or a leaking valve stem before it goes flat.",
        sample = "6 days of readings",
    )

    val priceVsMarket = InsightCardDto(
        id = "price_vs_market", group = "cost", title = "Price vs the market", status = "good",
        headline = "You paid 20¢/gal less than the US average, about $90 saved.",
        value = -20.0, valueUnit = "¢/gal vs average",
        chart = InsightChartDto(
            kind = "lines", xType = "time", yUnit = "$/gal",
            series = listOf(
                InsightSeriesDto("You paid", List(20) { i -> listOf<Double?>(T0 + i * 14 * DAY, 2.9 + 0.2 * sin(i / 3.0)) }),
                InsightSeriesDto("US average", List(20) { i -> listOf<Double?>(T0 + i * 14 * DAY, 3.1 + 0.15 * sin(i / 3.0 + 0.4)) }),
            ),
        ),
        how = "Each fill-up's price against the EIA weekly US average for the same week.",
        sample = "20 fill-ups",
    )

    val response = InsightsResponseDto(
        groups = groups,
        attention = listOf("tire_leak", "short_trips", "battery"),
        insights = listOf(shortTrips, bestSpeed, trimSplit, battery, warmup, misfires, tireLeak, priceVsMarket, fillDay),
        generatedAt = "2026-01-15T14:30:00+00:00",
        windowDays = 365.0,
    )

    val allClear = response.copy(
        attention = emptyList(),
        insights = listOf(trimSplit, warmup.copy(), misfires),
    )

    val ui = InsightsUi(data = response)
    val uiAllClear = InsightsUi(data = allClear)
    val uiLoading = InsightsUi(loading = true)
    val uiError = InsightsUi(error = "This server doesn't have Insights yet. Update the server to see them.")
}
