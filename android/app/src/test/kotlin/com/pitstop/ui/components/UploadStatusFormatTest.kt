package com.pitstop.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Byte and sample counts appear verbatim in the upload card, so their
 * formatting is part of the feedback rather than decoration.
 */
class UploadStatusFormatTest {

    @Test
    fun `bytes below a kilobyte stay in bytes`() {
        assertEquals("0 B", formatBytes(0L))
        assertEquals("512 B", formatBytes(512L))
    }

    @Test
    fun `kilobytes round down to whole units`() {
        assertEquals("1 KB", formatBytes(1024L))
        assertEquals("64 KB", formatBytes(64L * 1024L))
    }

    @Test
    fun `megabytes carry one decimal`() {
        assertEquals("1.0 MB", formatBytes(1024L * 1024L))
        assertEquals("3.5 MB", formatBytes((3.5 * 1024 * 1024).toLong()))
    }

    @Test
    fun `sample counts get thousands separators`() {
        assertEquals("0", formatCount(0))
        assertEquals("999", formatCount(999))
        assertEquals("1,000", formatCount(1_000))
        assertEquals("18,402", formatCount(18_402))
        assertEquals("1,234,567", formatCount(1_234_567))
    }
}
