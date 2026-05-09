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
import kotlinx.coroutines.flow.collectLatest
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
        observerJob = scope.launch {
            stateBus.latestByMetric.collectLatest { snapshot ->
                trends.ingest(snapshot)
                invalidate()
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
        val storedHome = runBlocking { settingsRepository.settings.first().aaTilesHome }
        val resolved = CarTileCatalog.resolveHome(storedHome)

        val tiles = resolved.map { spec ->
            buildTile(spec.label, spec.key, spec.unit, metrics, spec.digits, spec.accent)
        }

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
            .apply {
                if (!status.brokerConnected) {
                    // Coral-tinted "broker offline" hint — appears only when
                    // the chain is broken; otherwise the strip carries the
                    // Diagnostics action only and stays visually quiet.
                    addAction(
                        Action.Builder()
                            .setTitle("Broker off")
                            .build(),
                    )
                }
            }
            .build()

        return GridTemplate.Builder()
            .setTitle("Pitstop")
            .setSingleList(ItemList.Builder().apply { tiles.forEach { addItem(it) } }.build())
            .setHeaderAction(Action.APP_ICON)
            .setActionStrip(actions)
            .build()
    }

    private fun buildTile(
        label: String,
        key: String,
        unit: String,
        metrics: Map<String, MetricSample>,
        digits: Int = 0,
        accent: Boolean = false,
    ): GridItem {
        val sample = metrics[key]
        val value = sample?.value
        val trend = trends.classify(key)
        val displayValue = formatValue(value, unit, digits, trend)
        val builder = GridItem.Builder()
            .setTitle(displayValue)
            .setText(label)
        if (accent) {
            builder.setImage(brandIcon(), GridItem.IMAGE_TYPE_ICON)
        }
        return builder.build()
    }

    private fun formatValue(v: Double?, unit: String, digits: Int, trend: TrendDir): String {
        if (v == null) return "—"
        val num = "%.${digits}f".format(v)
        val unitTxt = if (unit.isBlank()) "" else " $unit"
        val arrow = when (trend) {
            TrendDir.Up -> " ▲"
            TrendDir.Down -> " ▼"
            TrendDir.Steady -> ""
        }
        return "$num$unitTxt$arrow"
    }

    private fun brandIcon(): CarIcon =
        CarIcon.Builder(
            androidx.core.graphics.drawable.IconCompat.createWithResource(
                carContext,
                R.drawable.ic_launcher_foreground,
            ),
        )
            .setTint(CarColor.PRIMARY)
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

    override fun onStart(owner: LifecycleOwner) {
        observerJob = scope.launch {
            stateBus.latestByMetric.collectLatest { snapshot ->
                trends.ingest(snapshot)
                invalidate()
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

    override fun onGetTemplate(): Template {
        val metrics = stateBus.latestByMetric.value
        val storedDiag = runBlocking { settingsRepository.settings.first().aaTilesDiag }
        val resolved = CarTileCatalog.resolveDiag(storedDiag)
        val tiles = resolved.map { spec ->
            tile(spec.label, metrics[spec.key]?.value, spec.unit, spec.digits, trends.classify(spec.key))
        }

        return GridTemplate.Builder()
            .setTitle("Diagnostics")
            .setSingleList(ItemList.Builder().apply { tiles.forEach { addItem(it) } }.build())
            .setHeaderAction(Action.BACK)
            .build()
    }

    private fun tile(label: String, v: Double?, unit: String, digits: Int, trend: TrendDir): GridItem {
        val txt = if (v == null) "—" else {
            val num = "%.${digits}f".format(v)
            val arrow = when (trend) {
                TrendDir.Up -> " ▲"; TrendDir.Down -> " ▼"; TrendDir.Steady -> ""
            }
            "$num ${unit}$arrow".trim()
        }
        return GridItem.Builder().setTitle(txt).setText(label).build()
    }
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
