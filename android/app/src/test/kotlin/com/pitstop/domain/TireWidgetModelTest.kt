package com.pitstop.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TireWidgetModelTest {
    private val now = 1_800_000_000_000L
    private fun r(v: Double, ageMin: Long) = v to (now - ageMin * 60_000L)

    /** Verified 2026-10-21: FR 196 / FL 213 / RR 205 / RL 209 kPa. */
    private val verified = mapOf(
        "tire_pressure_fr" to r(196.0, 5), "tire_pressure_fl" to r(213.0, 5),
        "tire_pressure_rr" to r(205.0, 5), "tire_pressure_rl" to r(209.0, 5),
        "tire_temp_fl" to r(22.0, 5),
    )

    @Test
    fun `whole psi, FR low, age from the newest pressure`() {
        val m = TireWidgetModel.of(verified, now, "metric")
        assertEquals(listOf("31", "28", "30", "30"), m.cells.map { it.psi })
        assertEquals(listOf(VehicleSignals.Corner.FR), m.cells.filter { it.low }.map { it.corner })
        assertEquals("5m ago", m.age)
        assertFalse(m.stale)
        assertFalse(m.empty)
        assertEquals("22°", m.cells.first { it.corner == VehicleSignals.Corner.FL }.temp)
        assertNull(m.placardLabel)
    }

    @Test
    fun `older than a day dims, never blanks`() {
        val old = verified.mapValues { (_, v) -> v.first to (now - 30L * 3_600_000L) }
        val m = TireWidgetModel.of(old, now, "imperial")
        assertTrue(m.stale)
        assertEquals("1d ago", m.age)
        assertEquals("31", m.cells.first().psi)
    }

    @Test
    fun `nothing ever reported is empty with dashes`() {
        val m = TireWidgetModel.of(emptyMap(), now, "imperial")
        assertTrue(m.empty)
        assertNull(m.age)
        assertTrue(m.cells.all { it.psi == "—" })
    }

    @Test
    fun `placard slots in as of 35 and a per-corner delta`() {
        val placard = 35.0 / VehicleSignals.PSI_PER_KPA
        val m = TireWidgetModel.of(verified, now, "imperial", placard, placard)
        assertEquals("of 35", m.placardLabel)
        // FR 28.4 psi vs 35 -> -7 (rounded); FL 30.9 -> -4.
        assertEquals("−7", m.cells.first { it.corner == VehicleSignals.Corner.FR }.vsPlacard)
        assertEquals("−4", m.cells.first { it.corner == VehicleSignals.Corner.FL }.vsPlacard)
    }

    @Test
    fun `a zero kPa sentinel is a dash, not a low tire`() {
        val m = TireWidgetModel.of(verified + ("tire_pressure_rr" to r(0.0, 1)), now, "imperial")
        assertEquals("—", m.cells.first { it.corner == VehicleSignals.Corner.RR }.psi)
        assertFalse(m.cells.first { it.corner == VehicleSignals.Corner.RR }.low)
    }
}
