package com.pitstop.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.pitstop.domain.TireWidgetModel
import com.pitstop.domain.VehicleSignals

/**
 * "Fuel & tires" widget, design B (picked 2026-10-22): a top-down car whose
 * BODY is the tank — it fills from the bottom to the fuel level, the fuel %
 * sits inside the body as large as the body allows — with the four whole-psi
 * pressures at the wheels (amber LOW, red under-inflated).
 *
 *  - [Size.Tiny] 1×1: body widened so the % is legible; no range line; a
 *    tiny age only when stale.
 *  - [Size.Wide] 2×1: plus "240 mi · 5m ago" under the car.
 *  - [Size.Large] 2×2: the 2×1 drawing with more room.
 * Same visual language as the tire widget: transparent bitmap on the
 * system-radius dynamic dark neutral background; dim past 24 h.
 */
internal object ComboWidgetRender {

    /** What the widget shows. Build with [modelOf]. */
    data class Model(
        val fuelPct: Double?,
        /** "240 mi" / "386 km"; null without a range basis. */
        val range: String?,
        val fuelStale: Boolean,
        val tires: TireWidgetModel,
        /** Age of the OLDER of the two readings ("1d ago"); null = nothing ever read. */
        val age: String?,
        /** Either half is over a day old. */
        val stale: Boolean,
        val empty: Boolean,
    )

    /**
     * Combine the two halves. Fuel and tire readings age independently; the
     * age shown is the OLDER one's, so "5m ago" can never hide a day-old half.
     */
    fun modelOf(
        fuelPct: Double?,
        fuelAtMs: Long?,
        range: String?,
        tires: TireWidgetModel,
        tiresAtMs: Long?,
        nowMs: Long,
    ): Model {
        val dayMs = TireWidgetModel.STALE_AFTER_MS
        val fuelStale = fuelAtMs != null && nowMs - fuelAtMs > dayMs
        val oldest = listOfNotNull(fuelAtMs.takeIf { fuelPct != null }, tiresAtMs).minOrNull()
        return Model(
            fuelPct = fuelPct,
            range = range,
            fuelStale = fuelStale,
            tires = tires,
            age = oldest?.let { VehicleSignals.ageLabel(it, nowMs) },
            stale = fuelStale || tires.stale,
            empty = fuelPct == null && tires.empty,
        )
    }

    enum class Size(val w: Int, val h: Int) {
        Tiny(240, 240),
        Wide(480, 220),
        Large(440, 440),
    }

    /** Size bucket for a widget of [wDp] × [hDp] (pre-Android-12 path). */
    fun sizeFor(wDp: Int, hDp: Int): Size = when {
        wDp < 100 -> Size.Tiny
        hDp < 100 -> Size.Wide
        else -> Size.Large
    }

    private const val TEXT = Color.WHITE
    private val MUTED = Color.parseColor("#A8B0BA")
    private val OUTLINE = Color.parseColor("#5B636E")
    private val WARN = Color.parseColor("#FFB020")
    private val BAD = Color.parseColor("#FF5A4E")
    private val GOOD = Color.parseColor("#3FB950")

