package com.pitstop.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Honda Mode 22 decoders (WiCAN firmware 6 profile, 2026-10-21). Offsets are
 * payload-absolute (payload[0] = 0x62, [1..2] = DID echo), and every test
 * puts a decoy next to the real byte so an off-by-one fails loudly.
 */
class HondaMode22Test {

    private fun payload(size: Int, did: Int, vararg at: Pair<Int, Int>): ByteArray {
        val p = ByteArray(size)
        p[0] = 0x62
        p[1] = ((did shr 8) and 0xFF).toByte()
        p[2] = (did and 0xFF).toByte()
        for ((i, v) in at) p[i] = v.toByte()
        return p
    }

    // ---- module 10 (PCM) ---------------------------------------------------

    @Test
    fun `active cylinders reads payload 53`() {
        assertEquals(6.0, HondaMode22.decodeActiveCylinders(payload(59, 0x2615, 53 to 6, 52 to 9, 54 to 9))!!, 0.0)
        assertEquals(3.0, HondaMode22.decodeActiveCylinders(payload(59, 0x2615, 53 to 3))!!, 0.0)
    }

    @Test
    fun `active cylinders is null when truncated or meaningless`() {
        // The 4.x ~34-byte cliff: byte 53 simply isn't there.
        assertNull(HondaMode22.decodeActiveCylinders(payload(34, 0x2615)))
        assertNull(HondaMode22.decodeActiveCylinders(payload(53, 0x2615)))
        assertNull(HondaMode22.decodeActiveCylinders(payload(59, 0x2615, 53 to 0)))
        assertNull(HondaMode22.decodeActiveCylinders(payload(59, 0x2615, 53 to 7)))
    }

    @Test
    fun `misfires are big-endian pairs at 35+2n`() {
        // cyl 1 = [37..38], cyl 6 = [47..48]
        val p = payload(
            49, 0x2663,
            37 to 0x00, 38 to 0x05, // 5
            39 to 0x01, 40 to 0x00, // 256
            47 to 0x00, 48 to 0x02, // 2
            36 to 0x7F, // decoy just before cyl 1
        )
        val m = HondaMode22.decodeMisfires(p)
        assertEquals(6, m.size)
        assertEquals(5.0, m["misfire_count_1"]!!, 0.0)
        assertEquals(256.0, m["misfire_count_2"]!!, 0.0)
        assertEquals(0.0, m["misfire_count_3"]!!, 0.0)
        assertEquals(2.0, m["misfire_count_6"]!!, 0.0)
    }

    @Test
    fun `misfires publish nothing when the block is short`() {
        assertTrue(HondaMode22.decodeMisfires(payload(48, 0x2663)).isEmpty())
    }

    // ---- module 60 (instrument cluster) -------------------------------------

    @Test
    fun `dash odometer is a 24-bit big-endian mile count at 9-11`() {
        // 80,545 mi = 0x013AA1
        val p = payload(24, 0x7022, 9 to 0x01, 10 to 0x3A, 11 to 0xA1, 8 to 0xFF, 12 to 0xFF)
        assertEquals(80_545.0, HondaMode22.decodeOdometerMi(p)!!, 0.0)
        assertNull(HondaMode22.decodeOdometerMi(payload(11, 0x7022)))
    }

    @Test
    fun `cluster fuel is 15-16 over 100 litres`() {
        // 47.05 L = 4705 = 0x1261
        val p = payload(24, 0x7029, 15 to 0x12, 16 to 0x61, 14 to 0xFF, 17 to 0xFF)
        assertEquals(47.05, HondaMode22.decodeFuelLitres(p)!!, 1e-9)
        assertNull(HondaMode22.decodeFuelLitres(payload(16, 0x7029)))
    }

    @Test
    fun `outside temp is a 12-bit signed Fahrenheit value`() {
        // 76 °F, high nibble garbage in byte 19 must be masked off.
        assertEquals(76.0, HondaMode22.decodeAmbientF(payload(24, 0x7028, 19 to 0xA0, 20 to 76))!!, 0.0)
        // -5 °F = 0xFFB (12-bit two's complement)
        assertEquals(-5.0, HondaMode22.decodeAmbientF(payload(24, 0x7028, 19 to 0x0F, 20 to 0xFB))!!, 0.0)
        assertNull(HondaMode22.decodeAmbientF(payload(20, 0x7028)))
    }

    // ---- module 26 (TPMS) -----------------------------------------------------

