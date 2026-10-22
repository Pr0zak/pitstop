package com.pitstop.car

import com.pitstop.domain.TireWidgetModel

/**
 * The Tires tab's pane rows, from the same [TireWidgetModel] the
 * home-screen widget draws — so the car, the widget and the Home card can
 * never disagree about which tire is LOW. Pure, unit-tested in TirePaneTest.
 *
 * TWO rows on purpose. At 800×480 the host shows about two and a half rows
 * beside the image and scrolls the image WITH the rows, so a third row put
 * half the car diagram off screen (seen on the DHU 2026-10-22). The image
 * carries the pressures, with LOW / under-inflated in colour; the rows are
 * how old the reading is and the temperature range.
 *
 * Row TITLES are fixed and there are always two: PaneTemplate's refresh
 * predicate compares row count and titles, so only the values may move or
 * the update spends one of the host's five templates per task.
 */
internal object TirePane {

    val TITLES = listOf("Updated", "Temps")

    fun rows(m: TireWidgetModel, system: String): List<Pair<String, String>> = listOf(
        TITLES[0] to updated(m),
        TITLES[1] to temps(m, system),
    )

    fun updated(m: TireWidgetModel): String = when (m.age) {
        null -> "No readings yet"
        "live" -> "Live"
        else -> m.age
    }

    /** Range across the tires that reported a temperature, in the user's unit. */
    fun temps(m: TireWidgetModel, system: String): String {
        val vals = m.cells.mapNotNull { it.temp?.removeSuffix("°")?.toIntOrNull() }
        if (vals.isEmpty()) return "—"
        val unit = if (system == "imperial") "°F" else "°C"
        val lo = vals.min()
        val hi = vals.max()
        return if (lo == hi) "$lo$unit" else "$lo–$hi$unit"
    }
}
