package com.pitstop.ui.fuel

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pitstop.domain.FuelGrade
import com.pitstop.domain.FuelPrices
import com.pitstop.domain.NearbyStatus
import com.pitstop.http.NearbyStationDto
import com.pitstop.ui.components.is24HourClock
import com.pitstop.ui.theme.LocalUnitSystem
import com.pitstop.util.DateLabel
import com.pitstop.util.UnitFormat
import java.time.Instant
import java.time.ZoneId

/**
 * "Nearby prices" on the Fuel tab (ADR-026): live Google Places prices as a
 * list — deliberately not map pins, Google's terms restrict Places content
 * on a non-Google map. A row opens the station in Google Maps.
 *
 * Stateless; [NearbyPricesViewModel] owns the state. [nowMs] is a parameter
 * so screenshot tests render fixed ages.
 */
@Composable
internal fun NearbyPricesCard(
    state: NearbyPricesUi,
    modifier: Modifier = Modifier,
    nowMs: Long = System.currentTimeMillis(),
    onGrade: (FuelGrade) -> Unit = {},
    onRefresh: () -> Unit = {},
    onToggleExpanded: () -> Unit = {},
    onOpenStation: (String) -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    val system = LocalUnitSystem.current
    val result = state.result
    val status = result?.let { NearbyStatus.fromWire(it.status) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Nearby prices",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() },
                    )
                    val asOfAgo = DateLabel.ago(result?.origin?.asOf, nowMs)
                    FuelPrices.originLine(result?.origin?.source, asOfAgo)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                }
                Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    if (state.loading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh prices")
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .padding(end = 12.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (g in FuelGrade.entries) {
                    FilterChip(
                        selected = g == state.grade,
                        onClick = { onGrade(g) },
                        label = { Text(g.label) },
                    )
                }
            }

            Column(modifier = Modifier.padding(end = 12.dp)) {
                when {
                    result == null && state.error != null -> {
                        StateLine(state.error)
                        TextButton(onClick = onRefresh, modifier = Modifier.padding(top = 2.dp)) { Text("Retry") }
                    }
                    result == null -> StateLine(if (state.loading) "Looking up prices…" else "Not loaded yet")
                    else -> {
                        val radiusLabel = UnitFormat.distanceKm(
                            result.radiusM / 1000.0,
                            system,
                            if (system == "imperial") 1 else 0,
                        )
                        FuelPrices.statusMessage(
                            status = status,
                            detail = result.detail,
                            stationCount = result.stations.size,
                            radiusLabel = radiusLabel,
                            hasLocationPermission = state.hasLocationPermission,
                        )?.let { StateLine(it, isError = status == NearbyStatus.UpstreamError) }
                        if (status == NearbyStatus.NoKey) {
                            FilledTonalButton(onClick = onOpenSettings, modifier = Modifier.padding(top = 8.dp)) {
                                Text("Open Settings")
                            }
                        }
                        StationList(
                            stations = result.stations,
                            expanded = state.expanded,
                            nowMs = nowMs,
                            onToggleExpanded = onToggleExpanded,
                            onOpenStation = onOpenStation,
                        )
                        if (state.error != null) {
                            Text(
                                "Couldn't refresh — ${state.error.replaceFirstChar { it.lowercase() }}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                        Text(
                            FuelPrices.footer(
                                monthCalls = result.usage?.monthCalls,
                                monthlyCap = result.usage?.monthlyCap,
                                cached = result.cached,
                                fetchedAgo = DateLabel.ago(result.fetchedAt, nowMs),
                                fetchedAgeMs = DateLabel.epochMs(result.fetchedAt)?.let { nowMs - it },
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = muted,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StateLine(text: String, isError: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 10.dp),
    )
}

@Composable
private fun StationList(
    stations: List<NearbyStationDto>,
    expanded: Boolean,
    nowMs: Long,
    onToggleExpanded: () -> Unit,
    onOpenStation: (String) -> Unit,
) {
    if (stations.isEmpty()) return
    // Server order is the contract: priced cheapest-first, then unpriced
    // nearest-first. Never re-sort here.
    val shown = if (expanded) stations else stations.take(FuelPrices.COLLAPSED_ROWS)
    Column(modifier = Modifier.padding(top = 6.dp)) {
        shown.forEachIndexed { i, st ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            StationRow(st, nowMs, onOpenStation)
        }
        val hidden = stations.size - FuelPrices.COLLAPSED_ROWS
        if (hidden > 0) {
            TextButton(onClick = onToggleExpanded) {
                Text(if (expanded) "Show fewer" else "Show all ${stations.size}")
            }
        }
    }
}

@Composable
private fun StationRow(st: NearbyStationDto, nowMs: Long, onOpenStation: (String) -> Unit) {
    val system = LocalUnitSystem.current
    val is24h = is24HourClock()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val url = st.mapsUrl
    val rowModifier = if (url != null) {
        Modifier.clickable(onClickLabel = "Open in Google Maps") { onOpenStation(url) }
    } else {
        Modifier
    }
    Row(
        modifier = rowModifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                st.name ?: shortAddress(st.address) ?: "Unnamed station",
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    UnitFormat.distanceKm(st.distanceM / 1000.0, system, 1),
                    shortAddress(st.address)?.takeIf { st.name != null },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val paidDate = st.myLastDate?.let {
                DateLabel.list(
                    it,
                    withTime = false,
                    grouped = false,
                    is24h = is24h,
                    now = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()),
                )
            }
            FuelPrices.youPaid(st.myLastPrice, paidDate)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = muted)
            }
        }
        Spacer(Modifier.size(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                FuelPrices.price(st.price, st.currency),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (st.price == null) muted else MaterialTheme.colorScheme.onSurface,
            )
            DateLabel.ago(st.priceUpdatedAt, nowMs)?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = muted)
            }
        }
    }
}

/** "123 Main St" out of "123 Main St, Springfield, IL 62701, USA". */
internal fun shortAddress(address: String?): String? =
    address?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() }
