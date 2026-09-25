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
import com.pitstop.service.BridgePhase
import com.pitstop.service.BridgeStatus
import com.pitstop.service.EngineState
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
 * Top-level Pitstop screen for the head unit: a TabTemplate of up to four
 * tabs (CarScreenKind). Metric tabs are a GridTemplate of up to six tiles;
 * the Status tab is a PaneTemplate. The car's own cluster already shows
 * speed, so the default Drive grid spends that slot elsewhere:
 *
 *   ┌──────────┬──────────┬──────────┐
 *   │ RPM      │ Fuel     │ Coolant  │
 *   │ 1850     │ 64 % ▼   │ 86 °C    │
 *   ├──────────┼──────────┼──────────┤
 *   │ Intake   │ Eng load │ Battery  │
 *   │ 28 °C    │ 24 %     │ 14.1 V   │
 *   └──────────┴──────────┴──────────┘
 *
 * Units follow the phone's imperial/metric toggle — the grid above is
 * drawn in metric; an imperial user sees °F / mph / psi in the same
 * slots. Each tile's unit comes from its CarTileSpec.quantity.
 *
 * Tile text comes from [renderCarTile]: trend arrows (30 s TrendTracker
 * window) on slow metrics only, a warning word + RED/YELLOW tint past a
 * bound, the sample's age after 10 s and "stale" after a minute, and — when
 * the whole grid is empty — the reason ("Engine off" / "Connecting…").
 *
 * STRUCTURE IS FIXED so every update is an in-place refresh: tab count and
 * titles never change, grid item count and titles never change, pane row
 * count and titles never change, and the pane's one action is permanent.
 * Only item TEXT, icon tint and badges move — all outside the host's
 * refresh diff, so the five-templates-per-task quota is never spent.
 *
 * Day/night handling is automatic: every colour we pass is a CarColor
 * enum (PRIMARY / RED / YELLOW / SECONDARY) and the host (Android Auto
 * or AAOS) translates it for the active palette. We never hard-code
 * pixel colours so the screen adapts when the car turns night-mode
 * on at sunset.
 */
