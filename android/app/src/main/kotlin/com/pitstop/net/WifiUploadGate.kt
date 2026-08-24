package com.pitstop.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.pitstop.data.Settings
import com.pitstop.data.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides whether the queued-drive uploader is allowed to drain right
 * now on the strength of the network alone — the "auto-upload when I get
 * on my home WiFi" policy.
 *
 * Three callers evaluate the same gate so they can't disagree:
 * [WifiUploadTrigger] (live, when a network arrives), [DriveUploadWorker]
 * (the periodic backstop, which otherwise refuses to drain in manual-sync
 * mode), and [com.pitstop.drive.DriveSealer] (a drive sealed while the
 * phone is already sitting on the target network — no callback fires
 * there, because nothing about the network changed).
 *
 * The gate deliberately overrides
 * [com.pitstop.data.Settings.manualSyncOnly] for the upload queue: manual
 * mode exists so drives don't stream over cellular, and "upload them when
 * I'm back on WiFi" is the automation of exactly the manual step that
 * mode leaves the user to do by hand. It does NOT re-enable any of manual
 * mode's live MQTT publishing — that stays suppressed.
 */
@Singleton
class WifiUploadGate @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val ssidReader: WifiSsidReader,
) {

    /** Why an automatic drain may or may not proceed. Everything except
     *  [Allowed] carries enough detail to log a diagnosable reason. */
    sealed interface Verdict {
        /** Drain now. [ssid] is null only when the network matched on the
         *  unmetered-WiFi rule and the name couldn't be read. */
        data class Allowed(val ssid: String?) : Verdict
        /** The user hasn't turned upload-on-WiFi on. */
        object Disabled : Verdict
        /** No validated WiFi network is the active one (cellular, or nothing). */
        object NotOnWifi : Verdict
        /** No SSIDs configured (= "any unmetered WiFi") but this one bills. */
        object MeteredWifi : Verdict
        /** On WiFi, but not one the user listed. */
        data class SsidMismatch(val seen: String?) : Verdict
        /** SSIDs are configured but we can't read the network's name. */
        object NoLocationPermission : Verdict
    }

    /** Read the current settings, then [evaluate]. */
    suspend fun evaluate(): Verdict {
        val snapshot = runCatching { settings.settings.first() }.getOrNull()
            ?: return Verdict.Disabled
        return evaluate(snapshot)
    }

    /**
     * Evaluate against an already-read settings snapshot — for callers
     * that have one in hand, and so the decision is testable without a
     * DataStore. Synchronous: it reads ConnectivityManager and WifiManager
     * state, both of which are cheap local lookups.
     */
    fun evaluate(s: Settings, caps: NetworkCapabilities? = null): Verdict {
        if (!s.uploadOnWifi) return Verdict.Disabled
        val effectiveCaps = caps ?: activeNetworkCapabilities() ?: return Verdict.NotOnWifi
        if (!effectiveCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return Verdict.NotOnWifi
        }
        // VALIDATED, not just INTERNET: a captive portal or a router with
        // no upstream would otherwise start a drain that can only fail,
        // burning the retry budget on every queued drive.
        val usable = effectiveCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            effectiveCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        if (!usable) return Verdict.NotOnWifi

        val configured = s.uploadOnWifiSsids
        if (configured.isEmpty()) {
            // Empty allowlist = "any unmetered WiFi". Requiring unmetered
            // here is what keeps the empty default safe: a metered WiFi
            // hotspot is the user's cellular plan wearing a different hat,
            // which is the exact thing manual-sync mode is avoiding.
            return if (effectiveCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
                Verdict.Allowed(ssidReader.resolve(effectiveCaps))
            } else {
                Verdict.MeteredWifi
            }
        }

        val seen = ssidReader.resolve(effectiveCaps)
        if (seen == null) {
            // Named networks can't be matched without the name. Distinguish
            // "we're not allowed to look" from "looked, no match" — the
            // former is fixable by granting a permission and the Settings
            // screen says so.
            return if (!ssidReader.hasPermission()) {
                Verdict.NoLocationPermission
            } else {
                Verdict.SsidMismatch(null)
            }
        }
        // A named network is an explicit user choice, so a metered one is
        // honoured — they said upload here.
        return if (WifiSsidReader.matches(seen, configured)) {
            Verdict.Allowed(seen)
        } else {
            Verdict.SsidMismatch(seen)
        }
    }

    private fun activeNetworkCapabilities(): NetworkCapabilities? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val net = cm.activeNetwork ?: return null
        return cm.getNetworkCapabilities(net)
    }
}

/** Short, stable reason string for the log depot. */
fun WifiUploadGate.Verdict.reason(): String = when (this) {
    is WifiUploadGate.Verdict.Allowed -> "allowed"
    WifiUploadGate.Verdict.Disabled -> "disabled"
    WifiUploadGate.Verdict.NotOnWifi -> "not-on-wifi"
    WifiUploadGate.Verdict.MeteredWifi -> "metered-wifi"
    is WifiUploadGate.Verdict.SsidMismatch -> "ssid-mismatch"
    WifiUploadGate.Verdict.NoLocationPermission -> "no-location-permission"
}
