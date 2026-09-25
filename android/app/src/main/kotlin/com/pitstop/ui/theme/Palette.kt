package com.pitstop.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Data-visualisation colours: chart series, the speed-bucket ramp and the
 * heatmap legends. These are NOT theme roles — a chart line has to stay the
 * same hue in every theme so the legend chip and the line keep matching —
 * but they live here, beside the theme, so no screen carries its own hex.
 *
 * Values match the web frontend's chart palette so a trip reads the same on
 * both clients.
 */
object ChartPalette {
    // ── Trip timeline series ────────────────────────────────────────
    val speed = Color(0xFF2F81F7)
    val rpm = Color(0xFFF59E0B)
    val load = Color(0xFFEAB308)
    val coolant = Color(0xFFEF4444)
    val fuel = Color(0xFF22C55E)
    val throttle = Color(0xFF14B8A6)
    val intake = Color(0xFF94A3B8)
    val maf = Color(0xFF06B6D4)
    val map = Color(0xFFA78BFA)
    val battery = Color(0xFFF472B6)
    val fuelRate = Color(0xFFF97316)
    val exhaust = Color(0xFFA3E635)
    val catB1 = Color(0xFFFB7185)
    val catB2 = Color(0xFFE879F9)
    val cmdAfr = Color(0xFF38BDF8)
    val o2 = Color(0xFF818CF8)
    val fuelRail = Color(0xFF34D399)
    val odometer = Color(0xFF8B949E)

    /** Single-series accent for stat-strip bars / sparklines. */
    val accent = Color(0xFFF97316)

    /** Empty-day stub in the 14-day distance bars. */
    val idleBar = Color(0xFF3A3F47)

    // ── Speed buckets (55 mph highway cutoff, shared with web) ──────
    val speedStop = Color(0xFFEF4444)
    val speedCity = Color(0xFFF59E0B)
    val speedSuburb = Color(0xFF22C55E)
    val speedHighway = Color(0xFF2F81F7)

    // ── Heatmap legends ─────────────────────────────────────────────
    /** Visit-count ramp: 1×, 3, 8, 20, 50, 50+. */
    val density = listOf(
        Color(0xFF475569), Color(0xFF06B6D4), Color(0xFF22C55E),
        Color(0xFFEAB308), Color(0xFFF97316), Color(0xFFEF4444),
    )

    /** 7 HSL stops 0..280° at s=80% l=50%, mirroring the line ramp. */
    val speedRamp = listOf(
        Color(0xFFE53935), Color(0xFFE5A03A), Color(0xFF99CC2E), Color(0xFF22C55E),
        Color(0xFF2AB7C5), Color(0xFF3D7CFF), Color(0xFFB047D6),
    )
}
