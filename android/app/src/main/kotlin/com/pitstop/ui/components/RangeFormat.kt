package com.pitstop.ui.components

import com.pitstop.domain.RangeBasis
import com.pitstop.domain.RangeEstimate
import com.pitstop.util.UnitFormat
import kotlin.math.roundToInt

/** Text for range-to-empty surfaces (Home hero, Fuel hub, widget). Pure. */
object RangeFormat {

    /** "248 mi" / "399 km"; "—" when unknown. */
    fun range(rangeMi: Double?, system: String): String =
        UnitFormat.Quantity.DistanceMi.format(rangeMi, system, 0)

    /** "12.2 gal · 62 %" — either half may be missing. */
    fun fuelLine(est: RangeEstimate, system: String): String = listOfNotNull(
        est.fuel.usGallons?.let { UnitFormat.volumeGal(it, system, 1) },
        est.fuel.pct?.let { "${it.roundToInt()} %" },
    ).joinToString(" · ").ifEmpty { "—" }

    /** "at 20.4 mpg (last 30 days)". */
    fun basisLine(basis: RangeBasis?, system: String): String? =
        basis?.let { "at ${UnitFormat.economy(it.mpg, system)} (${it.source.label})" }

    /** "Fuel reading 3 h old" once it is more than an hour old; null when fresh or unknown. */
    fun readingAge(readingAtMs: Long?, nowMs: Long): String? {
        val age = readingAtMs?.let { nowMs - it } ?: return null
        if (age < 3_600_000L) return null
        val h = age / 3_600_000L
        return if (h < 48) "Fuel reading $h h old" else "Fuel reading ${h / 24} d old"
    }

    /** "5 days ago" / "today" / "yesterday" for a date-ish ISO string. */
    fun daysAgo(iso: String, today: java.time.LocalDate = java.time.LocalDate.now()): String {
        val d = runCatching {
            java.time.OffsetDateTime.parse(iso).atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDate()
        }.getOrNull() ?: runCatching { java.time.LocalDate.parse(iso.take(10)) }.getOrNull() ?: return ""
        val n = java.time.temporal.ChronoUnit.DAYS.between(d, today)
        return when {
            n <= 0L -> "today"
            n == 1L -> "yesterday"
            else -> "$n days ago"
        }
    }
}
