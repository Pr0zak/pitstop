package com.pitstop.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.pitstop.data.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Restart the BLE+MQTT bridge after a device reboot — but only if the user
 * had it running before. Manifest registers this for ACTION_BOOT_COMPLETED;
 * Android delivers it once after the user has unlocked the device for the
 * first time post-boot (per Android 10+ background-execution rules, the
 * receiver also requires the app to have been launched at least once
 * since install).
 *
 * The settings flag is set true the first time the bridge is started
 * manually and cleared when the user explicitly stops it. Reboots inherit
 * whatever the last state was, which matches "the bridge stays running
 * until I tell it not to" from the user's mental model.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var settings: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        // Triggers we accept:
        //  - BOOT_COMPLETED / LOCKED_BOOT_COMPLETED + OEM quickboot variants:
        //    user power-cycled the phone.
        //  - MY_PACKAGE_REPLACED: in-place APK upgrade — Android kills the
        //    process and DOES NOT auto-restart foreground services. Without
        //    this, every Pitstop self-update silently stops the bridge until
        //    the user re-opens the app. We re-launch the bridge here so the
        //    update flow is truly background.
        val action = intent.action
        val isBoot = action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON"
        val isPackageReplaced = action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (!isBoot && !isPackageReplaced) return

        // Receivers can run for ~10 s before the OS kills them. Hop into a
        // coroutine, check the persisted flag, and start the foreground
        // service if the user wants it auto-running.
        val pendingResult = goAsync()
        scope.launch {
            try {
                if (settings.bridgeAutoStart()) {
                    val why = if (isPackageReplaced) "after self-update" else "after boot"
                    Log.i(TAG, "auto-starting pitstop bridge $why")
                    ContextCompat.startForegroundService(
                        context,
                        PitstopBridgeService.startIntent(context),
                    )
                } else {
                    Log.i(TAG, "trigger=${action}; bridge auto-start disabled, skipping")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "BootReceiver failure", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
