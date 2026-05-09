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
        // Strategy: try ASCII first (covers ELM327 + WiCAN's 29-bit echo); if
        // that fails, try raw CAN single-frame.
        val text = raw.toString(Charsets.US_ASCII)
        parseAscii(text)?.let { return it }
        return parseRawCanFrame(raw)
    }

    private fun parseAscii(text: String): Frame? {
        // Multi-ECU responses arrive on separate \r-delimited lines (Honda
        // Pilot replies from both engine ECUs at 7E0/7E1). Try each line; the
        // first successful parse wins. Anything unparseable falls through.
        val lines = text
            .replace(">", " ")
            .split('\r', '\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        for (line in lines) {
            parseAsciiLine(line)?.let { return it }
        }
        return null
    }

    private fun parseAsciiLine(line: String): Frame? {
        if (line.startsWith("NO DATA", ignoreCase = true)) return null
        if (line.startsWith("SEARCHING", ignoreCase = true)) return null
        if (line.startsWith("?")) return null
        if (line.startsWith("STOPPED", ignoreCase = true)) return null
        if (line.startsWith("BUS INIT", ignoreCase = true)) return null
        if (line.startsWith("UNABLE", ignoreCase = true)) return null
        if (line.startsWith("CAN ERROR", ignoreCase = true)) return null

        // Tokens are 2-char hex pairs. Some firmwares emit them with no spaces; handle both.
        val tokens = if (line.contains(" ")) {
            line.split(Regex("\\s+"))
        } else {
            line.chunked(2)
        }
        val bytes = tokens
            .filter { it.length == 2 && it.all { c -> c.isDigit() || c.lowercaseChar() in 'a'..'f' } }
            .map { it.toInt(16).toByte() }
            .toByteArray()
        if (bytes.size < 2) return null

        // Modern Hondas use 29-bit CAN with extended-address ELM responses:
        //   18 DA F1 <src-ecu> <isotp-len> 41 <pid> <data...>
        //   ^^^^^^^^^^^^^^^^^^ ^^^^^^^^^^ ^^^^^^^^^^^^^^^^^^^
        //    CAN ID (4 bytes)  ISO-TP len  Mode 01 response payload
        // Strip the 5-byte prefix when present.
        val payload = stripExtendedHeader(bytes) ?: bytes

        if (payload.size < 2) return null
        val modeByte = payload[0].toInt() and 0xFF
        if (modeByte and 0x40 == 0) return null // expect a response (mode | 0x40)
        val mode = modeByte and 0x3F
        val pid = payload[1].toInt() and 0xFF
        val data = payload.copyOfRange(2, payload.size)
        return Frame(mode = mode, pid = pid, data = data)
    }

    /** Detect and strip a 29-bit ELM CAN response header. */
    private fun stripExtendedHeader(bytes: ByteArray): ByteArray? {
        if (bytes.size < 7) return null
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        val b2 = bytes[2].toInt() and 0xFF
        // Honda 29-bit extended response: 18 DA F1 <ecu>
        if (b0 != 0x18 || b1 != 0xDA || b2 != 0xF1) return null
        // bytes[3] = source ECU
        // bytes[4] = ISO-TP single-frame PCI: high nibble must be 0 (single frame)
        val pci = bytes[4].toInt() and 0xFF
        if (pci and 0xF0 != 0) return null  // multi-frame; not yet handled
        val length = pci and 0x0F
        if (length < 2) return null
        // bytes[5] should be a Mode 01 response (0x40-prefixed).
        val modeByte = bytes[5].toInt() and 0xFF
        if (modeByte and 0x40 == 0) return null
        // Return just the response payload bytes, capped at the declared length.
        val end = (5 + length).coerceAtMost(bytes.size)
        return bytes.copyOfRange(5, end)
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
