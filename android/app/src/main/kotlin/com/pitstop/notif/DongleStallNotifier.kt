package com.pitstop.notif

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.pitstop.MainActivity
import com.pitstop.PitstopApp
import com.pitstop.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tells the user the OBD dongle has stopped answering and needs a power cycle.
 *
 * WHY THIS NEEDS ITS OWN DETECTOR RATHER THAN REUSING THE OBD-QUIET WATCHDOG:
 * that watchdog cannot tell "engine off" from "dongle hung" — both look
 * identical from the phone (BLE connected, no OBD frames). Notifying on it
 * would fire every single time the user parks, which is worse than not
 * notifying at all.
 *
 * The discriminator is GPS. If the phone is moving at road speed, the engine
 * is not off, so a silent OBD link means the dongle has stopped answering.
 * That is measurable without any new hardware signal.
 *
 * Delivered through [androidx.car.app.notification.CarNotificationManager]
 * with a CarAppExtender, so ONE post reaches both the phone and — when
 * projecting — the head unit. The user is by definition driving when this
 * fires, so the head unit is where they can actually see it.
 */
/**
 * The decision, separated from the delivery so it can be tested.
 *
 * Everything valuable about this feature is in NOT firing: a detector that
 * alerts every time the car is switched off is worse than none, because it
 * trains the user to dismiss it. The predicate lives here rather than inline
 * in the watchdog coroutine so the parking case is pinned by a test.
 */
object DongleStallDetector {

    /** Below this the phone could be stationary with GPS jitter. ~5 mph. */
    const val MOVING_MPS = 2.2

    /** A fix older than this cannot be trusted to mean "moving now". */
    const val MAX_FIX_AGE_MS = 30_000L

    /**
     * @param obdQuiet the ADR-017 OBD-quiet condition (BLE up, no frames)
     * @param gpsSpeedMps latest GPS speed, null if none has ever arrived
     * @param gpsFixAgeMs age of that speed sample
     */
    fun isStalled(obdQuiet: Boolean, gpsSpeedMps: Double?, gpsFixAgeMs: Long): Boolean {
        if (!obdQuiet) return false
        val speed = gpsSpeedMps ?: return false
        if (gpsFixAgeMs >= MAX_FIX_AGE_MS) return false
        return speed >= MOVING_MPS
    }
}

@Singleton
class DongleStallNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * Once per bridge session. Cleared by [reset] when the bridge starts, so
     * a genuine second hang on a later drive still notifies — but a single
     * hang cannot produce a notification every 10 s while the watchdog loop
     * keeps re-observing the same condition.
     */
    private var notifiedThisSession = false

    fun reset() {
        notifiedThisSession = false
        runCatching {
            androidx.car.app.notification.CarNotificationManager.from(context)
                .cancel(NOTIFICATION_ID)
        }
    }

    /**
     * @param obdAgeS seconds since the last OBD frame
     * @param speedMph current GPS speed, used only for the message body
     */
    fun notifyStalled(obdAgeS: Long, speedMph: Int) {
        if (notifiedThisSession) return
        notifiedThisSession = true

        val tap = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = "OBD data stopped"
        // Says what is still working, not just what broke. The drive is not
        // lost — GPS keeps recording route and distance — and knowing that is
        // the difference between "pull over now" and "sort it when I stop".
        val body = "No engine data for ${obdAgeS}s while moving at $speedMph mph. " +
            "The dongle has likely hung — unplug it and plug it back in. " +
            "GPS is still recording this drive."

        val builder = NotificationCompat.Builder(context, PitstopApp.DEVICE_ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bluetooth)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .extend(
                androidx.car.app.notification.CarAppExtender.Builder()
                    .setContentTitle(title)
                    // Shorter on the head unit: a driver gets one glance, and
                    // the actionable half is "unplug it".
                    .setContentText("No engine data — unplug the dongle to reset it")
                    .setChannelId(PitstopApp.DEVICE_ALERT_CHANNEL_ID)
                    .setImportance(androidx.core.app.NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .build(),
            )

        runCatching {
            androidx.car.app.notification.CarNotificationManager.from(context)
                .notify(NOTIFICATION_ID, builder)
        }
    }

    private companion object {
        const val NOTIFICATION_ID = 4310
    }
}
