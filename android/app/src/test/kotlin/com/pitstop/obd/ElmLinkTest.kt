package com.pitstop.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays the 2026-10-21 desync (v0.1.254, firmware 6, extended PIDs on).
 * See [ElmFramer] / [ElmEcho] KDoc for the root cause.
 */
class ElmLinkTest {

    /** A 59-byte 222615 answer in ELM's ATH0 numbered form, cylinders = 3. */
    private val cylAnswer: String = run {
        val p = ByteArray(59)
        p[0] = 0x62; p[1] = 0x26; p[2] = 0x15; p[53] = 3
        val hex = p.map { "%02X".format(it) }
        val sb = StringBuilder("222615\r03B\r")
        sb.append("0: ").append(hex.subList(0, 6).joinToString(" ")).append("\r")
        var i = 6
        var n = 1
        while (i < hex.size) {
            sb.append("%X: ".format(n and 0xF)).append(hex.subList(i, minOf(i + 7, hex.size)).joinToString(" ")).append("\r")
            i += 7
            n++
        }
        sb.append("\r>").toString()
    }

    @Test
    fun `a notification carrying two answers yields two frames`() {
        val f = ElmFramer()
        val frames = f.feed("ATSH18DA10F1\rOK\r\r>ATCRA18DAF110\rOK\r\r>")
        assertEquals(2, frames.size)
        assertTrue(frames[0].contains("ATSH18DA10F1"))
        assertTrue(frames[1].contains("ATCRA18DAF110"))
        assertEquals(0, f.pendingLength)
    }

    @Test
    fun `a multi-frame answer split across notifications is one frame`() {
        val f = ElmFramer()
        val chunks = cylAnswer.chunked(20) // MTU 23 → 20-byte notifies
        val frames = chunks.flatMap { f.feed(it) }
        assertEquals(1, frames.size)
        val payload = IsoTp.reassemble(frames[0])
        assertNotNull(payload)
        assertEquals(59, payload!!.size)
        assertEquals(3.0, HondaMode22.decodeActiveCylinders(payload)!!, 0.0)
    }

    @Test
    fun `the logged symptom - the first numbered line parses as Mode 22 'pid 26' with 4 bytes`() {
        // What v0.1.254 logged as "obd response for unpolled pid 2226 / 4":
        // the extended request had already been retired on a stale prompt,
        // so its real answer fell through to the Mode 01 parser.
        val f = ObdResponseParser.parse("0: 62 26 15 00 00 00".toByteArray())!!
        assertEquals(0x22, f.mode)
        assertEquals(0x26, f.pid)
        assertEquals(4, f.data.size)
    }

    @Test
    fun `a late prompt of an earlier command does not release the current one`() {
        val recent = listOf("010C", "ATSH18DA10F1", "ATCRA18DAF110", "222615")
        // Replay: the standard PID's answer was still streaming when the
        // header change went out; its prompt arrives while we wait on ATSH.
        assertFalse(ElmEcho.answers("010C\r41 0C 1A F8\r", "ATSH18DA10F1", recent))
        assertTrue(ElmEcho.answers("ATSH18DA10F1\rOK\r", "ATSH18DA10F1", recent))
        // ATSH's prompt must not release ATCRA, nor ATCRA's the request.
        assertFalse(ElmEcho.answers("ATSH18DA10F1\rOK\r", "ATCRA18DAF110", recent))
        assertFalse(ElmEcho.answers("ATCRA18DAF110\rOK\r", "222615", recent))
        // The request is released by its own (multi-frame) answer only.
        assertTrue(ElmEcho.answers(cylAnswer.removeSuffix(">"), "222615", recent))
    }

    @Test
    fun `echo off or junk before the echo still matches`() {
        assertTrue(ElmEcho.answers("41 0C 1A F8\r", "010C", listOf("010D")))
        assertTrue(ElmEcho.answers("{\"autopid\":1}junkATI\rELM327 v2.3\r", "ATI", emptyList()))
    }

    @Test
    fun `STOPPED and question mark are desync signals`() {
        assertTrue(ElmEcho.isDesync("223086\rSTOPPED\r"))
        assertTrue(ElmEcho.isDesync("?\r"))
        assertFalse(ElmEcho.isDesync("010D\r41 0D 32\r"))
        assertFalse(ElmEcho.isDesync("0100\rNO DATA\r"))
    }

    @Test
    fun `an unsolicited flood with no prompt is dropped, not grown`() {
        val f = ElmFramer(maxBuffer = 100)
        repeat(10) { f.feed("x".repeat(20)) }
        assertTrue(f.pendingLength <= 100)
        assertEquals(listOf("ATI\rELM327"), f.feed("ATI\rELM327>").map { it.takeLast(10) })
    }
}
