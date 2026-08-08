package com.pitstop.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * PIDs 0x24 (measured lambda) and 0x44 (commanded AFR) share a decode and
 * share a trap: A=B=0xFF is the "not available" sentinel, and it decodes to
 * 1.99997 — a number, not an obvious failure.
 *
 * Measured on a real drive before the guard: ~8 % of samples were the
 * sentinel while the genuine values averaged 0.99. Both metrics are charted
 * with 3 decimals precisely to resolve the 0.98-1.02 band, so a single 2.0
 * rescales the axis and flattens the real signal.
 */
class EquivalenceRatioTest {

    private fun lambda(vararg b: Int): Double? =
        Pids.O2S1Lambda.parser(b.map { it.toByte() }.toByteArray())

    private fun afr(vararg b: Int): Double? =
        Pids.CommandedAfrRatio.parser(b.map { it.toByte() }.toByteArray())

    @Test
    fun `stoichiometric decodes to 1_0`() {
        // 0x8000 = 32768 -> 32768 * 2 / 65536 = 1.0
        assertEquals(1.0, lambda(0x80, 0x00)!!, 1e-9)
        assertEquals(1.0, afr(0x80, 0x00)!!, 1e-9)
    }

    @Test
    fun `rich and lean both survive`() {
        // ~0.85 rich under load, ~1.05 lean on overrun — the real range.
        assertEquals(0.85, lambda(0x6C, 0xCD)!!, 1e-3)
        assertEquals(1.05, lambda(0x86, 0x66)!!, 1e-3)
    }

    @Test
    fun `the 0xFFFF not-available sentinel is rejected, not reported as 2_0`() {
        assertNull(lambda(0xFF, 0xFF))
        assertNull(afr(0xFF, 0xFF))
    }

    @Test
    fun `the guard clips only the sentinel, not the range below it`() {
        // 0xFD00 = 64768 -> 1.9766, still under the >= 1.99 guard. Nothing a
        // running engine produces, but it proves the threshold is not eating
        // the top of the legitimate scale.
        assertEquals(1.9766, lambda(0xFD, 0x00)!!, 1e-3)
    }

    @Test
    fun `truncated response yields null rather than a mis-decode`() {
        assertNull(lambda(0x80))
        assertNull(lambda())
    }
}
