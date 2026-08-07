package com.pitstop.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.Tab
import androidx.car.app.model.TabContents
import androidx.car.app.model.TabTemplate
import com.pitstop.service.BridgeStatus
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
    private var lastRendered: String? = null

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
                    // Only repaint when the RENDERED TEXT would actually
                    // differ. The car host resets the grid's scroll position
                    // whenever the template is replaced, so an unconditional
                    // timer yanked the user back to the top every 2 s while
                    // they were reading the lower tiles — even with the car
                    // parked and every value identical.
                    val sig = renderSignature()
                    if (sig != lastRendered) {
                        lastRendered = sig
                        invalidate()
                    }
                }
            }
        }
    }

    /**
     * Everything that affects what the grid draws, flattened to a string.
     * Compared against the last painted frame so an unchanged screen is
     * never re-sent. Deliberately built from the SAME helper the template
     * uses (`carTileText`), so a value that rounds to the same display text
     * counts as unchanged — e.g. RPM drifting 722 -> 723 with 0 decimals.
     */
    private fun renderSignature(): String {
        val metrics = stateBus.latestByMetric.value
        val status = stateBus.status.value
        val settings = runBlocking { settingsRepository.settings.first() }
        return buildString {
            append(activeTab?.id)
            append(status.brokerConnected)
            for (spec in tilesFor(activeTab ?: return@buildString, settings)) {
                append('|')
                append(
                    carTileText(
                        metrics[spec.key]?.value,
                        spec,
                        settings.unitSystem,
                        trends.classify(spec.key),
                    ),
                )
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

    /** Which tab the head unit is showing. Survives invalidate(); reset only
     *  when the screen is recreated. */
    private var activeTab: CarTileCatalog.CarScreenKind? = null

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

        val tabs = CarTileCatalog.CarScreenKind.resolveTabs(settings.aaTabs)
        // A stored tab could have been removed from the catalogue, or the
        // list re-ordered in Settings while the car screen was live.
        val active = activeTab?.takeIf { it in tabs } ?: tabs.first()
        activeTab = active

        val content = contentFor(active, metrics, status, settings)

        val builder = TabTemplate.Builder(
            object : TabTemplate.TabCallback {
                override fun onTabSelected(tabContentId: String) {
                    activeTab = CarTileCatalog.CarScreenKind.entries
                        .firstOrNull { it.id == tabContentId } ?: return
                    // Repaint immediately rather than waiting for the next
                    // refresh tick, and reset the signature so the tick does
                    // not immediately consider this frame stale.
                    lastRendered = null
                    invalidate()
                }
            },
        )
            // APP_ICON is the only header action a TabTemplate accepts.
            .setHeaderAction(Action.APP_ICON)
            .setTabContents(TabContents.Builder(content).build())
            .setActiveTabContentId(active.id)

        for (tab in tabs) {
            builder.addTab(
                Tab.Builder()
                    .setTitle(tabTitle(tab, status))
                    .setContentId(tab.id)
                    .setIcon(
                        CarIcon.Builder(
                            androidx.core.graphics.drawable.IconCompat
                                .createWithResource(carContext, tab.icon),
                        ).build(),
                    )
                    .build(),
            )
        }
        return builder.build()
    }

    /**
     * Broker state rides on the Diag tab's title rather than in an
     * ActionStrip. TabTemplate has no action strip at all, and the
     * ActionStrip it replaced could hold only ONE titled action — adding a
     * second "Broker off" action is what crashed the car app in v0.1.216.
     */
    private fun tabTitle(tab: CarTileCatalog.CarScreenKind, status: BridgeStatus): String =
        if (tab == CarTileCatalog.CarScreenKind.Diagnostics && !status.brokerConnected) {
            "Diag !"
        } else {
            tab.title
        }

    /**
     * One template per screen kind. Metric screens are a GridTemplate of
     * tiles; everything else is a PaneTemplate, which is the only general
     * template that pairs a large rendered image with a few rows of text.
     *
     * Pane row count is READ from the host rather than assumed —
     * getContentLimit(CONTENT_LIMIT_TYPE_PANE) — because exceeding a
     * content limit throws instead of truncating, and that takes the whole
     * car app down. Every limit assumed rather than measured has cost a
     * crash on this surface already.
     */
    private fun contentFor(
        kind: CarTileCatalog.CarScreenKind,
        metrics: Map<String, MetricSample>,
        status: BridgeStatus,
        settings: com.pitstop.data.Settings,
    ): Template = when {
        kind.isMetricGrid -> GridTemplate.Builder()
            .setSingleList(
                ItemList.Builder().apply {
                    tilesFor(kind, settings).forEach { spec ->
                        addItem(buildTile(spec, metrics, settings.unitSystem))
                    }
                }.build(),
            )
            .build()

        kind == CarTileCatalog.CarScreenKind.Session ->
            paneOf(sessionRows(status, metrics, settings.unitSystem))

        // Server-backed screens. Nothing is fetched yet, so they render an
        // honest placeholder rather than an empty pane that looks broken —
        // a car screen that silently shows nothing is worse than one that
        // says why.
        else -> paneOf(
            listOf(
                "Not yet available" to kind.title,
                "Needs" to "a connection to your server",
            ),
        )
    }

    private fun paneRowLimit(): Int = runCatching {
        carContext.getCarService(androidx.car.app.constraints.ConstraintManager::class.java)
            .getContentLimit(
                androidx.car.app.constraints.ConstraintManager.CONTENT_LIMIT_TYPE_PANE,
            )
    }.getOrDefault(4).coerceAtLeast(1)

    private fun paneOf(rows: List<Pair<String, String>>): Template {
        val pane = androidx.car.app.model.Pane.Builder()
        // Row.setTitle is the LABEL and addText the value, not the other way
        // round: the host treats a template as a refresh only when titles are
        // unchanged, so putting the changing value in the title makes every
        // update a replacement — which is what resets scroll position.
        for ((label, value) in rows.take(paneRowLimit())) {
            pane.addRow(
                androidx.car.app.model.Row.Builder()
                    .setTitle(label)
                    .addText(value)
                    .build(),
            )
        }
        return androidx.car.app.model.PaneTemplate.Builder(pane.build()).build()
    }

    private fun sessionRows(
        status: BridgeStatus,
        metrics: Map<String, MetricSample>,
        unitSystem: String,
    ): List<Pair<String, String>> = listOf(
        "Bridge" to status.phase.name,
        "Engine" to status.engineState.name,
        "Broker" to if (status.brokerConnected) "connected" else "offline",
        "Live metrics" to metrics.size.toString(),
    )

    private fun tilesFor(
        tab: CarTileCatalog.CarScreenKind,
        settings: com.pitstop.data.Settings,
    ): List<CarTileSpec> = CarTileCatalog.resolveTab(
        tab,
        when (tab) {
            CarTileCatalog.CarScreenKind.Drive -> settings.aaTilesHome
            CarTileCatalog.CarScreenKind.Engine -> settings.aaTilesEngine
            CarTileCatalog.CarScreenKind.Fuel -> settings.aaTilesFuel
            CarTileCatalog.CarScreenKind.Diagnostics -> settings.aaTilesDiag
            // Analytics screens carry no tile list; contentFor never routes
            // them here, and an empty list resolves to the kind's defaults.
            else -> emptyList()
        },
    )

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

/*
 * DiagnosticsCarScreen was removed in the TabTemplate migration. It existed
 * only because a GridTemplate can host one screen at a time and the diagnostics
 * metrics had to be pushed onto the ScreenManager behind a "Diagnostics" action.
 * Tabs render it in place, which also frees the 5-template-per-task quota that a
 * push consumed. Its tile list survives as CarTab.Diagnostics.
 */

/**
 * Tile text shared by every car tab: the value converted into the
 * user's unit system, its unit label, then the trend arrow. Centralised
 * so no two tabs can drift on units.
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
