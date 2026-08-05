package com.pitstop.obd

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ISO-TP reassembly. Every byte offset the Mode 22 decoders use is measured
 * against the reassembled payload, so a reassembly that is off by one byte —
 * or that quietly tolerates a dropped Consecutive Frame — turns "gear 2" into
 * "gear 15" with no visible error. These tests exist mostly to pin the
 * failure-is-null contract.
 */
class IsoTpTest {

    private fun bytes(vararg v: Int): ByteArray = v.map { it.toByte() }.toByteArray()

    /** Build a realistic 29-bit ELM response block for a payload. */
    private fun framesFor(payload: ByteArray, pad: Int = 0x55): String {
        val lines = mutableListOf<String>()
        fun hex(b: Int) = "%02X".format(b and 0xFF)
        val head = "18 DA F1 1E"
        // First Frame: 1L LL + 6 data bytes.
        val ff = StringBuilder(head)
        ff.append(" ").append(hex(0x10 or ((payload.size shr 8) and 0x0F)))
        ff.append(" ").append(hex(payload.size and 0xFF))
        for (i in 0 until 6) ff.append(" ").append(hex(payload.getOrElse(i) { pad.toByte() }.toInt()))
        lines.add(ff.toString())
        // Consecutive Frames: 2N + 7 data bytes, padded to a full CAN frame.
        var idx = 6
        var seq = 1
        while (idx < payload.size) {
            val cf = StringBuilder(head)
            cf.append(" ").append(hex(0x20 or (seq and 0x0F)))
            for (i in 0 until 7) {
                cf.append(" ").append(hex(payload.getOrElse(idx + i) { pad.toByte() }.toInt()))
            }
            lines.add(cf.toString())
            idx += 7
            seq += 1
        }
        return lines.joinToString("\r") + "\r\r>"
    }

    @Test
    fun `reassembles a realistic multi-frame ATF response`() {
        // 24-byte payload: 62 30 83 <data...> with the ATF byte at index 17.
        // 0x65 = 101 -> 101 - 40 = 61 degC, the value measured on the vehicle.
        val text =
            "18 DA F1 1E 10 18 62 30 83 00 00 00\r" +
                "18 DA F1 1E 21 00 00 00 00 00 00 00\r" +
                "18 DA F1 1E 22 00 00 00 00 65 00 00\r" +
                "18 DA F1 1E 23 00 00 00 00 55 55 55\r>"
        val payload = IsoTp.reassemble(text)!!
        // Declared length 0x018 = 24, so the trailing 55 55 55 CAN padding in
        // the last frame is trimmed rather than passed along as data.
        assertEquals(24, payload.size)
        assertEquals(0x62, payload[0].toInt() and 0xFF)
        assertEquals(0x30, payload[1].toInt() and 0xFF)
        assertEquals(0x83, payload[2].toInt() and 0xFF)
        assertEquals(0x65, payload[17].toInt() and 0xFF)
    }

    @Test
    fun `round-trips an arbitrary payload through first plus consecutive frames`() {
        val payload = ByteArray(32) { i -> ((i * 7) and 0xFF).toByte() }
        payload[0] = 0x62.toByte()
        payload[1] = 0x30.toByte()
        payload[2] = 0x86.toByte()
        assertArrayEquals(payload, IsoTp.reassemble(framesFor(payload)))
    }

    @Test
    fun `handles frames with no spaces and no CAN id prefix`() {
        val text = "1018623083000000\r" +
            "2100000000000000\r" +
            "2200000000650000\r" +
            "2300000000555555\r>"
        val payload = IsoTp.reassemble(text)!!
        assertEquals(24, payload.size)
        assertEquals(0x65, payload[17].toInt() and 0xFF)
    }

    @Test
    fun `reads a single-frame response`() {
        // 06 62 30 83 01 02 03 -> six payload bytes.
        val payload = IsoTp.reassemble("18 DA F1 1E 06 62 30 83 01 02 03\r>")!!
        assertArrayEquals(bytes(0x62, 0x30, 0x83, 0x01, 0x02, 0x03), payload)
    }