    @Test
    fun `tpms decodes the verified 2026-10-21 corners`() {
        // FR 196 / FL 213 / RR 205 / RL 209 kPa (dash 28 / 31 / 30 / 30 psi).
        val p = payload(
            40, 0x6001,
            11 to 0, 12 to 196,
            13 to 0, 14 to 213,
            15 to 0, 16 to 205,
            17 to 0, 18 to 209,
            27 to 21, 28 to 22, 29 to 23, 30 to 0xFE, // RL = -2 °C (signed)
        )
        val m = HondaMode22.decodeTpms(p)
        assertEquals(196.0, m["tire_pressure_fr"]!!, 0.0)
        assertEquals(213.0, m["tire_pressure_fl"]!!, 0.0)
        assertEquals(205.0, m["tire_pressure_rr"]!!, 0.0)
        assertEquals(209.0, m["tire_pressure_rl"]!!, 0.0)
        assertEquals(21.0, m["tire_temp_fr"]!!, 0.0)
        assertEquals(22.0, m["tire_temp_fl"]!!, 0.0)
        assertEquals(23.0, m["tire_temp_rr"]!!, 0.0)
        assertEquals(-2.0, m["tire_temp_rl"]!!, 0.0)
    }

    @Test
    fun `tpms skips a sensor that answered 0xFFFF and refuses a short block`() {
        val p = payload(31, 0x6001, 11 to 0xFF, 12 to 0xFF, 13 to 0, 14 to 213)
        val m = HondaMode22.decodeTpms(p)
        assertFalse(m.containsKey("tire_pressure_fr"))
        assertEquals(213.0, m["tire_pressure_fl"]!!, 0.0)
        assertTrue(HondaMode22.decodeTpms(payload(30, 0x6001)).isEmpty())
    }

    @Test
    fun `tpms decodes end to end from raw 29-bit frames`() {
        // 40-byte payload: FF 10 28, then 5 CFs. FL low byte at payload[14].
        val body = payload(
            40, 0x6001,
            11 to 0, 12 to 196, 13 to 0, 14 to 213, 15 to 0, 16 to 205, 17 to 0, 18 to 209,
            27 to 21, 28 to 22, 29 to 23, 30 to 24,
        )
        val frames = StringBuilder()
        frames.append("18 DA F1 26 10 28 ").append(body.copyOfRange(0, 6).hex()).append("\r")
        var i = 6
        var seq = 1
        while (i < body.size) {
            val chunk = body.copyOfRange(i, minOf(i + 7, body.size))
            frames.append("18 DA F1 26 2%X ".format(seq)).append(chunk.hex()).append("\r")
            i += 7
            seq = (seq + 1) and 0x0F
        }
        frames.append(">")
        val p = IsoTp.reassemble(frames.toString())!!
        assertTrue(HondaMode22.Tpms.matchesDidEcho(p))
        assertEquals(8, HondaMode22.Tpms.decodeAll(p).size)
    }

    private fun ByteArray.hex() = joinToString(" ") { "%02X".format(it) }

    // ---- wiring ---------------------------------------------------------------

    @Test
    fun `every request sets the module header and receive filter on separate lines`() {
        assertEquals(listOf("ATSH18DA10F1", "ATCRA18DAF110"), HondaMode22.ActiveCylinders.initCommands)
        assertEquals(listOf("ATSH18DA60F1", "ATCRA18DAF160"), HondaMode22.OdometerDash.initCommands)
        assertEquals(listOf("ATSH18DA26F1", "ATCRA18DAF126"), HondaMode22.Tpms.initCommands)
        assertEquals("222615\r", HondaMode22.ActiveCylinders.command())
        assertEquals("222663\r", HondaMode22.Misfires.command())
        assertEquals("227022\r", HondaMode22.OdometerDash.command())
        assertEquals("227029\r", HondaMode22.FuelLevelLitres.command())
        assertEquals("227028\r", HondaMode22.AmbientAirTempF.command())
        assertEquals("226001\r", HondaMode22.Tpms.command())
        for (p in HondaMode22.ALL) {
            assertTrue(p.isExtended)
            assertTrue(p.isoTp)
        }
    }

    @Test
    fun `cadences match the plan`() {
        assertEquals(2_000L, ZfTcm.GearPosition.periodMs)
        assertEquals(2_000L, HondaMode22.ActiveCylinders.periodMs)
        for (p in listOf(ZfTcm.AtfTemp, HondaMode22.OdometerDash, HondaMode22.FuelLevelLitres,
            HondaMode22.AmbientAirTempF, HondaMode22.Tpms)) {
            assertEquals(p.name, 30_000L, p.periodMs)
        }
        assertEquals(60_000L, HondaMode22.Misfires.periodMs)
    }

    @Test
    fun `published metric names are the canonical server names`() {
        assertEquals("active_cylinders", HondaMode22.ActiveCylinders.name)
        assertEquals("odometer_dash", HondaMode22.OdometerDash.name)
        assertEquals("fuel_level_l", HondaMode22.FuelLevelLitres.name)
        assertEquals("ambient_air_temp_f", HondaMode22.AmbientAirTempF.name)
        val names = HondaMode22.ALL.map { it.name }
        assertEquals(names.size, names.toSet().size)
    }
}
