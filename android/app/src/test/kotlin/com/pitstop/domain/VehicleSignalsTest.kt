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

/** Tire status + headline; parity with tireStatus/tireHeadline in vehicleSignals.ts. */
class TireHeadlineTest {
    private val kpa = 1 / VehicleSignals.PSI_PER_KPA
    private val placard = 35 * kpa
    private fun set(fl: Double?, fr: Double?, rl: Double?, rr: Double?) = VehicleSignals.tpms(
        mapOf(Corner.FL to fl?.times(kpa), Corner.FR to fr?.times(kpa), Corner.RL to rl?.times(kpa), Corner.RR to rr?.times(kpa)),
    )
    private fun head(t: List<VehicleSignals.Tire>, p: Double? = placard) = VehicleSignals.tireHeadline(t, p, p)

    @Test
    fun `one low tire is named with its gap`() {
        val h = head(set(31.0, 28.0, 30.0, 30.0))!!
        assertEquals(VehicleSignals.TireStatus.LOW, h.first)
        assertEquals("FR is 3 psi lower than the others", h.second)
        // Raw 30.9 vs 28.4 (2.5 psi) shows as 31 vs 28: the sentence says 3.
        assertEquals("FR is 3 psi lower than the others", head(set(30.9, 28.4, 30.0, 30.0))!!.second)
    }

    @Test
    fun `under placard only is the softer status`() {
        // All 3–4 psi under placard but within 1 psi of each other: not LOW.
        val even = set(31.0, 31.0, 32.0, 32.0)
        assertEquals(VehicleSignals.TireStatus.UNDER_PLACARD, VehicleSignals.tireStatus(even[0], placard))
        assertEquals("4 tires under the 35 psi placard", head(even)!!.second)
        // FL/FR are also 4 under the highest tire, so LOW wins.
        assertEquals("FL and FR are low", head(set(31.0, 31.0, 34.0, 35.0))!!.second)
    }

    @Test
    fun `under-inflated beats low`() {
        val h = head(set(35.0, 26.0, 35.0, 35.0))!!
        assertEquals(VehicleSignals.TireStatus.UNDER_INFLATED, h.first)
        assertEquals("FR is under-inflated: add air now", h.second)
    }

    @Test
    fun `good, partial, empty and no placard`() {
        assertEquals("All four tires look good", head(set(35.0, 35.0, 35.0, 36.0))!!.second)
        assertEquals("All reporting tires look good", head(set(35.0, null, 35.0, 36.0))!!.second)
        assertNull(head(set(null, null, null, null)))
        assertEquals("All four tires look good", head(set(31.0, 31.0, 32.0, 32.0), p = null)!!.second)
        assertEquals("2 tires under the door placard", VehicleSignals.tireHeadline(set(31.0, 31.0, 32.0, 32.0), placard, 33 * kpa)!!.second)
        assertEquals(VehicleSignals.TireStatus.NONE, VehicleSignals.tireStatus(set(null, 1.0, 1.0, 1.0)[0], placard))
    }

    @Test fun `oil life follows the Minder thresholds`() {
        assertEquals(VehicleSignals.OilStatus.NONE, VehicleSignals.oilLifeStatus(null))
        assertEquals(VehicleSignals.OilStatus.GOOD, VehicleSignals.oilLifeStatus(16.0))
        assertEquals(VehicleSignals.OilStatus.DUE_SOON, VehicleSignals.oilLifeStatus(15.0))
        assertEquals(VehicleSignals.OilStatus.DUE_SOON, VehicleSignals.oilLifeStatus(6.0))
        assertEquals(VehicleSignals.OilStatus.DUE_NOW, VehicleSignals.oilLifeStatus(5.0))
        assertEquals(VehicleSignals.OilStatus.DUE_NOW, VehicleSignals.oilLifeStatus(0.0))
        assertEquals("Oil change due soon", VehicleSignals.oilLifeHeadline(15.0))
        assertEquals("No reading yet", VehicleSignals.oilLifeHeadline(null))
    }
}
