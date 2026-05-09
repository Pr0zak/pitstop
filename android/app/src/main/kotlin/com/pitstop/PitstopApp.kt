package com.pitstop

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.pitstop.update.scheduleUpdateChecks
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class PitstopApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        // Periodic update check (every ~6 h). KEEP policy means a
        // re-launch doesn't reset the cadence — the existing periodic
        // job stays scheduled across reboots via WorkManager's
        // persistence layer.
        scheduleUpdateChecks(this)
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        val bridgeChannel = NotificationChannel(
            BRIDGE_CHANNEL_ID,
            "Bridge service",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Status of the OBD bridge service"
            setShowBadge(false)
        }
        nm.createNotificationChannel(bridgeChannel)
        val updatesChannel = NotificationChannel(
            UPDATES_CHANNEL_ID,
            "Updates",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "New pitstop releases on GitHub"
            setShowBadge(true)
        }
        nm.createNotificationChannel(updatesChannel)
    }

    companion object {
        const val BRIDGE_CHANNEL_ID = "pitstop_bridge"
        const val UPDATES_CHANNEL_ID = "pitstop_updates"
    }
}
