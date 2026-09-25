package com.pitstop.screenshots

import androidx.compose.runtime.Composable
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.pitstop.drive.UploadProgress
import com.pitstop.ui.live.LiveContent
import com.pitstop.ui.status.StatusContent
import com.pitstop.ui.theme.PitstopTheme
import org.junit.Rule
import org.junit.Test

/** Renders screens from [Fixtures] at Pixel 6 size, tall enough to show a whole scroll. */
class ScreenShots {
    @get:Rule val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6.copy(screenHeight = 4200, softButtons = false),
        useDeviceResolution = true,
    )

    private fun shot(name: String, content: @Composable () -> Unit) =
        paparazzi.snapshot(name) { PitstopTheme { content() } }

    @Test fun home() = shot("home") {
        StatusContent(
            ui = Fixtures.home, uploadProgress = UploadProgress.Idle, pendingDrives = 0,
            onRefresh = {}, onStart = {}, onStop = {}, onSync = {}, onCancelSync = {},
            onOpenHistory = {}, onOpenSettings = {},
        )
    }

    @Test fun live() = shot("live") {
        LiveContent(Fixtures.liveMetrics, Fixtures.connected, brokerConnected = true, unitSystem = "imperial", obdAgeS = 2)
    }

    @Test fun liveStale() = shot("live_stale") {
        LiveContent(Fixtures.liveMetrics, Fixtures.staleStatus, brokerConnected = true, unitSystem = "imperial", obdAgeS = 95)
    }
}
