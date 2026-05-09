package com.pitstop.ui.status

import android.app.Application
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pitstop.data.SettingsRepository
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

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
    /**
     * Phone-web parity hero data. Mirrors the web Overview hero strip:
     * 90-day rolling MPG, latest $/gal + delta vs 30-day avg, this
     * month's spend, miles since last fillup. Null when the QUERY
     * token isn't configured or the backend isn't reachable.
     */
    val hero: HeroCardData? = null,
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

    init {
        // Background check on first observe — best-effort, no UI block.
        viewModelScope.launch {
            updateChecking.value = true
            updateInfo.value = updateChecker.check()
            updateChecking.value = false
        }
        viewModelScope.launch { refreshHero() }
    }

    /**
     * Pull /vehicles + /fillups + /analytics/mpg from the backend, derive
     * the same four hero values the web Overview computes. Fails silently
     * (heroData stays null) when the QUERY token isn't set or the API
     * is unreachable — the rest of the screen keeps working.
     */
    private suspend fun refreshHero() {
        try {
            val secrets = settingsRepository.current()
            if (secrets.queryToken.isBlank()) {
                logBuffer.warn("hero refresh: QUERY_TOKEN not set in Settings; skipping")
                heroData.value = null
                return
            }
            if (secrets.settings.apiBaseUrl.isBlank()) {
                logBuffer.warn("hero refresh: API base URL not set in Settings; skipping")
                heroData.value = null
                return
            }
            val slug = secrets.settings.vehicleSlug.trim().ifEmpty {
                logBuffer.warn("hero refresh: vehicle slug not set in Settings; skipping")
                heroData.value = null
                return
            }
            // Resolve slug → vehicle_id locally rather than asking the
            // backend twice; we cached the vehicle list briefly.
            val vehicles = runCatching { api.getVehicles() }.getOrElse { exc ->
                logBuffer.warn(
                    "hero refresh: /vehicles fetch failed",
                    mapOf("err" to (exc.message ?: exc::class.java.simpleName)),
                )
                heroData.value = null
                return
            }
            val vehicleId = vehicles.firstOrNull { it.slug == slug }?.id ?: run {
                logBuffer.warn(
                    "hero refresh: configured slug not found in /vehicles list",
                    mapOf("slug" to slug, "available" to vehicles.map { it.slug }),
                )
                heroData.value = null
                return
            }
            val fillups = runCatching { api.getFillups(vehicleId, limit = 30) }.getOrElse { exc ->
                logBuffer.warn(
                    "hero refresh: /fillups fetch failed",
                    mapOf("vehicle_id" to vehicleId, "err" to (exc.message ?: exc::class.java.simpleName)),
                )
                heroData.value = null
                return
            }
            val mpgResp = runCatching {
                api.getMpgTrend(vehicleId, window = "year")
            }.getOrNull()
            val mpgPoints = mpgResp?.points.orEmpty()

            // 90-day rolling MPG — average of the year-window points
            // is close enough to a true 90-day rolling for hero display.
            val mpgs = mpgPoints.mapNotNull { it.mpg }.filter { it > 0 }
            val avgMpg = if (mpgs.isNotEmpty()) mpgs.average() else null

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

            // miles-since-last-fill — for the phone we don't have the
            // live OBD odo to subtract, so we surface "—" until the
            // bridge service publishes one.
            val liveOdoMi = bridgeStateBus.latestByMetric.value["odometer"]?.value
                ?.let { it * 0.621371 }
            val milesSinceFill =
                if (liveOdoMi != null && latest?.odo != null) {
                    (liveOdoMi - latest.odo).coerceAtLeast(0.0)
                } else null

            heroData.value = HeroCardData(
                avgConsumptionMpg = avgMpg,
                latestPpg = latestPpg,
                ppgDeltaPct = ppgDelta,
                monthCost = monthCost,
                monthCount = monthCount,
                milesSinceFill = milesSinceFill,
                mpgSeries = mpgPoints.mapNotNull { it.mpg },
            )
        } catch (_: Throwable) {
            heroData.value = null
        }
    }

    fun refreshHomeData() {
        viewModelScope.launch { refreshHero() }
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
            stateBus.status,
            settingsRepository.settings,
            logBuffer.bufferedCount,
            logShipper.lastFlushAtMs,
            logShipper.lastFlushResult,
            updateInfo,
            updateChecking,
            heroData,
        ) { values ->
            val status = values[0] as BridgeStatus
            val settings = values[1] as com.pitstop.data.Settings
            val buffered = values[2] as Int
            val lastFlush = values[3] as Long?
            val lastResult = values[4] as LogShipper.FlushResult?
            val info = values[5] as UpdateInfo?
            val checking = values[6] as Boolean
            val hero = values[7] as HeroCardData?
            StatusUiState(
                status = status.copy(
                    brokerConnected = mqttPublisher.isConnected(),
                    totalPublished = mqttPublisher.totalPublished,
                ),
                brokerInfo = settings.brokerUrl.takeIf { it.isNotBlank() },
                deepLinkUrl = settings.apiBaseUrl
                    .takeIf { it.isNotBlank() }
                    ?.trimEnd('/')
                    ?.let { "$it/live" },
                totalPublished = mqttPublisher.totalPublished,
                logsBuffered = buffered,
                logsLastFlushMs = lastFlush,
                logsLastResult = when (val r = lastResult) {
                    null -> "—"
                    LogShipper.FlushResult.Empty -> "empty"
                    is LogShipper.FlushResult.Ok -> "OK"
                    is LogShipper.FlushResult.Error -> "err: ${r.message}"
                },
                update = info,
                updateChecking = checking,
                hero = hero,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = StatusUiState(),
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
