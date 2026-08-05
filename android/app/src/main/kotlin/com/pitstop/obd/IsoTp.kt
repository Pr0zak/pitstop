package com.pitstop.obd

/**
 * ISO 15765-2 (ISO-TP) reassembly for the multi-frame responses that Mode 22
 * "extended" PIDs produce.
 *
 * Standard Mode 01 PIDs answer in a single CAN frame and are handled by
 * [ObdResponseParser]; nothing in this file is on that path. Mode 22 requests
 * to a module like the ZF 9HP TCM answer with a First Frame + a run of
 * Consecutive Frames that have to be stitched back together before any byte
 * offset means anything.
 *
 * ### What "payload" means here
 * The reassembled block starting at the **positive-response byte**:
 * ```
 *   payload[0]    = 0x62          (0x22 + 0x40, positive response to Mode 22)
 *   payload[1..2] = DID echo      (e.g. 30 83)
 *   payload[3..]  = data
 * ```
 * So a documented offset like "ATF temperature is payload byte 17" is literally
 * `payload[17]` — no further arithmetic, which is exactly why this returns the
 * block with the 0x62 still attached instead of stripping a header first.
 *
 * ### Frame layouts accepted
 *  1. **Raw PCI frames** (ELM with headers on, `ATH1`), one CAN frame per line,
 *     with or without spaces, with or without a 29-bit `18 DA F1 xx` CAN-ID
 *     prefix:
 *     ```
 *     18 DA F1 1E 10 18 62 30 83 00 00 00     <- First Frame:  1L LL + 6 data
 *     18 DA F1 1E 21 00 00 00 00 00 00 00     <- Consecutive:  2N + 7 data
 *     ```
 *  2. **ELM's own numbered multiline form** (`ATH0` + auto-format), where the
 *     adapter has already stripped the PCI bytes for us:
 *     ```
 *     018
 *     0: 62 30 83 00 00 00
 *     1: 00 00 00 00 00 00 00
 *     ```
 *
 * ### Failure is null, never a guess
 * A dropped Consecutive Frame would silently shift every byte offset after it
 * and turn "gear 2" into "gear 15". So the sequence numbers are validated and
 * any gap, any wrong order, or any unrecognised shape returns `null`. The
 * caller treats null as "no reading this cycle", which is always safe.
 */
object IsoTp {

    /** ELM/adapter status strings that are definitively not a response. */
    private val ERROR_PREFIXES = listOf(
        "NO DATA", "SEARCHING", "STOPPED", "UNABLE", "BUS INIT", "BUS BUSY",
        "CAN ERROR", "ERROR", "BUFFER FULL", "?",
    )

