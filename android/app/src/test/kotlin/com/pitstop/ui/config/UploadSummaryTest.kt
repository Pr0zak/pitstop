package com.pitstop.ui.config

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Settings line that tells the user where a finished drive goes.
 * Upload-on-WiFi overrides manual-sync for the upload queue, so the
 * summary has to say so — showing "uploads on demand" while drives are in
 * fact shipping themselves over WiFi would be the wrong kind of wrong.
 */
class UploadSummaryTest {

    @Test
    fun `manual sync alone reads as on demand`() {
        assertEquals(
            "Uploads on demand — sync from History",
            uploadSummary(
                manualSyncOnly = true,
                uploadOnWifi = false,
                uploadOnWifiSsids = emptyList(),
            ),
        )
    }

    @Test
    fun `neither switch means live streaming`() {
        assertEquals(
            "Uploads live over cellular",
            uploadSummary(
                manualSyncOnly = false,
                uploadOnWifi = false,
                uploadOnWifiSsids = emptyList(),
            ),
        )
    }

    @Test
    fun `upload on wifi wins over manual sync and names the networks`() {
        assertEquals(
            "Uploads on Home, Garage",
            uploadSummary(
                manualSyncOnly = true,
                uploadOnWifi = true,
                uploadOnWifiSsids = listOf("Home", "Garage"),
            ),
        )
    }

    @Test
    fun `an empty allowlist reads as any unmetered wifi`() {
        assertEquals(
            "Uploads on any unmetered WiFi",
            uploadSummary(
                manualSyncOnly = true,
                uploadOnWifi = true,
                uploadOnWifiSsids = emptyList(),
            ),
        )
    }
}
