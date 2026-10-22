package com.pitstop.car

import kotlin.math.roundToInt

/**
 * What the Android Auto Cylinders tab shows, from the phone's Honda Mode 22
 * poll: `active_cylinders` (PCM DID 2615, every 2 s — 6, or 3 when Variable
 * Cylinder Management runs on one bank) and `misfire_count_1..6` (DID 2663,
 * every 60 s). Pure; unit-tested in CylinderPaneTest.
 */
internal data class CylinderModel(
    /** Cylinders firing now; null when there is no fresh reading. */
    val active: Int?,
    /** Which of cylinders 1..6 are firing (index 0 = cylinder 1). */
    val firing: List<Boolean>?,
    /** Misfire count per cylinder 1..6; missing = not read yet. */
    val misfires: Map<Int, Int>,
    /** Why there is no fresh reading ("Engine off", "Bridge off"…), or null. */
    val idleReason: String?,
) {
    val eco: Boolean get() = active != null && active < CYLINDERS

    companion object {
        const val CYLINDERS = 6

        /** A cylinder count older than this is not "now" any more. */
        const val FRESH_MS = 10_000L

        /**
         * Honda's J35 VCM shuts the REAR bank — cylinders 1, 2, 3 — in
         * 3-cylinder mode (the 2016+ Pilot has no 4-cylinder mode). Any other
         * count has no known bank layout, so it gets no per-cylinder map.
         */
        fun firingFor(active: Int): List<Boolean>? = when (active) {
            6 -> List(6) { true }
            3 -> List(6) { it >= 3 }
            else -> null
        }

        fun of(
            activeValue: Double?,
            activeTsMs: Long?,
            misfires: Map<Int, Int>,
            nowMs: Long,
            idleReason: String?,
        ): CylinderModel {
            val fresh = activeValue != null && activeTsMs != null && nowMs - activeTsMs <= FRESH_MS
            val active = if (fresh) activeValue!!.roundToInt().takeIf { it in 1..CYLINDERS } else null
            return CylinderModel(
                active = active,
                firing = active?.let { firingFor(it) },
                misfires = misfires,
                idleReason = if (active == null) idleReason ?: "Waiting for data" else null,
            )
        }
    }
}

internal object CylinderPane {

    /**
     * Two rows, fixed titles, and text that changes only with the state: the
     * firing count and the eco share are in the image. VCM switches banks
     * every few seconds on a highway, so "3 of 6 · VCM eco" in a row was a
     * new template per switch, and the host blocks the app after about five
     * while driving (see [TirePane]).
     */
    val TITLES = listOf("Status", "Misfires")

    fun rows(m: CylinderModel, @Suppress("UNUSED_PARAMETER") share: VcmTracker.Share? = null): List<Pair<String, String>> = listOf(
        TITLES[0] to status(m),
        TITLES[1] to misfires(m),
    )

    fun status(m: CylinderModel): String = if (m.active == null) m.idleReason ?: "—" else "Live"

    /** Cylinders with misfires, without the counts (those are in the image). */
    fun misfires(m: CylinderModel): String {
        if (m.misfires.isEmpty()) return "—"
        val bad = m.misfires.filterValues { it > 0 }.keys.sorted()
        return if (bad.isEmpty()) "None" else "Cyl " + bad.joinToString(", ")
    }

    /** "50 %" eco over the last [VcmTracker] window, or null under a minute. */
    fun sharePct(s: VcmTracker.Share?): Int? {
        if (s == null || s.totalMs < 60_000L) return null
        return (100.0 * s.ecoMs / s.totalMs).roundToInt()
    }
}

/**
 * Time spent on fewer than six cylinders, from consecutive active-cylinder
 * samples while the car screen is open: each gap between two samples counts
 * toward the EARLIER sample's mode. A gap over [RESET_GAP_MS] (car parked,
 * screen closed) starts a fresh window, so the share describes the current
 * drive rather than the week.
 */
internal class VcmTracker {
    data class Share(val ecoMs: Long, val totalMs: Long)

    private var lastTs: Long? = null
    private var lastEco = false
    private var ecoMs = 0L
    private var totalMs = 0L

    fun ingest(activeCylinders: Double, tsMs: Long) {
        val prev = lastTs
        if (prev != null && tsMs <= prev) return // same sample re-seen
        if (prev != null) {
            val gap = tsMs - prev
            if (gap > RESET_GAP_MS) {
                ecoMs = 0L
                totalMs = 0L
            } else {
                totalMs += gap
                if (lastEco) ecoMs += gap
            }
        }
        lastTs = tsMs
        lastEco = activeCylinders.roundToInt() in 1 until CylinderModel.CYLINDERS
    }

    fun share(): Share? = if (totalMs > 0) Share(ecoMs, totalMs) else null

    companion object {
        const val RESET_GAP_MS = 10 * 60_000L
    }
}
