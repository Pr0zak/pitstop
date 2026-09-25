package com.pitstop.ui.status

import android.content.Intent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.clickable
import androidx.compose.runtime.setValue
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pitstop.ui.components.ActiveDtcsPanel
import com.pitstop.ui.components.BridgeStatePill
import com.pitstop.ui.components.CostPerMileCard
import com.pitstop.ui.components.FuelHeroCards
import com.pitstop.ui.components.MonthlySpendCard
import com.pitstop.ui.components.MpgLifetimeCard
import com.pitstop.ui.components.MpgYearChart
import com.pitstop.ui.components.PitstopTopAppBar
import com.pitstop.ui.components.PillTone
import com.pitstop.ui.components.StatusPill
import com.pitstop.ui.components.TrendPage
import com.pitstop.ui.components.TrendsCarousel
import com.pitstop.ui.components.UploadStatusCard
import com.pitstop.ui.components.is24HourClock
import com.pitstop.util.DateLabel
import com.pitstop.ui.theme.LocalUnitSystem
import com.pitstop.ui.theme.ext
import com.pitstop.util.UnitFormat
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import com.pitstop.drive.UploadProgress
import kotlinx.coroutines.launch

/**
 * Home dashboard. Layout (top→bottom):
 *   1. TopAppBar: brand + bridge-state pill (replaces the old hero banner)
 *   2. Active DTCs panel (only when non-empty)
 *   3. Fuel hero cards 2×2
 *   4. Trends carousel — one swipeable card: MPG · last 12 months,
 *      lifetime MPG, cost per mile, monthly fuel spend
 *   5. Recent trips
 *   6. Update-available card (conditional)
 *   7. Footer (version / build)
 *
 * The bridge-state pill replaces the old `HeroStatusBanner` card —
 * the user kept asking "why is this huge headline on the screen telling
 * me what I can already see in the notification?" so we shrank it to a
 * top-bar dot+label.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(
    viewModel: StatusViewModel = hiltViewModel(),
    onOpenHistory: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenDtc: (code: String, vehicleId: String) -> Unit = { _, _ -> },
    onOpenTrip: (id: String) -> Unit = {},
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val uploadProgress by viewModel.uploadProgress.collectAsStateWithLifecycle()
    val pendingDrives by viewModel.pendingDriveCount.collectAsStateWithLifecycle()
    StatusContent(
        ui = ui,
        uploadProgress = uploadProgress,
        pendingDrives = pendingDrives,
        onRefresh = { viewModel.refreshHomeData().join() },
        onStart = { viewModel.startService() },
        onStop = { viewModel.stopService() },
        onSync = { viewModel.syncNow() },
        onCancelSync = { viewModel.cancelSync() },
        onOpenHistory = onOpenHistory,
        onOpenSettings = onOpenSettings,
        onOpenDtc = onOpenDtc,
        onOpenTrip = onOpenTrip,
    )
}

/** Stateless body of [StatusScreen], split out so screenshot tests can render it from fixtures. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StatusContent(
    ui: StatusUiState,
    uploadProgress: UploadProgress,
    pendingDrives: Int,
    onRefresh: suspend () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSync: () -> Unit,
    onCancelSync: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDtc: (code: String, vehicleId: String) -> Unit = { _, _ -> },
    onOpenTrip: (id: String) -> Unit = {},
) {
    val context = LocalContext.current
    val refreshing = remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            PitstopTopAppBar(
                title = "Home",
                actions = {
                    BridgeStatePill(
                        phase = ui.status.phase,
                        brokerConnected = ui.status.brokerConnected,
                        engineState = ui.status.engineState,
                        manualSyncOnly = ui.manualSyncOnly,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing.value,
            onRefresh = {
                refreshing.value = true
                coroutineScope.launch {
                    // Await actual completion so the spinner dismisses when
                    // data lands, not after a fixed delay.
                    onRefresh()
                    refreshing.value = false
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Active DTCs sit at the top — most urgent thing.
                // Empty list → render nothing per spec.
                ui.activeDtcs?.takeIf { it.isNotEmpty() }?.let { codes ->
                    ActiveDtcsPanel(
                        dtcs = codes,
                        onOpen = { dtc -> onOpenDtc(dtc.code, dtc.vehicleId) },
                    )
                }

                // Bridge control + live status. Primary place to start /
                // stop the bridge (moved here from Settings) plus the
                // OBD-freshness / active-metrics / offline-buffer detail.
                BridgeControlCard(
                    status = ui.status,
                    onStart = onStart,
                    onStop = onStop,
                )

                // Drive-upload state. Renders nothing when the queue is
                // empty and no pass has run recently, so it costs the
                // dashboard no space in the normal case.
                UploadStatusCard(
                    progress = uploadProgress,
                    pendingCount = pendingDrives,
                    onSync = onSync,
                    onCancel = onCancelSync,
                )


                // Fresh install (no server/vehicle) → a setup CTA instead of
                // shimmering forever. refreshHomeData() bails when unconfigured,
                // so the skeleton would never resolve. Once configured, cold
                // start shows the shimmer until the first hero payload lands.
                if (!ui.configured) {
                    SetupPromptCard(
                        hasServer = ui.hasServer,
                        hasVehicle = ui.hasVehicle,
                        autoStartOn = ui.autoStartOn,
                        needsPairing = ui.captureNeedsPairing,
                        onSetUp = onOpenSettings,
                    )
                } else if (ui.hero == null) {
                    HomeSkeleton()
                }

                // Fuel hero 2×2.
                ui.hero?.let { hero ->
                    FuelHeroCards(data = hero)
                }

                // Long-range trends — MPG by month, lifetime MPG, cost per
                // distance, monthly spend — as one swipeable card so Recent
                // trips sits a screen higher. Pages only render state the
                // ViewModel already loaded; a page with too little data is
                // left out rather than shown empty.
                val system = LocalUnitSystem.current
                val trendPages = buildList {
                    ui.mpgMonthly?.takeIf { it.size >= 2 }?.let { monthly ->
                        add(
                            TrendPage(if (system == "imperial") "MPG, last 12 months" else "L/100 km, last 12 months") {
                                MpgYearChart(points = monthly, framed = false)
                            },
                        )
                    }
                    ui.mpgYearly?.takeIf { it.size >= 2 }?.let { yearly ->
                        add(
                            TrendPage(if (system == "imperial") "Lifetime MPG" else "Lifetime L/100 km") {
                                MpgLifetimeCard(yearlyPoints = yearly, framed = false)
                            },
                        )
                    }
                    ui.costPerMile?.takeIf { it.isNotEmpty() }?.let { cost ->
                        add(
                            TrendPage(if (system == "imperial") "Cost per mile" else "Cost per km") {
                                CostPerMileCard(points = cost, framed = false)
                            },
                        )
                    }
                    ui.monthlySpend?.takeIf { it.size >= 2 }?.let { spend ->
                        add(TrendPage("Monthly fuel spend") { MonthlySpendCard(months = spend, framed = false) })
                    }
                }
                TrendsCarousel(pages = trendPages)

                // Recent trips card.
                ui.recentTrips?.takeIf { it.isNotEmpty() }?.let { trips ->
                    RecentTripsCard(trips = trips, onOpenAll = onOpenHistory, onOpenTrip = onOpenTrip)
                }

                // Update-available card (conditional).
                ui.update?.takeIf { it.isNewer }?.let { info ->
                    UpdateAvailableCard(
                        info = info,
                        // Play, never the GitHub release page — see
                        // PlayStore's KDoc for why this is a policy matter.
                        onOpen = { com.pitstop.update.PlayStore.open(context) },
                    )
                }

                ui.deepLinkUrl?.let { url ->
                    OutlinedButton(
                        onClick = {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, url.toUri())
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.OpenInBrowser, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Open Live in browser")
                    }
                }

                Spacer(Modifier.size(4.dp))
                Text(
                    "pitstop  v${com.pitstop.BuildConfig.VERSION_NAME}  ·  build ${com.pitstop.BuildConfig.VERSION_CODE}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.size(20.dp))
            }
        }
    }
}

/**
 * Bridge control + live status card. This is the primary place to
 * start / stop the bridge (moved off Settings) and the single surface
 * for the live status detail that used to live in Settings → Bridge
 * service: status pill + active collectors, active-metrics · last-frame,
 * OBD freshness, and offline-buffer-queued size.
 *
 * The top-bar [com.pitstop.ui.components.BridgeStatePill] is the at-a-glance
 * summary; this card is the expanded detail + controls. We deliberately do
 * NOT add a second pill here — the card leads with the device + collector
 * line so the two surfaces don't read as duplicates.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BridgeControlCard(
    status: com.pitstop.service.BridgeStatus,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val phase = status.phase
    val (statusText, pillTone) = when (phase) {
        com.pitstop.service.BridgePhase.Idle -> "Idle" to PillTone.Neutral
        com.pitstop.service.BridgePhase.Scanning -> "Scanning" to PillTone.Connecting
        com.pitstop.service.BridgePhase.Connecting -> "Connecting" to PillTone.Connecting
        com.pitstop.service.BridgePhase.Connected -> "Running" to PillTone.Healthy
        com.pitstop.service.BridgePhase.Disconnected -> "Reconnecting" to PillTone.Degraded
        com.pitstop.service.BridgePhase.Error -> "Error" to PillTone.Offline
    }
    // Healthy capture collapses to one line; anything off-nominal (or a tap)
    // shows the full detail and controls.
    val obdAge = status.lastObdFrameAtMs?.let {
        ((System.currentTimeMillis() - it) / 1000L).coerceAtLeast(0L)
    }
    val healthy = phase == com.pitstop.service.BridgePhase.Connected &&
        obdAge != null && obdAge < 10 && status.errorMessage == null &&
        status.offlineBufferBytes == 0L
    var expanded by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    if (healthy && !expanded) {
        Card(onClick = { expanded = true }) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusPill(tone = pillTone, label = "Capturing", compact = true, subject = "Bridge")
                Spacer(Modifier.size(8.dp))
                Text(
                    listOfNotNull("OBD ${obdAge}s", status.deviceName).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                androidx.compose.material3.FilledTonalIconButton(onClick = onStop) {
                    Icon(Icons.Filled.Stop, contentDescription = "Stop bridge")
                }
            }
        }
        return
    }
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill(tone = pillTone, label = statusText, subject = "Bridge")
                Spacer(Modifier.size(8.dp))
                Text(
                    text = activeCollectorsLabel(status),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            status.errorMessage?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            (status.deviceName ?: status.deviceMac)?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                "Active metrics: ${status.metricsActive} · Last frame: ${formatRelative(status.lastFrameAtMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // OBD freshness (BLE-3): dedicated OBD-frame clock, independent
            // of GPS / WiCAN traffic. Healthy <10s / Degraded <60s / Offline.
            run {
                val ageS = status.lastObdFrameAtMs?.let {
                    ((System.currentTimeMillis() - it) / 1000L).coerceAtLeast(0L)
                }
                val (obdText, obdColor) = when (ageS) {
                    null -> "OBD: no frames yet" to MaterialTheme.colorScheme.onSurfaceVariant
                    in 0..9 -> "OBD: healthy (${ageS}s)" to MaterialTheme.ext.good
                    in 10..59 -> "OBD: degraded (${ageS}s)" to MaterialTheme.colorScheme.onSurfaceVariant
                    else -> "OBD: offline (${ageS}s)" to MaterialTheme.colorScheme.error
                }
                Text(obdText, style = MaterialTheme.typography.bodySmall, color = obdColor)
            }
            if (status.offlineBufferBytes > 0) {
                Text(
                    "Offline buffer: ${humanBytes(status.offlineBufferBytes)} queued",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onStart,
                    enabled = phase == com.pitstop.service.BridgePhase.Idle ||
                        phase == com.pitstop.service.BridgePhase.Error,
                ) { Text("Start") }
                OutlinedButton(
                    onClick = onStop,
                    enabled = phase != com.pitstop.service.BridgePhase.Idle,
                ) { Text("Stop") }
            }
        }
    }
}

private fun activeCollectorsLabel(status: com.pitstop.service.BridgeStatus): String {
    // The bridge doesn't echo per-collector enable flags into BridgeStatus,
    // so describe by phase: a running bridge with OBD frames flowing reads
    // "OBD active"; otherwise fall back to the device-presence hint.
    return when (status.phase) {
        com.pitstop.service.BridgePhase.Connected ->
            if (status.lastObdFrameAtMs != null) "Capturing" else "Connected — waiting for frames"
        com.pitstop.service.BridgePhase.Scanning -> "Looking for the WiCAN"
        com.pitstop.service.BridgePhase.Connecting -> "Linking up"
        com.pitstop.service.BridgePhase.Disconnected -> "Link dropped — retrying"
        com.pitstop.service.BridgePhase.Error -> "Stopped"
        com.pitstop.service.BridgePhase.Idle -> "Not running"
    }
}

private fun formatRelative(tsMs: Long?): String {
    if (tsMs == null) return "never"
    val delta = kotlin.math.max(0L, System.currentTimeMillis() - tsMs)
    return when {
        delta < 1_500 -> "just now"
        delta < 60_000 -> "${delta / 1000}s ago"
        delta < 3_600_000 -> "${delta / 60_000}m ago"
        else -> "${delta / 3_600_000}h ago"
    }
}

private fun humanBytes(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
    else -> "%.2f MB".format(b / 1024.0 / 1024.0)
}

/**
 * Shown on Home when the app isn't configured yet (no server URL or vehicle
 * slug). Replaces the never-resolving shimmer with a named state, a two-item
 * checklist, and a button that jumps to Settings.
 */
