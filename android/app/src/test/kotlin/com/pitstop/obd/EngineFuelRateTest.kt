package com.pitstop.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Mode 01 PID 0x9D layout (data bytes after the `41 9D` echo):
 *   A, B  = engine fuel rate, raw
 *   C, D  = vehicle-level fuel rate (mirrored by this ECU, unused)
 *
 * Scaling is `((A*256)+B) * 0.02` grams per second, pinned empirically
 * against a simultaneous MAF reading (0.400 vs 0.380 g/s, 5.2% off) because
 * the SAE-published /32 and /20 scalings were 64% and 163% off respectively.
 * Getting the multiplier wrong silently mis-scales every fuel integration,
 * so the observed-on-car payload bytes are asserted literally here.
 */
class EngineFuelRateTest {

    private fun parse(vararg bytes: Int): Double? =
        Pids.EngineFuelRate.parser(bytes.map { it.toByte() }.toByteArray())

    @Test
    fun `decodes the real observed idle payload at the documented scaling`() {
        // Live frame: 18 DA F1 10 06 41 9D 00 13 00 13
        // -> A=0x00 B=0x13 (=19) -> 19 * 0.02 = 0.38 g/s
        assertEquals(0.38, parse(0x00, 0x13, 0x00, 0x13)!!, 1e-9)

        // The other sampled idle value: A=0x00 B=0x14 (=20) -> 0.40 g/s.
        // This is the exact point cross-checked against MAF 5.59 g/s air
        // / 14.7 stoich = 0.380 g/s reference.
        assertEquals(0.40, parse(0x00, 0x14, 0x00, 0x14)!!, 1e-9)
    }

    @Test
    fun `applies the 0_02 multiplier across the byte range not SAE 32 or 20`() {
        // A=0x01 B=0x00 -> 256 -> 5.12 g/s. Under /32 this would read 8.0
        // and under /20 it would read 12.8 — both wildly wrong.
        assertEquals(5.12, parse(0x01, 0x00)!!, 1e-9)
        // A plausible highway-cruise burn: 0x03E8 = 1000 -> 20.0 g/s.
        assertEquals(20.0, parse(0x03, 0xE8)!!, 1e-9)
    }

    @Test
    fun `zero is a legitimate reading not an error`() {
        // Engine off, or DFCO on a closed-throttle over-run: the PCM has
        // shut the injectors off and the true rate really is zero. Dropping
        // this as "no data" would make MAF-style over-reporting reappear.
        assertEquals(0.0, parse(0x00, 0x00, 0x00, 0x00)!!, 1e-9)
        assertEquals(0.0, parse(0x00, 0x00)!!, 1e-9)
    }

    @Test
    fun `returns null on a truncated response rather than mis-decoding`() {
        assertNull(parse(0x00))
        assertNull(parse())
    }

    @Test
    fun `decodes the observed frame end-to-end through ObdResponseParser`() {
        // The other tests hand the parser its data bytes directly, which
        // assumes the A/B offsets line up after header stripping. Drive the
        // WHOLE path the service uses instead, from the literal frame seen on
        // the car: 29-bit CAN ID + ISO-TP length + mode/PID echo + payload.
        val frame = "18 DA F1 10 06 41 9D 00 13 00 13"
        val parsed = ObdResponseParser.parse(frame.toByteArray(Charsets.US_ASCII))!!
        assertEquals(0x01, parsed.mode)
        assertEquals(0x9D, parsed.pid)
        // Header + echo stripped, ISO-TP length honoured -> exactly A B C D.
        assertEquals(4, parsed.data.size)
        assertEquals(0.38, Pids.EngineFuelRate.parser(parsed.data)!!, 1e-9)
    }

    @Test
    fun `is polled by default under the engine_fuel_rate metric name`() {
        assertEquals("engine_fuel_rate", Pids.EngineFuelRate.name)
        assertEquals(0x9D, Pids.EngineFuelRate.pid)
        assertEquals(0x01, Pids.EngineFuelRate.mode)
        assertEquals("019D\r", Pids.EngineFuelRate.command())
        assert(Pids.DEFAULT.contains(Pids.EngineFuelRate))
        assertEquals(1, Pids.DEFAULT.count { it.name == "engine_fuel_rate" })
        // 0x66 stays in the poll list — it is the cross-check that pinned
        // this scaling. 0x10 does NOT: it was measured unsupported on this
        // PCM (2026-07-31), so it only ever returned NO DATA.
        assert(Pids.DEFAULT.contains(Pids.MafSensorA))
        assert(!Pids.DEFAULT.contains(Pids.MafAirFlow))
    }
}
