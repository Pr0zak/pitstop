package com.pitstop.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mode 22 PIDs on the ZF 9HP transmission controller.
 *
 * Two things these tests are really guarding:
 *  1. The byte offsets. They were measured on the vehicle against the
 *     reassembled ISO-TP payload (0x62 at index 0), and every off-by-one lands
 *     on a neighbouring byte that also holds a plausible-looking number.
 *  2. That a short payload decodes to null. The adapter truncates its own
 *     reassembly at ~34 bytes; a decoder that reached past the end and got a
 *     zero would publish "-40 degC" and "Park" all day without complaint.
 */
class ZfTcmPidsTest {

    /** A payload of [size] bytes with a correct 62 + DID echo header. */
    private fun payload(size: Int, did: Int, vararg at: Pair<Int, Int>): ByteArray {
        val p = ByteArray(size)
        if (size > 0) p[0] = 0x62
        if (size > 1) p[1] = ((did shr 8) and 0xFF).toByte()
        if (size > 2) p[2] = (did and 0xFF).toByte()
        for ((idx, value) in at) p[idx] = value.toByte()
        return p
    }

    // ---- ATF temperature (223083, payload[17], degC = byte - 40) ----------

    @Test
    fun `decodes the ATF temperature measured on the vehicle`() {
        // Live reading was 61 degC, i.e. raw 0x65 = 101.
        val p = payload(24, ZfTcm.ATF_DID, 17 to 0x65)
        assertEquals(61.0, ZfTcm.decodeAtfTempC(p)!!, 1e-9)
        assertEquals(61.0, ZfTcm.AtfTemp.parser(p)!!, 1e-9)
    }

    @Test
    fun `applies the -40 offset across the range including sub-zero`() {
        assertEquals(-40.0, ZfTcm.decodeAtfTempC(payload(24, ZfTcm.ATF_DID, 17 to 0x00))!!, 1e-9)
        assertEquals(0.0, ZfTcm.decodeAtfTempC(payload(24, ZfTcm.ATF_DID, 17 to 40))!!, 1e-9)
        // A cold-soak winter start: 0x14 = 20 -> -20 degC.
        assertEquals(-20.0, ZfTcm.decodeAtfTempC(payload(24, ZfTcm.ATF_DID, 17 to 0x14))!!, 1e-9)
        // Hot towing: 0x96 = 150 -> 110 degC.
        assertEquals(110.0, ZfTcm.decodeAtfTempC(payload(24, ZfTcm.ATF_DID, 17 to 0x96))!!, 1e-9)
    }

    @Test
    fun `reads index 17 of the payload, not of the data after the DID echo`() {
        // Offsets are payload-absolute: index 0 is the 0x62 response byte.
        // Put a decoy three bytes earlier (where a "skip 62 + DID" reading
        // would land) and confirm it is not what comes back.
        val p = payload(24, ZfTcm.ATF_DID, 17 to 0x65, 20 to 0x7D)
        assertEquals(61.0, ZfTcm.decodeAtfTempC(p)!!, 1e-9)
    }

    @Test
    fun `returns null for a truncated ATF payload instead of mis-decoding`() {
        assertNull(ZfTcm.decodeAtfTempC(payload(17, ZfTcm.ATF_DID))) // one byte short
        assertNull(ZfTcm.decodeAtfTempC(payload(10, ZfTcm.ATF_DID)))
        assertNull(ZfTcm.decodeAtfTempC(ByteArray(0)))
        assertEquals(18, ZfTcm.ATF_PAYLOAD_INDEX + 1) // shortest payload that decodes
    }

    // ---- gear position (223086, payload[23]) ------------------------------

    @Test
    fun `decodes the validated D to R to N to D shift sequence`() {
        // Recorded on the vehicle: 2 -> 15 -> 0 -> 2, 4/4.
        val observed = listOf(2, 15, 0, 2)
        val decoded = observed.map {
            ZfTcm.decodeGearPosition(payload(32, ZfTcm.GEAR_DID, 23 to it))
        }
        assertEquals(listOf(2.0, 15.0, 0.0, 2.0), decoded)
    }