class LiveCarScreen(
    carContext: CarContext,
    private val stateBus: BridgeStateBus,
    private val settingsRepository: SettingsRepository,
    private val rangeRepository: com.pitstop.data.RangeRepository? = null,
) : Screen(carContext), DefaultLifecycleObserver {

    /** Live metrics plus the synthetic range tile (see [withRangeTile]). */
    private fun currentMetrics(): Map<String, MetricSample> =
        withRangeTile(stateBus.latestByMetric.value, rangeRepository?.inputs?.value)

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
     * uses (`renderCarTile`), so a value that rounds to the same display text
     * counts as unchanged — e.g. RPM drifting 722 -> 723 with 0 decimals.
     */
    private fun renderSignature(): String {
        val metrics = currentMetrics()
        val status = stateBus.status.value
        val settings = cachedSettings ?: return ""
        val now = System.currentTimeMillis()
        return buildString {
            val tab = activeTab ?: return@buildString
            append(tab.id)
            append(obdDown(status, now))
            if (!tab.isMetricGrid) {
                for ((_, v) in sessionRows(status, settings, now)) append('|').append(v)
                return@buildString
            }
            val specs = tilesFor(tab, settings)
            val reason = emptyGridReason(specs, metrics, status)
            for (spec in specs) {
                append('|')
                // Includes the 5 s age bucket, so a quiet tile's "· 25s"
                // advances without every tick counting as a change.
                append(tileRender(spec, metrics, settings.unitSystem, now, reason))
            }
        }
    }

    private fun tileRender(
        spec: CarTileSpec,
        metrics: Map<String, MetricSample>,
        system: String,
        now: Long,
        emptyReason: String?,
    ): CarTileRender {
        val sample = metrics[spec.key]
        return renderCarTile(
            value = sample?.value,
            spec = spec,
            system = system,
            trend = trends.classify(spec.key),
            ageS = sample?.let { ((now - it.tsMs) / 1000L).coerceAtLeast(0L) },
            emptyReason = emptyReason,
        )
    }

    /**
     * Why an ENTIRELY empty grid is empty, shown as each tile's text so the
     * screen explains itself instead of reading as six dashes. Null when
     * any tile has a value (a single missing PID keeps its "—").
     */
    private fun emptyGridReason(
        specs: List<CarTileSpec>,
        metrics: Map<String, MetricSample>,
        status: BridgeStatus,
    ): String? {
        if (specs.any { metrics[it.key] != null }) return null
        return when {
            status.engineState == EngineState.Off -> "Engine off"
            status.phase == BridgePhase.Idle -> "Bridge off"
            status.phase == BridgePhase.Error -> "Bridge error"
            status.phase == BridgePhase.Connected -> "Waiting for data"
            else -> "Connecting…"
        }
    }

    /**
     * The red-dot condition: the OBD link is down or has gone quiet for
     * more than 10 s. Deliberately NOT the MQTT broker — the broker only
     * affects upload, which the Status pane's Upload row reports; the dot
     * means "these numbers aren't live".
     */
    private fun obdDown(status: BridgeStatus, now: Long): Boolean {
        if (status.phase != BridgePhase.Connected) return true
        val last = status.lastObdFrameAtMs ?: return true
        return now - last > TILE_AGE_AFTER_S * 1000L
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
        val metrics = currentMetrics()
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
     * the same title and icon, so flipping "Diag" to "Diag !" on a state
     * flap was an unconditional template replacement — five in a task and
     * the host closes the app.
     *
     * Link state instead shows as a dot Badge on the first tile plus a text
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
            val now = System.currentTimeMillis()
            val specs = tilesFor(kind, settings).take(limit)
            val reason = emptyGridReason(specs, metrics, status)
            val down = obdDown(status, now)
            GridTemplate.Builder()
                .setSingleList(
                    ItemList.Builder().apply {
                        specs.forEachIndexed { i, spec ->
                            addItem(
                                buildTile(
                                    spec,
                                    tileRender(spec, metrics, settings.unitSystem, now, reason),
                                    badged = i == 0 && down,
                                ),
                            )
                        }
                    }.build(),
                )
                .build()
        }
        else -> paneOf(sessionRows(status, settings, System.currentTimeMillis()))
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
        // PERMANENT action — present in every state, even when connected,
        // because adding it only when the link is down would change the
        // pane's structure and cost a template. Idle/errored bridge: start
        // it; otherwise: cut the reconnect backoff short (wakeUp).
        pane.addAction(
            Action.Builder()
                .setTitle("Reconnect")
                .setOnClickListener { reconnect() }
                .build(),
        )
        return androidx.car.app.model.PaneTemplate.Builder(pane.build()).build()
    }

    private fun reconnect() {
        val phase = stateBus.status.value.phase
        if (phase == BridgePhase.Idle || phase == BridgePhase.Error) {
            runCatching {
                androidx.core.content.ContextCompat.startForegroundService(
                    carContext,
                    com.pitstop.service.PitstopBridgeService.startIntent(carContext),
                )
            }
        } else {
            stateBus.wakeUp()
        }
        androidx.car.app.CarToast.makeText(carContext, "Reconnecting…", androidx.car.app.CarToast.LENGTH_SHORT).show()
    }

    /**
     * Fixed row COUNT and fixed row TITLES — only the values move. Adding or
     * removing a row on a state change would be a structural change and cost
     * a template. Four rows = the pane's usual content limit.
     */
    private fun sessionRows(
        status: BridgeStatus,
        settings: com.pitstop.data.Settings,
        now: Long,
    ): List<Pair<String, String>> {
        val obdAge = status.lastObdFrameAtMs?.let { ageBucketS(((now - it) / 1000L).coerceAtLeast(0L)) }
        val link = when (status.phase) {
            BridgePhase.Connected -> "Connected" + (obdAge?.let { " · ${agoText(it)}" } ?: " · no data yet")
            BridgePhase.Scanning -> "Searching for the WiCAN"
            BridgePhase.Connecting -> "Connecting…"
            BridgePhase.Disconnected -> "Reconnecting" + (obdAge?.let { " · last data ${agoText(it)}" } ?: "")
            BridgePhase.Idle -> "Bridge off"
            BridgePhase.Error -> "Error — tap Reconnect"
        }
        val engine = when (status.engineState) {
            EngineState.On -> "Running"
            EngineState.Off -> "Off"
            EngineState.Unknown -> "Unknown"
        }
        val queued = status.offlineBufferBytes.takeIf { it > 0 }?.let { " · ${humanBytes(it)} queued" }.orEmpty()
        val upload = when {
            settings.manualSyncOnly -> "Local-only (manual sync)$queued"
            status.brokerConnected -> "Live$queued"
            else -> "Offline$queued"
        }
        val device = (status.deviceName ?: status.deviceMac ?: "Not paired") +
            (status.rssi?.let { " · $it dBm" } ?: "")
        return listOf(
            "OBD link" to link,
            "Engine" to engine,
            "Upload" to upload,
            "Device" to device,
        )
    }

    private fun agoText(s: Long): String = if (s < 60) "${s}s ago" else "${s / 60}m ago"

    private fun humanBytes(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> "%.0f KB".format(b / 1024.0)
        else -> "%.1f MB".format(b / 1024.0 / 1024.0)
    }

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
        render: CarTileRender,
        badged: Boolean,
    ): GridItem {
        // EVERY GridItem must carry an image. androidx.car.app enforces
        // "when a grid item is loading, the image must not be set and vice
        // versa" in GridItem.Builder.build() — a tile with neither an image
        // nor setLoading(true) throws IllegalStateException, which takes the
        // whole car app down with "Pitstop has encountered an unexpected
        // error". There is no text-only grid item in the template model.
        //
        // Label in the TITLE, value in the TEXT — never the reverse.
        // GridTemplate's refresh predicate is "the number of grid items and
        // the TITLE of each grid item have not changed". Item text, image
        // tint and badge are excluded from that diff; the title is not. With
        // the value in the title, every tick was a template REPLACEMENT, and
        // the host allows five per task before it CLOSES THE APP.
        val tint = when {
            render.warn == TileWarn.Severe -> CarColor.RED
            render.warn == TileWarn.Caution -> CarColor.YELLOW
            render.stale -> CarColor.SECONDARY
            spec.accent -> CarColor.PRIMARY
            else -> CarColor.DEFAULT
        }
        return GridItem.Builder()
            .setTitle(spec.label)
            .setText(render.text)
            .apply {
                val icon = metricIcon(spec, tint)
                // The badge is attached ONLY when it has a dot to show.
                // Badge.Builder().build() throws "A badge must have a dot or
                // an icon set" for an empty badge, and because CarAppService
                // shares the app's process that exception crash-looped the
                // phone UI as well as the car screen.
                if (badged) {
                    setImage(
                        icon,
                        GridItem.IMAGE_TYPE_ICON,
                        androidx.car.app.model.Badge.Builder()
                            .setHasDot(true)
                            .setBackgroundColor(CarColor.RED)
                            .build(),
                    )
                } else {
                    setImage(icon, GridItem.IMAGE_TYPE_ICON)
                }
            }
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

// ── Trend tracking ────────────────────────────────────────────────────

enum class TrendDir { Up, Down, Steady }

/**
 * Per-metric rolling history. Classifies the slope over a window into
 * Up / Down / Steady. Threshold is 2 % of the value's mean magnitude
 * (floor 0.05) — see [classify] — so noise doesn't flip the arrow on
 * every tick. We only keep two samples (oldest in window + latest) per
 * metric — cheap memory + cheap math. Only CarTileSpec.trend tiles show
 * the result.
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