    @Test
    fun `reads ELM's numbered multiline form`() {
        // ATH0 + auto-format: the adapter strips the PCI itself and prints the
        // total length followed by index-prefixed lines.
        val text = "018\r0: 62 30 83 00 00 00\r1: 00 00 00 00 00 00 00\r" +
            "2: 00 00 00 00 65 00 00\r3: 00 00 00 00 00 00 00\r>"
        val payload = IsoTp.reassemble(text)!!
        assertEquals(24, payload.size)
        assertEquals(0x65, payload[17].toInt() and 0xFF)
    }

    @Test
    fun `returns null when a consecutive frame is missing`() {
        // Dropping CF 22 would shift every byte after index 12 by seven —
        // exactly the mis-decode this has to refuse.
        val text =
            "18 DA F1 1E 10 18 62 30 83 00 00 00\r" +
                "18 DA F1 1E 21 00 00 00 00 00 00 00\r" +
                "18 DA F1 1E 23 00 00 00 00 55 55 55\r>"
        assertNull(IsoTp.reassemble(text))
    }

    @Test
    fun `returns null when consecutive frames arrive out of order`() {
        val text =
            "18 DA F1 1E 10 18 62 30 83 00 00 00\r" +
                "18 DA F1 1E 22 00 00 00 00 65 00 00\r" +
                "18 DA F1 1E 21 00 00 00 00 00 00 00\r>"
        assertNull(IsoTp.reassemble(text))
    }

    @Test
    fun `ignores a command echo that looks like a consecutive frame`() {
        // ATE1 echoes "223083", which tokenises to 22 30 83 — byte-for-byte a
        // CF with sequence 2. Anything before the First Frame is ignored, and
        // the sequence check would catch it afterwards.
        val text = "223083\r" +
            "18 DA F1 1E 10 18 62 30 83 00 00 00\r" +
            "18 DA F1 1E 21 00 00 00 00 00 00 00\r" +
            "18 DA F1 1E 22 00 00 00 00 65 00 00\r" +
            "18 DA F1 1E 23 00 00 00 00 00 00 00\r>"
        val payload = IsoTp.reassemble(text)!!
        assertEquals(0x62, payload[0].toInt() and 0xFF)
        assertEquals(0x65, payload[17].toInt() and 0xFF)
    }

    @Test
    fun `returns null for adapter status strings and junk`() {
        assertNull(IsoTp.reassemble("NO DATA\r>"))
        assertNull(IsoTp.reassemble("STOPPED\r>"))
        assertNull(IsoTp.reassemble("UNABLE TO CONNECT\r>"))
        assertNull(IsoTp.reassemble("SEARCHING...\r>"))
        assertNull(IsoTp.reassemble("?\r>"))
        assertNull(IsoTp.reassemble(""))
        assertNull(IsoTp.reassemble("   \r\r>"))
    }

    @Test
    fun `leaves a standard Mode 01 single-frame response for the normal parser`() {
        // "41 0C 1A F8" has no PCI byte at all, so there is nothing here to
        // reassemble — the extended path must decline it and fall through.
        assertNull(IsoTp.reassemble("41 0C 1A F8\r>"))
    }

    @Test
    fun `keeps a device-truncated block instead of padding it out`() {
        // The adapter stops reassembling at ~34 payload bytes: the First Frame
        // declares more than actually arrives. Keep what came, let the
        // decoders return null if their offset didn't make it.
        val text =
            "18 DA F1 1E 10 40 62 30 86 00 00 00\r" +
                "18 DA F1 1E 21 00 00 00 00 00 00 00\r>"
        val payload = IsoTp.reassemble(text)!!
        assertEquals(13, payload.size) // 6 + 7, far short of the declared 0x40
    }
}
