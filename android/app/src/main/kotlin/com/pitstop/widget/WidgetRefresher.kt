package com.pitstop.widget

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rate-limited fan-out point for "metric changed, ask the home-screen
 * widgets to re-render". The widgets re-fetch from /vehicles on each
 * onUpdate cycle, so this exists to break the 30-minute OS update
 * floor — without it, the fuel widget only refreshes every half hour
 * (and Doze can stretch even that).
 *
 * Hooked from PitstopBridgeService (BLE poll path) and WiCanSubscriber
 * (MQTT path) so both data routes keep the widget current. Rate limit
 * prevents a 1 Hz BLE poll from triggering 60 widget refreshes per
 * minute; one refresh per 30 s is plenty.
 */
@Singleton
class WidgetRefresher @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    @Volatile private var lastFuelRefreshMs: Long = 0L

    fun refreshFuelWidget() {
        val now = System.currentTimeMillis()
        if (now - lastFuelRefreshMs < MIN_INTERVAL_MS) return
        lastFuelRefreshMs = now
        FuelWidgetProvider.refreshWidgets(context)
    }

    @Volatile private var lastTireRefreshMs: Long = 0L

    /** A tire metric arrived (BLE Mode 22 or WiCAN MQTT): re-render the
     *  tire widget, at most once a minute — TPMS only moves every ~30 s. */
    fun refreshTireWidget() {
        val now = System.currentTimeMillis()
        if (now - lastTireRefreshMs < 60_000L) return
        lastTireRefreshMs = now
        TireWidgetProvider.refreshWidgets(context)
    }

    private companion object {
        const val MIN_INTERVAL_MS = 30_000L
    }
}
