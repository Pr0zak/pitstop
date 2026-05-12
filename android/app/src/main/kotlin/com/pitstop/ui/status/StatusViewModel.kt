package com.pitstop.ui.status

import android.app.Application
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pitstop.data.SettingsRepository
import com.pitstop.http.CostPerMilePointDto
import com.pitstop.http.DtcDto
import com.pitstop.http.MonthlySpendPointDto
import com.pitstop.http.MpgPointDto
import com.pitstop.http.PitstopApi
import com.pitstop.log.LogBuffer
import com.pitstop.log.LogShipper
import com.pitstop.mqtt.MqttPublisher
import com.pitstop.service.BridgeStateBus
import com.pitstop.service.BridgeStatus
import com.pitstop.service.PitstopBridgeService
import com.pitstop.ui.components.HeroCardData
import com.pitstop.update.UpdateChecker
import com.pitstop.update.UpdateInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Per-card state for the new Home dashboard. Each card loads
 * independently — one failed fetch shouldn't blank the others — so
 * the UI model surfaces nullable lists rather than a single monolithic
 * "is everything ready" flag.
 *
 * Null = still loading (or query token unset / api base unset).
 * Empty list = backend returned no data for this vehicle.
 */
data class StatusUiState(
    val status: BridgeStatus = BridgeStatus(),
    val brokerInfo: String? = null,
    val deepLinkUrl: String? = null,
    val totalPublished: Long = 0,
    val logsBuffered: Int = 0,
    val logsLastFlushMs: Long? = null,
    val logsLastResult: String = "—",
    val update: UpdateInfo? = null,
    val updateChecking: Boolean = false,
    val hero: HeroCardData? = null,
    val recentTrips: List<com.pitstop.http.TripDto>? = null,
    /** /analytics/mpg?window=month — last 12 months. Null while loading. */
    val mpgMonthly: List<MpgPointDto>? = null,
    /** /analytics/mpg?window=all — yearly buckets across the vehicle's life. */
    val mpgYearly: List<MpgPointDto>? = null,
    /** /analytics/cost-per-mi — full history, monthly. */
    val costPerMile: List<CostPerMilePointDto>? = null,
    /** /analytics/monthly-spend — full history, monthly. */
    val monthlySpend: List<MonthlySpendPointDto>? = null,
    /** /dtcs?active_only=true — empty list when nothing active. */
    val activeDtcs: List<DtcDto>? = null,
    /** Mirrors [com.pitstop.data.Settings.manualSyncOnly]; the
     *  BridgeStatePill flips to a "Local-only" badge when this is on
     *  AND the bridge is otherwise running. */
    val manualSyncOnly: Boolean = false,
)

