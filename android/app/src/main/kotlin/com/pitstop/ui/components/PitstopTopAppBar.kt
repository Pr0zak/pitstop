package com.pitstop.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Compose mirror of the web Sidebar's brand chrome — small arc-ticks-redline
 * mark + "pitstop" wordmark — used as a TopAppBar title across primary
 * destinations. The mark is drawn directly with Canvas (no SVG load) so
 * startup cost is zero and the tint follows the theme's onSurface.
 *
 * Uses Material 3 CenterAlignedTopAppBar so the brand sits visually
 * balanced between the (optional) navigationIcon and the actions slot,
 * matching how Material You system apps frame their headers on Pixel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PitstopTopAppBar(
    @Suppress("UNUSED_PARAMETER") title: String? = null,
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    // Per Material 3 guidance: when bottom NavigationBar already labels
    // the active destination, the TopAppBar's screen title is redundant.
    // We drop the per-screen title entirely and show only the brand
    // chrome (mark + "pitstop" wordmark). The `title` param is kept on
    // the function signature for source-compat with existing call sites
    // but ignored in the render — easier than touching every caller.
    //
    // Also: windowInsets = WindowInsets(0) — the outer Scaffold (in
    // MainActivity) already absorbs the status-bar inset for the whole
    // pager. Without this override the per-screen inner Scaffold's
    // TopAppBar re-applies the same inset on top, ending up with
    // double-padding (the brand mark sits ~80 dp lower than it should
    // and there's a fat empty band above the title).
    CenterAlignedTopAppBar(
        title = {
            androidx.compose.foundation.layout.Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                BrandMark(sizeDp = 22)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "pitstop",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
        ),
        scrollBehavior = scrollBehavior,
        windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
    )
}

/**
 * Tiny arc-ticks-redline-needle mark drawn directly with Compose
 * Canvas. Same geometry as the web favicon: 270° arc from -135° to
 * +135°, redline runs +95° → +135°. Major ticks every 30°. A small
 * triangular needle points from the hub toward the redline boundary.
 */
@Composable
private fun BrandMark(sizeDp: Int) {
    val accent = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onSurface

    Canvas(modifier = Modifier.size(sizeDp.dp)) {
        val w = size.minDimension
        val cx = w / 2f
        val cy = w / 2f + w * 0.04f
        val radius = w * 0.36f
        val arcStroke = (w * 0.06f).coerceAtLeast(1.5f)
        val tickLen = w * 0.14f
        val tickStroke = (w * 0.045f).coerceAtLeast(1f)

        fun pointAt(angleDeg: Float, r: Float): Offset {
            val rad = ((angleDeg - 90f) * PI / 180f).toFloat()
            return Offset(cx + cos(rad) * r, cy + sin(rad) * r)
        }

        // Cool body arc: -135° → +95°
        val coolPath = Path().apply {
            val start = pointAt(-135f, radius)
            moveTo(start.x, start.y)
            arcTo(
                rect = androidx.compose.ui.geometry.Rect(
                    Offset(cx - radius, cy - radius),
                    Size(radius * 2, radius * 2),
                ),
                startAngleDegrees = -135f - 90f,
                sweepAngleDegrees = 230f,
                forceMoveTo = false,
            )
        }
        drawPath(
            path = coolPath,
            color = ink.copy(alpha = 0.92f),
            style = Stroke(width = arcStroke),
        )

        // Redline segment: +95° → +135°
        val redPath = Path().apply {
            val start = pointAt(95f, radius)
            moveTo(start.x, start.y)
            arcTo(
                rect = androidx.compose.ui.geometry.Rect(
                    Offset(cx - radius, cy - radius),
                    Size(radius * 2, radius * 2),
                ),
                startAngleDegrees = 95f - 90f,
                sweepAngleDegrees = 40f,
                forceMoveTo = false,
            )
        }
        drawPath(
            path = redPath,
            color = accent,
            style = Stroke(width = arcStroke),
        )

        // Major ticks every 30° from -135° → +135°
        for (deg in -135..135 step 30) {
            val a = deg.toFloat()
            val isRed = a >= 95f
            val outer = pointAt(a, radius - arcStroke / 2f - 1f)
            val inner = pointAt(a, radius - arcStroke / 2f - 1f - tickLen)
            drawLine(
                color = if (isRed) accent else ink.copy(alpha = 0.9f),
                start = inner,
                end = outer,
                strokeWidth = tickStroke,
            )
        }

        // Triangular needle pointing at the redline boundary (+95°).
        // Tip at 78 % radius — same proportion the web variant uses
        // (0.80 of r=24 ≈ 19.2; here 0.78 of (radius - arcStroke/2)
        // gives the same visual stop just inside the arc).
        val needleR = radius - arcStroke / 2f
        val tip = pointAt(95f, needleR * 0.78f)
        // Base width: 5 % of canvas on each side of hub, perpendicular
        // to the needle axis. Two helpers compute the perpendicular by
        // taking pointAt at angles ±90° from the needle direction at a
        // small radius from the hub.
        val baseHalfW = w * 0.05f
        val perpA = Offset(
            cx + cos(((95f - 90f) - 90f) * (PI / 180f)).toFloat() * baseHalfW,
            cy + sin(((95f - 90f) - 90f) * (PI / 180f)).toFloat() * baseHalfW,
        )
        val perpB = Offset(
            cx + cos(((95f - 90f) + 90f) * (PI / 180f)).toFloat() * baseHalfW,
            cy + sin(((95f - 90f) + 90f) * (PI / 180f)).toFloat() * baseHalfW,
        )
        val needlePath = Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(perpA.x, perpA.y)
            lineTo(perpB.x, perpB.y)
            close()
        }
        drawPath(path = needlePath, color = ink)

        // Hub dot — slightly larger than the baseline (0.06 vs 0.045)
        // so the needle reads anchored rather than floating.
        drawCircle(
            color = accent,
            radius = w * 0.06f,
            center = Offset(cx, cy),
        )
    }
    @Suppress("UNUSED_VARIABLE")
    val _x: Color = ink // keep linter happy when only one branch uses ink
}
