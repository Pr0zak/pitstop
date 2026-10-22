package com.pitstop.widget

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
 * Draws the tire widget's content (RemoteViews can't host Compose): a
 * top-down car outline with the four pressures at its corners — the shape
 * a TPMS display uses, readable at a glance. The widget background (system
 * corner radius, dynamic neutral colour on API 31+) comes from the layout;
 * this bitmap is transparent and only carries the diagram.
 *
 * Three sizes, picked by the launcher (size-mapped RemoteViews on API 31+):
 *  - [Size.Tiny]  1×1: diagram + four big numbers, nothing else; a small
 *    age only when the reading is over a day old.
 *  - [Size.Wide]  2×1 (primary): wider spacing, placard deltas ("−4") next
 *    to the numbers when the vehicle has a placard, small age at the bottom.
 *  - [Size.Large] 2×2: adds tire temps and an "of 35 psi" line.
 * Colour carries meaning only: LOW (2 psi under the highest) and below-spec
 * (≥ 3 psi under placard) amber, under-inflated (≥ 25 % under) red.
 */
internal object TireWidgetRender {

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

    private fun paint(size: Float, color: Int, bold: Boolean = false, align: Paint.Align = Paint.Align.CENTER) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            textAlign = align
            typeface = Typeface.create("sans-serif-condensed", if (bold) Typeface.BOLD else Typeface.NORMAL)
        }

    private fun Int.dim(stale: Boolean) =
        if (!stale) this else Color.argb(140, Color.red(this), Color.green(this), Color.blue(this))

    /**
     * Number + wheel colour: red under-inflated, amber LOW (2 psi under the
     * highest tire). Below-spec vs placard colours only its "−4" delta — on
     * a car where all four sit a few psi under placard, painting every number
     * amber would hide the one tire that actually stands out.
     */
    private fun toneOf(c: TireWidgetModel.Cell?): Int = when {
        c == null -> TEXT
        c.underInflated -> BAD
        c.low -> WARN
        else -> TEXT
    }

    fun render(m: TireWidgetModel, size: Size): Bitmap {
        val w = size.w
        val h = size.h
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val stale = m.stale
        val footer = when (size) {
            Size.Tiny -> if (stale) 30f else 0f
            Size.Wide -> 30f
            Size.Large -> 40f
        }
        val areaH = h - footer

        // Car, top-down, nose up: body + windscreen line + four wheels.
        val carW = when (size) {
            Size.Tiny -> w * 0.22f
            Size.Wide -> w * 0.13f
            Size.Large -> w * 0.17f
        }
        val carH = areaH * 0.78f
        val cx = w / 2f
        val cy = areaH / 2f
        val body = RectF(cx - carW / 2, cy - carH / 2, cx + carW / 2, cy + carH / 2)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = if (size == Size.Tiny) 4f else 5f
            color = OUTLINE.dim(stale)
        }
        c.drawRoundRect(body, carW * 0.45f, carW * 0.45f, stroke)
        val wsY = body.top + carH * 0.28f
        c.drawPath(
            Path().apply {
                moveTo(body.left + carW * 0.18f, wsY)
                quadTo(cx, wsY - carH * 0.06f, body.right - carW * 0.18f, wsY)
            },
            stroke,
        )
        val byCorner = m.cells.associateBy { it.corner }
        val wheelW = carW * 0.22f
        val wheelH = carH * 0.17f
        for (corner in VehicleSignals.Corner.entries) {
            val left = corner == VehicleSignals.Corner.FL || corner == VehicleSignals.Corner.RL
            val front = corner == VehicleSignals.Corner.FL || corner == VehicleSignals.Corner.FR
            val wx = if (left) body.left - wheelW * 0.55f else body.right - wheelW * 0.45f
            val wy = if (front) body.top + carH * 0.14f else body.bottom - carH * 0.14f - wheelH
            val cell = byCorner[corner]
            val tone = toneOf(cell)
            c.drawRoundRect(
                RectF(wx, wy, wx + wheelW, wy + wheelH), wheelW / 2, wheelW / 2,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = (if (tone == TEXT) OUTLINE else tone).dim(stale)
                },
            )
        }

        // Numbers, one per quadrant beside the car.
        val numSize = when (size) {
            Size.Tiny -> 64f
            Size.Wide -> 60f
            Size.Large -> 84f
        }
        val sideW = (w - carW) / 2f - wheelW
        for (corner in VehicleSignals.Corner.entries) {
            val left = corner == VehicleSignals.Corner.FL || corner == VehicleSignals.Corner.RL
            val front = corner == VehicleSignals.Corner.FL || corner == VehicleSignals.Corner.FR
            val cell = byCorner[corner]
            val tone = toneOf(cell).dim(stale)
            val colX = if (left) sideW / 2f else w - sideW / 2f
            val rowMid = if (front) areaH * 0.27f else areaH * 0.73f
            val num = cell?.psi ?: "—"
            val baseline = rowMid + numSize * 0.35f
            c.drawText(num, colX, baseline, paint(numSize, tone, bold = true))
            // Detail under the number: placard delta (Wide/Large), temp (Large).
            if (size != Size.Tiny) {
                val bits = listOfNotNull(
                    cell?.vsPlacard?.let { if (cell.underInflated) "under $it" else it },
                    if (size == Size.Large) cell?.temp else null,
                )
                if (bits.isNotEmpty()) {
                    val detailColor = when {
                        cell?.underInflated == true -> BAD
                        cell?.belowSpec == true -> WARN
                        else -> MUTED
                    }.dim(stale)
                    val detailSize = numSize * (if (size == Size.Wide) 0.4f else 0.3f)
                    c.drawText(bits.joinToString("  "), colX, baseline + detailSize * 1.25f, paint(detailSize, detailColor))
                }
            }
        }

        // Footer: age (and "of 35 psi" on Large). Tiny shows it only when stale.
        if (footer > 0f) {
            val age = m.age ?: "no readings yet"
            val text = when (size) {
                Size.Large -> listOfNotNull(m.placardLabel?.let { "psi $it" } ?: "psi", age).joinToString(" · ")
                else -> age
            }
            c.drawText(
                text, cx, h - footer * 0.3f,
                paint(if (size == Size.Large) 26f else 22f, if (stale) WARN else MUTED),
            )
        }
        return bmp
    }
}
