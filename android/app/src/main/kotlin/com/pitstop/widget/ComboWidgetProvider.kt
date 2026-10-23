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
import com.pitstop.data.CachedFuel
import com.pitstop.data.CachedReading
import com.pitstop.data.RangeRepository
import com.pitstop.data.SettingsRepository
import com.pitstop.domain.RangeMath
import com.pitstop.domain.TireReadings
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
 * "Fuel & tires" home-screen widget — design B: the car body is the tank
 * (filled to the fuel level, % inside), the four tire pressures at the
 * wheels. 1×1 is the target size; 2×1 adds range + age; 2×2 scales up.
 *
 * Data, same rules as the two single widgets (which stay):
 *  - fuel: [RangeMath.fuelSnapshot] (the server's fuel_display_* pick, then
 *    the estimator, then the raw sensor) and the fuel widget's range;
 *  - tires: [TireReadings.merge] of the persisted snapshot, the server's
 *    latest readings and live samples.
 * Both halves are persisted ([AppPrefs.fuelSnapshot] / [AppPrefs.tireSnapshot])
 * and drawn from the cache first, so it never goes blank once anything has
 * been read. Refreshed by every fuel- and tire-widget trigger (their
 * refreshWidgets() fan out here) and the 15-min worker.
 */
class ComboWidgetProvider : AppWidgetProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ComboWidgetEntryPoint {
        fun api(): PitstopApi
        fun settings(): SettingsRepository
        fun logBuffer(): LogBuffer
        fun appPrefs(): AppPrefs
        fun stateBus(): BridgeStateBus
        fun rangeRepository(): RangeRepository
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val entry = EntryPointAccessors.fromApplication(context.applicationContext, ComboWidgetEntryPoint::class.java)
        entry.logBuffer().info("combo widget onUpdate", mapOf("count" to ids.size))
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            val s = runCatching { entry.settings().current().settings }.getOrNull()
            val slug = com.pitstop.data.effectiveVehicleSlug(
                runCatching { entry.appPrefs().viewVehicleSlug.first() }.getOrNull(), s?.vehicleSlug.orEmpty(),
            )
            val system = s?.unitSystem ?: "imperial"
            // 1) Cached halves straight away — never blank.
            var fuel = runCatching { entry.appPrefs().fuelSnapshot(slug) }.getOrNull()
            var tires = runCatching { entry.appPrefs().tireSnapshot(slug) }.getOrDefault(emptyMap())
            draw(context, manager, ids, fuel, tires, system)
            // 2) Fresh, merged, persisted.
            runCatching {
                if (s == null || slug.isEmpty() || s.apiBaseUrl.isBlank()) return@runCatching
                val vehicle = TireReadings.pickVehicle(
                    entry.api().getVehicles(cacheControl = "no-cache"), { it.slug },
                    entry.appPrefs().viewVehicleSlug.first(), s.vehicleSlug,
                ) ?: return@runCatching
                val snap = RangeMath.fuelSnapshot(vehicle)
                val basis = runCatching { entry.rangeRepository().ensureBasis(vehicle) }.getOrNull()
                val est = RangeMath.estimate(snap, basis)
                if (snap.pct != null) {
                    fuel = CachedFuel(snap.pct, est.rangeMi, snap.readingAtMs, snap.source.name)
                    entry.appPrefs().setFuelSnapshot(slug, fuel!!)
                }
                val fresh = HashMap<String, CachedReading>()
                for ((k, v) in entry.stateBus().latestByMetric.value) {
                    if (k.startsWith("tire_")) fresh[k] = CachedReading(v.value, v.tsMs)
                }
                vehicle.tirePlacardFrontKpa?.let { fresh["_tire_placard_front_kpa"] = CachedReading(it, System.currentTimeMillis()) }
                vehicle.tirePlacardRearKpa?.let { fresh["_tire_placard_rear_kpa"] = CachedReading(it, System.currentTimeMillis()) }
                for (r in entry.api().getReadingsLatest(vehicle.id, cacheControl = "no-cache")) {
                    if (!r.metric.startsWith("tire_")) continue
                    val v = r.value ?: continue
                    val t = com.pitstop.util.DateLabel.epochMs(r.time) ?: continue
                    val cur = fresh[r.metric]
                    if (cur == null || t > cur.t) fresh[r.metric] = CachedReading(v, t)
                }
                tires = entry.appPrefs().mergeTireSnapshot(slug, fresh)
                entry.logBuffer().info(
                    "combo widget fetch result",
                    mapOf("slug" to vehicle.slug, "fuel_pct" to (fuel?.pct ?: -1.0), "tire_metrics" to tires.size),
                )
            }.onFailure { e ->
                entry.logBuffer().warn(
                    "combo widget fetch threw — showing cached values",
                    mapOf("err" to (e.message ?: e::class.java.simpleName)),
                )
            }
            draw(context, manager, ids, fuel, tires, system)
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, o: android.os.Bundle) {
        super.onAppWidgetOptionsChanged(context, manager, id, o)
        onUpdate(context, manager, intArrayOf(id))
    }

    private suspend fun draw(
        context: Context,
        manager: AppWidgetManager,
        ids: IntArray,
        fuel: CachedFuel?,
        tires: Map<String, CachedReading>,
        system: String,
    ) {
        val m = modelOf(fuel, tires, System.currentTimeMillis(), system)
        withContext(Dispatchers.Main) {
            for (id in ids) manager.updateAppWidget(id, buildViews(context, manager, id, m))
        }
    }

    private fun viewsFor(context: Context, m: ComboWidgetRender.Model, size: ComboWidgetRender.Size) =
        RemoteViews(context.packageName, R.layout.combo_widget).apply {
            // One bitmap per theme; layout/ shows the light one, layout-night/
            // the dark one. The launcher re-inflates on a light/dark switch,
            // which never reaches the provider, so both must already be there.
            setImageViewBitmap(R.id.combo_widget_image_light, ComboWidgetRender.render(m, size, dark = false))
            setImageViewBitmap(R.id.combo_widget_image_dark, ComboWidgetRender.render(m, size, dark = true))
            val desc = describe(m)
            setContentDescription(R.id.combo_widget_image_light, desc)
            setContentDescription(R.id.combo_widget_image_dark, desc)
            setOnClickPendingIntent(android.R.id.background, openHome(context))
        }

    private fun buildViews(context: Context, manager: AppWidgetManager, id: Int, m: ComboWidgetRender.Model): RemoteViews {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            return RemoteViews(
                mapOf(
                    android.util.SizeF(40f, 40f) to viewsFor(context, m, ComboWidgetRender.Size.Tiny),
                    android.util.SizeF(110f, 40f) to viewsFor(context, m, ComboWidgetRender.Size.Wide),
                    android.util.SizeF(110f, 110f) to viewsFor(context, m, ComboWidgetRender.Size.Large),
                ),
            )
        }
        val o = manager.getAppWidgetOptions(id)
        return viewsFor(
            context, m,
            ComboWidgetRender.sizeFor(
                o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 40),
                o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 40),
            ),
        )
    }

    private fun describe(m: ComboWidgetRender.Model): String =
        if (m.empty) "Fuel and tires: no readings yet"
        else listOfNotNull(
            m.fuelPct?.let { "Fuel ${it.toInt()} percent" },
            m.range?.let { "range $it" },
            m.tires.cells.joinToString(", ") { "${it.corner.label} ${it.psi} psi${if (it.low) " low" else ""}" },
            m.age,
        ).joinToString(", ")

    private fun openHome(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 2,
        Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_HOME
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        /** The widget model from the two cached halves — shared with the preview test. */
        internal fun modelOf(
            fuel: CachedFuel?,
            tires: Map<String, CachedReading>,
            nowMs: Long,
            system: String,
        ): ComboWidgetRender.Model {
            val tireModel = TireWidgetProvider.modelOf(tires, nowMs, system)
            val tiresAt = com.pitstop.domain.VehicleSignals.Corner.entries
                .mapNotNull { tires[it.pressureMetric]?.takeIf { r -> r.v > 0 }?.t }.maxOrNull()
            return ComboWidgetRender.modelOf(
                fuelPct = fuel?.pct,
                fuelAtMs = fuel?.atMs,
                range = fuel?.rangeMi?.let { com.pitstop.ui.components.RangeFormat.range(it, system) },
                tires = tireModel,
                tiresAtMs = tiresAt,
                nowMs = nowMs,
            )
        }

        fun refreshWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(android.content.ComponentName(context, ComboWidgetProvider::class.java))
            if (ids.isEmpty()) return
            context.sendBroadcast(
                Intent(context, ComboWidgetProvider::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                },
            )
        }
    }
}
