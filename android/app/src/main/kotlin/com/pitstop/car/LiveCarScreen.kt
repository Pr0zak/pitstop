package com.pitstop.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.pitstop.R
import com.pitstop.data.SettingsRepository
import com.pitstop.service.BridgeStateBus
import com.pitstop.service.MetricSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Top-level Pitstop screen for the head unit. The user's car cluster
 * already shows speed natively, so we drop our duplicate and use the
 * freed grid slot for telemetry the head unit DOESN'T show:
 *
 *   ┌──────────┬──────────┬──────────┐
 *   │ Coolant  │ Fuel     │ RPM      │
 *   │ 86 °C ·  │ 64 % ▼   │ 1850 ▲   │
 *   ├──────────┼──────────┼──────────┤
 *   │ Eng load │ Battery  │ Intake   │
 *   │ 24 % ·   │ 14.1 V · │ 28 °C ·  │
 *   └──────────┴──────────┴──────────┘
 *
 * Units follow the phone's imperial/metric toggle — the grid above is
 * drawn in metric; an imperial user sees °F / mph / psi in the same
 * slots. Each tile's unit comes from its CarTileSpec.quantity.
 *
 * Trend arrows on each tile come from a rolling 30-second history
 * (TrendTracker) — slope-based classification: ▲ rising, ▼ falling,
 * "·" steady. Useful while driving: the user sees "fuel ▼" without
 * having to read the number.
 *
 * "Diagnostics" tile in the action strip pushes a second screen with
 * deeper telemetry (ATF temp, fuel trims, run time, IMU magnitude).
 *
 * Day/night handling is automatic: every colour we pass is a CarColor
 * enum (PRIMARY / GREEN / RED / SECONDARY) and the host (Android Auto
 * or AAOS) translates it for the active palette. We never hard-code
 * pixel colours so the screen adapts when the car turns night-mode
 * on at sunset.
 */
