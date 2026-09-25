package com.pitstop.ui.status

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pitstop.domain.DriveSummary
import com.pitstop.domain.DtcGuide
import com.pitstop.domain.DueState
import com.pitstop.domain.Maintenance
import com.pitstop.domain.Parked
import com.pitstop.domain.ParkedSpot
import com.pitstop.domain.RangeEstimate
import com.pitstop.domain.ReminderItem
import com.pitstop.drive.UploadProgress
import com.pitstop.http.DtcDto
import com.pitstop.ui.components.CostPerMileCard
import com.pitstop.ui.components.HeroCardData
import com.pitstop.ui.components.MonthlySpendCard
import com.pitstop.ui.components.MpgLifetimeCard
import com.pitstop.ui.components.MpgYearChart
import com.pitstop.ui.components.PitstopTopAppBar
import com.pitstop.ui.components.RangeFormat
import com.pitstop.ui.components.SeverityChip
import com.pitstop.ui.components.TrendPage
import com.pitstop.ui.components.TrendsCarousel
import com.pitstop.ui.components.UploadStatusCard
import com.pitstop.ui.components.is24HourClock
import com.pitstop.ui.history.TagChip
import com.pitstop.ui.theme.LocalUnitSystem
import com.pitstop.ui.theme.ext
import com.pitstop.util.DateLabel
import com.pitstop.util.UnitFormat
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Home — the car at a glance. Top to bottom:
 *   1. Finish-setup / pair CTA (only while setup is incomplete)
 *   2. Range hero: miles to empty, fuel bar, "12.2 gal · 62 %", the mpg basis
 *   3. Attention strip: active codes (with severity) and service due — only
 *      when something needs attention
 *   4. Last drive (route sketch, economy vs usual) and Parked (navigate back)
 *   5. A compact KPI row (average economy, gas price, this month)
 *   6. The Trends carousel, then Recent trips
 *
 * The bridge card moved behind the top-bar logging chip.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusScreen(
    viewModel: StatusViewModel = hiltViewModel(),
    onOpenHistory: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenDtc: (code: String, vehicleId: String) -> Unit = { _, _ -> },
    onOpenTrip: (id: String) -> Unit = {},
    onOpenService: () -> Unit = {},
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val uploadProgress by viewModel.uploadProgress.collectAsStateWithLifecycle()
    val pendingDrives by viewModel.pendingDriveCount.collectAsStateWithLifecycle()
    val context = LocalContext.current
    StatusContent(
        ui = ui,
        uploadProgress = uploadProgress,
        pendingDrives = pendingDrives,
        onRefresh = { viewModel.refreshHomeData().join() },
        onSync = { viewModel.syncNow() },
        onCancelSync = { viewModel.cancelSync() },
        onOpenHistory = onOpenHistory,
        onOpenSettings = onOpenSettings,
        onOpenDtc = onOpenDtc,
        onOpenTrip = onOpenTrip,
        onOpenService = onOpenService,
        onNavigate = { spot -> navigateTo(context, spot) },
    )
}

