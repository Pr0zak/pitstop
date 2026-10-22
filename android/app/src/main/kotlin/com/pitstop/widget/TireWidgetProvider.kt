package com.pitstop.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.pitstop.MainActivity
import com.pitstop.R
import com.pitstop.data.AppPrefs
import com.pitstop.data.CachedReading
import com.pitstop.data.SettingsRepository
import com.pitstop.domain.TireWidgetModel
import com.pitstop.http.PitstopApi
import com.pitstop.log.LogBuffer
import com.pitstop.service.BridgeStateBus
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

/**
 * Home-screen widget with the LAST tire pressures: FL FR / RL RR in whole
 * psi, LOW on any tire 2 psi or more below the highest, and how old the
 * reading is (dimmed past 24 h). Tap → the app on Home.
 *
 * "Last readings must always show": every fetch is merged into a persisted
 * per-vehicle snapshot ([AppPrefs.tireSnapshot]) and the widget renders
 * from that snapshot — first immediately (so offline / after a reboot it
 * shows the cached values, never "loading…"), then again once the
 * network answers. Live bridge samples (phone Mode 22 poll or WiCAN MQTT)
 * are merged in too, newest per metric wins.
 *
 * Same refresh plumbing as [FuelWidgetProvider]: 30-min OS tick, the
 * 15-min WorkManager fallback ([FuelWidgetRefreshWorker]), app open, and
 * [WidgetRefresher.refreshTireWidget] when a tire metric arrives.
 */
class TireWidgetProvider : AppWidgetProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface TireWidgetEntryPoint {
        fun api(): PitstopApi
        fun settings(): SettingsRepository
        fun logBuffer(): LogBuffer
        fun appPrefs(): AppPrefs
        fun stateBus(): BridgeStateBus
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val entry = EntryPointAccessors.fromApplication(
            context.applicationContext, TireWidgetEntryPoint::class.java,
        )
        entry.logBuffer().info("tire widget onUpdate", mapOf("count" to ids.size))
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            val slug = runCatching { currentSlug(entry) }.getOrDefault("")
            // 1) Cached snapshot straight away — never blank.
            val cached = runCatching { entry.appPrefs().tireSnapshot(slug) }.getOrDefault(emptyMap())
            render(context, manager, ids, entry, cached)
            // 2) Fresh from the server (no-cache) + live bus, merged and persisted.
            val merged = runCatching { fetchAndMerge(entry, slug) }.getOrElse { exc ->
                entry.logBuffer().warn(
                    "tire widget fetch threw — showing cached readings",
                    mapOf("err" to (exc.message ?: exc::class.java.simpleName), "cached" to cached.size),
                )
                null
            }
            if (merged != null) {
                entry.logBuffer().info("tire widget fetch result", mapOf("metrics" to merged.size))
                render(context, manager, ids, entry, merged)
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle,
    ) {
        super.onAppWidgetOptionsChanged(context, manager, appWidgetId, newOptions)
        onUpdate(context, manager, intArrayOf(appWidgetId))
    }

    private suspend fun currentSlug(entry: TireWidgetEntryPoint): String {
        val s = entry.settings().current().settings
        return com.pitstop.data.effectiveVehicleSlug(entry.appPrefs().viewVehicleSlug.first(), s.vehicleSlug)
    }

    private suspend fun fetchAndMerge(entry: TireWidgetEntryPoint, slug: String): Map<String, CachedReading>? {
        if (slug.isEmpty()) return null
        val fresh = HashMap<String, CachedReading>()
        // Live in-process samples first (bridge running): newest wins anyway.
        for ((k, v) in entry.stateBus().latestByMetric.value) {
            if (k.startsWith("tire_")) fresh[k] = CachedReading(v.value, v.tsMs)
        }
        val settings = entry.settings().current().settings
        if (settings.apiBaseUrl.isNotBlank()) {
            // The SELECTED vehicle — /api/vehicles lists the Truck first.
            val vehicle = com.pitstop.domain.TireReadings.pickVehicle(
                entry.api().getVehicles(cacheControl = "no-cache"),
                { it.slug },
                entry.appPrefs().viewVehicleSlug.first(),
                settings.vehicleSlug,
            )
            entry.logBuffer().info(
                "tire widget vehicle",
                mapOf("slug" to slug, "picked" to (vehicle?.slug ?: "none")),
            )
            if (vehicle != null) {
                val now = System.currentTimeMillis()
                vehicle.tirePlacardFrontKpa?.let { fresh[PLACARD_FRONT] = CachedReading(it, now) }
                vehicle.tirePlacardRearKpa?.let { fresh[PLACARD_REAR] = CachedReading(it, now) }
                for (r in entry.api().getReadingsLatest(vehicle.id, cacheControl = "no-cache")) {
                    if (!r.metric.startsWith("tire_")) continue
                    val v = r.value ?: continue
                    val t = com.pitstop.util.DateLabel.epochMs(r.time) ?: continue
                    val cur = fresh[r.metric]
                    if (cur == null || t > cur.t) fresh[r.metric] = CachedReading(v, t)
                }
            }
        }
        return entry.appPrefs().mergeTireSnapshot(slug, fresh)
    }

