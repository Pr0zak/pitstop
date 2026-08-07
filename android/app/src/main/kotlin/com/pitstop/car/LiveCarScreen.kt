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
                settingsRepository.settings.collect { cachedSettings = it }
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
        val settings = cachedSettings ?: return ""
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

    /**
     * Settings, kept current by a collector rather than read synchronously.
     *
     * onGetTemplate() used to runBlocking { settings.first() } twice per tick.
     * The justification was that DataStore resolves from an in-memory cache —
     * true only AFTER the first collection. The first read after process
     * start is disk I/O on the main thread, and it lands exactly at car
     * connect, which is the moment the head unit is cold and the moment the
     * app-quality launch-time requirements measure.
     */
    @Volatile
    private var cachedSettings: com.pitstop.data.Settings? = null

    override fun onGetTemplate(): Template {
        val metrics = stateBus.latestByMetric.value
        val status = stateBus.status.value

        // Tile order comes from DataStore via the collector above, so a
        // change in Settings shows up on the next tick without re-pairing
        // or restarting the service.
        //
        // Loading is not a fallback, it is the sanctioned first frame: every
        // refresh predicate begins "the previous template is in a loading
        // state", so the loading -> real transition is a free refresh
        // whatever shape it takes.
        val settings = cachedSettings
            ?: return GridTemplate.Builder().setLoading(true).setTitle("Pitstop").build()

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
                    .setTitle(tabTitle(tab))
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
     * Tab titles are STRUCTURAL and must never change.
     *
     * TabTemplate's refresh predicate requires the same number of tabs with
     * the same title and icon, so flipping "Diag" to "Diag !" on a broker
     * flap was an unconditional template replacement. MQTT reconnects are
     * routine on this stack and the render signature includes the broker
     * flag, so every flap fired one — five in a task and the host closes the
     * app.
     *
     * Broker state now shows as a dot Badge on the affected tile plus a text
     * row in the Status pane. Both, not either: a red dot alone fails
     * WCAG 1.4.1 and is invisible to roughly one man in twelve. The dot is
     * the fast cue, the row carries the meaning.
     */
    private fun tabTitle(tab: CarTileCatalog.CarScreenKind): String = tab.title

    /**
     * One template per screen kind. Metric screens are a GridTemplate;
     * Status is a PaneTemplate.
     *
     * Grid size is READ from the host, not assumed. MAX_TILES = 6 in the
     * catalogue is the library's FALLBACK, not any particular car's limit —
     * real head units often allow more. (An over-limit list does not throw,
     * contrary to an earlier comment here; the host silently drops the
     * overflow. Truncating is still right, but for honesty about what the
     * user configured, not to avoid a crash.)
     */
    private fun contentFor(
        kind: CarTileCatalog.CarScreenKind,
        metrics: Map<String, MetricSample>,
        status: BridgeStatus,
        settings: com.pitstop.data.Settings,
    ): Template = when {
        kind.isMetricGrid -> {
            val limit = contentLimit(
                androidx.car.app.constraints.ConstraintManager.CONTENT_LIMIT_TYPE_GRID,
                CarTileCatalog.MAX_TILES,
            )
            GridTemplate.Builder()
                .setSingleList(
                    ItemList.Builder().apply {
                        tilesFor(kind, settings).take(limit).forEach { spec ->
                            addItem(buildTile(spec, metrics, settings.unitSystem, status))
                        }
                    }.build(),
                )
                .build()
        }
        else -> paneOf(sessionRows(status, metrics))
    }

    private fun contentLimit(type: Int, fallback: Int): Int = runCatching {
        carContext
            .getCarService(androidx.car.app.constraints.ConstraintManager::class.java)
            .getContentLimit(type)
    }.getOrDefault(fallback).coerceAtLeast(1)

    private fun paneOf(rows: List<Pair<String, String>>): Template {
        val limit = contentLimit(
            androidx.car.app.constraints.ConstraintManager.CONTENT_LIMIT_TYPE_PANE,
            4,
        )
        val pane = androidx.car.app.model.Pane.Builder()
        // Row.setTitle is the LABEL and addText the value, for exactly the
        // reason buildTile now does the same: the host treats an update as a
        // refresh only when titles are unchanged, and a non-refresh spends
        // one of five templates per task before the app is closed.
        for ((label, value) in rows.take(limit)) {
            pane.addRow(
                androidx.car.app.model.Row.Builder()
                    .setTitle(label)
                    .addText(value)
                    .build(),
            )
        }
        return androidx.car.app.model.PaneTemplate.Builder(pane.build()).build()
    }

    /**
     * Fixed row COUNT and fixed row TITLES — only the values move. Adding or
     * removing a row on a state change would be a structural change and cost
     * a template.
     */
    private fun sessionRows(
        status: BridgeStatus,
        metrics: Map<String, MetricSample>,
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
        status: BridgeStatus,
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
        // Label in the TITLE, value in the TEXT — never the reverse.
        //
        // GridTemplate's refresh predicate is "the number of grid items and
        // the TITLE of each grid item have not changed". Item text and image
        // are excluded from that diff; the title is not. With the value in
        // the title, every tick was a template REPLACEMENT rather than a
        // refresh, and the host allows five templates per task before it
        // shows an error and CLOSES THE APP. At a 2 s repaint that is roughly
        // ten seconds of driving.
        //
        // paneOf() below has always done this correctly and says why in its
        // own comment. This was a one-place inconsistency, not a design
        // position — and the scroll-reset that drove the move to three tiles
        // per tab was the same bug seen from the other side.
        return GridItem.Builder()
            .setTitle(spec.label)
            .setText(displayValue)
            .setImage(
                metricIcon(spec, if (spec.accent) CarColor.PRIMARY else CarColor.DEFAULT),
                GridItem.IMAGE_TYPE_ICON,
                // A dot on the accent tile when the broker is down. Badges sit
                // OUTSIDE the refresh diff (only item count and title are
                // compared), so this conveys state without costing a template
                // — which is exactly what the old "Diag !" tab title did not.
                // Paired with the Status pane's "Broker" row, because colour
                // alone is not an accessible signal.
                androidx.car.app.model.Badge.Builder()
                    .setHasDot(spec.accent && !status.brokerConnected)
                    .setBackgroundColor(CarColor.RED)
                    .build(),
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
            if (existing == null) {
                byMetric[key] = Window(now, v, now, v)
            } else if ((now - existing.firstTs) > windowMs) {
                // Roll the window forward, seeding the new baseline with the
                // PREVIOUS reading rather than the current one.
                //
                // Resetting to (now, v, now, v) made firstVal == lastVal and
                // lastTs - firstTs == 0, so classify() returned Steady until
                // 2 s of fresh samples accumulated: every tile's arrow
                // vanished and re-appeared once per window. That is a wasted
                // repaint and, worse, unrequested motion on a car display.
                byMetric[key] = Window(now - 2_001, existing.lastVal, now, v)
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
