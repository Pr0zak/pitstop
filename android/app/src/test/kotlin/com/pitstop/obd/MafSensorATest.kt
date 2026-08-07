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
    fun `exactly one airflow PID is polled, under the canonical name`() {
        // The invariant this guards is the COUNT: two airflow PIDs in the
        // poll list would put two independent sample streams into one metric
        // and double the integrated burn.
        //
        // It used to guard that by giving 0x66 its own name, `maf_sensor_a`.
        // That "fix" was worse than the problem: every consumer — the Live
        // tile, the car tile, the backend's _TRIP_SAMPLE_METRICS — reads
        // `maf_air_flow`, so the same physical sensor was invisible whenever
        // it arrived over BLE and visible when it arrived from the WiCAN over
        // WiFi. The server already canonicalises the dongle's 66-MAFSensorA
        // to `maf_air_flow`; the phone is the one that was out of step.
        //
        // Distinct names were never what prevented double-counting. Polling
        // only one of them is.
        assertEquals("maf_air_flow", Pids.MafSensorA.name)
        assertEquals(0x66, Pids.MafSensorA.pid)
        assert(Pids.DEFAULT.contains(Pids.MafSensorA))
        assertEquals(
            1,
            Pids.DEFAULT.count { it.name == "maf_air_flow" },
        )
    }

    @Test
    fun `no two polled PIDs share a metric name`() {
        // Generalises the rule above to the whole list, now that it carries
        // 24 PIDs rather than 15: any duplicate name silently merges two
        // streams into one metric, which is a data-integrity bug rather than
        // a display one and would not show up as an empty tile.
        val dupes = Pids.DEFAULT.groupBy { it.name }.filterValues { it.size > 1 }
        assertEquals(emptyMap<String, List<Pid>>(), dupes)
    }

    @Test
    fun `0x10 is defined but not polled - measured unsupported on this PCM`() {
        // Live-probed 2026-07-31: this PCM does not advertise support for
        // 0x10 and never answers it, so polling it burned a round-robin
        // slot per cycle for a guaranteed NO DATA. 0x66 is the airflow
        // source that answers. The definition stays for other vehicles.
        assertEquals("maf_air_flow", Pids.MafAirFlow.name)
        assertEquals(0x10, Pids.MafAirFlow.pid)
        assert(!Pids.DEFAULT.contains(Pids.MafAirFlow))
    }
}
