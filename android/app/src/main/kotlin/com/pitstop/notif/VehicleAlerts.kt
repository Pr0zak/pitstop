package com.pitstop.notif

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.pitstop.R
import com.pitstop.data.AppPrefs
import com.pitstop.domain.AlertRules
import com.pitstop.domain.DtcGuide
import com.pitstop.domain.DtcSeverity
import com.pitstop.domain.DueState
import com.pitstop.domain.Maintenance
import com.pitstop.domain.ReminderItem
import com.pitstop.http.DtcDto
import com.pitstop.http.VehicleDto
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Check-engine and service-due notifications. Called from wherever the app
 * already has fresh data — Home's refresh, History's refresh, the Service
 * screen — and from the daily [VehicleCheckWorker]. Dedupe state lives in
 * [AppPrefs], so however many of those run, each code / reminder state
 * notifies once.
 *
 * With a toggle off the state is still recorded, so switching it back on
 * doesn't replay everything that happened meanwhile.
 */
@Singleton
class VehicleAlerts @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: AppPrefs,
) {
    private val lock = Mutex()

    suspend fun onActiveDtcs(vehicle: VehicleDto, active: List<DtcDto>) = lock.withLock {
        val (seen, seeded) = prefs.seenDtcs(vehicle.id)
        val (fresh, newSeen) = AlertRules.newDtcCodes(active.map { it.code }.toSet(), seen, seeded)
        if (newSeen != seen || !seeded) prefs.setSeenDtcs(vehicle.id, newSeen)
        if (fresh.isEmpty() || !prefs.vehicleAlertNotif.first() || !AppNotifications.canPost(context)) return@withLock
        val nm = context.getSystemService(NotificationManager::class.java) ?: return@withLock
        for (code in fresh) {
            val desc = active.firstOrNull { it.code.equals(code, ignoreCase = true) }?.description
            val g = DtcGuide.lookup(code, desc)
            val id = AppNotifications.dtcNotificationId(vehicle.id, code)
            nm.notify(
                id,
                NotificationCompat.Builder(context, AppNotifications.VEHICLE_ALERT_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_warning)
                    .setContentTitle("New check-engine code $code · ${g.title} · ${g.severity.label}")
                    .setContentText(g.safeToDrive)
                    .setStyle(
                        NotificationCompat.BigTextStyle().bigText(
                            "${vehicle.name}: ${g.safeToDrive}\n${DtcGuide.DISCLAIMER}",
                        ),
                    )
                    .setPriority(
                        if (g.severity == DtcSeverity.STOP_NOW) NotificationCompat.PRIORITY_HIGH
                        else NotificationCompat.PRIORITY_DEFAULT,
                    )
                    .setAutoCancel(true)
                    .setContentIntent(AppNotifications.openDtc(context, code, vehicle.id, id))
                    .build(),
            )
        }
    }

    suspend fun onReminders(vehicle: VehicleDto, items: List<ReminderItem>) = lock.withLock {
        val miles = Maintenance.distInMiles(vehicle)
        val current = items.associate { it.expenseId to Maintenance.dueState(it, miles) }
        val last = prefs.reminderStates(vehicle.id)
        val (notify, next) = AlertRules.reminderTransitions(current, last)
        if (next != last) prefs.setReminderStates(vehicle.id, next)
        if (notify.isEmpty() || !prefs.serviceReminderNotif.first() || !AppNotifications.canPost(context)) return@withLock
        val nm = context.getSystemService(NotificationManager::class.java) ?: return@withLock
        val unit = if (miles) "mi" else "km"
        for (id in notify) {
            val item = items.firstOrNull { it.expenseId == id } ?: continue
            val overdue = current[id] == DueState.Overdue
            val detail = listOfNotNull(
                item.distanceRemaining?.let { d ->
                    if (d < 0) "${"%,.0f".format(-d)} $unit over" else "${"%,.0f".format(d)} $unit left"
                },
                item.daysRemaining?.let { d -> if (d < 0) "${-d} days over" else "$d days left" },
            ).joinToString(" · ")
            val nid = AppNotifications.reminderNotificationId(id)
            nm.notify(
                nid,
                NotificationCompat.Builder(context, AppNotifications.SERVICE_REMINDER_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_wrench)
                    .setContentTitle(if (overdue) "${item.title} is overdue" else "${item.title} due soon")
                    .setContentText(listOf(vehicle.name, detail).filter { it.isNotBlank() }.joinToString(" · "))
                    .setAutoCancel(true)
                    .setContentIntent(AppNotifications.openService(context, nid))
                    .build(),
            )
        }
    }
}
