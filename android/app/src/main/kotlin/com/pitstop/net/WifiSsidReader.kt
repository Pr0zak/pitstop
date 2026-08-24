package com.pitstop.net

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single place that answers "which WiFi network is the phone associated
 * with right now". Two callers need it and they must agree, or a network
 * that auto-starts the bridge could fail to auto-upload (or vice versa):
 * [com.pitstop.presence.InCarDetector] for the car-hotspot in-car signal,
 * and [WifiUploadGate] for the upload-on-WiFi allowlist.
 *
 * Reading an SSID requires `ACCESS_FINE_LOCATION` on Android Q+ — already
 * declared and requested for GPS capture, so this piggybacks on that
 * prompt rather than adding one. Without the grant every read returns
 * null; callers surface that rather than silently treating it as "no
 * match", because the two are very different for the user.
 */
@Singleton
class WifiSsidReader @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Whether we can read an SSID at all. False → every read returns null. */
    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Android 12+ carries [WifiInfo] on the network's capabilities, which
     * is the cheapest read and the one that works inside a
     * `NetworkCallback`. Returns null below API 31, when the transport
     * info isn't WiFi, or when the platform redacted the SSID (which it
     * does for a synchronous `getNetworkCapabilities` read without the
     * location grant) — callers fall back to [current].
     */
    fun fromCapabilities(caps: NetworkCapabilities?): String? {
        if (caps == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val info = caps.transportInfo as? WifiInfo ?: return null
        return normalize(info.ssid)
    }

    /**
     * Pre-Android-12 path, and the fallback when the capabilities read
     * came back redacted. `WifiManager.connectionInfo` is deprecated but
     * still the only synchronous SSID source that works across our whole
     * minSdk range.
     */
    @SuppressLint("MissingPermission")
    fun current(): String? {
        if (!hasPermission()) return null
        val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        @Suppress("DEPRECATION")
        val info = runCatching { wifi.connectionInfo }.getOrNull() ?: return null
        return normalize(info.ssid)
    }

    /** [fromCapabilities] when it can answer, else [current]. */
    fun resolve(caps: NetworkCapabilities?): String? =
        fromCapabilities(caps) ?: current()

    companion object {
        /** What the platform hands back instead of an SSID when the caller
         *  lacks the location grant or nothing is associated. */
        const val UNKNOWN_SSID: String = "<unknown ssid>"

        /**
         * Strip the quotes the platform wraps around a UTF-8 SSID and map
         * every "no usable answer" form — blank, the unknown-SSID
         * sentinel, null — onto null.
         */
        fun normalize(raw: String?): String? {
            val trimmed = raw?.trim() ?: return null
            val unquoted = if (
                trimmed.length >= 2 && trimmed.startsWith('"') && trimmed.endsWith('"')
            ) {
                trimmed.substring(1, trimmed.length - 1)
            } else {
                trimmed
            }
            return unquoted.takeIf { it.isNotBlank() && it != UNKNOWN_SSID }
        }

        /**
         * Case-insensitive allowlist match. SSIDs are technically
         * case-sensitive byte strings, but users type them from memory and
         * a case slip that silently never uploads is a worse failure than
         * matching a network that differs only in case.
         */
        fun matches(seen: String?, configured: Collection<String>): Boolean {
            if (seen == null) return false
            return configured.any { it.trim().equals(seen, ignoreCase = true) }
        }
    }
}
