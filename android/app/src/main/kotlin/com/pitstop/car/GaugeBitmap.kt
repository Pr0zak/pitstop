package com.pitstop.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/**
 * Draws one gauge tile's image: a 270° arc filled to [fraction] with the
 * value's number in the middle, big enough to read at a glance on an
 * 800×480 head unit — the number is the tile's point, and as GridItem text
 * it was the smallest thing on it.
 *
 * Why a bitmap: templates have no custom drawing, but a GridItem's image may
 * be a bitmap, and the grid's refresh predicate compares item COUNT and
 * TITLES only. Swapping the image every tick is therefore an in-place
 * refresh and never spends the five-template quota.
 *
 * Colours are literal ARGB, not CarColor: the host cannot re-tint a
 * multi-colour bitmap. They are chosen for Android Auto's dark surface,
 * which is the only one it draws; the values are the host's own palette
 * (Material dark blue / yellow / red / grey) so the tile sits in with it.
 *
 * Kept small on purpose: every tile's bitmap crosses Binder with every
 * template, and six ARGB bitmaps at [SIZE_PX] come to ~250 KB, a safe
 * margin under the ~1 MB transaction limit.
 */
object GaugeBitmap {
    const val SIZE_PX = 104

    private const val TRACK = 0xFF3C4043.toInt()
    private const val NORMAL = 0xFF8AB4F8.toInt()
    private const val CAUTION = 0xFFFDD663.toInt()
    private const val SEVERE = 0xFFF28B82.toInt()
    private const val STALE = 0xFF9AA0A6.toInt()
    private const val NUMBER = 0xFFFFFFFF.toInt()

    private const val START_DEG = 135f
    private const val SWEEP_DEG = 270f

    /**
     * @param arc draw the arc at all (the spec has a gauge range); the track
     *   still shows when [fraction] is null, so an empty tile keeps its shape.
     */
    fun render(number: String, fraction: Double?, arc: Boolean, warn: TileWarn, stale: Boolean): Bitmap {
        val size = SIZE_PX
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val stroke = size * 0.09f
        val inset = stroke / 2f + 1f
        val oval = RectF(inset, inset, size - inset, size - inset)
        val color = when {
            warn == TileWarn.Severe -> SEVERE
            warn == TileWarn.Caution -> CAUTION
            stale -> STALE
            else -> NORMAL
        }
        if (arc) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = stroke
                strokeCap = Paint.Cap.ROUND
            }
            paint.color = TRACK
            c.drawArc(oval, START_DEG, SWEEP_DEG, false, paint)
            val f = (fraction ?: 0.0).coerceIn(0.0, 1.0).toFloat()
            if (f > 0f) {
                paint.color = color
                c.drawArc(oval, START_DEG, SWEEP_DEG * f, false, paint)
            }
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = if (stale) STALE else if (warn == TileWarn.None) NUMBER else color
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            textSize = size * 0.34f
        }
        // Shrink long values ("1850", "14.1", "—") to fit inside the arc.
        val maxW = size * 0.66f
        val w = text.measureText(number)
        if (w > maxW) text.textSize *= maxW / w
        val y = size / 2f - (text.descent() + text.ascent()) / 2f
        c.drawText(number, size / 2f, y, text)
        return bmp
    }

    /** Where [value] sits on [range], or null when there is no arc to fill. */
    fun fraction(value: Double?, range: ClosedFloatingPointRange<Double>?): Double? {
        if (value == null || range == null || value.isNaN()) return null
        val span = range.endInclusive - range.start
        if (span <= 0) return null
        return (value - range.start) / span
    }
}
