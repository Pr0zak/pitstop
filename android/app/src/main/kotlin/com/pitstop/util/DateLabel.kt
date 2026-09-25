package com.pitstop.util

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Group-aware date labels for lists and detail titles. The web frontend
 * applies the same rules, so a trip reads the same on both clients.
 *
 * List rules, by how many calendar days back the (local) date is:
 *  - today / yesterday → time only ("6:33 AM") when the row sits under a
 *    "Today" / "Yesterday" group header ([grouped] = true); prefixed
 *    ("Today 6:33 AM", "Yesterday 6:33 AM") in an ungrouped list
 *  - 2–6 days back     → weekday + time ("Tue 6:32 PM")
 *  - same year         → "Sep 18", plus ", 6:32 PM" when [withTime]
 *  - older             → "Sep 7, 2025" (never a time)
 *
 * Detail titles: "Thu, Sep 25 · 6:33 AM" ("Sun, Sep 7, 2025 · 6:33 AM"
 * in another year).
 *
 * 12h vs 24h follows the system setting — callers pass
 * `android.text.format.DateFormat.is24HourFormat(context)`; this object
 * stays pure-Kotlin so it can be unit-tested. The backend serves UTC;
 * everything is converted to [zone] before a date is taken, so a 7 PM
 * drive whose UTC stamp rolls into tomorrow is still "today".
 */
object DateLabel {

    /** List label for an ISO-8601 timestamp; the raw prefix if it doesn't parse. */
    fun list(
        iso: String,
        withTime: Boolean,
        grouped: Boolean,
        is24h: Boolean,
        now: ZonedDateTime = ZonedDateTime.now(),
        zone: ZoneId = now.zone,
        locale: Locale = Locale.getDefault(),
    ): String {
        val t = parse(iso, zone) ?: return iso.take(16)
        val today = now.withZoneSameInstant(zone).toLocalDate()
        val daysAgo = ChronoUnit.DAYS.between(t.toLocalDate(), today)
        val time = time(t, is24h, locale)
        return when {
            daysAgo <= 0L -> if (grouped) time else "Today $time"
            daysAgo == 1L -> if (grouped) time else "Yesterday $time"
            // Strictly under a week: seven days back is the same weekday
            // as today, which a bare "Thu" would misread as this week's.
            daysAgo < 7L -> "${t.format(fmt("EEE", locale))} $time"
            t.year == today.year -> {
                val d = t.format(fmt("MMM d", locale))
                if (withTime) "$d, $time" else d
            }
            else -> t.format(fmt("MMM d, yyyy", locale))
        }
    }

    /** "Fri, Sep 25 · 6:33 AM" — the top-bar title of a trip / fillup. */
    fun detailTitle(
        iso: String?,
        is24h: Boolean,
        now: ZonedDateTime = ZonedDateTime.now(),
        zone: ZoneId = now.zone,
        locale: Locale = Locale.getDefault(),
    ): String {
        val t = iso?.let { parse(it, zone) } ?: return "—"
        val sameYear = t.year == now.withZoneSameInstant(zone).year
        val date = t.format(fmt(if (sameYear) "EEE, MMM d" else "EEE, MMM d, yyyy", locale))
        return "$date · ${time(t, is24h, locale)}"
    }

    /** "6:33 AM" / "18:33". */
    fun time(t: ZonedDateTime, is24h: Boolean, locale: Locale = Locale.getDefault()): String =
        t.format(fmt(if (is24h) "HH:mm" else "h:mm a", locale))

    private fun parse(iso: String, zone: ZoneId): ZonedDateTime? =
        runCatching { OffsetDateTime.parse(iso).atZoneSameInstant(zone) }.getOrNull()
            // No offset: the wire is UTC.
            ?: runCatching { LocalDateTime.parse(iso).atZone(ZoneOffset.UTC).withZoneSameInstant(zone) }.getOrNull()
            // Date-only values (some imported fillups) read as local midnight.
            ?: runCatching { LocalDate.parse(iso.take(10)).atStartOfDay(zone) }.getOrNull()

    private fun fmt(pattern: String, locale: Locale): DateTimeFormatter =
        DateTimeFormatter.ofPattern(pattern, locale)
}
