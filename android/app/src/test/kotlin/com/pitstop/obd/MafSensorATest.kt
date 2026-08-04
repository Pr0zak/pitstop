package com.pitstop.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Mode 01 PID 0x66 layout:
 *   A     = sensor-support bitmap (bit0 = sensor A, bit1 = sensor B)
 *   B, C  = sensor A, g/s scaled x32
 *   D, E  = sensor B
 *
 * The x32 scaling is the easy thing to get wrong — 0x10's is /100, and
 * mixing them up would silently mis-scale every fuel integration.
 */
class MafSensorATest {

    private fun parse(vararg bytes: Int): Double? =
        Pids.MafSensorA.parser(bytes.map { it.toByte() }.toByteArray())

    @Test
    fun `decodes sensor A at the documented x32 scaling`() {
        // B,C = 0x0100 = 256 -> 256/32 = 8.0 g/s
        assertEquals(8.0, parse(0x01, 0x01, 0x00)!!, 1e-9)
        // A plausible cruise value: 0x0640 = 1600 -> 50.0 g/s
        assertEquals(50.0, parse(0x01, 0x06, 0x40)!!, 1e-9)
    }

    @Test
    fun `idle and wide-open-throttle land in physically sane ranges`() {
        val idle = parse(0x01, 0x00, 0x60)!!          // 96/32 = 3 g/s
        val wot = parse(0x01, 0x0F, 0xA0)!!           // 4000/32 = 125 g/s
        assertEquals(3.0, idle, 1e-9)
        assertEquals(125.0, wot, 1e-9)
    }

    @Test
    fun `returns null when the bitmap says sensor A is absent`() {
        // bit0 clear -> no sensor A, even though B/C carry bytes.
        assertNull(parse(0x02, 0x06, 0x40))
        assertNull(parse(0x00, 0x06, 0x40))
    }

    @Test
    fun `returns null on a truncated response rather than mis-decoding`() {
        assertNull(parse(0x01))
        assertNull(parse(0x01, 0x06))
        assertNull(parse())
    }

    @Test
    fun `is polled under its own metric name so it cannot double-count`() {
        // Sharing `maf_air_flow` with PID 0x10 would put two independent
        // sample streams in one metric and double the integrated burn.
        assertEquals("maf_sensor_a", Pids.MafSensorA.name)
        assertEquals(0x66, Pids.MafSensorA.pid)
        assert(Pids.DEFAULT.contains(Pids.MafSensorA))
        assert(Pids.DEFAULT.contains(Pids.MafAirFlow))
        assertEquals(
            2,
            Pids.DEFAULT.count { it.name == "maf_air_flow" || it.name == "maf_sensor_a" },
        )
    }
}