class LiveCarScreen(
    carContext: CarContext,
    private val stateBus: BridgeStateBus,
    private val settingsRepository: SettingsRepository,
) : Screen(carContext), DefaultLifecycleObserver {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var observerJob: Job? = null
    private val trends = TrendTracker(windowMs = 30_000L)

    init {
        lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        // Two jobs on purpose. Trend accuracy wants EVERY sample; the car
        // host wants very few repaints.
        //
        // This used to call invalidate() once per metric snapshot. Mid-drive
        // that is many repaints per second across the polled PID set, and
        // Android Auto rate-limits template updates — over the limit the host
        // starts dropping frames and can tear the app down. It never showed
        // up in review because it only misbehaves with live telemetry
        // flowing, which had never happened on a head unit.
        observerJob = scope.launch {
            launch {
                stateBus.latestByMetric.collect { snapshot -> trends.ingest(snapshot) }
            }
            launch {
                while (isActive) {
                    delay(REFRESH_INTERVAL_MS)
                    invalidate()
                }
            }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        observerJob?.cancel()
        observerJob = null
    }

    override fun onDestroy(owner: LifecycleOwner) {
        scope.cancel()
    }

    private companion object {
        /**
         * Repaint cadence for the car grid. Slow enough to stay well inside
         * the host's template rate limit, fast enough that a driver glancing
         * at coolant or fuel sees a current number.
         */
        const val REFRESH_INTERVAL_MS = 2_000L
    }

    override fun onGetTemplate(): Template {
        val metrics = stateBus.latestByMetric.value
        val status = stateBus.status.value

        // Read user-customised tile order from DataStore. The CarApp
        // framework calls onGetTemplate() on every invalidate, so a
        // change in Settings shows up on the next sample without
        // re-pairing or restarting the service.
        //
        // runBlocking is acceptable here: we're already on the main
        // thread inside the framework's render call, and DataStore's
        // first() resolves quickly from the in-memory cache.
        val settings = runBlocking { settingsRepository.settings.first() }
        val resolved = CarTileCatalog.resolveHome(settings.aaTilesHome)

        val tiles = resolved.map { spec ->
            buildTile(spec, metrics, settings.unitSystem)
        }

        // EXACTLY ONE action here may carry a custom title. androidx.car.app
        // enforces "Action list exceeded max number of 1 actions with custom
        // titles" in ActionStrip.Builder.build(), and it throws hard enough
        // to take the whole car app down.
        //
        // This used to add a second titled "Broker off" action whenever the
        // broker was disconnected — so the screen rendered fine on a healthy
        // system and crashed exactly when something was already wrong, which
        // is the worst possible time and why it survived review. The broker
        // state now rides in the template title, where it needs no action
        // slot and is actually more legible on a head unit.
        val actions = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setTitle("Diagnostics")
                    .setOnClickListener {
                        screenManager.push(
                            DiagnosticsCarScreen(carContext, stateBus, settingsRepository),
                        )
                    }
                    .build(),
            )
            .build()

        return GridTemplate.Builder()
            .setTitle(if (status.brokerConnected) "Pitstop" else "Pitstop · broker offline")
            .setSingleList(ItemList.Builder().apply { tiles.forEach { addItem(it) } }.build())
            .setHeaderAction(Action.APP_ICON)
            .setActionStrip(actions)
            .build()
    }

    private fun buildTile(
        spec: CarTileSpec,
        metrics: Map<String, MetricSample>,
        system: String,
    ): GridItem {
        val trend = trends.classify(spec.key)
        val displayValue = carTileText(metrics[spec.key]?.value, spec, system, trend)
        // EVERY GridItem must carry an image. androidx.car.app enforces
        // "when a grid item is loading, the image must not be set and vice
        // versa" in GridItem.Builder.build() — a tile with neither an image
        // nor setLoading(true) throws IllegalStateException, which takes the
        // whole car app down with "Pitstop has encountered an unexpected
        // error". There is no text-only grid item in the template model.
        //
        // This previously set an image only for accent tiles, so the very
        // first render on a head unit crashed. Accent is now expressed by
        // the icon TINT rather than by the icon's presence.
        return GridItem.Builder()
            .setTitle(displayValue)
            .setText(spec.label)
            .setImage(
                metricIcon(spec, if (spec.accent) CarColor.PRIMARY else CarColor.DEFAULT),
                GridItem.IMAGE_TYPE_ICON,
            )
            .build()
    }

    private fun metricIcon(spec: CarTileSpec, tint: CarColor): CarIcon =
        CarIcon.Builder(
            androidx.core.graphics.drawable.IconCompat.createWithResource(
                carContext,
                spec.icon,
            ),
        )
            .setTint(tint)
            .build()
}

/**
 * Drill-down screen pushed from the home grid's action strip. Same
 * GridTemplate shape but shows diagnostic / phone-bridge telemetry the
 * top tiles don't have room for.
 */
