package com.pitstop.domain

import com.pitstop.http.TireAmbientDayDto
import com.pitstop.http.TireAnalyticsDto
import com.pitstop.http.TireCornerHistoryDto
import com.pitstop.http.TireDayDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class TireHistoryTest {
    private val psi35 = 35 / VehicleSignals.PSI_PER_KPA
    private val psi31 = 31 / VehicleSignals.PSI_PER_KPA

    private fun dto(
        frCold: Double? = psi31,
        placardF: Double? = 241.3,
        placardR: Double? = 241.3,
    ) = TireAnalyticsDto(
        hasData = true,
        days = listOf("2026-01-01", "2026-01-02"),
        ambient = listOf(TireAmbientDayDto("2026-01-01", 50.0), TireAmbientDayDto("2026-01-02", null)),
        corners = listOf(
            TireCornerHistoryDto(
                corner = "fl",
                daily = listOf(
                    TireDayDto("2026-01-01", pressureMeanKpa = psi35, coldPressureKpa = psi35, tempMaxC = 20.0),
                    TireDayDto("2026-01-02"),
                ),
                currentColdKpa = psi35,
                currentColdAt = "2026-01-01T12:00:00+00:00",
                slopePsiPerWeek = -0.04,
                slopeSamples = 6.0,
                warmupMedianPsi = 2.47,
                belowPlacardPsi = 0.0,
            ),
            TireCornerHistoryDto(
                corner = "fr",
                daily = listOf(TireDayDto("2026-01-01"), TireDayDto("2026-01-02", coldPressureKpa = frCold, tempMaxC = -128.0)),
                currentColdKpa = frCold,
                slopePsiPerWeek = -0.9,
                slopeSamples = 2.0,
                belowPlacardPsi = 4.0,
                belowSpec = true,
            ),
        ),
        slowLeakMinSamples = 4.0,
        placardFrontKpa = placardF,
        placardRearKpa = placardR,
    )

    @Test fun `parses the live response shape with int-valued numbers`() {
        val json = """
            {"has_data":true,"days":["2026-10-23"],"ambient":[{"day":"2026-10-23","ambient_mean_f":53.9}],
             "corners":[{"corner":"fr","daily":[{"day":"2026-10-23","pressure_min_kpa":200.0,"pressure_mean_kpa":209.8,
             "pressure_max_kpa":217.0,"temp_mean_c":16.4,"temp_max_c":23.0,"cold_pressure_kpa":200.0,"warmup_psi":2.47}],
             "cold_readings":[{"time":"2026-10-23T11:16:15.297000+00:00","pressure_kpa":200.0,"temp_c":11.0}],
             "current_cold_kpa":200.0,"current_cold_at":"2026-10-23T11:16:15.297000+00:00","slope_psi_per_week":null,
             "slope_samples":3,"possible_slow_leak":false,"warmup_median_psi":2.47,"placard_kpa":241.3,
             "below_placard_psi":6.0,"under_inflated":false,"below_spec":true}],
             "low_corners":[],"low_delta_psi":2.0,"slow_leak_psi_per_week":-0.5,"slow_leak_min_samples":4,
             "cold_park_hours":3,"placard_front_kpa":241.3,"placard_rear_kpa":241.3,"under_inflated_fraction":0.25,
             "below_spec_psi":3.0,"window_days":30}
        """.trimIndent()
        val d = Json { ignoreUnknownKeys = true }.decodeFromString(TireAnalyticsDto.serializer(), json)
        assertEquals(3.0, d.corners.single().slopeSamples)
        assertEquals(4.0, d.slowLeakMinSamples)
        assertTrue(d.corners.single().belowSpec)
    }

    @Test fun `cold pressure chart has four fixed series in psi and the placard line`() {
        val c = TireHistory.pressureChart(dto(), TireHistory.PressureMode.Cold)!!
        assertEquals(listOf("FL", "FR", "RL", "RR"), c.series!!.map { it.name })
        assertEquals(35.0, c.series!![0].points.single()[1]!!, 0.05)
        assertEquals(31.0, c.series!![1].points.single()[1]!!, 0.05)
        assertTrue(c.series!![2].points.isEmpty())
        assertEquals(35.0, c.ref!!.value!!, 0.0)
        assertEquals("Door placard 35 psi", c.ref!!.label)
    }

    @Test fun `no values means no chart, never zeros`() {
        val empty = dto().copy(corners = dto().corners.map { it.copy(daily = it.daily.map { d -> TireDayDto(d.day) }) })
        assertNull(TireHistory.pressureChart(empty, TireHistory.PressureMode.Cold))
        assertNull(TireHistory.tempChart(empty, "imperial"))
    }

    @Test fun `placard line only when both axles agree`() {
        assertNull(TireHistory.placardRef(dto(placardR = 262.0)))
        assertEquals("Door placard: front 35 psi, rear 38 psi", TireHistory.placardText(dto(placardR = 262.0)))
        assertEquals(35.0, TireHistory.placardRef(dto(placardR = null))!!.value!!, 0.0)
        assertNull(TireHistory.placardText(dto(placardF = null, placardR = null)))
    }

    @Test fun `temp chart converts and drops sentinel temps, adds outside`() {
        val c = TireHistory.tempChart(dto(), "imperial")!!
        assertEquals(68.0, c.series!![0].points.single()[1]!!, 0.05)
        assertTrue(c.series!![1].points.isEmpty()) // -128 °C sentinel dropped
        val outside = c.series!!.last()
        assertEquals(TireHistory.OUTSIDE, outside.name)
        assertEquals(50.0, outside.points.single()[1]!!, 0.05)
        assertEquals("°F", c.yUnit)
        assertEquals(10.0, TireHistory.tempChart(dto(), "metric")!!.series!!.last().points.single()[1]!!, 0.05)
    }

    @Test fun `corner stats mirror the web cards`() {
        val s = TireHistory.cornerStats(dto(), ZoneOffset.UTC).associateBy { it.corner }
        val fl = s.getValue(VehicleSignals.Corner.FL)
        assertEquals("35", fl.coldPsi)
        assertEquals("cold · Jan 1", fl.coldCaption)
        assertEquals("0.0 psi/week", fl.trend)
        assertEquals("highest", fl.spread)
        assertEquals("±0 psi", fl.vsPlacard)
        assertEquals("+2.5 psi", fl.warmup)
        assertNull(fl.flag)

        val fr = s.getValue(VehicleSignals.Corner.FR)
        assertEquals("31", fr.coldPsi)
        assertFalse(fr.trendKnown)
        assertEquals("needs 4 cold readings (have 2)", fr.trend)
        assertEquals("−4 psi vs highest", fr.spread)
        assertEquals("−4 psi", fr.vsPlacard)
        assertTrue(fr.low)
        assertEquals("Below spec", fr.flag)

        val rl = s.getValue(VehicleSignals.Corner.RL)
        assertEquals("—", rl.coldPsi)
        assertEquals("no cold reading yet", rl.coldCaption)
        assertNull(rl.spread)
    }

    @Test fun `psi per week keeps a true minus and one decimal`() {
        assertEquals("−0.4 psi/week", TireHistory.psiPerWeek(-0.43))
        assertEquals("+1.0 psi/week", TireHistory.psiPerWeek(0.98))
        assertEquals("0.0 psi/week", TireHistory.psiPerWeek(0.01))
        assertEquals("—", TireHistory.psiPerWeek(null))
    }

    @Test fun `leak check keeps tire cards only`() {
        val cards = listOf(
            com.pitstop.http.InsightCardDto(id = "tire_leak", group = "tires"),
            com.pitstop.http.InsightCardDto(id = "braking", group = "tires"),
            com.pitstop.http.InsightCardDto(id = "speedometer", group = "tires"),
            com.pitstop.http.InsightCardDto(id = "battery", group = "engine"),
        )
        assertEquals(listOf("tire_leak"), TireHistory.tireInsights(cards).map { it.id })
    }
}
