package com.pitstop.domain

import com.pitstop.http.ExpenseCategoryDto
import com.pitstop.http.ExpenseCreateRequest
import com.pitstop.http.ExpenseDto
import com.pitstop.http.RemindersResponse
import com.pitstop.http.VehicleDto
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Ok / due soon / overdue — drives the bar colour and the notification. */
enum class DueState { Ok, DueSoon, Overdue }

/**
 * One reminder, normalised the way the web's `normalizeReminder` does it:
 * the backend splits rows into `overdue` (miles_over / days_over) and
 * `upcoming` (miles_until / days_until) with no status field, so the
 * remaining distance / days become signed numbers here (negative = over).
 *
 * Distances are in the VEHICLE's distance unit (Fuelio dist_unit), exactly
 * as the backend stores and returns them.
 */
data class ReminderItem(
    val expenseId: String,
    val title: String,
    val category: String?,
    val currentOdo: Double?,
    val remindOdo: Double?,
    val remindDate: String?,
    val distanceRemaining: Double?,
    val daysRemaining: Int?,
    val overdue: Boolean,
)

data class MaintenancePreset(
    val key: String,
    val title: String,
    val miles: Double,
    val km: Double,
    val months: Int? = null,
)

/** "Last service logged 68,384 mi ago (Jan 2020)". [behind] null = unknown distance. */
data class StaleService(val behind: Double?, val lastDate: String)

/**
 * The phone's maintenance rules. Mirrors frontend/src/views/MaintenanceView.vue
 * (presets, the Scheduled list, the stale-service warning, the progress bar)
 * so the two clients agree; the due-soon threshold is the phone's own
 * (see [dueState]).
 */
object Maintenance {

    /** Notes marker on the $0 anchor a preset creates (web constant, verbatim). */
    const val PRESET_NOTE = "Reminder preset — interval starts at this odometer"

    /** Web PRESETS, verbatim. */
    val PRESETS: List<MaintenancePreset> = listOf(
        MaintenancePreset("oil", "Oil change", 5_000.0, 8_000.0, months = 6),
        MaintenancePreset("rotation", "Tire rotation", 7_500.0, 12_000.0),
        MaintenancePreset("air", "Engine air filter", 30_000.0, 48_000.0),
        MaintenancePreset("atf", "ATF", 30_000.0, 48_000.0),
    )

    /** "Due soon" = within this distance (miles) or [SOON_DAYS] days. */
    const val SOON_MILES = 300.0
    const val SOON_KM = 480.0
    const val SOON_DAYS = 14

    /** Categories counted as "service history" on the phone. Uncategorised
     *  rows count too — web presets and hand-logged services carry none. */
    val SERVICE_CATEGORIES = setOf("service", "maintenance", "repairs", "tires", "tuning")

    fun normalize(r: RemindersResponse): List<ReminderItem> =
        r.overdue.map { x ->
            ReminderItem(
                expenseId = x.expenseId,
                title = x.title?.takeIf { it.isNotBlank() } ?: "Service",
                category = x.category,
                currentOdo = x.currentOdo,
                remindOdo = x.remindOdo,
                remindDate = x.remindDate,
                distanceRemaining = x.milesOver?.let { -it },
                daysRemaining = x.daysOver?.let { -it },
                overdue = true,
            )
        } + r.upcoming.map { x ->
            ReminderItem(
                expenseId = x.expenseId,
                title = x.title?.takeIf { it.isNotBlank() } ?: "Service",
                category = x.category,
                currentOdo = x.currentOdo,
                remindOdo = x.remindOdo,
                remindDate = x.remindDate,
                distanceRemaining = x.milesUntil,
                daysRemaining = x.daysUntil,
                overdue = false,
            )
        }

    /**
     * Overdue when the backend says so; due soon within 300 mi (480 km) or
     * 14 days; else ok. The web tints "warn" tighter (100 mi / 7 d) — the
     * phone uses the notification threshold so the bar and the alert agree.
     */
    fun dueState(item: ReminderItem, distInMiles: Boolean): DueState {
        if (item.overdue) return DueState.Overdue
        val soonDist = if (distInMiles) SOON_MILES else SOON_KM
        val nearDist = item.distanceRemaining?.let { it <= soonDist } ?: false
        val nearDays = item.daysRemaining?.let { it <= SOON_DAYS } ?: false
        return if (nearDist || nearDays) DueState.DueSoon else DueState.Ok
    }