/** Hand the parking spot to a maps app; fall back to a browser map. */
internal fun navigateTo(context: Context, spot: ParkedSpot) {
    val geo = Intent(Intent.ACTION_VIEW, Parked.geoUri(spot).toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(geo)
    } catch (_: ActivityNotFoundException) {
        runCatching {
            context.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    "https://www.openstreetmap.org/?mlat=${spot.lat}&mlon=${spot.lon}#map=18/${spot.lat}/${spot.lon}".toUri(),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

/** Stateless body of [StatusScreen], split out so screenshot tests can render it from fixtures. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StatusContent(
    ui: StatusUiState,
    uploadProgress: UploadProgress,
    pendingDrives: Int,
    onRefresh: suspend () -> Unit,
    onSync: () -> Unit,
    onCancelSync: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDtc: (code: String, vehicleId: String) -> Unit = { _, _ -> },
    onOpenTrip: (id: String) -> Unit = {},
    onOpenService: () -> Unit = {},
    onNavigate: (ParkedSpot) -> Unit = {},
    nowMs: Long = System.currentTimeMillis(),
) {
    val context = LocalContext.current
    val refreshing = remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val system = LocalUnitSystem.current

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = { PitstopTopAppBar() },
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
                // Fresh install (no server/vehicle) or auto-start without a
                // paired WiCAN → the setup CTA stays on Home.
                if (!ui.configured) {
                    SetupPromptCard(
                        hasServer = ui.hasServer,
                        hasVehicle = ui.hasVehicle,
                        autoStartOn = ui.autoStartOn,
                        needsPairing = ui.captureNeedsPairing,
                        onSetUp = onOpenSettings,
                    )
                }
                if (ui.hasServer && ui.hasVehicle && ui.hero == null && ui.range == null) {
                    HomeSkeleton()
                }

                ui.range?.let { RangeHeroCard(it, nowMs = nowMs, stale = ui.hero?.fuelLevelStale == true) }

                AttentionStrip(
                    dtcs = ui.activeDtcs.orEmpty(),
                    reminders = ui.reminders.orEmpty(),
                    distInMiles = ui.distInMiles,
                    onOpenDtc = onOpenDtc,
                    onOpenService = onOpenService,
                )

                // Drive-upload state. Renders nothing when the queue is
                // empty and no pass has run recently.
                UploadStatusCard(
                    progress = uploadProgress,
                    pendingCount = pendingDrives,
                    onSync = onSync,
                    onCancel = onCancelSync,
                )

                ui.lastDrive?.let { LastDriveCard(it, onOpen = { onOpenTrip(it.trip.id) }) }
                ui.parked?.let { ParkedCard(it, nowMs = nowMs, onNavigate = { onNavigate(it) }) }

                ui.hero?.let { HomeKpiRow(it) }

                // Long-range trends as one swipeable card. Pages only render
                // state the ViewModel already loaded.
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

                // Recent trips — the last drive already has its own card.
                val lastId = ui.lastDrive?.trip?.id
                ui.recentTrips?.filter { it.id != lastId }?.takeIf { it.isNotEmpty() }?.let { trips ->
                    RecentTripsCard(trips = trips.take(5), onOpenAll = onOpenHistory, onOpenTrip = onOpenTrip)
                }

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
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.size(20.dp))
            }
        }
    }
}

// ── Range hero ─────────────────────────────────────────────────────────

/**
 * Range to empty, big. Without an mpg basis (a new vehicle) it shows the
 * fuel left instead of inventing a range.
 */
@Composable
private fun RangeHeroCard(range: RangeEstimate, nowMs: Long, stale: Boolean) {
    val system = LocalUnitSystem.current
    val pct = range.fuel.pct
    val barColor = when {
        pct == null -> MaterialTheme.colorScheme.outline
        pct < 15 -> MaterialTheme.ext.bad
        pct < 30 || range.low -> MaterialTheme.ext.warn
        else -> MaterialTheme.ext.good
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp)
                .semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                if (range.rangeMi != null) "Range to empty" else "Fuel left",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (range.rangeMi != null) {
                    RangeFormat.range(range.rangeMi, system)
                } else {
                    range.fuel.usGallons?.let { UnitFormat.volumeGal(it, system, 1) } ?: "—"
                },
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (range.low) MaterialTheme.ext.warn else MaterialTheme.colorScheme.onSurface,
            )
            // Fuel bar.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .semantics { contentDescription = "Fuel ${pct?.roundToInt() ?: "unknown"} percent" },
            ) {
                if (pct != null) {
                    Box(
                        Modifier
                            .fillMaxWidth((pct / 100.0).toFloat().coerceIn(0.02f, 1f))
                            .height(10.dp)
                            .background(barColor),
                    )
                }
            }
            Text(RangeFormat.fuelLine(range, system), style = MaterialTheme.typography.titleMedium)
            val basis = RangeFormat.basisLine(range.basis, system)
            Text(
                basis ?: "Range appears after a few drives with fuel data or a couple of fillups",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val age = RangeFormat.readingAge(range.fuel.readingAtMs, nowMs)
            if (age != null || stale) {
                Text(
                    listOfNotNull(age, if (stale) "estimate may be stale" else null).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.ext.warn,
                )
            }
        }
    }
}

// ── Attention strip ────────────────────────────────────────────────────

/**
 * Only when something needs attention: active trouble codes with their
 * severity, and service that's due soon or overdue. Nothing → nothing.
 */
