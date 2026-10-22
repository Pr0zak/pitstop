package com.pitstop.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.pitstop.domain.TireWidgetModel
import com.pitstop.domain.VehicleSignals

/**
 * Draws the tire widget to a bitmap (RemoteViews can't host Compose). Two
 * shapes, picked from the widget's size:
 *  - [renderTireGrid]: 2×2 — FL FR / RL RR around a car outline, title + age.
 *  - [renderTireRow]: 2×1 — the four corners in one row, age at the end.
 * Same colours as the app: corner labels FL blue, FR orange, RL green,
 * RR magenta (the trip Tires chart); LOW in amber with an amber border.
 */
internal object TireWidgetRender {

    private val CORNER_ARGB = mapOf(
        VehicleSignals.Corner.FL to Color.parseColor("#2F81F7"),
        VehicleSignals.Corner.FR to Color.parseColor("#F97316"),
        VehicleSignals.Corner.RL to Color.parseColor("#3FB950"),
        VehicleSignals.Corner.RR to Color.parseColor("#D946EF"),
    )
    private val BG = Color.parseColor("#15181C")
    private val CELL = Color.parseColor("#1E2228")
    private val LINE = Color.parseColor("#2F343C")
    private val MUTED = Color.parseColor("#9CA3AF")
    private val WARN = Color.parseColor("#FFB020")
    private val BAD = Color.parseColor("#EF4444")

    private fun paint(size: Float, color: Int, bold: Boolean = false, mono: Boolean = false, align: Paint.Align = Paint.Align.LEFT) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            textAlign = align
            typeface = Typeface.create(if (mono) Typeface.MONOSPACE else Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }

    /** Fade everything when the newest reading is over a day old. */
    private fun Int.dim(stale: Boolean): Int = if (!stale) this else Color.argb(150, Color.red(this), Color.green(this), Color.blue(this))

    fun renderTireGrid(m: TireWidgetModel, w: Int = 440, h: Int = 320): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(BG)
        val pad = 14f
        // Header: "TIRES  of 35" left, age right.
        c.drawText("TIRES", pad, 34f, paint(22f, MUTED.dim(m.stale), bold = true).apply { letterSpacing = 0.15f })
        m.placardLabel?.let { c.drawText("psi $it", pad + 92f, 34f, paint(20f, MUTED.dim(m.stale))) }
        c.drawText(
            m.age ?: "no readings yet",
            w - pad, 34f,
            paint(20f, (if (m.stale) WARN else MUTED), align = Paint.Align.RIGHT),
        )
        val top = 50f
        val carW = 46f
        val cellW = (w - 2 * pad - carW - 2 * 10f) / 2f
        val cellH = (h - top - pad - 10f) / 2f
        // Car outline between the columns, spanning both rows.
        val carLeft = pad + cellW + 10f
        c.drawRoundRect(
            RectF(carLeft, top + 4f, carLeft + carW, h - pad - 4f), 16f, 16f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = LINE },
        )
        val byCorner = m.cells.associateBy { it.corner }
        val slots = listOf(
            VehicleSignals.Corner.FL to RectF(pad, top, pad + cellW, top + cellH),
            VehicleSignals.Corner.FR to RectF(w - pad - cellW, top, w - pad, top + cellH),
            VehicleSignals.Corner.RL to RectF(pad, top + cellH + 10f, pad + cellW, top + 2 * cellH + 10f),
            VehicleSignals.Corner.RR to RectF(w - pad - cellW, top + cellH + 10f, w - pad, top + 2 * cellH + 10f),
        )
        for ((corner, r) in slots) drawCell(c, r, byCorner[corner], corner, m.stale, big = true)
        return bmp
    }

    fun renderTireRow(m: TireWidgetModel, w: Int = 560, h: Int = 150): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(BG)
        val pad = 10f
        c.drawText(
            m.age ?: "no readings yet",
            w - pad, 26f,
            paint(18f, (if (m.stale) WARN else MUTED), align = Paint.Align.RIGHT),
        )
        c.drawText("TIRES" + (m.placardLabel?.let { "  psi $it" } ?: ""), pad, 26f, paint(18f, MUTED.dim(m.stale), bold = true))
        val top = 36f
        val gap = 8f
        val cellW = (w - 2 * pad - 3 * gap) / 4f
        val byCorner = m.cells.associateBy { it.corner }
        VehicleSignals.Corner.entries.forEachIndexed { i, corner ->
            val l = pad + i * (cellW + gap)
            drawCell(c, RectF(l, top, l + cellW, h - pad), byCorner[corner], corner, m.stale, big = false)
        }
        return bmp
    }

    private fun drawCell(c: Canvas, r: RectF, cell: TireWidgetModel.Cell?, corner: VehicleSignals.Corner, stale: Boolean, big: Boolean) {
        val low = cell?.low == true
        c.drawRoundRect(r, 14f, 14f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (low) Color.argb(46, 255, 176, 32) else CELL
        })
        c.drawRoundRect(r, 14f, 14f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = if (low) 3f else 1.5f
            color = if (low) WARN else LINE
        })
        val ix = r.left + 12f
        val labelSize = if (big) 20f else 17f
        c.drawText(corner.label, ix, r.top + labelSize + 6f, paint(labelSize, CORNER_ARGB.getValue(corner).dim(stale), bold = true))
        if (low) {
            c.drawText("LOW", ix + labelSize * 1.6f, r.top + labelSize + 6f, paint(labelSize * 0.85f, WARN, bold = true))
        }
        val psi = cell?.psi ?: "—"
        val numSize = if (big) 56f else 40f
        val numPaint = paint(numSize, (if (low) WARN else Color.WHITE).dim(stale), bold = true, mono = true)
        val numY = r.top + labelSize + 10f + numSize * 0.9f
        c.drawText(psi, ix, numY, numPaint)
        if (psi != "—") {
            val unitX = ix + numPaint.measureText(psi) + 6f
            c.drawText("psi", unitX, numY, paint(numSize * 0.32f, MUTED.dim(stale)))
        }
        // Top-right detail: tire temp (2×2 only) and the placard delta —
        // amber when ≥ 3 psi under, red "under" when ≥ 25 % under.
        val y = r.top + labelSize + 6f
        var x = r.right - 10f
        if (big) {
            cell?.temp?.let {
                val p = paint(18f, MUTED.dim(stale), align = Paint.Align.RIGHT)
                c.drawText(it, x, y, p)
                x -= p.measureText(it) + 8f
            }
        }
        cell?.vsPlacard?.let { d ->
            val text = if (cell.underInflated) "under $d" else d
            val color = when {
                cell.underInflated -> BAD
                cell.belowSpec -> WARN
                else -> MUTED
            }
            c.drawText(text, x, y, paint(if (big) 18f else 15f, color.dim(stale), bold = cell.belowSpec, align = Paint.Align.RIGHT))
        }
    }
}
