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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInBrowser
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
import kotlinx.coroutines.launch

/**
 * Home dashboard. Layout (top→bottom):
 *   1. TopAppBar: brand + bridge-state pill (replaces the old hero banner)
 *   2. Active DTCs panel (only when non-empty)
 *   3. Fuel hero cards 2×2
 *   4. MPG · last 12 months (line chart with min/max + tap-to-inspect)
 *   5. MPG lifetime (yearly bars + trend chip)
 *   6. Cost per mile
 *   7. Monthly fuel spend
 *   8. Recent trips
 *   9. Update-available card (conditional)
 *  10. Footer (version / build)
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
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
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
                    viewModel.refreshHomeData().join()
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
                        onOpen = { onOpenHistory() },
                    )
                }

                // Cold start shows shimmer skeletons until the first hero
                // payload lands, instead of a near-blank screen (every card
                // is null-gated). Once hero != null the real cards render.
                if (ui.hero == null) {
                    HomeSkeleton()
                }

                // Fuel hero 2×2.
                ui.hero?.let { hero ->
                    FuelHeroCards(data = hero)
                }

                // MPG last 12 months — clarified line chart with axes
                // + tooltip. Skipped when there's not enough data.
                ui.mpgMonthly?.takeIf { it.size >= 2 }?.let { monthly ->
                    MpgYearChart(points = monthly)
                }

                // Lifetime MPG — yearly bars + trend chip vs prior years.
                ui.mpgYearly?.takeIf { it.size >= 2 }?.let { yearly ->
                    MpgLifetimeCard(yearlyPoints = yearly)
                }

                // Cost per mile (lifetime number + last-12-mo bar chart).
                ui.costPerMile?.takeIf { it.isNotEmpty() }?.let { cost ->
                    CostPerMileCard(points = cost)
                }

                // Monthly fuel spend (last 12 months, current month
                // highlighted).
                ui.monthlySpend?.takeIf { it.size >= 2 }?.let { spend ->
                    MonthlySpendCard(months = spend)
                }

                // Recent trips card.
                ui.recentTrips?.takeIf { it.isNotEmpty() }?.let { trips ->
                    RecentTripsCard(trips = trips)
                }

                // Update-available card (conditional).
                ui.update?.takeIf { it.isNewer }?.let { info ->
                    UpdateAvailableCard(
                        info = info,
                        onOpen = {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, info.releaseUrl.toUri())
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        },
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
private fun RecentTripsCard(trips: List<com.pitstop.http.TripDto>) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Recent trips",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            for (trip in trips) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = formatTripDate(trip.startedAt),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = formatTripSubtitle(trip),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val mi = trip.distanceKm?.let { it * 0.621371 }
                    Text(
                        text = mi?.let { "%.1f mi".format(it) } ?: "—",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

// Backend serves UTC; convert to local zone before formatting (otherwise
// a trip at 20:32Z renders as "8:32PM" instead of "3:32PM" in CDT).
private fun formatTripDate(iso: String): String {
    return try {
        java.time.OffsetDateTime.parse(iso)
            .atZoneSameInstant(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("MMM d, h:mma"))
    } catch (_: Throwable) {
        iso.take(16)
    }
}

private fun formatTripSubtitle(trip: com.pitstop.http.TripDto): String {
    val parts = mutableListOf<String>()
    trip.durationS?.let {
        parts += if (it >= 60) "${it / 60}m ${it % 60}s" else "${it}s"
    }
    trip.maxSpeedKph?.let { parts += "max %.0f mph".format(it * 0.621371) }
    trip.maxRpm?.let { parts += "%.0f rpm".format(it) }
    if (trip.dtcCount > 0) parts += "${trip.dtcCount} DTC"
    return parts.joinToString(" · ").ifEmpty { "—" }
}
