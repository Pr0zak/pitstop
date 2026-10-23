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
 * sits inside the body — with the four whole-psi pressures at the wheels.
 *
 * Polish pass (2026-10-22, options 1B · 2B · 3B+3D · 4B · 5B · 6B):
 *  - 1B: tighter geometry (slim wheels, 4 dp layout padding) so the psi
 *    numbers are as large as a 1×1 cell allows.
 *  - 2B: a low tire's number sits in a filled pill — amber when 2 psi or
 *    more under the highest tire, red when 25 % or more under placard.
 *  - 3B: the fuel number turns amber under 30 %, red under 15 %; the part
 *    of it the fill covers switches to a colour readable on the fill.
 *  - 3D: a stronger fill with a meniscus line at the fuel level.
 *  - 4B: light and dark palettes; the provider draws one bitmap per theme
 *    and night-qualified layouts pick the right one (see ComboWidgetProvider).
 *  - 5B: an age chip ("3h") once the older half is [AGE_SHOWN_AFTER_MS] old,
 *    a light dim from then and a stronger one past a day ([Model.stale]).
 *  - 6B: a smoother body (round nose, slight waist, rear window).
 *
 * Sizes: [Size.Tiny] 1×1 (the target), [Size.Wide] 2×1 and [Size.Large] 2×2
 * add the range under the car. The bitmap is transparent; the layout supplies
 * the system-radius, theme-following background.
 */
internal object ComboWidgetRender {

