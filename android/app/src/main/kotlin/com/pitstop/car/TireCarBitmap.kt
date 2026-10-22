package com.pitstop.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.pitstop.domain.TireWidgetModel
import com.pitstop.domain.VehicleSignals

/**
 * The Android Auto Tires tab's pane image: a simple line drawing of the
 * Pilot seen from above, nose up — body outline, windscreen, rear window,
 * mirrors, four wheels — with each tire's pressure (whole psi, big) and
 * temperature beside its wheel. LOW tires (2 psi under the highest) turn
 * amber, under-inflated (≥ 25 % under placard) red — wheel and number both,
 * the same rules as the home-screen widget ([TireWidgetModel]).
 *
 * Square, because the host centre-crops the pane image to a square — a
 * landscape bitmap lost the outer digits on the DHU — and draws it at only
 * ~190 px on an 800×480 head unit, so the numbers get most of the space: no
 * placard deltas and no footer; the reading's age and the temp range are
 * the pane's rows (TirePane).
 *
 * Colours are literal ARGB for Android Auto's dark surface (see
 * [GaugeBitmap] for why not CarColor), and the bitmap is small because it
 * crosses Binder with every template.
 */
internal object TireCarBitmap {
    const val SIZE_PX = 320

    private const val TEXT = Color.WHITE
    private val MUTED = Color.parseColor("#A8B0BA")
    private val WARN = Color.parseColor("#FFB020")
    private val BAD = Color.parseColor("#FF5A4E")
    private val LINE = Color.parseColor("#9AA1A9")

    private fun line(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = SIZE_PX * 0.013f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private fun text(size: Float, color: Int, bold: Boolean) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun Int.dim(stale: Boolean) =
        if (!stale) this else Color.argb(140, Color.red(this), Color.green(this), Color.blue(this))

    private fun toneOf(c: TireWidgetModel.Cell?): Int? = when {
        c == null -> null
        c.underInflated -> BAD
        c.low -> WARN
        else -> null
    }

    fun render(m: TireWidgetModel): Bitmap {
        val s = SIZE_PX.toFloat()
        val bmp = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val stale = m.stale
        val byCorner = m.cells.associateBy { it.corner }
        val body = line(LINE.dim(stale))

        val carW = s * 0.32f
        val carH = s * 0.94f
        val cx = s / 2f
        val top = (s - carH) / 2f
        val left = cx - carW / 2
        val right = cx + carW / 2
        val bottom = top + carH
        val wheelW = carW * 0.20f
        val wheelH = carH * 0.15f
        val frontY = top + carH * 0.20f
        val rearY = top + carH * 0.79f

        // Wheels: only the part outside the body, so no line crosses it.
        for (corner in VehicleSignals.Corner.entries) {
            val isLeft = corner == VehicleSignals.Corner.FL || corner == VehicleSignals.Corner.RL
            val front = corner == VehicleSignals.Corner.FL || corner == VehicleSignals.Corner.FR
            val wy = (if (front) frontY else rearY) - wheelH / 2
            val out = wheelW * 0.45f
            val r = RectF(
                if (isLeft) left - out else right,
                wy,
                if (isLeft) left else right + out,
                wy + wheelH,
            )
            c.drawRoundRect(r, out * 0.45f, out * 0.45f, line((toneOf(byCorner[corner]) ?: LINE).dim(stale)))
        }

        // Body: rounded nose, squarer tail.
        val nose = carW * 0.30f
        val tail = carW * 0.14f
        c.drawPath(
            Path().apply {
                moveTo(left, top + nose)
                quadTo(left, top, left + nose, top)
                lineTo(right - nose, top)
                quadTo(right, top, right, top + nose)
                lineTo(right, bottom - tail)
                quadTo(right, bottom, right - tail, bottom)
                lineTo(left + tail, bottom)
                quadTo(left, bottom, left, bottom - tail)
                close()
            },
            body,
        )
        // Windscreen and rear window.
        val gl = left + carW * 0.14f
        val gr = right - carW * 0.14f
        val wsTop = top + carH * 0.27f
        val wsBot = top + carH * 0.38f
        c.drawPath(
            Path().apply {
                moveTo(gl, wsBot)
                lineTo(gl + carW * 0.05f, wsTop)
                lineTo(gr - carW * 0.05f, wsTop)
                lineTo(gr, wsBot)
                close()
            },
            body,
        )
        val rwTop = top + carH * 0.80f
        val rwBot = top + carH * 0.88f
        c.drawPath(
            Path().apply {
                moveTo(gl, rwTop)
                lineTo(gr, rwTop)
                lineTo(gr - carW * 0.04f, rwBot)
                lineTo(gl + carW * 0.04f, rwBot)
                close()
            },
            body,
        )
        // Mirrors at the base of the windscreen.
        val mir = carW * 0.12f
        c.drawLine(left, wsBot, left - mir, wsBot - mir * 0.3f, body)
        c.drawLine(right, wsBot, right + mir, wsBot - mir * 0.3f, body)

        // Numbers beside each wheel: psi big, temp under it.
        // Numbers centred in the space left of the wheel, minus a gap.
        val sideW = left - wheelW * 0.45f - s * 0.03f
        val numSize = s * 0.26f
        val tempSize = s * 0.10f
        for (corner in VehicleSignals.Corner.entries) {
            val isLeft = corner == VehicleSignals.Corner.FL || corner == VehicleSignals.Corner.RL
            val front = corner == VehicleSignals.Corner.FL || corner == VehicleSignals.Corner.FR
            val cell = byCorner[corner]
            val colX = if (isLeft) sideW / 2f else s - sideW / 2f
            val baseline = (if (front) frontY else rearY) + numSize * 0.2f
            c.drawText(cell?.psi ?: "—", colX, baseline, text(numSize, (toneOf(cell) ?: TEXT).dim(stale), bold = true))
            cell?.temp?.let {
                c.drawText(it, colX, baseline + tempSize * 1.15f, text(tempSize, MUTED.dim(stale), bold = false))
            }
        }
        return bmp
    }
}
