package com.pitstop.obd

/**
 * Pure pieces of the phone's ELM327 conversation over the WiCAN's raw BLE
 * bridge, split out so the 2026-10-21 desync is unit-tested.
 *
 * ### What went wrong (v0.1.254, firmware 6, extended PIDs on)
 * Within ten seconds of enabling Mode 22 polling the session fell apart:
 * Mode 22 answers logged as "response for unpolled pid 2226/2230/2260/2270",
 * then `?` and `STOPPED` on Mode 22 AND standard PIDs. Three faults stacked:
 *
 *  1. **Nothing waited for the previous command to finish.** The standard
 *     round-robin wrote a PID every ≥50 ms without waiting for its `>`. A
 *     real ELM327 (firmware 6 bridges to the actual chip) aborts the current
 *     answer with `STOPPED` when any byte arrives, and a command that lands
 *     mid-answer is mangled (`?`). The 4.x WiCAN emulation queued commands,
 *     which hid this.
 *  2. **Prompts were counted, not matched.** The Mode 22 path waited for
 *     "the next `>`", so the late prompt of the standard PID written just
 *     before (or of an aborted command) was taken as the ATSH ack, the ATSH
 *     prompt as the ATCRA ack, and so on: every step was one prompt early.
 *     The request's wait then ended on the wrong prompt, the extended request
 *     was retired as unanswered, and when its real answer arrived it fell
 *     through to the Mode 01 parser — `0: 62 26 15 xx xx xx` parses as mode
 *     0x22, "pid" 0x26, four data bytes: exactly the logged `2226 / 4`.
 *  3. **One frame per notification.** The rx path took only the first `>`
 *     out of each BLE notification; with a 247-byte MTU one notification
 *     can carry two answers, so the second waited for the next notify and
 *     the prompt count lagged further.
 *
 * The fix is a strict one-command-at-a-time link (the service's `elmSend`):
 * write, then wait for the prompt of THAT command — recognised by its echo
 * ([ElmEcho]) — before anything else is written; and a framer that yields
 * every complete frame in a notification ([ElmFramer]).
 */
class ElmFramer(private val maxBuffer: Int = 4096) {
    private val buf = StringBuilder()

    /**
     * Feed one BLE notification. Returns every frame completed by it, in
     * order — the text before each `>` (prompt excluded). A runaway buffer
     * with no prompt (unsolicited flood) is dropped rather than grown.
     */
    fun feed(bytes: ByteArray): List<String> = feed(String(bytes, Charsets.US_ASCII))

    fun feed(text: String): List<String> {
        buf.append(text)
        val out = ArrayList<String>(2)
        while (true) {
            val i = buf.indexOf('>')
            if (i < 0) break
            out += buf.substring(0, i)
            buf.delete(0, i + 1)
        }
        if (buf.length > maxBuffer) buf.clear()
        return out
    }

    /** Bytes received since the last prompt. */
    val pendingLength: Int get() = buf.length

    fun clear() = buf.clear()
}

/**
 * Decide whether a completed frame is the answer to the command we're
 * waiting on, using the ELM's echo (on by default — `ATE1`).
 */
object ElmEcho {
    private fun norm(s: String) = s.replace(" ", "").trim().uppercase()

    /**
     * True when [frame] answers [command]:
     *  - its echo line is [command] → yes;
     *  - its echo line is a DIFFERENT command we sent recently ([recent]) →
     *    no: that is a late prompt of an earlier command, and taking it
     *    would put every following step one prompt early (fault 2 above);
     *  - no recognisable echo (echo off, or a bare `STOPPED`/`?` with the
     *    echo already consumed) → yes, the best we can do.
     * The LAST echo-looking line wins, so junk ahead of the echo (an
     * unsolicited flood) doesn't hide it.
     */
    fun answers(frame: String, command: String, recent: Collection<String>): Boolean {
        val want = norm(command)
        val known = recent.map(::norm).toSet() + want
        val echo = frame.split('\r', '\n')
            .map(::norm)
            .lastOrNull { it.isNotEmpty() && it in known }
            ?: return true
        return echo == want
    }

    /** ELM said the line it got was garbage (`?`) or it aborted (`STOPPED`). */
    fun isDesync(frame: String): Boolean = frame.split('\r', '\n').map { it.trim() }.any {
        it == "?" || it.startsWith("STOPPED", ignoreCase = true)
    }
}