@Composable
private fun SetupPromptCard(
    hasServer: Boolean,
    hasVehicle: Boolean,
    autoStartOn: Boolean,
    needsPairing: Boolean,
    onSetUp: () -> Unit,
) {
    // Two flavours share one card: the fresh-install "connect your server" state
    // and the subtler "everything's set but the WiCAN isn't paired, so drives
    // silently never auto-log" state. The headline adapts so the pairing case
    // doesn't tell an already-connected user to "connect to your server".
    val serverIncomplete = !hasServer || !hasVehicle
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                if (serverIncomplete) Icons.Outlined.CloudOff else Icons.Outlined.Bluetooth,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp),
            )
            Text(
                if (serverIncomplete) "Finish setup to see your data" else "One step from auto-logging",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                if (serverIncomplete) {
                    "Connect pitstop to your server to load fuel, MPG and trips."
                } else {
                    "Pair the WiCAN so drives start logging on their own — even when the app is closed."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SetupChecklistRow(done = hasServer, label = "Server", missing = "not set")
                SetupChecklistRow(done = hasVehicle, label = "Vehicle", missing = "not picked")
                // The auto-start row only appears when the user wants auto-start;
                // a deliberate manual-start user isn't shown a "not armed" nag.
                if (autoStartOn) {
                    SetupChecklistRow(
                        done = !needsPairing,
                        label = "Auto-start",
                        missing = "pair the WiCAN",
                    )
                }
            }
            Button(
                onClick = onSetUp,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            ) { Text(if (serverIncomplete) "Set up now" else "Pair WiCAN") }
        }
    }
}

