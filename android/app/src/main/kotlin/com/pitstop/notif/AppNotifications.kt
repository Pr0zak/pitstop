package com.pitstop.notif

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.pitstop.MainActivity

/**
 * Shared plumbing for the three user-facing notification kinds added with
 * the phone-first redesign: drive summaries, service reminders and vehicle
 * (check-engine) alerts. Channels are created in PitstopApp.
 */
object AppNotifications {
    const val DRIVE_SUMMARY_CHANNEL_ID = "drive_summary"
    const val SERVICE_REMINDER_CHANNEL_ID = "service_reminder"
    const val VEHICLE_ALERT_CHANNEL_ID = "vehicle_alert"

    /** Batch summary ("3 drives synced") — one slot, replaced each time. */
    const val DRIVE_BATCH_ID = 5100

    fun driveNotificationId(tripId: String): Int = 5_200_000 + Math.floorMod(tripId.hashCode(), 100_000)
    fun reminderNotificationId(expenseId: String): Int = 5_400_000 + Math.floorMod(expenseId.hashCode(), 100_000)
    fun dtcNotificationId(vehicleId: String, code: String): Int =
        5_600_000 + Math.floorMod((vehicleId + code).hashCode(), 100_000)

    /** Posting needs POST_NOTIFICATIONS on 13+; everywhere, the app-level switch. */
    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        return context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true
    }

    fun openTrip(context: Context, tripId: String, edit: Boolean, requestCode: Int): PendingIntent =
        activity(context, requestCode) {
            action = MainActivity.ACTION_OPEN_TRIP
            putExtra(MainActivity.EXTRA_TRIP_ID, tripId)
            putExtra(MainActivity.EXTRA_EDIT, edit)
        }

    fun openTrips(context: Context, requestCode: Int): PendingIntent =
        activity(context, requestCode) { action = MainActivity.ACTION_SYNC_DRIVES }

    fun openDtc(context: Context, code: String, vehicleId: String, requestCode: Int): PendingIntent =
        activity(context, requestCode) {
            action = MainActivity.ACTION_OPEN_DTC
            putExtra(MainActivity.EXTRA_DTC_CODE, code)
            putExtra(MainActivity.EXTRA_VEHICLE_ID, vehicleId)
        }

    fun openService(context: Context, requestCode: Int): PendingIntent =
        activity(context, requestCode) { action = MainActivity.ACTION_OPEN_SERVICE }

    private fun activity(context: Context, requestCode: Int, block: Intent.() -> Unit): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            block()
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