    @Test
    fun `stopped in Drive reads 2 because the 9HP launches in second`() {
        // Not a bug and not something to "correct" to 1 — the ZF 9HP starts in
        // second gear, so a stationary car in D reports 2.
        val p = payload(32, ZfTcm.GEAR_DID, 23 to 2)
        assertEquals(2.0, ZfTcm.decodeGearPosition(p)!!, 1e-9)
    }

    @Test
    fun `zero is Park or Neutral and stays ambiguous`() {
        // The TCM cannot distinguish the two here, so 0 is reported as 0
        // rather than guessed into one of them.
        assertEquals(0.0, ZfTcm.decodeGearPosition(payload(32, ZfTcm.GEAR_DID, 23 to 0))!!, 1e-9)
    }

    @Test
    fun `fifteen is Reverse`() {
        assertEquals(15.0, ZfTcm.decodeGearPosition(payload(32, ZfTcm.GEAR_DID, 23 to 15))!!, 1e-9)
        assertEquals(15, ZfTcm.GEAR_REVERSE)
    }

    @Test
    fun `decodes every forward gear the 9HP has`() {
        for (g in 1..9) {
            val p = payload(32, ZfTcm.GEAR_DID, 23 to g)
            assertEquals(g.toDouble(), ZfTcm.decodeGearPosition(p)!!, 1e-9)
        }
    }

    @Test
    fun `returns null for gear codes with no known meaning`() {
        // 10..14 and >15 have never been observed. Publishing them as a gear
        // number would put a value on the chart that means nothing.
        for (raw in listOf(10, 11, 14, 16, 200, 255)) {
            assertNull(ZfTcm.decodeGearPosition(payload(32, ZfTcm.GEAR_DID, 23 to raw)))
        }
    }

    @Test
    fun `returns null for a truncated gear payload instead of mis-decoding`() {
        assertNull(ZfTcm.decodeGearPosition(payload(23, ZfTcm.GEAR_DID))) // one byte short
        assertNull(ZfTcm.decodeGearPosition(payload(18, ZfTcm.GEAR_DID))) // long enough for ATF, not gear
        assertNull(ZfTcm.decodeGearPosition(ByteArray(0)))
    }

    // ---- end to end: wire text -> metric ---------------------------------

    @Test
    fun `decodes ATF straight from a raw multi-frame response block`() {
        val text =
            "18 DA F1 1E 10 18 62 30 83 00 00 00\r" +
                "18 DA F1 1E 21 00 00 00 00 00 00 00\r" +
                "18 DA F1 1E 22 00 00 00 00 65 00 00\r" +
                "18 DA F1 1E 23 00 00 00 00 55 55 55\r>"
        val p = IsoTp.reassemble(text)!!
        assertTrue(ZfTcm.AtfTemp.matchesDidEcho(p))
        assertEquals(61.0, ZfTcm.AtfTemp.parser(p)!!, 1e-9)
    }

    @Test
    fun `decodes gear straight from a raw multi-frame response block`() {
        // 32-byte payload, gear byte 0x0F (Reverse) at index 23 -> CF 23's
        // fourth data byte.
        val text =
            "18 DA F1 1E 10 20 62 30 86 00 00 00\r" +
                "18 DA F1 1E 21 00 00 00 00 00 00 00\r" +
                "18 DA F1 1E 22 00 00 00 00 00 00 00\r" +
                "18 DA F1 1E 23 00 00 00 0F 00 00 00\r" +
                "18 DA F1 1E 24 00 00 00 00 00 55 55\r>"
        val p = IsoTp.reassemble(text)!!
        assertEquals(32, p.size)
        assertTrue(ZfTcm.GearPosition.matchesDidEcho(p))
        assertEquals(15.0, ZfTcm.GearPosition.parser(p)!!, 1e-9)
    }