@Composable
private fun SetupChecklistRow(done: Boolean, label: String, missing: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            if (done) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
            contentDescription = if (done) "Done" else "Missing",
            tint = if (done) MaterialTheme.ext.good else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp),
        )
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        if (!done) {
            Text(
                missing,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * Cold-start placeholder for the Home dashboard: a shimmering fuel-hero
 * 2×2 plus a chart-height block, so the screen reads as "loading" rather
 * than near-blank while the first payload fetches.
 */
@Composable
private fun HomeSkeleton() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ShimmerBlock(modifier = Modifier.weight(1f).height(96.dp))
            ShimmerBlock(modifier = Modifier.weight(1f).height(96.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ShimmerBlock(modifier = Modifier.weight(1f).height(120.dp))
            ShimmerBlock(modifier = Modifier.weight(1f).height(120.dp))
        }
        ShimmerBlock(modifier = Modifier.fillMaxWidth().height(180.dp))
    }
}

@Composable
private fun ShimmerBlock(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "shimmer-alpha",
    )
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)),
    )
}

@Composable
private fun UpdateAvailableCard(
    info: com.pitstop.update.UpdateInfo,
    onOpen: () -> Unit,
) {
    Card(
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Update available",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "v${info.currentVersion} → v${info.latestVersion}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onOpen) {
                Icon(Icons.Filled.OpenInBrowser, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Download v${info.latestVersion}")
            }
        }
    }
}

