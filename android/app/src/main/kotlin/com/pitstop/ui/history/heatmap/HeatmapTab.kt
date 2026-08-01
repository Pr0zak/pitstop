package com.pitstop.ui.history.heatmap

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun HeatmapTab(viewModel: HeatmapViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val showStations by viewModel.showStations.collectAsStateWithLifecycle()

    // Pull-to-refresh wraps the whole column — drag down from the
    // mode-toggle row to force a fresh /api/analytics/route-trace
    // fetch. Map gestures inside the map widget still work normally
    // because the pull gesture only fires from above-the-top scroll.
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = state.loading,
        onRefresh = { viewModel.refresh() },
        modifier = Modifier.fillMaxSize(),
    ) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Controls + count
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = mode == HeatmapMode.Density,
                onClick = { viewModel.setMode(HeatmapMode.Density) },
                label = { Text("Density") },
            )
            FilterChip(
                selected = mode == HeatmapMode.Speed,
                onClick = { viewModel.setMode(HeatmapMode.Speed) },
                label = { Text("Speed") },
            )
            FilterChip(
                selected = mode == HeatmapMode.Single,
                onClick = { viewModel.setMode(HeatmapMode.Single) },
                label = { Text("Single") },
            )
            // Stations toggle — overlays a fuel-pump dot at every
            // historical fillup that carries GPS. Independent of the
            // density/speed mode (which colors the route polylines).
            FilterChip(
                selected = showStations,
                onClick = { viewModel.toggleStations() },
                label = {
                    Text(
                        if (state.stations.isEmpty()) "Stations"
                        else "Stations (${state.stations.size})"
                    )
                },
                enabled = state.stations.isNotEmpty(),
            )
            Box(modifier = Modifier.width(8.dp))
            if (state.points.isNotEmpty()) {
                Text(
                    text = "${state.total} pts" +
                        if (state.stride > 1) " (every ${state.stride})" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
            }
        }

        // Map / state
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            when {
                state.error != null -> CenteredText("Couldn't load: ${state.error}")
                state.points.isEmpty() && state.loading -> CenteredSpinner()
                state.points.isEmpty() -> CenteredText("No GPS data yet — take a drive.")
                else -> MapLibreHeatmapView(
                    points = state.points,
                    mode = mode,
                    stations = if (showStations) state.stations else emptyList(),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // Legend
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (mode == HeatmapMode.Single) {
                Swatch(Color(0xFFF97316))
                Text(
                    "all trips, one colour",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (mode == HeatmapMode.Density) {
                Text("1×", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Swatch(Color(0xFF475569))
                Swatch(Color(0xFF06B6D4))
                Text("3", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Swatch(Color(0xFF22C55E))
                Text("8", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Swatch(Color(0xFFEAB308))
                Text("20", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Swatch(Color(0xFFF97316))
                Text("50", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Swatch(Color(0xFFEF4444))
                Text("50+ visits", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(
                    "slow",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 7 HSL stops 0..300° at sat=80% light=50% to mirror the line colour
                Swatch(Color(0xFFE53935))   // 0°
                Swatch(Color(0xFFE5A03A))   // 60°
                Swatch(Color(0xFF99CC2E))   // 100°
                Swatch(Color(0xFF22C55E))   // 130°
                Swatch(Color(0xFF2AB7C5))   // 180°
                Swatch(Color(0xFF3D7CFF))   // 220°
                Swatch(Color(0xFFB047D6))   // 280°
                Text(
                    "fast (~80 mph)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(modifier = Modifier.width(8.dp))
            AssistChip(
                onClick = { viewModel.refresh() },
                label = { Text("Refresh") },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        }
    }
    } // PullToRefreshBox
}

@Composable
private fun Swatch(color: Color) {
    Box(
        modifier = Modifier
            .size(14.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(color),
    )
}

@Composable
private fun CenteredSpinner() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(strokeWidth = 2.dp)
    }
}

@Composable
private fun CenteredText(msg: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(msg, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Suppress("unused")
private fun unusedKeepImport(p: PaddingValues): PaddingValues = p
