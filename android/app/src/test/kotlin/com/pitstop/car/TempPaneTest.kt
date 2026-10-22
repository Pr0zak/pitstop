package com.pitstop.car

import com.pitstop.service.MetricSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TempPaneTest {
    private val now = 1_800_000_000_000L

    private fun live(vararg kv: Pair<String, Double>, ageMs: Long = 2_000L) =
        kv.associate { (k, v) -> k to MetricSample(k, v, now - ageMs) }

    private fun model(vararg kv: Pair<String, Double>, ageMs: Long = 2_000L, system: String = "imperial") =
        TempModel.of(live(*kv, ageMs = ageMs), now, system, "Engine off")

    private val warm = arrayOf("coolant_temp" to 90.0, "atf_temp" to 70.0, "intake_air_temp" to 25.0, "ambient_air_temp_f" to 60.0)

    @Test fun allNormalImperial() {
        val m = model(*warm)
        assertEquals("All normal", TempPane.status(m))
        assertEquals(listOf("194", "158", "77", "60"), m.cells.map { it.number })
        assertEquals("°F", m.cells[0].unit)
    }

    @Test fun coolantHotIsSevere() {
        val m = model("coolant_temp" to 108.0, "atf_temp" to 70.0)
        assertEquals(TileWarn.Severe, m.cells[0].warn)
        assertEquals("Coolant HOT · 226°F", TempPane.status(m))
    }

    @Test fun transHotIsCaution() {
        val m = model("coolant_temp" to 90.0, "atf_temp" to 115.0)
        assertEquals(TileWarn.Caution, m.cells[1].warn)
        assertEquals("Trans HOT · 239°F", TempPane.status(m))
    }

    @Test fun warmingUpThenIce() {
        assertEquals("Warming up · 104°F", TempPane.status(model("coolant_temp" to 40.0)))
        assertEquals("Ice possible · 34°F outside", TempPane.status(model("coolant_temp" to 90.0, "ambient_air_temp_f" to 34.0)))
    }

    @Test fun staleReadingsShowTheIdleReason() {
        val m = model(*warm, ageMs = 5 * 60_000L)
        assertTrue(m.cells.all { it.stale })
        assertEquals("Engine off", TempPane.status(m))
    }

    @Test fun catalystRow() {
        assertEquals("B1 1202° · B2 1184°", TempPane.catalyst(650.0, 640.0, "imperial"))
        assertEquals("B1 650°", TempPane.catalyst(650.0, null, "metric"))
        assertEquals("—", TempPane.catalyst(null, null, "imperial"))
    }

    @Test fun titlesNeverChange() {
        assertEquals(TempPane.TITLES, TempPane.rows(model(), null, null, "imperial").map { it.first })
        assertEquals(TempPane.TITLES, TempPane.rows(model(*warm), 600.0, 600.0, "metric").map { it.first })
    }
}
