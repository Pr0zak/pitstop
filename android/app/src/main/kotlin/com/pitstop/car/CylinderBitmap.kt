package com.pitstop.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/**
 * The Cylinders tab's pane image: the V6 as two banks of three — front bank
 * (4 5 6) on top, rear bank (1 2 3) below, nose-up like the Tires car — with
 * the firing count big above them. A firing cylinder is a filled circle; one
 * VCM has shut off is an outline. A cylinder with misfires gets a red ring
 * and its count.
 *
 * Square and drawn large for the same reasons as [TireCarBitmap]: the host
 * centre-crops the pane image to a square and shows it at ~190 px.
 */
internal object CylinderBitmap {
    const val SIZE_PX = 320

    private const val TEXT = Color.WHITE
    private val MUTED = Color.parseColor("#9AA1A9")
    private val FIRING = Color.parseColor("#8AB4F8")
    private val ECO = Color.parseColor("#81C995")
    private val BAD = Color.parseColor("#FF5A4E")
    private val DIM = Color.parseColor("#5F6368")

    private fun text(size: Float, color: Int, bold: Boolean) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-condensed", if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    fun render(m: CylinderModel): Bitmap {
        val s = SIZE_PX.toFloat()
        val bmp = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        // Headline: "6" / "3" with "of 6" + ECO, or a dash when idle.
        val head = m.active?.toString() ?: "—"
        val headColor = when {
            m.active == null -> DIM
            m.eco -> ECO
            else -> TEXT
        }
        c.drawText(head, s * 0.36f, s * 0.29f, text(s * 0.30f, headColor, bold = true))
        val sub = Paint(text(s * 0.085f, MUTED, bold = false)).apply { textAlign = Paint.Align.LEFT }
        c.drawText("of 6", s * 0.50f, s * 0.17f, sub)
        val tag = when {
            m.active == null -> ""
            m.eco -> "ECO"
            else -> "ALL"
        }
        c.drawText(tag, s * 0.50f, s * 0.28f, Paint(text(s * 0.085f, if (m.eco) ECO else MUTED, bold = true)).apply { textAlign = Paint.Align.LEFT })

        // Two banks of three.
        val r = s * 0.105f
        val xs = listOf(s * 0.20f, s * 0.50f, s * 0.80f)
        val rows = listOf(s * 0.52f to listOf(4, 5, 6), s * 0.82f to listOf(1, 2, 3))
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = s * 0.016f
        }
        val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        for ((cy, cyls) in rows) {
            cyls.forEachIndexed { i, n ->
                val cx = xs[i]
                val on = m.firing?.get(n - 1)
                val miss = m.misfires[n] ?: 0
                when (on) {
                    true -> {
                        disc.color = if (m.eco) ECO else FIRING
                        c.drawCircle(cx, cy, r, disc)
                    }
                    false -> {
                        ring.color = DIM
                        c.drawCircle(cx, cy, r, ring)
                    }
                    null -> {
                        ring.color = DIM
                        c.drawCircle(cx, cy, r, ring)
                    }
                }
                if (miss > 0) {
                    ring.color = BAD
                    c.drawCircle(cx, cy, r + s * 0.022f, ring)
                }
                val numColor = if (on == true) Color.parseColor("#202124") else MUTED
                c.drawText(n.toString(), cx, cy + s * 0.032f, text(s * 0.09f, numColor, bold = true))
                if (miss > 0) {
                    c.drawText("×$miss", cx + r * 1.05f, cy - r * 0.75f, Paint(text(s * 0.07f, BAD, bold = true)).apply { textAlign = Paint.Align.LEFT })
                }
            }
        }
        return bmp
    }
}