@Composable
private fun AttentionStrip(
    dtcs: List<DtcDto>,
    reminders: List<ReminderItem>,
    distInMiles: Boolean,
    onOpenDtc: (String, String) -> Unit,
    onOpenService: () -> Unit,
) {
    val due = reminders
        .map { it to Maintenance.dueState(it, distInMiles) }
        .filter { it.second != DueState.Ok }
        .sortedBy { if (it.second == DueState.Overdue) 0 else 1 }
    if (dtcs.isEmpty() && due.isEmpty()) return
    val system = LocalUnitSystem.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text(
                "Needs attention",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .semantics { heading() },
            )
            var first = true
            for (dtc in dtcs) {
                if (!first) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                first = false
                val g = DtcGuide.lookup(dtc.code, dtc.description)
                AttentionRow(
                    onClick = { onOpenDtc(dtc.code, dtc.vehicleId) },
                    clickLabel = "Open ${dtc.code}",
                    icon = { Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = com.pitstop.ui.components.severityColors(g.severity).first) },
                    title = "${dtc.code} · ${g.title}",
                    chip = { SeverityChip(g.severity) },
                )
            }
            for ((r, state) in due) {
                if (!first) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                first = false
                val (fg, bg) = if (state == DueState.Overdue) MaterialTheme.ext.bad to MaterialTheme.ext.badContainer
                else MaterialTheme.ext.warn to MaterialTheme.ext.warnContainer
                val left = r.distanceRemaining?.let { d ->
                    val s = com.pitstop.ui.vehicle.vehicleDistance(abs(d), distInMiles, system)
                    if (d < 0) "$s over" else "$s left"
                } ?: r.daysRemaining?.let { d -> if (d < 0) "${-d} d over" else "$d d left" }
                AttentionRow(
                    onClick = onOpenService,
                    clickLabel = "Open service",
                    icon = { Icon(Icons.Outlined.Build, contentDescription = null, tint = fg) },
                    title = listOfNotNull(r.title, left).joinToString(" · "),
                    chip = { TagChip(if (state == DueState.Overdue) "Overdue" else "Due soon", fg, bg) },
                )
            }
        }
    }
}

@Composable
private fun AttentionRow(
    onClick: () -> Unit,
    clickLabel: String,
    icon: @Composable () -> Unit,
    title: String,
    chip: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClickLabel = clickLabel, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        icon()
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            modifier = Modifier.weight(1f),
        )
        chip()
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── Last drive + parked ────────────────────────────────────────────────

