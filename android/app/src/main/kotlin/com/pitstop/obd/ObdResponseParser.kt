package com.pitstop.obd

/**
 * Parses raw bytes coming back over the UART characteristic into PID data bytes.
 *
 * Two response formats are supported:
 *
 *  1. ELM327 ASCII: a string like "41 0C 1A F8\r\r>" — mode echo (0x41 = 0x40 + 0x01),
 *     PID echo, then payload bytes. Whitespace optional. Lines may end with ">" prompt.
 *
 *  2. Raw CAN frame (8 bytes): "06 41 0C 1A F8 00 00 00" — first byte is the PCI length
 *     header (single-frame; 0x0X with X = data length).
 *
 * The parser is intentionally lenient: anything that doesn't match is dropped with a
 * `null` return and the caller logs+continues.
 */
object ObdResponseParser {

    data class Frame(val mode: Int, val pid: Int, val data: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Frame) return false
            return mode == other.mode && pid == other.pid && data.contentEquals(other.data)
        }

        override fun hashCode(): Int =
            mode.hashCode() * 31 + pid.hashCode() * 31 + data.contentHashCode()
    }

    fun parse(raw: ByteArray): Frame? {
        // Strategy: try ASCII first; if that fails, try raw CAN single-frame.
        val text = raw.toString(Charsets.US_ASCII).trim()
        parseAscii(text)?.let { return it }
        return parseRawCanFrame(raw)
    }

    private fun parseAscii(text: String): Frame? {
        // Strip the ELM ">" prompt, line endings, "SEARCHING…", "NO DATA", etc.
        val cleaned = text
            .replace(">", " ")
            .replace("\r", " ")
            .replace("\n", " ")
            .trim()
        if (cleaned.isEmpty()) return null
        if (cleaned.startsWith("NO DATA", ignoreCase = true)) return null
        if (cleaned.startsWith("SEARCHING", ignoreCase = true)) return null
        if (cleaned.startsWith("?")) return null
        if (cleaned.startsWith("STOPPED", ignoreCase = true)) return null

        // Tokens are 2-char hex pairs. Some firmwares emit them with no spaces; handle both.
        val tokens = if (cleaned.contains(" ")) {
            cleaned.split(Regex("\\s+"))
        } else {
            cleaned.chunked(2)
        }
        val bytes = tokens
            .filter { it.length == 2 && it.all { c -> c.isDigit() || c.lowercaseChar() in 'a'..'f' } }
            .map { it.toInt(16).toByte() }
            .toByteArray()
        if (bytes.size < 2) return null

        val modeByte = bytes[0].toInt() and 0xFF
        if (modeByte and 0x40 == 0) return null // expect a response (mode | 0x40)
        val mode = modeByte and 0x3F
        val pid = bytes[1].toInt() and 0xFF
        val data = bytes.copyOfRange(2, bytes.size)
        return Frame(mode = mode, pid = pid, data = data)
    }

    private fun parseRawCanFrame(raw: ByteArray): Frame? {
        if (raw.size < 4) return null
        // Single-frame PCI: high nibble of byte 0 == 0, low nibble == data length.
        val pci = raw[0].toInt() and 0xFF
        if (pci and 0xF0 != 0) return null
        val length = pci and 0x0F
        if (length < 2 || length > raw.size - 1) return null
        val modeByte = raw[1].toInt() and 0xFF
        if (modeByte and 0x40 == 0) return null
        val mode = modeByte and 0x3F
        val pid = raw[2].toInt() and 0xFF
        val end = (length + 1).coerceAtMost(raw.size)
        val data = raw.copyOfRange(3, end)
        return Frame(mode = mode, pid = pid, data = data)
    }
}
