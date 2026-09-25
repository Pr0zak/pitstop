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
import com.pitstop.log.LogBuffer
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant

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
        fun logBuffer(): LogBuffer
        fun appPrefs(): com.pitstop.data.AppPrefs
        fun rangeRepository(): com.pitstop.data.RangeRepository
    }

    /** One render's worth of data. */
    private data class WidgetData(val pct: Double?, val sub: String, val range: String? = null)

    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        ids: IntArray,
    ) {
        // First, paint a "loading…" placeholder synchronously so the widget
        // never sits blank between the host calling onUpdate and the
        // network round-trip resolving.
        ids.forEach { id ->
            manager.updateAppWidget(
                id,
                buildRemoteViews(context, manager, id, WidgetData(pct = null, sub = "loading…")),
            )
        }

        val entry = EntryPointAccessors.fromApplication(
            context.applicationContext, WidgetEntryPoint::class.java,
        )
        entry.logBuffer().info(
            "fuel widget onUpdate",
            mapOf("count" to ids.size),
        )
        // SupervisorJob: one failure (e.g. /vehicles 401) shouldn't crash
        // the whole onUpdate handler. We just leave the widget showing
        // whatever it had last.
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            val data = runCatching { fetchFuel(entry) }.getOrElse { exc ->
                entry.logBuffer().warn(
                    "fuel widget fetch threw",
                    mapOf("err" to (exc.message ?: exc::class.java.simpleName)),
                )
                WidgetData(null, exc.message?.take(24) ?: "err")
            }
            entry.logBuffer().info(
                "fuel widget fetch result",
                mapOf("pct" to (data.pct ?: -1.0), "sub" to data.sub, "range" to (data.range ?: "")),
            )
            withContext(Dispatchers.Main) {
                ids.forEach { id ->
                    manager.updateAppWidget(id, buildRemoteViews(context, manager, id, data))
                }
            }
        }
    }

    /** Called by the launcher when the user resizes the widget. Re-render
     *  so the subtitle visibility (gated on widget height) tracks the new
     *  dimensions immediately rather than waiting for the next 30-min
     *  tick. */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle,
    ) {
        super.onAppWidgetOptionsChanged(context, manager, appWidgetId, newOptions)
        onUpdate(context, manager, intArrayOf(appWidgetId))
    }

    /** Resolve the vehicle the app is showing, pull its fuel state, and
     *  add the range line from the cached mpg basis (RangeRepository only
     *  refetches trips when its cache is over 6 h old). */
    private suspend fun fetchFuel(entry: WidgetEntryPoint): WidgetData {
        val secrets = entry.settings().current()
        if (secrets.queryToken.isBlank() || secrets.settings.apiBaseUrl.isBlank()) {
            return WidgetData(null, "not configured")
        }
        val slug = com.pitstop.data.effectiveVehicleSlug(
            entry.appPrefs().viewVehicleSlug.first(),
            secrets.settings.vehicleSlug,
        )
        if (slug.isEmpty()) return WidgetData(null, "set slug in app")
        val vehicles = entry.api().getVehicles()
        val vehicle = vehicles.firstOrNull { it.slug == slug }
            ?: return WidgetData(null, "no vehicle")
        // Same fuel rules as Home (estimator first, sensor fallback) — the
        // shared RangeMath, so the widget and the app agree.
        val fuel = com.pitstop.domain.RangeMath.fuelSnapshot(vehicle)
        val basis = runCatching { entry.rangeRepository().ensureBasis(vehicle) }.getOrNull()
        val est = com.pitstop.domain.RangeMath.estimate(fuel, basis)
        val system = secrets.settings.unitSystem
        val age = fuel.readingAtMs?.let { formatAgeMs(it) }
        val sub = listOfNotNull(
            fuel.usGallons?.let { com.pitstop.util.UnitFormat.volumeGal(it, system, 1) },
            age,
        ).joinToString(" · ").ifEmpty { "—" }
        val range = est.rangeMi?.let { com.pitstop.ui.components.RangeFormat.range(it, system) }
        return WidgetData(fuel.pct, sub, range)
    }

    /** Build the RemoteViews bundle: gauge bitmap, conditional subtitle,
     *  tap-to-open pending intent. Rebuilt fresh each render because
     *  RemoteViews can't be mutated after dispatch.
     *
     *  Subtitle visibility is gated on widget height — at 1×1 (~70 dp)
     *  there's no vertical room, so the row hides; once the user
     *  resizes to 1×2 or 2×2 (≥110 dp tall) the gallons + age text
     *  comes back in. The "FUEL" label is baked into the gauge bitmap
     *  itself so it's visible at every size. */
    private fun buildRemoteViews(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        data: WidgetData,
    ): RemoteViews {
        val options = manager.getAppWidgetOptions(widgetId)
        val minHeightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
        // 100 dp threshold: 1×1 ~70dp / 1×2 ~110dp / 2×2 ~150dp.
        val showSubtitle = minHeightDp >= 100
        return RemoteViews(context.packageName, R.layout.fuel_widget).apply {
            setImageViewBitmap(R.id.widget_gauge, renderGauge(data.pct))
            if (showSubtitle) {
                setViewVisibility(R.id.widget_subtitle, android.view.View.VISIBLE)
                setTextViewText(R.id.widget_subtitle, data.sub)
                if (data.range != null) {
                    setViewVisibility(R.id.widget_range, android.view.View.VISIBLE)
                    setTextViewText(R.id.widget_range, data.range)
                } else {
                    setViewVisibility(R.id.widget_range, android.view.View.GONE)
                }
            } else {
                setViewVisibility(R.id.widget_subtitle, android.view.View.GONE)
                setViewVisibility(R.id.widget_range, android.view.View.GONE)
            }
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

/** Render a 270° arc gauge (open at the bottom) using the full bitmap.
 *  Inside the ring, two stacked text rows: small "FUEL" label above
 *  centre, big percentage just below. Designed so the entire radial
 *  gauge stays legible at 1×1 (~140 px rendered). Color steps from red
 *  <15% → amber <30% → green elsewhere. Returns a fresh ARGB_8888
 *  bitmap the caller can hand to RemoteViews. */
private fun renderGauge(pct: Double?): Bitmap {
    val size = GAUGE_PX
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    // Full-bitmap gauge — no top reservation; both label and percentage
    // sit inside the ring's open interior.
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

    // "FUEL" label — sits just above centre, inside the ring.
    val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9CA3AF")
        textSize = 22f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        letterSpacing = 0.18f
    }
    canvas.drawText("FUEL", cx, cy - 22f, labelPaint)

    // Percentage — big, monospace, just below centre so the ascender
    // tops sit right under the FUEL baseline. The exact offset
    // (~+textSize/2.4) lands the digits visually centred in the lower
    // half of the ring.
    val centerText = pct?.let { "${it.toInt()}%" } ?: "—"
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 56f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    canvas.drawText(centerText, cx, cy + textPaint.textSize / 2.4f, textPaint)

    return bitmap
}

private fun formatAgeMs(ms: Long): String? = runCatching {
    val ageSec = Duration.between(Instant.ofEpochMilli(ms), Instant.now()).seconds
    when {
        ageSec < 0 -> "live"
        ageSec < 90 -> "live"
        ageSec < 3600 -> "${ageSec / 60}m ago"
        ageSec < 86_400 -> "${ageSec / 3600}h ago"
        else -> "${ageSec / 86_400}d ago"
    }
}.getOrNull()