@HiltViewModel
class StatusViewModel @Inject constructor(
    application: Application,
    private val settingsRepository: SettingsRepository,
    stateBus: BridgeStateBus,
    private val mqttPublisher: MqttPublisher,
    private val logBuffer: LogBuffer,
    logShipper: LogShipper,
    private val updateChecker: UpdateChecker,
    private val api: PitstopApi,
    private val bridgeStateBus: BridgeStateBus = stateBus,
) : AndroidViewModel(application) {

    private val updateInfo = MutableStateFlow<UpdateInfo?>(null)
    private val updateChecking = MutableStateFlow(false)
    private val heroData = MutableStateFlow<HeroCardData?>(null)
    private val recentTrips = MutableStateFlow<List<com.pitstop.http.TripDto>?>(null)
    private val mpgMonthly = MutableStateFlow<List<MpgPointDto>?>(null)
    private val mpgYearly = MutableStateFlow<List<MpgPointDto>?>(null)
    private val costPerMile = MutableStateFlow<List<CostPerMilePointDto>?>(null)
    private val monthlySpend = MutableStateFlow<List<MonthlySpendPointDto>?>(null)
    private val activeDtcs = MutableStateFlow<List<DtcDto>?>(null)

    init {
        viewModelScope.launch {
            updateChecking.value = true
            updateInfo.value = updateChecker.check()
            updateChecking.value = false
        }
        viewModelScope.launch { refreshHomeDataInternal() }
    }

    /**
     * Pull every Home-tab dataset in parallel. Each fetch is independently
     * `runCatching`-wrapped so a backend hiccup on (say) cost-per-mi
     * doesn't block the MPG card from updating. Per-card flows go to null
     * on failure → the corresponding component renders empty / skeleton.
     */
    private suspend fun refreshHomeDataInternal() {
        val secrets = runCatching { settingsRepository.current() }.getOrNull()
        if (secrets == null || secrets.queryToken.isBlank() || secrets.settings.apiBaseUrl.isBlank()) {
            logBuffer.warn("home refresh: QUERY_TOKEN / API base URL not configured; skipping")
            clearHomeData()
            return
        }
        val slug = secrets.settings.vehicleSlug.trim().ifEmpty {
            logBuffer.warn("home refresh: vehicle slug not set in Settings; skipping")
            clearHomeData()
            return
        }
        // Resolve slug → UUID once; every other endpoint takes vehicle_id.
        val vehicles = runCatching { api.getVehicles() }.getOrElse { exc ->
            logBuffer.warn(
                "home refresh: /vehicles fetch failed",
                mapOf("err" to (exc.message ?: exc::class.java.simpleName)),
            )
            clearHomeData()
            return
        }
        val vehicleId = vehicles.firstOrNull { it.slug == slug }?.id ?: run {
            logBuffer.warn(
                "home refresh: configured slug not found",
                mapOf("slug" to slug, "available" to vehicles.map { it.slug }),
            )
            clearHomeData()
            return
        }

        // Parallel-fan-out: every fetch lives in its own async{}. They
        // resolve independently so the cards populate as soon as their
        // data lands instead of waiting for the slowest endpoint.
        coroutineScope {
            val fillupsJob = async {
                runCatching { api.getFillups(vehicleId, limit = 30) }.getOrNull()
            }
            val mpgMonthlyJob = async {
                runCatching { api.getMpgTrend(vehicleId, window = "month") }.getOrNull()
            }
            val mpgYearlyJob = async {
                runCatching { api.getMpgTrend(vehicleId, window = "all") }.getOrNull()
            }
            val costJob = async {
                runCatching { api.getCostPerMile(vehicleId) }.getOrNull()
            }
            val spendJob = async {
                runCatching { api.getMonthlySpend(vehicleId) }.getOrNull()
            }
            val tripsJob = async {
                runCatching { api.getTrips(vehicleId, limit = 5) }.getOrNull()
            }
            val dtcsJob = async {
                runCatching { api.getDtcs(vehicleId, activeOnly = true) }.getOrNull()
            }

            // Wait for each then publish; cards independently reactive.
            val fillups = fillupsJob.await()
            val monthly = mpgMonthlyJob.await()
            val yearly = mpgYearlyJob.await()
            val cost = costJob.await()
            val spend = spendJob.await()
            val trips = tripsJob.await()
            val dtcs = dtcsJob.await()

            mpgMonthly.value = monthly?.points
            mpgYearly.value = yearly?.points
            costPerMile.value = cost?.points
            monthlySpend.value = spend?.months
            recentTrips.value = trips ?: emptyList()
            activeDtcs.value = dtcs ?: emptyList()

            // Derive the 2×2 hero strip from the same data the cards
            // load (no separate fetches). MPG sparkline source flips
            // from the year sparkline (now handled by MpgYearChart)
            // to a 90-day rolling derived from `monthly`.
            if (fillups != null && monthly != null) {
                heroData.value = buildHeroData(fillups, monthly.points)
            }
        }
    }

    private fun buildHeroData(
        fillups: List<com.pitstop.http.FillupDto>,
        monthly: List<MpgPointDto>,
    ): HeroCardData {
        val mpgs = monthly.mapNotNull { it.mpg }.filter { it > 0 }
        val avgMpg = if (mpgs.isNotEmpty()) mpgs.takeLast(3).average() else null

        val latest = fillups.firstOrNull()
        val ppgList = fillups.mapNotNull { it.pricePerUnit }.filter { it > 0 }
        val avgPpg = if (ppgList.isNotEmpty()) ppgList.average() else null
        val latestPpg = latest?.pricePerUnit
        val ppgDelta = if (latestPpg != null && avgPpg != null && avgPpg > 0) {
            ((latestPpg - avgPpg) / avgPpg) * 100.0
        } else null

        val now = java.time.LocalDate.now()
        val thisMonthPrefix = "%04d-%02d".format(now.year, now.monthValue)
        val monthFills = fillups.filter { it.fillupDate.startsWith(thisMonthPrefix) }
        val monthCost = monthFills.mapNotNull { it.priceTotal }.sum()
        val monthCount = monthFills.size

        val liveOdoMi = bridgeStateBus.latestByMetric.value["odometer"]?.value
            ?.let { it * 0.621371 }
        val milesSinceFill = if (liveOdoMi != null && latest?.odo != null) {
            (liveOdoMi - latest.odo).coerceAtLeast(0.0)
        } else null

        return HeroCardData(
            avgConsumptionMpg = avgMpg,
            latestPpg = latestPpg,
            ppgDeltaPct = ppgDelta,
            monthCost = monthCost,
            monthCount = monthCount,
            milesSinceFill = milesSinceFill,
            // mpgSeries field is now unused by the FuelHeroCards card
            // (the year sparkline moved into its own MpgYearChart), but
            // we keep the field populated for source-compat. Empty if
            // monthly is empty.
            mpgSeries = monthly.mapNotNull { it.mpg },
        )
    }

    private fun clearHomeData() {
        heroData.value = null
        recentTrips.value = null
        mpgMonthly.value = null
        mpgYearly.value = null
        costPerMile.value = null
        monthlySpend.value = null
        activeDtcs.value = null
    }

    fun refreshHomeData() {
        viewModelScope.launch { refreshHomeDataInternal() }
    }

    fun checkForUpdates() {
        viewModelScope.launch {
            updateChecking.value = true
            updateInfo.value = updateChecker.check()
            updateChecking.value = false
        }
    }

    val uiState: StateFlow<StatusUiState> =
        combine(
            // First five — the bridge / settings / logs / update group.
            combine(
                stateBus.status,
                settingsRepository.settings,
                logBuffer.bufferedCount,
                logShipper.lastFlushAtMs,
                logShipper.lastFlushResult,
            ) { status, settings, buffered, lastFlush, lastResult ->
                BridgeBundle(status, settings, buffered, lastFlush, lastResult)
            },
            updateInfo,
            updateChecking,
            heroData,
            recentTrips,
            mpgMonthly,
            mpgYearly,
            costPerMile,
            monthlySpend,
            activeDtcs,
        ) { values ->
            @Suppress("UNCHECKED_CAST")
            val bridge = values[0] as BridgeBundle
            val info = values[1] as UpdateInfo?
            val checking = values[2] as Boolean
            val hero = values[3] as HeroCardData?
            @Suppress("UNCHECKED_CAST")
            val trips = values[4] as List<com.pitstop.http.TripDto>?
            @Suppress("UNCHECKED_CAST")
            val mpgM = values[5] as List<MpgPointDto>?
            @Suppress("UNCHECKED_CAST")
            val mpgY = values[6] as List<MpgPointDto>?
            @Suppress("UNCHECKED_CAST")
            val cost = values[7] as List<CostPerMilePointDto>?
            @Suppress("UNCHECKED_CAST")
            val spend = values[8] as List<MonthlySpendPointDto>?
            @Suppress("UNCHECKED_CAST")
            val dtcs = values[9] as List<DtcDto>?

            StatusUiState(
                status = bridge.status.copy(
                    brokerConnected = mqttPublisher.isConnected(),
                    totalPublished = mqttPublisher.totalPublished,
                ),
                brokerInfo = bridge.settings.brokerUrl.takeIf { it.isNotBlank() },
                deepLinkUrl = bridge.settings.apiBaseUrl
                    .takeIf { it.isNotBlank() }
                    ?.trimEnd('/')
                    ?.let { "$it/live" },
                totalPublished = mqttPublisher.totalPublished,
                logsBuffered = bridge.buffered,
                logsLastFlushMs = bridge.lastFlush,
                logsLastResult = when (val r = bridge.lastResult) {
                    null -> "—"
                    LogShipper.FlushResult.Empty -> "empty"
                    is LogShipper.FlushResult.Ok -> "OK"
                    is LogShipper.FlushResult.Error -> "err: ${r.message}"
                },
                update = info,
                updateChecking = checking,
                hero = hero,
                recentTrips = trips,
                mpgMonthly = mpgM,
                mpgYearly = mpgY,
                costPerMile = cost,
                monthlySpend = spend,
                activeDtcs = dtcs,
                manualSyncOnly = bridge.settings.manualSyncOnly,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = StatusUiState(),
        )

    private data class BridgeBundle(
        val status: BridgeStatus,
        val settings: com.pitstop.data.Settings,
        val buffered: Int,
        val lastFlush: Long?,
        val lastResult: LogShipper.FlushResult?,
    )

    fun startService() {
        val ctx = getApplication<Application>()
        ContextCompat.startForegroundService(ctx, PitstopBridgeService.startIntent(ctx))
    }

    fun stopService() {
        val ctx = getApplication<Application>()
        ctx.startService(PitstopBridgeService.stopIntent(ctx))
    }
}
