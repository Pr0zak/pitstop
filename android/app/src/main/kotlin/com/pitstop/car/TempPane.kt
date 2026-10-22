package com.pitstop.car

import com.pitstop.service.MetricSample
import com.pitstop.util.UnitFormat

/**
 * What the Android Auto Temps tab shows: four temperatures as a 2×2 of big
 * numbers (coolant, transmission fluid, intake, outside) plus two rows — a
 * status line and the catalyst temps. Bounds and units come from each
 * metric's [CarTileSpec], so "HOT" means the same here as on a grid tile.
 * Pure; unit-tested in TempPaneTest.
 */
internal data class TempModel(val cells: List<Cell>, val idleReason: String?) {
    data class Cell(
        val key: String,
        val label: String,
        /** Whole degrees in the user's unit, or "—". */
        val number: String,
        val unit: String,
        /** Position on the spec's gauge range, or null. */
        val fraction: Double?,
        val warn: TileWarn,
        /** Coolant below operating temperature / outside near freezing. */
        val cold: Boolean,
        /** No reading, or one older than [TempModel.FRESH_MS]. */
        val stale: Boolean,
        /** Raw value in the spec's own unit (°C, or °F for outside), for the status row. */
        val raw: Double?,
    )

    companion object {
        /** The slowest of these polls is 30 s (outside, ATF); allow three. */
        const val FRESH_MS = 90_000L

        /** Coolant under this (°C) is still warming up. */
        const val WARM_C = 70.0

        /** Outside at or under this (°F) can mean ice on the road. */
        const val ICE_F = 37.0

        /** The Outside tile has no gauge of its own; its bar spans this (°F). */
        val OUTSIDE_RANGE_F = -4.0..104.0

        val KEYS = listOf("coolant_temp", "atf_temp", "intake_air_temp", "ambient_air_temp_f")
        private val LABELS = mapOf(
            "coolant_temp" to "Coolant",
            "atf_temp" to "Trans",
            "intake_air_temp" to "Intake",
            "ambient_air_temp_f" to "Outside",
        )

        fun of(live: Map<String, MetricSample>, nowMs: Long, system: String, idleReason: String?): TempModel {
            val cells = KEYS.map { key ->
                val spec = CarTileCatalog.byKey(key)!!
                val s = live[key]
                val fresh = s != null && nowMs - s.tsMs <= FRESH_MS
                val v = s?.value
                val warn = when {
                    v == null -> TileWarn.None
                    spec.warnHigh != null && v > spec.warnHigh ->
                        if (spec.warnSevere) TileWarn.Severe else TileWarn.Caution
                    spec.warnLow != null && v < spec.warnLow -> TileWarn.Caution
                    else -> TileWarn.None
                }
                Cell(
                    key = key,
                    label = LABELS.getValue(key),
                    number = spec.number(v, system),
                    unit = spec.quantity.unit(system),
                    fraction = GaugeBitmap.fraction(
                        v,
                        spec.gauge ?: OUTSIDE_RANGE_F.takeIf { key == "ambient_air_temp_f" },
                    ),
                    warn = warn,
                    cold = v != null && when (key) {
                        "coolant_temp" -> v < WARM_C
                        "ambient_air_temp_f" -> v <= ICE_F
                        else -> false
                    },
                    stale = !fresh,
                    raw = v,
                )
            }
            val any = cells.any { !it.stale }
            return TempModel(cells, if (any) null else idleReason ?: "Waiting for data")
        }
    }
}

internal object TempPane {

    /** Two rows, fixed titles — same no-scroll rule as [TirePane]. */
    val TITLES = listOf("Status", "Catalyst")

    fun rows(m: TempModel, catB1C: Double?, catB2C: Double?, system: String): List<Pair<String, String>> = listOf(
        TITLES[0] to status(m),
        TITLES[1] to catalyst(catB1C, catB2C, system),
    )

    /** Worst first: severe, caution, warming up, ice, else normal. */
    fun status(m: TempModel): String {
        m.idleReason?.let { return it }
        val live = m.cells.filter { !it.stale }
        live.firstOrNull { it.warn == TileWarn.Severe }?.let { return "${it.label} HOT · ${it.number}${it.unit}" }
        live.firstOrNull { it.warn == TileWarn.Caution }?.let { return "${it.label} HOT · ${it.number}${it.unit}" }
        live.firstOrNull { it.key == "coolant_temp" && it.cold }?.let { return "Warming up · ${it.number}${it.unit}" }
        live.firstOrNull { it.key == "ambient_air_temp_f" && it.cold }?.let { return "Ice possible · ${it.number}${it.unit} outside" }
        return "All normal"
    }

    fun catalyst(b1C: Double?, b2C: Double?, system: String): String {
        if (b1C == null && b2C == null) return "—"
        val q = UnitFormat.Quantity.TempC
        return listOfNotNull(
            b1C?.let { "B1 ${q.number(it, system, 0)}°" },
            b2C?.let { "B2 ${q.number(it, system, 0)}°" },
        ).joinToString(" · ")
    }
}
