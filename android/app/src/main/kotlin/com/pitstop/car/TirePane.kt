package com.pitstop.car

import com.pitstop.domain.TireWidgetModel

/**
 * The Tires tab's pane rows, from the same [TireWidgetModel] the
 * home-screen widget draws — so the car, the widget and the Home card can
 * never disagree about which tire is LOW. Pure, unit-tested in TirePaneTest.
 *
 * TWO rows on purpose. At 800×480 the host shows about two and a half rows
 * beside the image and scrolls the image WITH the rows, so a third row put
 * half the car diagram off screen (seen on the DHU 2026-10-22).
 *
 * The row TEXT must stay put while driving, not only the titles.
 * PaneTemplate's refresh predicate compares each row's title AND texts, so
 * any change in a row's text is a new template, and the host allows about
 * five per task while driving before it blocks the app with "This task
 * can't be completed while driving" (seen in the car 2026-10-22, when the
 * rows carried a temperature range that moved as the tires warmed). The
 * image is not compared, so every number lives in the image; the rows carry
 * only states that change a few times a drive: "Live" or the clock time of
 * the last reading (not an age, which would tick every minute), and which
 * tire is low.
 */
internal object TirePane {

    val TITLES = listOf("Updated", "Pressure")

    fun rows(
        m: TireWidgetModel,
        @Suppress("UNUSED_PARAMETER") system: String,
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
        nowMs: Long = System.currentTimeMillis(),
    ): List<Pair<String, String>> = listOf(
        TITLES[0] to updated(m, zone, nowMs),
        TITLES[1] to pressure(m),
    )

    fun updated(m: TireWidgetModel, zone: java.time.ZoneId, nowMs: Long): String {
        val at = m.newestMs ?: return "No readings yet"
        if (m.age == "live") return "Live"
        val t = java.time.Instant.ofEpochMilli(at).atZone(zone)
        val today = java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val clock = java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.US)
        return if (t.toLocalDate() == today) {
            "At ${t.format(clock)}"
        } else {
            t.format(java.time.format.DateTimeFormatter.ofPattern("MMM d, h:mm a", java.util.Locale.US))
        }
    }

    /** Which tires need air; changes only when a tire crosses a rule. */
    fun pressure(m: TireWidgetModel): String {
        if (m.empty) return "—"
        val under = m.cells.filter { it.underInflated }.map { it.corner.label }
        val low = m.cells.filter { it.low && !it.underInflated }.map { it.corner.label }
        return when {
            under.isNotEmpty() -> under.joinToString(", ") + " under-inflated"
            low.isNotEmpty() -> low.joinToString(", ") + " low"
            else -> "All OK"
        }
    }
}