    private suspend fun render(
        context: Context,
        manager: AppWidgetManager,
        ids: IntArray,
        entry: TireWidgetEntryPoint,
        snapshot: Map<String, CachedReading>,
    ) {
        val system = runCatching { entry.settings().current().settings.unitSystem }.getOrDefault("imperial")
        val model = modelOf(snapshot, System.currentTimeMillis(), system)
        withContext(Dispatchers.Main) {
            for (id in ids) manager.updateAppWidget(id, buildViews(context, manager, id, model))
        }
    }

    private fun viewsFor(context: Context, m: TireWidgetModel, size: TireWidgetRender.Size): RemoteViews =
        RemoteViews(context.packageName, R.layout.tire_widget).apply {
            setImageViewBitmap(R.id.tire_widget_image, TireWidgetRender.render(m, size))
            setContentDescription(R.id.tire_widget_image, describe(m))
            setOnClickPendingIntent(android.R.id.background, openHome(context))
        }

    /**
     * Android 12+: one RemoteViews per size bucket and the launcher picks the
     * largest that fits — 1×1 diagram, 2×1 with deltas, 2×2 with temps.
     * Older: pick from the widget's reported size.
     */
    private fun buildViews(context: Context, manager: AppWidgetManager, id: Int, m: TireWidgetModel): RemoteViews {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            return RemoteViews(
                mapOf(
                    android.util.SizeF(40f, 40f) to viewsFor(context, m, TireWidgetRender.Size.Tiny),
                    android.util.SizeF(110f, 40f) to viewsFor(context, m, TireWidgetRender.Size.Wide),
                    android.util.SizeF(110f, 110f) to viewsFor(context, m, TireWidgetRender.Size.Large),
                ),
            )
        }
        val o = manager.getAppWidgetOptions(id)
        val wDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 110)
        val hDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 40)
        return viewsFor(context, m, TireWidgetRender.sizeFor(wDp, hDp))
    }

    private fun describe(m: TireWidgetModel): String =
        if (m.empty) "Tire pressures: no readings yet"
        else "Tire pressures, " + m.cells.joinToString(", ") {
            "${it.corner.label} ${it.psi} psi${if (it.low) " low" else ""}"
        } + (m.age?.let { ", $it" } ?: "")

    private fun openHome(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_HOME
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val PLACARD_FRONT = "_tire_placard_front_kpa"
        private const val PLACARD_REAR = "_tire_placard_rear_kpa"

        /** The widget model from a cached snapshot — shared with the preview test. */
        internal fun modelOf(snapshot: Map<String, CachedReading>, nowMs: Long, system: String): TireWidgetModel =
            TireWidgetModel.of(
                readings = snapshot.mapValues { it.value.v to it.value.t },
                nowMs = nowMs,
                system = system,
                placardFrontKpa = snapshot[PLACARD_FRONT]?.v,
                placardRearKpa = snapshot[PLACARD_REAR]?.v,
            )

        fun refreshWidgets(context: Context) {
            // Every tire trigger also feeds the Fuel & tires widget.
            ComboWidgetProvider.refreshWidgets(context)
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(android.content.ComponentName(context, TireWidgetProvider::class.java))
            if (ids.isEmpty()) return
            context.sendBroadcast(
                Intent(context, TireWidgetProvider::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                },
            )
        }
    }
}
