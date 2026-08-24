package com.pitstop.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SSID-matching half of upload-on-WiFi. Both failure modes here are
 * silent — a network that never matches just never uploads, with no error
 * anywhere — so the normalisation rules are worth pinning down.
 */
class WifiSsidReaderTest {

    @Test
    fun `strips the quotes the platform wraps around an SSID`() {
        assertEquals("HomeNetwork", WifiSsidReader.normalize("\"HomeNetwork\""))
    }

    @Test
    fun `leaves an unquoted SSID alone`() {
        assertEquals("HomeNetwork", WifiSsidReader.normalize("HomeNetwork"))
    }

    @Test
    fun `keeps quotes that are part of the name itself`() {
        // Only a matched leading+trailing pair is platform framing.
        assertEquals("say \"hi", WifiSsidReader.normalize("say \"hi"))
    }

    @Test
    fun `maps every no-answer form onto null`() {
        assertNull(WifiSsidReader.normalize(null))
        assertNull(WifiSsidReader.normalize(""))
        assertNull(WifiSsidReader.normalize("   "))
        assertNull(WifiSsidReader.normalize(WifiSsidReader.UNKNOWN_SSID))
        assertNull(WifiSsidReader.normalize("\"\""))
    }

    @Test
    fun `matches ignoring case and surrounding whitespace`() {
        assertTrue(WifiSsidReader.matches("HomeNetwork", listOf("homenetwork")))
        assertTrue(WifiSsidReader.matches("HomeNetwork", listOf(" HomeNetwork ")))
        assertTrue(WifiSsidReader.matches("Garage", listOf("Home", "Garage")))
    }

    @Test
    fun `does not match a different network or an unknown one`() {
        assertFalse(WifiSsidReader.matches("Neighbour", listOf("Home", "Garage")))
        assertFalse(WifiSsidReader.matches(null, listOf("Home")))
        assertFalse(WifiSsidReader.matches("Home", emptyList()))
    }
}