    /** Show the age chip (and dim lightly) once the older reading is this old. */
    const val AGE_SHOWN_AFTER_MS = 2L * 3_600_000L

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
        /** Age of the OLDER of the two readings in ms; null = nothing ever read. */
        val ageMs: Long? = null,
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
            ageMs = oldest?.let { (nowMs - it).coerceAtLeast(0L) },
        )
    }

    /** "3h" / "45m" / "2d" — the 1×1 age chip; short so it fits under the car. */
    fun compactAge(ageMs: Long): String {
        val m = ageMs / 60_000L
        return when {
            m < 60 -> "${m}m"
            m < 24 * 60 -> "${m / 60}h"
            else -> "${m / (24 * 60)}d"
        }
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

    /** Colours for one theme. Status hues follow the in-app tire/fuel cards. */
    private class Palette(
        val text: Int,
        val muted: Int,
        val outline: Int,
        val wheel: Int,
        val good: Int,
        val warn: Int,
        val bad: Int,
        /** Amber / red as TEXT on the tile (darker in light mode). */
        val warnInk: Int,
        val badInk: Int,
        /** Text on a filled amber / red / green shape. */
        val onWarn: Int,
        val onBad: Int,
        val onGood: Int,
        /** Meniscus line colour + alpha; null = a darker shade of the fuel tone. */
        val meniscus: Int?,
        val meniscusAlpha: Int,
    )

    private val DARK by lazy {
        Palette(
            text = Color.WHITE,
            muted = Color.parseColor("#A8B0BA"),
            outline = Color.parseColor("#5B636E"),
            wheel = Color.parseColor("#5B636E"),
            good = Color.parseColor("#3FB950"),
            warn = Color.parseColor("#FFB020"),
            bad = Color.parseColor("#FF5A4E"),
            warnInk = Color.parseColor("#FFB020"),
            badInk = Color.parseColor("#FF6B60"),
            onWarn = Color.parseColor("#241500"),
            onBad = Color.parseColor("#2B0805"),
            onGood = Color.WHITE,
            meniscus = Color.WHITE,
            meniscusAlpha = 140,
        )
    }

    private val LIGHT by lazy {
        Palette(
            text = Color.parseColor("#1B1C20"),
            muted = Color.parseColor("#5D626B"),
            outline = Color.parseColor("#8E959F"),
            wheel = Color.parseColor("#5F6670"),
            good = Color.parseColor("#1E8E3E"),
            warn = Color.parseColor("#F29900"),
            bad = Color.parseColor("#D93025"),
            warnInk = Color.parseColor("#9A5B00"),
            badInk = Color.parseColor("#C5221F"),
            onWarn = Color.parseColor("#2A1800"),
            onBad = Color.WHITE,
            onGood = Color.WHITE,
            meniscus = null,
            meniscusAlpha = 230,
        )
    }

    // Lazy: the model half of this object runs in plain JVM unit tests,
    // where android.graphics is stubbed.
    private val CONDENSED_BOLD: Typeface by lazy { Typeface.create("sans-serif-condensed", Typeface.BOLD) }
    private val SANS_BOLD: Typeface by lazy { Typeface.create("sans-serif", Typeface.BOLD) }

    private fun textPaint(size: Float, color: Int, face: Typeface = CONDENSED_BOLD) =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            textSize = size
            this.color = color
            textAlign = Paint.Align.CENTER
            typeface = face
            fontFeatureSettings = "tnum"
        }

    private fun darken(c: Int, f: Float) =
        Color.rgb((Color.red(c) * f).toInt(), (Color.green(c) * f).toInt(), (Color.blue(c) * f).toInt())

    private fun Int.alpha(a: Int) = Color.argb(a, Color.red(this), Color.green(this), Color.blue(this))

    private enum class Level { Ok, Warn, Bad }

    /** Fuel widget's steps: red < 15 %, amber < 30 %. */
    private fun fuelLevel(pct: Double) = when {
        pct < 15 -> Level.Bad
        pct < 30 -> Level.Warn
        else -> Level.Ok
    }

    private fun tireLevel(c: TireWidgetModel.Cell?) = when {
        c == null -> Level.Ok
        c.underInflated -> Level.Bad
        c.low -> Level.Warn
        else -> Level.Ok
    }

    /** Per-size geometry, in bitmap pixels. */
    private class Spec(
        val footer: Float,
        val carFrac: Float,
        val carHFrac: Float,
        val psiSize: Float,
        val fuelCap: Float,
        val stroke: Float,
        val chipText: Float,
        val footerText: Float,
    )

    private fun specFor(size: Size, ageShown: Boolean, empty: Boolean) = when (size) {
        Size.Tiny -> Spec(
            footer = if (ageShown || empty) 46f else 0f, carFrac = 0.29f, carHFrac = 0.9f,
            psiSize = 66f, fuelCap = 78f, stroke = 4.5f, chipText = 34f, footerText = 30f,
        )
        Size.Wide -> Spec(
            footer = 46f, carFrac = 0.27f, carHFrac = 0.88f,
            psiSize = 68f, fuelCap = 64f, stroke = 5f, chipText = 32f, footerText = 32f,
        )
        Size.Large -> Spec(
            footer = 60f, carFrac = 0.30f, carHFrac = 0.88f,
            psiSize = 94f, fuelCap = 100f, stroke = 6f, chipText = 38f, footerText = 38f,
        )
    }

    /** Smoother top-down body: round nose, slight waist, squarer tail (6B). */
    private fun bodyPath(b: RectF): Path {
        val w = b.width()
        val h = b.height()
        val cx = b.centerX()
        val nf = h * 0.16f
        val nr = h * 0.10f
        val waist = w * 0.035f
        return Path().apply {
            moveTo(cx, b.top)
            cubicTo(b.right - w * 0.12f, b.top, b.right, b.top + nf * 0.35f, b.right, b.top + nf)
            cubicTo(b.right - waist, b.top + h * 0.45f, b.right - waist, b.top + h * 0.6f, b.right, b.bottom - nr)
            cubicTo(b.right, b.bottom - nr * 0.2f, b.right - w * 0.10f, b.bottom, cx, b.bottom)
            cubicTo(b.left + w * 0.10f, b.bottom, b.left, b.bottom - nr * 0.2f, b.left, b.bottom - nr)
            cubicTo(b.left + waist, b.top + h * 0.6f, b.left + waist, b.top + h * 0.45f, b.left, b.top + nf)
            cubicTo(b.left, b.top + nf * 0.35f, b.left + w * 0.12f, b.top, cx, b.top)
            close()
        }
    }

    /**
     * Draw [m] at [size]. [dark] picks the palette; the provider renders both
     * and lets night-qualified layouts choose, so a theme switch needs no
     * re-render.
     */
    fun render(m: Model, size: Size, dark: Boolean = true): Bitmap {
        val p = if (dark) DARK else LIGHT
        val w = size.w.toFloat()
        val h = size.h.toFloat()
        val bmp = Bitmap.createBitmap(size.w, size.h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        val ageMs = m.ageMs
        val ageShown = ageMs != null && ageMs >= AGE_SHOWN_AFTER_MS
        val s = specFor(size, ageShown, m.empty)
        val box = when (size) {
            Size.Tiny -> RectF(0f, 2f, w, h - s.footer)
            Size.Wide -> RectF(w * 0.14f, 2f, w * 0.86f, h - s.footer)
            Size.Large -> RectF(0f, 6f, w, h - s.footer)
        }

        // Dim the diagram (not the footer): lightly once the age chip shows,
        // more past a day.
        val dimAlpha = when {
            m.stale -> 140
            ageShown -> 205
            else -> 255
        }
        val layer = if (dimAlpha < 255) c.saveLayerAlpha(0f, 0f, w, h, dimAlpha) else -1
        drawDiagram(c, m, p, s, box)
        if (layer >= 0) c.restoreToCount(layer)

        drawFooter(c, m, p, s, size, w, h, ageShown)
        return bmp
    }

    private fun drawDiagram(c: Canvas, m: Model, p: Palette, s: Spec, box: RectF) {
        val carW = box.width() * s.carFrac
        val carH = box.height() * s.carHFrac
        val cx = box.centerX()
        val cy = box.centerY()
        val body = RectF(cx - carW / 2, cy - carH / 2, cx + carW / 2, cy + carH / 2)
        val bodyPath = bodyPath(body)

        val pct = m.fuelPct?.coerceIn(0.0, 100.0)
        val fuelLvl = pct?.let { fuelLevel(it) }
        val fuelTone = when (fuelLvl) {
            Level.Bad -> p.bad
            Level.Warn -> p.warn
            Level.Ok -> p.good
            null -> p.outline
        }
        val level = pct?.let { body.bottom - carH * (it / 100.0).toFloat() } ?: body.bottom

        // Tank fill + meniscus (3D), clipped to the body.
        if (pct != null) {
            c.save()
            c.clipPath(bodyPath)
            c.drawRect(
                body.left, level, body.right, body.bottom,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(
                        0f, level, 0f, body.bottom,
                        fuelTone.alpha(242), fuelTone.alpha(178), Shader.TileMode.CLAMP,
                    )
                },
            )
            if (pct > 2 && pct < 98) {
                c.drawPath(
                    Path().apply {
                        moveTo(body.left, level)
                        quadTo(cx, level + carH * 0.025f, body.right, level)
                    },
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = s.stroke * 0.8f
                        color = (p.meniscus ?: darken(fuelTone, 0.72f)).alpha(p.meniscusAlpha)
                    },
                )
            }
            c.restore()
        }

        // Body outline, windscreen, rear window.
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = s.stroke
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = p.outline
        }
        c.drawPath(bodyPath, stroke)
        val wsY = body.top + carH * 0.2f
        c.drawPath(
            Path().apply {
                moveTo(body.left + carW * 0.16f, wsY)
                quadTo(cx, wsY - carH * 0.05f, body.right - carW * 0.16f, wsY)
            },
            stroke,
        )
        val rwY = body.bottom - carH * 0.09f
        c.drawPath(
            Path().apply {
                moveTo(body.left + carW * 0.2f, rwY)
                quadTo(cx, rwY + carH * 0.03f, body.right - carW * 0.2f, rwY)
            },
            Paint(stroke).apply { strokeWidth = s.stroke * 0.78f },
        )

        // Wheels + pressures; a low tire's number sits in a filled pill (2B).
        val by = m.tires.cells.associateBy { it.corner }
        val wheelW = carW * 0.14f
        val wheelH = carH * 0.17f
        val sideW = box.width() / 2f - carW / 2f - wheelW * 0.75f
        val psiPaint = textPaint(s.psiSize, p.text)
        for (corner in VehicleSignals.Corner.entries) {
            val left = corner == VehicleSignals.Corner.FL || corner == VehicleSignals.Corner.RL
            val front = corner == VehicleSignals.Corner.FL || corner == VehicleSignals.Corner.FR
            val cell = by[corner]
            val lvl = tireLevel(cell)
            val tone = when (lvl) {
                Level.Bad -> p.bad
                Level.Warn -> p.warn
                Level.Ok -> p.wheel
            }
            val wx = if (left) body.left - wheelW * 0.6f else body.right - wheelW * 0.4f
            val wy = if (front) body.top + carH * 0.12f else body.bottom - carH * 0.12f - wheelH
            c.drawRoundRect(
                RectF(wx, wy, wx + wheelW, wy + wheelH), wheelW / 2, wheelW / 2,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tone },
            )

            val colX = if (left) box.left + sideW / 2f else box.right - sideW / 2f
            val rowMid = if (front) box.top + box.height() * 0.26f else box.top + box.height() * 0.74f
            val baseline = rowMid + s.psiSize * 0.35f
            val num = cell?.psi ?: "—"
            if (lvl == Level.Ok) {
                psiPaint.color = p.text
                c.drawText(num, colX, baseline, psiPaint)
            } else {
                val tw = psiPaint.measureText(num)
                val padX = s.psiSize * 0.12f
                val hh = s.psiSize * 0.9f
                var x0 = colX - tw / 2 - padX
                var x1 = colX + tw / 2 + padX
                // Keep the pill clear of its wheel.
                if (left) {
                    val lim = body.left - wheelW * 0.6f - s.stroke
                    if (x1 > lim) { x0 -= x1 - lim; x1 = lim }
                } else {
                    val lim = body.right + wheelW * 0.6f + s.stroke
                    if (x0 < lim) { x1 += lim - x0; x0 = lim }
                }
                x0 = maxOf(x0, box.left - 2f)
                x1 = minOf(x1, box.right + 2f)
                c.drawRoundRect(
                    RectF(x0, rowMid - hh / 2, x1, rowMid + hh / 2), hh * 0.32f, hh * 0.32f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tone },
                )
                psiPaint.color = if (lvl == Level.Bad) p.onBad else p.onWarn
                c.drawText(num, (x0 + x1) / 2f, baseline, psiPaint)
            }
        }

        // Fuel % inside the body (3B): the number takes the fuel state's
        // colour; where the fill covers it, a colour readable on the fill.
        val inner = carW - 2 * (s.stroke + carW * 0.04f)
        val digits = pct?.let { "${it.toInt()}" } ?: "—"
        val digitPaint = textPaint(100f, p.text)
        val fit = 100f * inner / digitPaint.measureText(digits).coerceAtLeast(1f)
        digitPaint.textSize = fit.coerceAtMost(s.fuelCap)
        val ds = digitPaint.textSize
        val textMid = body.top + carH * 0.56f
        val digitsBase = textMid + ds * 0.30f
        val pctPaint = textPaint(ds * 0.5f, p.text)
        val pctBase = digitsBase + pctPaint.textSize * 1.15f
        val above = when (fuelLvl) {
            Level.Bad -> p.badInk
            Level.Warn -> p.warnInk
            else -> p.text
        }
        val below = when (fuelLvl) {
            Level.Bad -> p.onBad
            Level.Warn -> p.onWarn
            else -> p.onGood
        }
        fun drawFuelText(color: Int) {
            digitPaint.color = color
            c.drawText(digits, cx, digitsBase, digitPaint)
            if (pct != null) {
                pctPaint.color = color
                c.drawText("%", cx, pctBase, pctPaint)
            }
        }
        c.save()
        c.clipRect(body.left - 4f, body.top - 4f, body.right + 4f, level)
        drawFuelText(above)
        c.restore()
        if (pct != null) {
            c.save()
            c.clipRect(body.left - 4f, level, body.right + 4f, body.bottom + 4f)
            drawFuelText(below)
            c.restore()
        }
    }

    /**
     * Tiny: the age chip (only past [AGE_SHOWN_AFTER_MS]) or "no readings
     * yet". Wide/Large: the range, plus the chip when it applies. "Live" is
     * never spelled out.
     */
    private fun drawFooter(c: Canvas, m: Model, p: Palette, s: Spec, size: Size, w: Float, h: Float, ageShown: Boolean) {
        if (s.footer <= 0f) return
        val midY = h - s.footer / 2f
        if (m.empty) {
            val tp = textPaint(s.footerText, p.muted, Typeface.DEFAULT)
            c.drawText("no readings yet", w / 2f, midY + s.footerText * 0.35f, tp)
            return
        }
        val chipLabel = if (ageShown) m.ageMs?.let { compactAge(it) } else null
        val rangeText = if (size == Size.Tiny) null else m.range
        val rangePaint = textPaint(s.footerText, p.muted, CONDENSED_BOLD)
        val chipPaint = textPaint(s.chipText, if (m.stale) p.warnInk else p.muted, SANS_BOLD)
        val chipPadX = s.chipText * 0.45f
        val chipH = minOf(s.footer - 6f, s.chipText * 1.4f)
        val gap = s.footerText * 0.5f
        val rangeW = rangeText?.let { rangePaint.measureText(it) } ?: 0f
        val chipW = chipLabel?.let { chipPaint.measureText(it) + 2 * chipPadX } ?: 0f
        val total = rangeW + chipW + if (rangeText != null && chipLabel != null) gap else 0f
        var x = w / 2f - total / 2f
        if (rangeText != null) {
            c.drawText(rangeText, x + rangeW / 2f, midY + s.footerText * 0.35f, rangePaint)
            x += rangeW + if (chipLabel != null) gap else 0f
        }
        if (chipLabel != null) {
            val chip = RectF(x, midY - chipH / 2f, x + chipW, midY + chipH / 2f)
            c.drawRoundRect(
                chip, chipH / 2f, chipH / 2f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = (if (m.stale) p.warn else p.muted).alpha(if (m.stale) 60 else 48)
                },
            )
            c.drawText(chipLabel, chip.centerX(), midY + s.chipText * 0.36f, chipPaint)
        }
    }
}