@Composable
private fun RecentTripsCard(
    trips: List<com.pitstop.http.TripDto>,
    onOpenAll: () -> Unit,
    onOpenTrip: (String) -> Unit,
) {
    val system = LocalUnitSystem.current
    val is24h = is24HourClock()
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Recent trips",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                )
                // A plain navigation link, not a primary action: neutral,
                // so the accent stays reserved for things that do something.
                androidx.compose.material3.TextButton(
                    onClick = onOpenAll,
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) { Text("See all") }
            }
            for (trip in trips) {
                // Each row opens THAT trip's detail (History → trip/{id}).
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(onClickLabel = "Open trip") { onOpenTrip(trip.id) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            // Ungrouped list: "Today 6:33 AM", "Tue 6:32 PM", "Sep 18, 6:32 PM".
                            text = DateLabel.list(trip.startedAt, withTime = true, grouped = false, is24h = is24h),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = formatTripSubtitle(trip, system),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = UnitFormat.distanceKm(trip.distanceKm, system),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Economy first, like the History list: "24.8 mpg · 22m · max 68 mph". */
private fun formatTripSubtitle(trip: com.pitstop.http.TripDto, system: String): String {
    val parts = mutableListOf<String>()
    com.pitstop.ui.history.tripMpg(trip)?.let { parts += UnitFormat.economy(it, system) }
    trip.durationS?.let {
        parts += when {
            it >= 3600 -> "${it / 3600}h ${(it % 3600) / 60}m"
            it >= 60 -> "${it / 60}m"
            else -> "${it}s"
        }
    }
    trip.maxSpeedKph?.let { parts += "max ${UnitFormat.Quantity.SpeedKph.format(it, system, 0)}" }
    if (trip.dtcCount > 0) parts += "${trip.dtcCount} DTC"
    return parts.joinToString(" · ").ifEmpty { "—" }
}
