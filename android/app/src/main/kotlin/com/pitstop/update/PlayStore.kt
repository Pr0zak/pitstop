package com.pitstop.update

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

/**
 * Single place that knows how to send the user to the app's store page.
 *
 * Every "an update is available" surface must route here. Google Play's
 * Device and Network Abuse policy forbids an app distributed on Play from
 * updating itself by any mechanism other than Play, and that includes
 * merely pointing the user at an off-store APK — removing the installer
 * and the REQUEST_INSTALL_PACKAGES permission does not cure a button that
 * opens a GitHub release page with an APK attached to it.
 *
 * Centralised precisely because it was got wrong once: the Settings path
 * was converted while the update notification and the Home update card
 * were left opening `releaseUrl`, so two live surfaces still advertised
 * the APK download.
 */
object PlayStore {
    /**
     * Opens the Play listing for this app. `market://` hands off to the
     * Play app directly; the https listing is the fallback for a device
     * without Play or a locally-built variant.
     */
    fun open(context: Context) {
        val pkg = context.packageName
        val market = Intent(Intent.ACTION_VIEW, "market://details?id=$pkg".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val web = Intent(
            Intent.ACTION_VIEW,
            "https://play.google.com/store/apps/details?id=$pkg".toUri(),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(market) }
            .onFailure { runCatching { context.startActivity(web) } }
    }

    /** Same target, as a PendingIntent-able Intent for notifications. */
    fun intent(context: Context): Intent =
        Intent(
            Intent.ACTION_VIEW,
            "market://details?id=${context.packageName}".toUri(),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
