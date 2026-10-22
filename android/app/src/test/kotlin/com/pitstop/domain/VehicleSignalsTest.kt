package com.pitstop.domain

import com.pitstop.domain.VehicleSignals.Corner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parity with frontend/src/lib/vehicleSignals.ts. */
class VehicleSignalsTest {

    @Test
    fun `gear labels`() {
        assertEquals("P/N", VehicleSignals.gearLabel(0.0))
        assertEquals("R", VehicleSignals.gearLabel(15.0))
        assertEquals("D2", VehicleSignals.gearLabel(2.0)) // stopped in D, as-is
        assertEquals("D9", VehicleSignals.gearLabel(9.0))
        assertEquals("12", VehicleSignals.gearLabel(12.0)) // unknown code raw
        assertEquals("—", VehicleSignals.gearLabel(null))
    }

    @Test
    fun `cylinder labels`() {
        assertEquals("6", VehicleSignals.cylindersLabel(6.0))
        assertEquals("3 (VCM)", VehicleSignals.cylindersLabel(3.0))
        assertEquals("—", VehicleSignals.cylindersLabel(null))
        assertTrue(VehicleSignals.vcmActive(3.0))
        assertFalse(VehicleSignals.vcmActive(6.0))
        assertFalse(VehicleSignals.vcmActive(0.0))
    }

    @Test
    fun `verified 2026-10-21 tires flag FR only`() {
        // FR 196 kPa (28.4 psi) vs FL 213 (30.9): 2.5 psi spread -> low.
        val tires = VehicleSignals.tpms(
            mapOf(Corner.FR to 196.0, Corner.FL to 213.0, Corner.RR to 205.0, Corner.RL to 209.0),
        )
        assertEquals(listOf(Corner.FR), tires.filter { it.low }.map { it.corner })
        assertEquals(listOf(Corner.FL, Corner.FR, Corner.RL, Corner.RR), tires.map { it.corner })
    }

    @Test
    fun `exactly 2 psi below counts, a single tire never does`() {
        val high = 220.0
        val twoPsiBelow = high - 2.0 / VehicleSignals.PSI_PER_KPA
        val t = VehicleSignals.tpms(mapOf(Corner.FL to high, Corner.FR to twoPsiBelow))
        assertTrue(t.first { it.corner == Corner.FR }.low)
        assertFalse(VehicleSignals.tpms(mapOf(Corner.FL to 100.0)).any { it.low })
    }

    @Test
    fun `tire pressures are whole psi in any unit system`() {
        assertEquals("28", VehicleSignals.tirePressure(196.0))
        assertEquals("31", VehicleSignals.tirePressure(213.0))
        assertEquals("—", VehicleSignals.tirePressure(null))
    }

    @Test
    fun `tpmsFrom is null until some corner reported`() {
        assertNull(VehicleSignals.tpmsFrom(mapOf("tire_temp_fl" to 20.0)))
        val t = VehicleSignals.tpmsFrom(mapOf("tire_pressure_rl" to 209.0))!!
        assertEquals(4, t.size)
        assertEquals(209.0, t.first { it.corner == Corner.RL }.pressureKpa!!, 0.0)
    }

    @Test
    fun `age label matches the web`() {
        val now = 10_000_000L
        assertEquals("live", VehicleSignals.ageLabel(now - 89_000, now))
        assertEquals("1m ago", VehicleSignals.ageLabel(now - 90_000, now))
        assertEquals("2h ago", VehicleSignals.ageLabel(now - 2 * 3_600_000L, now))
    }

    @Test
    fun `placard verdict - amber at 3 psi under, red under at 25 percent`() {
        val placard = 35.0 / VehicleSignals.PSI_PER_KPA
        val fr = VehicleSignals.placardVerdict(196.0, placard)!! // 28.4 psi
        assertEquals(35, fr.placardPsi)
        assertEquals(6.6, fr.belowPsi, 0.05)
        assertEquals("−7", fr.deltaLabel)
        assertTrue(fr.belowSpec)
        assertFalse(fr.underInflated)
        val ok = VehicleSignals.placardVerdict(placard - 2.0 / VehicleSignals.PSI_PER_KPA, placard)!!
        assertFalse(ok.belowSpec)
        assertEquals("−2", ok.deltaLabel)
        val flat = VehicleSignals.placardVerdict(placard * 0.75, placard)!!
        assertTrue(flat.underInflated)
        assertNull(VehicleSignals.placardVerdict(200.0, null))
        assertNull(VehicleSignals.placardVerdict(0.0, placard))
    }
}