    /**
     * Fraction (0–1) of the interval used — by distance or by date, whichever
     * is further along. The reminder row IS the last service, so its own odo
     * and date are the start. Web `progress()`, verbatim.
     */
    fun progress(item: ReminderItem, expense: ExpenseDto?, today: LocalDate): Double? {
        if (item.overdue) return 1.0
        val fracs = mutableListOf<Double>()
        val startOdo = expense?.odo
        val remindOdo = item.remindOdo
        val cur = item.currentOdo
        if (startOdo != null && remindOdo != null && cur != null && remindOdo > startOdo) {
            fracs += (cur - startOdo) / (remindOdo - startOdo)
        }
        val start = expense?.expenseDate?.let(::parseDate)
        val end = item.remindDate?.let(::parseDate)
        if (start != null && end != null && end.isAfter(start)) {
            val total = ChronoUnit.DAYS.between(start, end).toDouble()
            fracs += ChronoUnit.DAYS.between(start, today) / total
        }
        if (fracs.isEmpty()) return null
        return fracs.max().coerceIn(0.0, 1.0)
    }

    /** Same predicate as the backend: a real reminder, not Fuelio's 2011-01-01 placeholder. */
    fun hasReminder(e: ExpenseDto): Boolean {
        if (e.isTemplate) return false
        if (e.remindOdo != null && e.remindOdo > 0) return true
        val rd = e.remindDate ?: return false
        return rd >= e.expenseDate.take(10)
    }

    /** Reminders the endpoint omits because they're further out than its window. */
    fun scheduled(expenses: List<ExpenseDto>, listed: List<ReminderItem>): List<ExpenseDto> {
        val ids = listed.mapTo(HashSet()) { it.expenseId }
        return expenses.filter { hasReminder(it) && it.id !in ids }
    }

    fun isPresetAnchor(e: ExpenseDto): Boolean = e.notes == PRESET_NOTE

    /** Every logged cost, newest first — the web's "Completed" list. */
    fun completedAll(expenses: List<ExpenseDto>): List<ExpenseDto> =
        expenses.filter { !it.isTemplate && !it.isIncome }.sortedByDescending { it.expenseDate }

    /** The phone's service history: [completedAll] narrowed to service-type categories. */
    fun serviceHistory(expenses: List<ExpenseDto>, categories: List<ExpenseCategoryDto>): List<ExpenseDto> {
        val byId = categories.associate { it.id to it.name.trim().lowercase() }
        return completedAll(expenses).filter { e ->
            val cat = e.costTypeId?.let { byId[it] }
            e.costTypeId == null || cat == null || cat in SERVICE_CATEGORIES
        }
    }

    /**
     * Current odometer in the vehicle's unit — the web's basis for presets
     * (latest_odo_km, rounded; miles when dist_unit = 1).
     */
    fun currentOdo(v: VehicleDto?): Double? {
        val km = v?.latestOdoKm ?: return null
        return Math.round(if (distInMiles(v)) km * 0.621371 else km).toDouble()
    }

    fun distInMiles(v: VehicleDto?): Boolean = v?.distUnit != 0

    /**
     * Web `staleService`: the newest real service (preset anchors excluded)
     * is over a year old or more than 10,000 mi / 16,000 km behind. Built
     * from [completedAll] like the web, so it agrees with the admin view.
     */
    fun staleService(
        expenses: List<ExpenseDto>,
        currentOdo: Double?,
        distInMiles: Boolean,
        today: LocalDate,
    ): StaleService? {
        val last = completedAll(expenses).firstOrNull { !isPresetAnchor(it) } ?: return null
        val date = parseDate(last.expenseDate)
        val ageDays = date?.let { ChronoUnit.DAYS.between(it, today) } ?: 0L
        val behind = if (currentOdo != null && last.odo != null) currentOdo - last.odo else null
        val limit = if (distInMiles) 10_000.0 else 16_000.0
        if (ageDays > 365 || (behind != null && behind > limit)) {
            return StaleService(behind = behind?.takeIf { it > 0 }, lastDate = last.expenseDate)
        }
        return null
    }

    /** The web's preset POST body, field for field. */
    fun presetRequest(
        p: MaintenancePreset,
        vehicleId: String,
        currentOdo: Double?,
        distInMiles: Boolean,
        today: LocalDate,
    ): ExpenseCreateRequest {
        val interval = if (distInMiles) p.miles else p.km
        return ExpenseCreateRequest(
            vehicleId = vehicleId,
            title = p.title,
            expenseDate = today.toString(),
            cost = 0.0,
            odo = currentOdo,
            notes = PRESET_NOTE,
            repeatOdo = interval,
            repeatMonths = p.months?.toDouble(),
            remindOdo = currentOdo?.let { it + interval },
            remindDate = p.months?.let { today.plusMonths(it.toLong()).toString() },
        )
    }

    private fun parseDate(s: String): LocalDate? =
        runCatching { LocalDate.parse(s.take(10)) }.getOrNull()
}