@Composable
private fun LastDriveCard(d: LastDrive, onOpen: () -> Unit) {
    val system = LocalUnitSystem.current
    val is24h = is24HourClock()
    val trip = d.trip
    val mpg = UnitFormat.mpgFrom(trip.distanceKm, trip.fuelUsedL)
    val mpgDelta = DriveSummary.mpgDeltaPct(trip, d.baseline)
    val durDelta = DriveSummary.durationDeltaPct(trip, d.baseline)
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RouteSketch(d.route, Modifier.size(76.dp))
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "Last drive · " + DateLabel.list(trip.startedAt, withTime = true, grouped = false, is24h = is24h),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        listOfNotNull(
                            UnitFormat.distanceKm(trip.distanceKm, system),
                            mpg?.let { UnitFormat.economy(it, system) },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    if (mpgDelta != null && abs(mpgDelta) >= 1) {
                        Spacer(Modifier.size(6.dp))
                        Text(
                            if (mpgDelta > 0) "▲" else "▼",
                            color = if (mpgDelta > 0) MaterialTheme.ext.good else MaterialTheme.ext.bad,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.semantics {
                                contentDescription = "${DriveSummary.signedPct(mpgDelta)} economy vs usual"
                            },
                        )
                    }
                }
                Text(
                    listOfNotNull(
                        trip.durationS?.let { DriveSummary.durationLabel(it) },
                        durDelta?.let { "${DriveSummary.signedPct(it)} vs usual" },
                    ).joinToString(" · ").ifEmpty { " " },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The route's shape, drawn from its own lat/lon — no map tiles, so it costs
 * nothing, works offline and renders in screenshots (MapLibre can't). A car
 * glyph stands in when the trip has no GPS.
 */
@Composable
private fun RouteSketch(route: List<Pair<Double, Double>>, modifier: Modifier = Modifier) {
    val bg = MaterialTheme.colorScheme.surfaceContainerHigh
    val line = MaterialTheme.colorScheme.primary
    val end = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        if (route.size < 2) {
            Icon(Icons.Outlined.DirectionsCar, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Box
        }
        Canvas(Modifier.fillMaxSize().padding(10.dp)) {
            val lats = route.map { it.first }
            val lons = route.map { it.second }
            val midLat = Math.toRadians((lats.min() + lats.max()) / 2)
            // Equirectangular: longitude shrinks with cos(lat).
            val xs = lons.map { it * kotlin.math.cos(midLat) }
            val minX = xs.min(); val maxX = xs.max()
            val minY = lats.min(); val maxY = lats.max()
            val span = maxOf(maxX - minX, maxY - minY).takeIf { it > 0 } ?: 1e-6
            val scale = minOf(size.width, size.height) / span
            val ox = (size.width - (maxX - minX) * scale) / 2
            val oy = (size.height - (maxY - minY) * scale) / 2
            fun pt(i: Int) = Offset(
                (ox + (xs[i] - minX) * scale).toFloat(),
                (oy + (maxY - lats[i]) * scale).toFloat(),
            )
            val path = Path().apply {
                moveTo(pt(0).x, pt(0).y)
                for (i in 1 until route.size) lineTo(pt(i).x, pt(i).y)
            }
            drawPath(path, line, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawCircle(end, radius = 3.5.dp.toPx(), center = pt(route.size - 1))
        }
    }
}

@Composable
private fun ParkedCard(spot: ParkedSpot, nowMs: Long, onNavigate: () -> Unit) {
    val is24h = is24HourClock()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Place, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Parked", style = MaterialTheme.typography.titleSmall)
                Text(
                    parkedSince(spot.sinceMs, nowMs, is24h),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalButton(onClick = onNavigate) {
                Icon(Icons.Outlined.Navigation, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Navigate")
            }
        }
    }
}

/** "since 6:13 PM · 2 h ago" / "since Tue 6:13 PM · 3 d ago". */
internal fun parkedSince(sinceMs: Long?, nowMs: Long, is24h: Boolean): String {
    if (sinceMs == null) return "Last trip's end point"
    val zone = java.time.ZoneId.systemDefault()
    val at = java.time.Instant.ofEpochMilli(sinceMs).atZone(zone)
    val when_ = DateLabel.list(at.toOffsetDateTime().toString(), withTime = true, grouped = false, is24h = is24h)
        .removePrefix("Today ")
    val mins = ((nowMs - sinceMs) / 60_000L).coerceAtLeast(0)
    val ago = when {
        mins < 1 -> "just now"
        mins < 60 -> "$mins min ago"
        mins < 48 * 60 -> "${mins / 60} h ago"
        else -> "${mins / (24 * 60)} d ago"
    }
    return "since $when_ · $ago"
}

// ── KPI row ────────────────────────────────────────────────────────────

/** What's left of the old 2×2: average economy, gas price, this month. */
@Composable
private fun HomeKpiRow(hero: HeroCardData) {
    val system = LocalUnitSystem.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Kpi(
            title = "Average",
            value = UnitFormat.economyNumber(hero.avgConsumptionMpg, system),
            unit = UnitFormat.economyUnit(system),
            sub = "90 days",
            modifier = Modifier.weight(1f),
        )
        val delta = hero.ppgDeltaPct
        Kpi(
            title = "Gas price",
            value = UnitFormat.money(UnitFormat.pricePerVolumeValue(hero.latestPpg, system), 3),
            unit = UnitFormat.perVolumeUnit(system),
            sub = delta?.let { d ->
                val arrow = when { d > 0.5 -> "▲"; d < -0.5 -> "▼"; else -> "·" }
                "$arrow ${"%.1f".format(abs(d))}% vs avg"
            } ?: "—",
            subColor = delta?.let { d ->
                when { d > 0.5 -> MaterialTheme.ext.bad; d < -0.5 -> MaterialTheme.ext.good; else -> null }
            },
            modifier = Modifier.weight(1f),
        )
        Kpi(
            title = "This month",
            value = UnitFormat.money(hero.monthCost, 0),
            unit = "",
            sub = "${hero.monthCount} fill${if (hero.monthCount == 1) "" else "s"}",
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Kpi(
    title: String,
    value: String,
    unit: String,
    sub: String,
    modifier: Modifier = Modifier,
    subColor: Color? = null,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .semantics(mergeDescendants = true) {},
        ) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
                if (unit.isNotEmpty()) {
                    Spacer(Modifier.size(2.dp))
                    Text(
                        unit,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                }
            }
            Text(sub, style = MaterialTheme.typography.labelSmall, color = subColor ?: MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
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
