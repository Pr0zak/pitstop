package com.pitstop.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-shot network-type probe. Used by HistoryViewModel.requestSyncNow
 * to warn before draining a queue of multi-MB drive payloads over
 * cellular. The "metered" flag (NET_CAPABILITY_NOT_METERED) is the
 * Android-canonical signal — handles WiFi hotspots that bill, and
 * unmetered Ethernet over USB-OTG.
 *
 * Stateless / synchronous on purpose: a one-second-stale snapshot at
 * the moment the user taps Sync is exactly what we want. Long-lived
 * observers would only matter if we auto-decided in the background,
 * which DriveUploader's queue-drain doesn't do.
 */
@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    enum class NetworkClass {
        /** No network at all — sync will fail; warn but allow Try. */
        None,
        /** Unmetered (WiFi / Ethernet). Default proceed. */
        Unmetered,
        /** Metered (cellular, metered WiFi). Confirm before draining. */
        Metered,
    }

    fun classify(): NetworkClass {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return NetworkClass.None
        val net = cm.activeNetwork ?: return NetworkClass.None
        val caps = cm.getNetworkCapabilities(net) ?: return NetworkClass.None
        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        if (!hasInternet) return NetworkClass.None
        return if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
            NetworkClass.Unmetered
        } else {
            NetworkClass.Metered
        }
    }
}