    private fun paint(size: Float, color: Int, bold: Boolean = false, align: Paint.Align = Paint.Align.CENTER) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            textAlign = align
            typeface = Typeface.create("sans-serif-condensed", if (bold) Typeface.BOLD else Typeface.NORMAL)
        }

    private fun Int.dim(stale: Boolean) =
        if (!stale) this else Color.argb(140, Color.red(this), Color.green(this), Color.blue(this))

    private fun Int.alpha(a: Int) = Color.argb(a, Color.red(this), Color.green(this), Color.blue(this))

    /** Fuel widget's steps: red < 15 %, amber < 30 %. */
    private fun fuelColor(pct: Double?) = when {
        pct == null -> OUTLINE
        pct < 15 -> BAD
        pct < 30 -> WARN
        else -> GOOD
    }

    private fun tireTone(c: TireWidgetModel.Cell?) = when {
        c == null -> TEXT
        c.underInflated -> BAD
        c.low -> WARN
        else -> TEXT
    }

    fun render(m: Model, size: Size): Bitmap {
        val w = size.w.toFloat()
        val h = size.h.toFloat()
        val bmp = Bitmap.createBitmap(size.w, size.h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        val footer = when (size) {
            Size.Tiny -> if (m.stale || m.empty) 30f else 0f
            Size.Wide -> 34f
            Size.Large -> 44f
        }
        // The diagram's box, and the car's share of its width. 1×1 gets a
        // wide body (34 %) so the % inside it is readable at a glance.
        val box = when (size) {
            Size.Tiny -> RectF(0f, 2f, w, h - footer)
            Size.Wide -> RectF(w * 0.14f, 2f, w * 0.86f, h - footer)
            Size.Large -> RectF(0f, 6f, w, h - footer)
        }
        val carFrac = when (size) {
            Size.Tiny -> 0.32f
            Size.Wide -> 0.27f
            Size.Large -> 0.30f
        }
        val numSize = when (size) {
            Size.Tiny -> 48f
            Size.Wide -> 60f
            Size.Large -> 84f
        }
        val carW = box.width() * carFrac
        val carH = box.height() * 0.86f
        val cx = box.centerX()
        val cy = box.centerY()
        val body = RectF(cx - carW / 2, cy - carH / 2, cx + carW / 2, cy + carH / 2)
        val radius = carW * 0.42f

        // Tank fill: from the bottom of the body up to the fuel level.
        m.fuelPct?.let { pct ->
            val level = body.bottom - body.height() * (pct.coerceIn(0.0, 100.0) / 100.0).toFloat()
            val col = fuelColor(pct).dim(m.fuelStale)
            c.save()
            c.clipPath(Path().apply { addRoundRect(body, radius, radius, Path.Direction.CW) })
            c.drawRect(body.left, level, body.right, body.bottom, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(0f, level, 0f, body.bottom, col.alpha(190), col.alpha(110), Shader.TileMode.CLAMP)
            })
            c.restore()
        }

        // Body outline + windscreen + wheels.
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = if (size == Size.Tiny) 4f else 5f
            color = OUTLINE.dim(m.stale)
        }
        c.drawRoundRect(body, radius, radius, stroke)
        val wsY = body.top + carH * 0.22f
        c.drawPath(Path().apply {
            moveTo(body.left + carW * 0.16f, wsY)
            quadTo(cx, wsY - carH * 0.05f, body.right - carW * 0.16f, wsY)
        }, stroke)

        val by = m.tires.cells.associateBy { it.corner }
        val wheelW = carW * 0.16f
        val wheelH = carH * 0.17f
        val sideW = (box.width() - carW) / 2f - wheelW * 0.5f
        for (corner in VehicleSignals.Corner.entries) {
            val left = corner == VehicleSignals.Corner.FL || corner == VehicleSignals.Corner.RL
            val front = corner == VehicleSignals.Corner.FL || corner == VehicleSignals.Corner.FR
            val cell = by[corner]
            val tone = tireTone(cell)
            val wx = if (left) body.left - wheelW * 0.6f else body.right - wheelW * 0.4f
            val wy = if (front) body.top + carH * 0.12f else body.bottom - carH * 0.12f - wheelH
            c.drawRoundRect(RectF(wx, wy, wx + wheelW, wy + wheelH), wheelW / 2, wheelW / 2,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (if (tone == TEXT) OUTLINE else tone).dim(m.tires.stale) })
            val colX = if (left) box.left + sideW / 2f else box.right - sideW / 2f
            val rowMid = if (front) box.top + box.height() * 0.26f else box.top + box.height() * 0.74f
            c.drawText(cell?.psi ?: "—", colX, rowMid + numSize * 0.35f, paint(numSize, tone.dim(m.tires.stale), bold = true))
        }

        // Fuel % inside the body, as large as the body's inner width allows:
        // the digits big, "%" small underneath (1×1 needs every pixel).
        val inner = carW - 2 * (if (size == Size.Tiny) 6f else 10f)
        val digits = m.fuelPct?.let { "${it.toInt()}" } ?: "—"
        val digitPaint = paint(100f, TEXT.dim(m.fuelStale), bold = true)
        val fit = 100f * inner / digitPaint.measureText(digits).coerceAtLeast(1f)
        val maxSize = when (size) {
            Size.Tiny -> 66f
            Size.Wide -> 58f
            Size.Large -> 96f
        }
        digitPaint.textSize = fit.coerceAtMost(maxSize)
        val textMid = body.top + carH * 0.58f
        c.drawText(digits, cx, textMid + digitPaint.textSize * 0.35f, digitPaint)
        if (m.fuelPct != null) {
            c.drawText("%", cx, textMid + digitPaint.textSize * 0.35f + digitPaint.textSize * 0.5f,
                paint(digitPaint.textSize * 0.42f, TEXT.dim(m.fuelStale), bold = true))
        }

        // Footer.
        if (footer > 0f) {
            val text = when {
                m.empty -> "no readings yet"
                size == Size.Tiny -> m.age.orEmpty()
                else -> listOfNotNull(m.range, m.age).joinToString(" · ")
            }
            c.drawText(text, w / 2f, h - footer * 0.28f,
                paint(if (size == Size.Large) 28f else 22f, if (m.stale) WARN else MUTED))
        }
        return bmp
    }
}
