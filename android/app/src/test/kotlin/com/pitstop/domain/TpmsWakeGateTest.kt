package com.pitstop.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Same vectors as backend tests/test_tpms_wake_gate.py. */
class TpmsWakeGateTest {
    private val t0 = 1_000_000_000L

    /** 2026-11-16, FR: 218 stored until the sensor woke at 152 (kPa). */
    @Test
    fun stored_set_is_held_until_the_sensor_wakes() {
        val g = TpmsWakeGate()
        val fr = listOf(218.0, 218.0, 218.0, 152.0, 152.0, 155.0)
        val admitted = fr.mapIndexed { i, v -> v.takeIf { g.admit("tire_pressure_fr", v, t0 + i * 32_000L) } }
        assertEquals(listOf(null, null, null, 152.0, 152.0, 155.0), admitted)
    }

    /** 2026-11-15: a 40 s trip on the stored set records nothing. */
    @Test
    fun short_trip_before_wake_records_nothing() {
        val g = TpmsWakeGate()
        assertFalse(g.admit("tire_pressure_fr", 218.0, t0))
        assertFalse(g.admit("tire_temp_fr", 14.0, t0))
        assertFalse(g.admit("tire_pressure_fr", 218.0, t0 + 33_000))
        assertFalse(g.admit("tire_temp_fr", 14.0, t0 + 33_000))
    }

    /** 2026-11-14 13:11, RR: 225 for 4 min, then 228. */
    @Test
    fun slow_drift_releases_on_first_change() {
        val g = TpmsWakeGate()
        for (i in 0..7) assertFalse(g.admit("tire_pressure_rr", 225.0, t0 + i * 30_000L))
        assertTrue(g.admit("tire_pressure_rr", 228.0, t0 + 244_000))
        assertTrue(g.admit("tire_temp_rr", 12.0, t0 + 244_000))
        assertTrue(g.admit("tire_pressure_rr", 228.0, t0 + 274_000))
    }

    @Test
    fun a_park_rearms_the_corner() {
        val g = TpmsWakeGate()
        g.admit("tire_pressure_fl", 229.0, t0)
        assertTrue(g.admit("tire_pressure_fl", 230.0, t0 + 30_000))
        val later = t0 + 30_000 + TpmsWakeGate.REARM_MS
        assertFalse(g.admit("tire_pressure_fl", 236.0, later))
        assertFalse(g.admit("tire_temp_fl", 12.0, later))
        assertTrue(g.admit("tire_pressure_fl", 235.0, later + 30_000))
    }

    @Test
    fun corners_wake_independently_and_other_metrics_pass() {
        val g = TpmsWakeGate()
        g.admit("tire_pressure_fl", 229.0, t0)
        g.admit("tire_pressure_fr", 218.0, t0)
        assertTrue(g.admit("tire_pressure_fl", 220.0, t0 + 30_000))
        assertFalse(g.admit("tire_pressure_fr", 218.0, t0 + 30_000))
        assertTrue(g.admit("engine_rpm", 700.0, t0))
        assertTrue(g.admit("oil_life_pct", 15.0, t0))
    }
}
