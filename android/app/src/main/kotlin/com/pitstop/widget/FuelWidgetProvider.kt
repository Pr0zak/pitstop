package com.pitstop.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.widget.RemoteViews
import com.pitstop.MainActivity
import com.pitstop.R
import com.pitstop.data.SettingsRepository
import com.pitstop.http.PitstopApi
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Small (2×2) home-screen widget that draws a circular fuel-level gauge
 * for the configured vehicle.
 *
 * Data path mirrors the in-app FuelHeroCards: pulls /vehicles, reads
 * `latest.fuel_level.value_num`, multiplies tank1_capacity by % / 100
 * for the gallons subtitle. No new endpoint required.
 *
 * Refresh model:
 *   - `updatePeriodMillis = 30 min` (Android's floor). On each cycle
 *     the OS calls onUpdate; we kick off the fetch off the main thread.
 *   - Tap the widget → opens MainActivity. The hosting activity also
 *     calls refreshWidgets() after a manual data refresh so the gauge
 *     stays in sync without waiting for the 30-min tick.
 *
 * Hilt note: `AppWidgetProvider` is a BroadcastReceiver and can't be
 * `@AndroidEntryPoint`-annotated cleanly across all gradle/agp
 * combinations, so we pull dependencies via `EntryPointAccessors` from
 * the application graph. Both SettingsRepository and PitstopApi are
 * @Singleton — safe to retrieve any number of times.
 */
class FuelWidgetProvider : AppWidgetProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WidgetEntryPoint {
        fun api(): PitstopApi
        fun settings(): SettingsRepository
    }

    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        ids: IntArray,
    ) {
        // First, paint a "loading…" placeholder synchronously so the widget
        // never sits blank between the host calling onUpdate and the
        // network round-trip resolving.
        ids.forEach { id ->
            manager.updateAppWidget(id, buildRemoteViews(context, pct = null, sub = "loading…"))
        }

        val entry = EntryPointAccessors.fromApplication(
            context.applicationContext, WidgetEntryPoint::class.java,
        )
        // SupervisorJob: one failure (e.g. /vehicles 401) shouldn't crash
        // the whole onUpdate handler. We just leave the widget showing
        // whatever it had last.
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            val (pct, sub) = runCatching { fetchFuel(entry) }.getOrElse { exc ->
                null to (exc.message?.take(24) ?: "err")
            }
            withContext(Dispatchers.Main) {
                ids.forEach { id ->
                    manager.updateAppWidget(id, buildRemoteViews(context, pct, sub))
                }
            }
        }
    }

    /** Resolve the active vehicle, pull its latest fuel reading, format
     *  a percent + "X.X gal · Yh ago" subtitle. Returns Pair(pct, sub).
     *  Either half may be null when the data is incomplete. */
    private suspend fun fetchFuel(entry: WidgetEntryPoint): Pair<Double?, String> {
        val secrets = entry.settings().current()
        if (secrets.queryToken.isBlank() || secrets.settings.apiBaseUrl.isBlank()) {
            return null to "not configured"
        }
        val slug = secrets.settings.vehicleSlug.trim()
        if (slug.isEmpty()) return null to "set slug in app"
        val vehicles = entry.api().getVehicles()
        val vehicle = vehicles.firstOrNull { it.slug == slug }
            ?: return null to "no vehicle"
        val fuelEntry = vehicle.latest["fuel_level"]
        val pct = fuelEntry?.valueNum
        val gallons = vehicle.tank1Capacity
            ?.takeIf { it > 0 && pct != null }
            ?.let { it * pct!! / 100.0 }
        val age = fuelEntry?.time?.let { formatAge(it) }
        val sub = listOfNotNull(
            gallons?.let { "%.1f gal".format(it) },
            age,
        ).joinToString(" · ").ifEmpty { "—" }
        return pct to sub
    }

    /** Build the RemoteViews bundle: gauge bitmap, subtitle, tap-to-open
     *  pending intent. Rebuilt fresh each render because RemoteViews
     *  can't be mutated after dispatch. */
    private fun buildRemoteViews(context: Context, pct: Double?, sub: String): RemoteViews {
        return RemoteViews(context.packageName, R.layout.fuel_widget).apply {
            setImageViewBitmap(R.id.widget_gauge, renderGauge(pct))
            setTextViewText(R.id.widget_subtitle, sub)
            setOnClickPendingIntent(R.id.widget_root, openAppPendingIntent(context))
        }
    }

    private fun openAppPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        /** Force a re-render of every installed FuelWidget instance.
         *  Call after any in-app action that produces fresher fuel data
         *  (manual refresh, fillup add) so the gauge updates without
         *  waiting for the 30-minute OS tick. */
        fun refreshWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = android.content.ComponentName(context, FuelWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            if (ids.isEmpty()) return
            val intent = Intent(context, FuelWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            }
            context.sendBroadcast(intent)
        }
    }
}

// ---- gauge bitmap rendering --------------------------------------------------

private const val GAUGE_PX = 220
private const val GAUGE_START_ANGLE = 135f
private const val GAUGE_SWEEP_MAX = 270f
private const val GAUGE_STROKE = 18f

/** Render a 270° arc gauge (open at the bottom) with the percentage
 *  centered. Color steps from red <15% → amber <30% → green elsewhere.
 *  Returns a fresh ARGB_8888 bitmap the caller can hand to RemoteViews. */
private fun renderGauge(pct: Double?): Bitmap {
    val size = GAUGE_PX
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val cx = size / 2f
    val cy = size / 2f
    val radius = size / 2f - GAUGE_STROKE / 2f - 4f
    val rect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = GAUGE_STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#2A2D33")
    }
    canvas.drawArc(rect, GAUGE_START_ANGLE, GAUGE_SWEEP_MAX, false, bgPaint)

    if (pct != null) {
        val fgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = GAUGE_STROKE
            strokeCap = Paint.Cap.ROUND
            color = when {
                pct < 15 -> Color.parseColor("#EF4444")
                pct < 30 -> Color.parseColor("#F59E0B")
                else -> Color.parseColor("#22C55E")
            }
        }
        val sweep = (pct.coerceIn(0.0, 100.0) / 100.0 * GAUGE_SWEEP_MAX).toFloat()
        canvas.drawArc(rect, GAUGE_START_ANGLE, sweep, false, fgPaint)
    }

    val centerText = pct?.let { "${it.toInt()}%" } ?: "—"
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 60f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    val textY = cy + textPaint.textSize / 3f
    canvas.drawText(centerText, cx, textY, textPaint)

    return bitmap
}

private fun formatAge(isoTime: String): String? = runCatching {
    val readingInstant = OffsetDateTime.parse(isoTime).toInstant()
    val ageSec = Duration.between(readingInstant, Instant.now()).seconds
    when {
        ageSec < 0 -> "live"
        ageSec < 90 -> "live"
        ageSec < 3600 -> "${ageSec / 60}m ago"
        ageSec < 86_400 -> "${ageSec / 3600}h ago"
        else -> "${ageSec / 86_400}d ago"
    }
}.getOrNull()
