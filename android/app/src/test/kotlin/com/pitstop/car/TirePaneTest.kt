package com.pitstop.car

import com.pitstop.data.CachedReading
import com.pitstop.widget.TireWidgetProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class TirePaneTest {
    private val now = 1_800_000_000_000L
    private val placard = 241.3 // 35 psi

    private fun snap(fl: Double, fr: Double, rl: Double, rr: Double, ageMs: Long = 20_000L, temps: Boolean = true) =
        buildMap {
            put("tire_pressure_fl", CachedReading(fl, now - ageMs))
            put("tire_pressure_fr", CachedReading(fr, now - ageMs))
            put("tire_pressure_rl", CachedReading(rl, now - ageMs))
            put("tire_pressure_rr", CachedReading(rr, now - ageMs))
            if (temps) {
                put("tire_temp_fl", CachedReading(14.0, now - ageMs))
                put("tire_temp_fr", CachedReading(12.0, now - ageMs))
                put("tire_temp_rl", CachedReading(16.0, now - ageMs))
                put("tire_temp_rr", CachedReading(15.0, now - ageMs))
            }
            put(TireWidgetProvider.PLACARD_FRONT, CachedReading(placard, now))
            put(TireWidgetProvider.PLACARD_REAR, CachedReading(placard, now))
        }

    private val utc = java.time.ZoneOffset.UTC

    // now = 2027-01-15 08:00 UTC
    private fun rows(s: Map<String, CachedReading>, system: String = "imperial") =
        TirePane.rows(TireWidgetProvider.modelOf(s, now, system), system, utc, now).toMap()

    @Test fun titlesNeverChange() {
        val a = TirePane.rows(TireWidgetProvider.modelOf(emptyMap(), now, "imperial"), "imperial", utc, now)
        val b = TirePane.rows(TireWidgetProvider.modelOf(snap(240.0, 200.0, 240.0, 240.0), now, "imperial"), "imperial", utc, now)
        assertEquals(TirePane.TITLES, a.map { it.first })
        assertEquals(TirePane.TITLES, b.map { it.first })
    }

    @Test fun liveAndAllOk() {
        val r = rows(snap(241.0, 240.0, 242.0, 241.0))
        assertEquals("Live", r["Updated"])
        assertEquals("All OK", r["Pressure"])
    }

    @Test fun parkedShowsTheClockTimeNotAnAge() {
        // An age ("3h ago") would change the row every minute and spend a template each time.
        assertEquals("At 5:00 AM", rows(snap(241.0, 240.0, 242.0, 241.0, ageMs = 3 * 3_600_000L))["Updated"])
        assertEquals("Jan 13, 8:00 AM", rows(snap(241.0, 240.0, 242.0, 241.0, ageMs = 2 * 86_400_000L))["Updated"])
    }

    @Test fun lowAndUnderInflated() {
        assertEquals("FR low", rows(snap(240.0, 200.0, 240.0, 240.0))["Pressure"])
        assertEquals("FR under-inflated", rows(snap(240.0, 170.0, 240.0, 240.0))["Pressure"])
    }

    @Test fun rowTextIgnoresTemperatures() {
        assertEquals(rows(snap(241.0, 240.0, 242.0, 241.0)), rows(snap(241.0, 240.0, 242.0, 241.0, temps = false)))
    }

    @Test fun empty() {
        assertEquals("No readings yet", rows(emptyMap())["Updated"])
        assertEquals("—", rows(emptyMap())["Pressure"])
    }
}
