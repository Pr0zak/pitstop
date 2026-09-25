package com.pitstop.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DtcGuideTest {
    @Test fun `table covers the required codes`() {
        val required = listOf(
            "P0420", "P0430", "P0171", "P0174", "P0300", "P0301", "P0302", "P0303", "P0304", "P0305", "P0306",
            "P0128", "P0442", "P0455", "P0456", "P0401", "P0113", "P0101", "P0335", "P0340", "P0500", "P0700",
            "P0505", "P0135", "P0141", "P0507", "U0100",
        )
        for (c in required) assertTrue("$c missing", DtcGuide.lookup(c).known)
        assertTrue("expected ~60 entries, got ${DtcGuide.size}", DtcGuide.size >= 60)
    }

    @Test fun `lookup normalises case and whitespace`() {
        val g = DtcGuide.lookup("  p0420 ")
        assertEquals("P0420", g.code)
        assertEquals(DtcSeverity.CHECK_SOON, g.severity)
        assertTrue(g.causes.size in 2..4)
    }

    @Test fun `every misfire is urgent and mentions the flashing light`() {
        for (c in listOf("P0300", "P0301", "P0306", "P0308")) {
            val g = DtcGuide.lookup(c)
            assertEquals(c, DtcSeverity.STOP_NOW, g.severity)
            assertTrue(c, g.safeToDrive.contains("flashing"))
        }
    }

    @Test fun `every table entry has a safe-to-drive line and two to four causes`() {
        for (c in listOf("P0011", "P0217", "P0524", "U0121", "C0035", "P2135")) {
            val g = DtcGuide.lookup(c)
            assertTrue(c, g.known)
            assertTrue(c, g.safeToDrive.isNotBlank())
            assertTrue(c, g.causes.size in 1..4)
        }
    }

    @Test fun `unknown codes fall back by family, honestly`() {
        val p0 = DtcGuide.lookup("P0999")
        assertFalse(p0.known)
        assertEquals(DtcSeverity.CHECK_SOON, p0.severity)
        assertEquals("generic powertrain", DtcGuide.familyLabel("P0999"))

        val p1 = DtcGuide.lookup("P1456")
        assertFalse(p1.known)
        assertEquals("manufacturer-specific powertrain", DtcGuide.familyLabel("P1456"))

        assertEquals(DtcSeverity.MONITOR, DtcGuide.lookup("B1234").severity)
        assertEquals(DtcSeverity.CHECK_SOON, DtcGuide.lookup("B0012").severity) // restraint range
        assertEquals(DtcSeverity.CHECK_SOON, DtcGuide.lookup("C1234").severity)
        assertEquals(DtcSeverity.CHECK_SOON, DtcGuide.lookup("U0404").severity)
    }

    @Test fun `unknown code keeps the server description as its title`() {
        val g = DtcGuide.lookup("P1456", "EVAP control system leak (fuel tank)")
        assertEquals("EVAP control system leak (fuel tank)", g.title)
        assertFalse(g.known)
    }
}
