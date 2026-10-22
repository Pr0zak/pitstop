package com.pitstop.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/**
 * The Temps tab's pane image: a 2×2 of label, big number and a range bar
 * per temperature. Colour carries the state: blue normal, cyan cold
 * (warming up / near freezing outside), amber and red past the spec's
 * bounds, grey when the reading is stale.
 *
 * Square and drawn large for the same reasons as [TireCarBitmap]: the host
 * centre-crops the pane image to a square and shows it at ~190 px.
 */
internal object TempBitmap {
    const val SIZE_PX = 320

    private const val TEXT = Color.WHITE
    private val MUTED = Color.parseColor("#9AA1A9")
    private val TRACK = Color.parseColor("#3C4043")
    private val NORMAL = Color.parseColor("#8AB4F8")
    private val COLD = Color.parseColor("#78D9EC")
    private val CAUTION = Color.parseColor("#FDD663")
    private val SEVERE = Color.parseColor("#F28B82")
    private val STALE = Color.parseColor("#5F6368")

    private fun text(size: Float, color: Int, bold: Boolean) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    fun render(m: TempModel): Bitmap {
        val s = SIZE_PX.toFloat()
        val bmp = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val half = s / 2f
        m.cells.forEachIndexed { i, cell ->
            val cx = if (i % 2 == 0) half / 2f else half * 1.5f
            val top = if (i < 2) 0f else half
            val tone = when {
                cell.stale -> STALE
                cell.warn == TileWarn.Severe -> SEVERE
                cell.warn == TileWarn.Caution -> CAUTION
                cell.cold -> COLD
                else -> NORMAL
            }
            c.drawText(cell.label.uppercase(), cx, top + s * 0.085f, text(s * 0.07f, MUTED, bold = true))
            val numColor = when {
                cell.stale -> STALE
                cell.warn != TileWarn.None || cell.cold -> tone
                else -> TEXT
            }
            val shown = if (cell.number == "—") "—" else "${cell.number}°"
            c.drawText(shown, cx, top + s * 0.30f, text(s * 0.20f, numColor, bold = true))
            // Range bar.
            val barW = half * 0.72f
            val barH = s * 0.022f
            val y = top + s * 0.37f
            val track = RectF(cx - barW / 2, y, cx + barW / 2, y + barH)
            c.drawRoundRect(track, barH, barH, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TRACK })
            cell.fraction?.let { f ->
                val fill = RectF(track.left, y, track.left + barW * f.toFloat().coerceIn(0.04f, 1f), y + barH)
                c.drawRoundRect(fill, barH, barH, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tone })
            }
        }
        // Hairline cross between the four cells.
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TRACK; strokeWidth = s * 0.006f }
        c.drawLine(half, s * 0.04f, half, s * 0.96f, line)
        c.drawLine(s * 0.04f, half - s * 0.04f, s * 0.96f, half - s * 0.04f, line)
        return bmp
    }
}
