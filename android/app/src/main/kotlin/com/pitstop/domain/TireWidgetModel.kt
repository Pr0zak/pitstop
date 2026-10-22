package com.pitstop.domain

import kotlin.math.roundToInt

/**
 * What the tire home-screen widget shows, computed from the last readings
 * (metric → value, epoch ms). Pure so it is unit-tested; the provider only
 * draws it. Same rules as the in-app Tires card: whole psi always, LOW =
 * 2 psi or more below the highest tire ([VehicleSignals.tpms]).
 */
data class TireWidgetModel(
    val cells: List<Cell>,
    /** "5m ago" / "2h ago" / "3d ago" / "live"; null when nothing reported. */
    val age: String?,
    /** Newest reading older than 24 h — the widget dims. */
    val stale: Boolean,
    /** "of 35" when the vehicle has a placard (front = rear); else null. */
    val placardLabel: String?,
    val empty: Boolean,
) {
    data class Cell(
        val corner: VehicleSignals.Corner,
        /** Whole psi or "—". */
        val psi: String,
        val low: Boolean,
        /** "22°" in the user's unit, or null. */
        val temp: String?,
        /** "−4" against the placard (cold-pressure reference), or null. */
        val vsPlacard: String?,
        /** ≥ 3 psi under placard → the delta turns amber. */
        val belowSpec: Boolean = false,
        /** ≥ 25 % under placard → red "under". */
        val underInflated: Boolean = false,
    )

    companion object {
        const val STALE_AFTER_MS = 24L * 3_600_000L

        fun of(
            readings: Map<String, Pair<Double, Long>>,
            nowMs: Long,
            system: String,
            placardFrontKpa: Double? = null,
            placardRearKpa: Double? = null,
        ): TireWidgetModel {
            // Sentinels the backend drops too: 0 kPa = no sensor answer.
            val pressure = VehicleSignals.Corner.entries.associateWith { c ->
                readings[c.pressureMetric]?.first?.takeIf { it > 0 }
            }
            val tires = VehicleSignals.tpms(
                pressure,
                VehicleSignals.Corner.entries.associateWith { readings[it.tempMetric]?.first },
            )
            val newest = VehicleSignals.Corner.entries
                .mapNotNull { c -> readings[c.pressureMetric]?.takeIf { it.first > 0 }?.second }
                .maxOrNull()
            val cells = tires.map { t ->
                val verdict = VehicleSignals.placardVerdict(
                    t.pressureKpa,
                    VehicleSignals.placardFor(t.corner, placardFrontKpa, placardRearKpa),
                )
                Cell(
                    corner = t.corner,
                    psi = VehicleSignals.tirePressure(t.pressureKpa),
                    low = t.low,
                    temp = t.tempC?.takeIf { VehicleSignals.validTireTempC(it) }?.let {
                        val v = if (system == "imperial") it * 9.0 / 5.0 + 32.0 else it
                        "${v.roundToInt()}°"
                    },
                    vsPlacard = verdict?.deltaLabel,
                    belowSpec = verdict?.belowSpec == true,
                    underInflated = verdict?.underInflated == true,
                )
            }
            val placardLabel = if (placardFrontKpa != null &&
                (placardRearKpa == null || placardRearKpa == placardFrontKpa)
            ) {
                "of ${(placardFrontKpa * VehicleSignals.PSI_PER_KPA).roundToInt()}"
            } else {
                null
            }
            return TireWidgetModel(
                cells = cells,
                age = newest?.let { VehicleSignals.ageLabel(it, nowMs) },
                stale = newest != null && nowMs - newest > STALE_AFTER_MS,
                placardLabel = placardLabel,
                empty = newest == null,
            )
        }
    }
}
