package com.pitstop.domain

import java.util.Locale

/** The three linked numbers of a fillup. */
enum class FuelField { Total, Price, Volume }

/**
 * Total, price-per-unit and volume, where any two determine the third.
 *
 * [pinned] holds the fields the user typed, most recent last (at most two).
 * The field NOT pinned is the derived one and is recomputed on every edit,
 * so typing "Total, then price" fills the volume, and switching to typing
 * the volume re-derives whichever of the other two was touched longest ago.
 * All three are text in DISPLAY units — conversion happens at save time.
 */
data class FuelTriple(
    val total: String = "",
    val price: String = "",
    val volume: String = "",
    val pinned: List<FuelField> = listOf(FuelField.Total, FuelField.Price),
) {
    val derived: FuelField
        get() = FuelField.entries.first { it !in pinned.takeLast(2) }

    fun edit(field: FuelField, value: String): FuelTriple {
        val newPinned = (pinned - field + field).takeLast(2)
        val next = when (field) {
            FuelField.Total -> copy(total = value)
            FuelField.Price -> copy(price = value)
            FuelField.Volume -> copy(volume = value)
        }.copy(pinned = newPinned)
        return next.recompute()
    }

    /** Fill the derived field from the pinned two; leaves it alone if they aren't usable. */
    fun recompute(): FuelTriple {
        val t = total.toPositiveOrNull()
        val p = price.toPositiveOrNull()
        val v = volume.toPositiveOrNull()
        return when (derived) {
            FuelField.Volume -> if (t != null && p != null) copy(volume = fmt(t / p, 3)) else this
            FuelField.Price -> if (t != null && v != null) copy(price = fmt(t / v, 3)) else this
            FuelField.Total -> if (p != null && v != null) copy(total = fmt(p * v, 2)) else this
        }
    }

    private fun String.toPositiveOrNull(): Double? = trim().toDoubleOrNull()?.takeIf { it > 0.0 }

    private fun fmt(x: Double, digits: Int): String {
        val s = String.format(Locale.US, "%.${digits}f", x)
        return if (digits == 2) s else s.trimEnd('0').trimEnd('.')
    }
}