class DiagnosticsCarScreen(
    carContext: CarContext,
    private val stateBus: BridgeStateBus,
    private val settingsRepository: SettingsRepository,
) : Screen(carContext), DefaultLifecycleObserver {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var observerJob: Job? = null
    private val trends = TrendTracker(windowMs = 30_000L)

    init {
        lifecycle.addObserver(this)
    }

    // Same split as LiveCarScreen: ingest every sample, repaint rarely.
    // The car host rate-limits template updates on this screen too.
    override fun onStart(owner: LifecycleOwner) {
        observerJob = scope.launch {
            launch {
                stateBus.latestByMetric.collect { snapshot -> trends.ingest(snapshot) }
            }
            launch {
                while (isActive) {
                    delay(DIAG_REFRESH_INTERVAL_MS)
                    invalidate()
                }
            }
        }
    }

    private companion object {
        const val DIAG_REFRESH_INTERVAL_MS = 2_000L
    }

    override fun onStop(owner: LifecycleOwner) {
        observerJob?.cancel()
        observerJob = null
    }

    override fun onDestroy(owner: LifecycleOwner) {
        scope.cancel()
    }

    override fun onGetTemplate(): Template {
        val metrics = stateBus.latestByMetric.value
        val settings = runBlocking { settingsRepository.settings.first() }
        val resolved = CarTileCatalog.resolveDiag(settings.aaTilesDiag)
        val tiles = resolved.map { spec ->
            tile(spec, metrics[spec.key]?.value, settings.unitSystem, trends.classify(spec.key))
        }

        return GridTemplate.Builder()
            .setTitle("Diagnostics")
            .setSingleList(ItemList.Builder().apply { tiles.forEach { addItem(it) } }.build())
            .setHeaderAction(Action.BACK)
            .build()
    }

    // Same GridItem image requirement as the home grid — see buildTile.
    // This screen set no image on any tile at all, so every diagnostics
    // tile would have thrown.
    private fun tile(spec: CarTileSpec, v: Double?, system: String, trend: TrendDir): GridItem =
        GridItem.Builder()
            .setTitle(carTileText(v, spec, system, trend))
            .setText(spec.label)
            .setImage(
                CarIcon.Builder(
                    androidx.core.graphics.drawable.IconCompat.createWithResource(
                        carContext,
                        spec.icon,
                    ),
                ).setTint(CarColor.DEFAULT).build(),
                GridItem.IMAGE_TYPE_ICON,
            )
            .build()
}

/**
 * Tile text shared by both car grids: the value converted into the
 * user's unit system, its unit label, then the trend arrow. Centralised
 * so the home grid and the diagnostics grid can't drift on units again.
 */
internal fun carTileText(
    v: Double?,
    spec: CarTileSpec,
    system: String,
    trend: TrendDir,
): String {
    val num = spec.quantity.number(v, system, spec.digits)
    if (num == "—") return "—"
    val unit = spec.unit(system)
    val arrow = when (trend) {
        TrendDir.Up -> " ▲"
        TrendDir.Down -> " ▼"
        TrendDir.Steady -> ""
    }
    return (if (unit.isBlank()) num else "$num $unit") + arrow
}

// ── Trend tracking ────────────────────────────────────────────────────

enum class TrendDir { Up, Down, Steady }

/**
 * Per-metric rolling history. Classifies the slope over a window into
 * Up / Down / Steady. Threshold is intentionally permissive (5% of
 * the value's running range) so noise doesn't flip the arrow on every
 * tick. We only keep two samples (oldest in window + latest) per
 * metric — cheap memory + cheap math.
 */
class TrendTracker(private val windowMs: Long) {
    private data class Window(val firstTs: Long, val firstVal: Double, val lastTs: Long, val lastVal: Double)

    private val byMetric = mutableMapOf<String, Window>()

    fun ingest(snapshot: Map<String, MetricSample>) {
        val now = System.currentTimeMillis()
        for ((key, sample) in snapshot) {
            val v = sample.value
            if (v.isNaN()) continue
            val existing = byMetric[key]
            if (existing == null || (now - existing.firstTs) > windowMs) {
                byMetric[key] = Window(now, v, now, v)
            } else {
                byMetric[key] = existing.copy(lastTs = now, lastVal = v)
            }
        }
    }

    fun classify(key: String): TrendDir {
        val w = byMetric[key] ?: return TrendDir.Steady
        if (w.lastTs - w.firstTs < 2_000) return TrendDir.Steady // not enough samples
        val delta = w.lastVal - w.firstVal
        // Threshold: 2% of mean magnitude, floor 0.05. Tuned so a fuel
        // gauge dropping 1% over 30 s reads "▼" but tiny noise on a
        // throttle reading at idle reads "·".
        val mean = (kotlin.math.abs(w.lastVal) + kotlin.math.abs(w.firstVal)) / 2.0
        val threshold = kotlin.math.max(0.05, mean * 0.02)
        return when {
            delta > threshold -> TrendDir.Up
            delta < -threshold -> TrendDir.Down
            else -> TrendDir.Steady
        }
    }
}