    @Test
    fun `a response echoing a different DID is not attributed to this PID`() {
        // The offsets are DID-specific; decoding 3086's answer with 3083's
        // offsets would be a silent lie.
        val p = payload(32, ZfTcm.GEAR_DID, 23 to 2)
        assertFalse(ZfTcm.AtfTemp.matchesDidEcho(p))
        assertTrue(ZfTcm.GearPosition.matchesDidEcho(p))
    }

    // ---- wiring / opt-in guarantees --------------------------------------

    @Test
    fun `extended PIDs are never in the default poll set`() {
        assertFalse(Pids.DEFAULT.contains(ZfTcm.AtfTemp))
        assertFalse(Pids.DEFAULT.contains(ZfTcm.GearPosition))
        assertTrue(Pids.DEFAULT.none { it.isExtended })
        assertEquals(listOf(ZfTcm.AtfTemp, ZfTcm.GearPosition), Pids.EXTENDED)
    }

    @Test
    fun `publish under the agreed metric names`() {
        assertEquals("atf_temp", ZfTcm.AtfTemp.name)
        assertEquals("gear_position", ZfTcm.GearPosition.name)
    }

    @Test
    fun `request the measured Mode 22 DIDs on the TCM header`() {
        assertEquals("223083\r", ZfTcm.AtfTemp.command())
        assertEquals("223086\r", ZfTcm.GearPosition.command())
        assertEquals(listOf("ATSH18DA1EF1"), ZfTcm.AtfTemp.initCommands)
        assertEquals(listOf("ATSH18DA1EF1"), ZfTcm.GearPosition.initCommands)
        assertTrue(ZfTcm.AtfTemp.isoTp)
        assertTrue(ZfTcm.GearPosition.isoTp)
        assertTrue(ZfTcm.AtfTemp.changesSessionHeader)
        assertTrue(ZfTcm.GearPosition.changesSessionHeader)
    }

    @Test
    fun `poll slowly enough to stay cheap on the bus`() {
        // Fluid temperature moves over minutes; gear is a state trace, not a
        // shift-quality instrument.
        assertEquals(10_000L, ZfTcm.AtfTemp.periodMs)
        assertEquals(2_000L, ZfTcm.GearPosition.periodMs)
    }

    @Test
    fun `the default-header restore is two SEPARATE ELM commands`() {
        // ATSH7DF (broadcast TX header) + ATCRA (reset the receive filter).
        // A header is sticky for the whole session, so these are what stop one
        // Mode 22 poll from breaking every standard PID after it.
        //
        // They must stay separate: an ELM327 parses one command per
        // CR-terminated line, so "ATSH7DFATCRA" is parsed as SH with the
        // argument "7DFATCRA" — not 3/6/8 hex digits ('T' and 'R' aren't hex)
        // — which answers "?" and leaves the header exactly where it was.
        assertEquals(listOf("ATSH7DF", "ATCRA"), ElmSession.DEFAULT_HEADER_RESTORE)
        for (cmd in ElmSession.DEFAULT_HEADER_RESTORE) {
            assertTrue("$cmd must be a single AT command", cmd.startsWith("AT"))
            assertEquals(
                "$cmd must not chain a second AT command onto one line",
                2,
                cmd.split("AT").size,
            )
        }
    }

    @Test
    fun `standard PIDs are untouched by the model change`() {
        // Purely additive: no existing PID declares init commands, ISO-TP or a
        // request override, so all of them still format and parse exactly as
        // they did.
        for (p in Pids.DEFAULT) {
            assertTrue(p.initCommands.isEmpty())
            assertFalse(p.isoTp)
            assertNull(p.request)
            assertFalse(p.isExtended)
        }
        assertEquals("010C\r", Pids.EngineRpm.command())
        assertEquals("0142\r", Pids.ControlModuleVoltage.command())
    }
}
