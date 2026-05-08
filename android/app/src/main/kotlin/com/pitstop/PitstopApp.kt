package com.pitstop

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class PitstopApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
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
    }

    companion object {
        const val BRIDGE_CHANNEL_ID = "pitstop_bridge"
    }
}