    /**
     * Stitch an ELM response block into one ISO-TP payload.
     *
     * @param text the raw text captured between the previous prompt and the
     *   `>` that terminated this response (i.e. what the bridge's rx buffer
     *   hands to the parser).
     * @return the reassembled payload starting at the response-SID byte, or
     *   null if the text isn't a well-formed multi/single-frame block.
     */
    fun reassemble(text: String): ByteArray? {
        val lines = text
            .replace(">", "\n")
            .split('\r', '\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filter { line -> ERROR_PREFIXES.none { line.startsWith(it, ignoreCase = true) } }
        if (lines.isEmpty()) return null

        // ELM's numbered form is unambiguous — if we see "N:" anywhere, the
        // adapter has already done the reassembly and mixing in the PCI path
        // would double-count bytes.
        if (lines.any { NUMBERED_LINE.matches(it) }) return reassembleNumbered(lines)
        return reassemblePci(lines)
    }

    // ---- raw PCI frames -------------------------------------------------

    private fun reassemblePci(lines: List<String>): ByteArray? {
        val out = ArrayList<Byte>(64)
        var declaredLength = -1
        var started = false
        // ISO-TP Consecutive Frames are numbered 1..15 then wrap to 0.
        var expectedSeq = 1

        for (line in lines) {
            val bytes = stripCanId(hexBytes(line))
            if (bytes.isEmpty()) continue
            val pci = bytes[0].toInt() and 0xFF
            when (pci and 0xF0) {
                0x00 -> {
                    // Single Frame. Only meaningful as the *first* thing we
                    // see; a stray SF mid-block is not something we try to
                    // merge.
                    if (started) return null
                    val len = pci and 0x0F
                    if (len < 1 || bytes.size < 1 + len) return null
                    return bytes.copyOfRange(1, 1 + len)
                }
                0x10 -> {
                    // First Frame: 12-bit length across the low nibble + next byte.
                    if (started) return null // two First Frames in one block
                    if (bytes.size < 3) return null
                    declaredLength = ((pci and 0x0F) shl 8) or (bytes[1].toInt() and 0xFF)
                    if (declaredLength < 3) return null
                    out.addAll(bytes.drop(2))
                    started = true
                    expectedSeq = 1
                }
                0x20 -> {
                    // Consecutive Frame. Ignore any that arrive before a First
                    // Frame — an ELM command echo like "223083" tokenises to
                    // 22 30 83 and would otherwise look exactly like CF #2.
                    if (!started) continue
                    val seq = pci and 0x0F
                    if (seq != expectedSeq) return null // dropped/reordered frame
                    expectedSeq = (expectedSeq + 1) and 0x0F
                    if (bytes.size < 2) return null
                    out.addAll(bytes.drop(1))
                }
                else -> continue // not a PCI byte — header noise, echo, junk
            }
        }

        if (!started || out.isEmpty()) return null
        val payload = out.toByteArray()
        // Trim the CAN padding that rides along in the last frame. If the
        // device truncated reassembly early (the WiCAN stops at ~34 payload
        // bytes) we keep whatever we actually got — short is handled by the
        // decoders returning null, which is honest; padding a guess is not.
        return if (declaredLength in 1..payload.size) {
            payload.copyOfRange(0, declaredLength)
        } else {
            payload
        }
    }

    /** Drop a leading 29-bit `18 DA xx xx` CAN-ID if present. */
    private fun stripCanId(bytes: ByteArray): ByteArray {
        if (bytes.size >= 5 &&
            (bytes[0].toInt() and 0xFF) == 0x18 &&
            (bytes[1].toInt() and 0xFF) == 0xDA
        ) {
            return bytes.copyOfRange(4, bytes.size)
        }
        return bytes
    }

    // ---- ELM numbered multiline form ------------------------------------

    private val NUMBERED_LINE = Regex("^[0-9A-Fa-f]:.*$")
    private val BARE_LENGTH = Regex("^[0-9A-Fa-f]{1,4}$")

    private fun reassembleNumbered(lines: List<String>): ByteArray? {
        var declaredLength = -1
        val chunks = sortedMapOf<Int, ByteArray>()
        for (line in lines) {
            if (NUMBERED_LINE.matches(line)) {
                val idx = line.substring(0, 1).toInt(16)
                val bytes = hexBytes(line.substring(2))
                if (bytes.isEmpty()) continue
                if (chunks.containsKey(idx)) return null // duplicate index
                chunks[idx] = bytes
            } else if (BARE_LENGTH.matches(line) && declaredLength < 0) {
                declaredLength = line.toInt(16)
            }
        }
        if (chunks.isEmpty()) return null
        // Indices must be a dense run starting at 0 — a hole means a dropped
        // line and therefore shifted offsets.
        if (chunks.keys.first() != 0) return null
        var expected = 0
        for (k in chunks.keys) {
            if (k != expected) return null
            expected += 1
        }
        val out = ArrayList<Byte>(64)
        for (chunk in chunks.values) out.addAll(chunk.toList())
        val payload = out.toByteArray()
        return if (declaredLength in 1..payload.size) {
            payload.copyOfRange(0, declaredLength)
        } else {
            payload
        }
    }

    // ---- shared ---------------------------------------------------------

    /**
     * Hex-tokenise a line. Spaced form splits on whitespace; unspaced form is
     * chunked two characters at a time. Non-hex tokens are dropped, which is
     * what filters out 11-bit CAN-ID columns ("7E8") and stray ASCII.
     */
    internal fun hexBytes(line: String): ByteArray {
        val tokens = if (line.contains(' ')) {
            line.split(Regex("\\s+"))
        } else {
            line.chunked(2)
        }
        return tokens
            .filter { tok -> tok.length == 2 && tok.all { c -> c.isDigit() || c.lowercaseChar() in 'a'..'f' } }
            .map { it.toInt(16).toByte() }
            .toByteArray()
    }
}
