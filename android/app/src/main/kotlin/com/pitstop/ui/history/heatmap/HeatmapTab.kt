package com.pitstop.ui.history.heatmap

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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

/** Translucent backing for the floating controls so chips stay legible
 *  over both bright route lines and the near-black basemap. */
private const val SCRIM_ALPHA = 0.82f

@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun HeatmapTab(viewModel: HeatmapViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val showStations by viewModel.showStations.collectAsStateWithLifecycle()

    // Pull-to-refresh wraps the whole tab. Note the map widget consumes
    // drags, so the pull only fires from the non-map edges — the
    // Refresh chip in the floating controls is the reliable path.
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = state.loading,
        onRefresh = { viewModel.refresh() },
        modifier = Modifier.fillMaxSize(),
    ) {
        // The map owns the ENTIRE tab area, edge to edge. Controls and
        // legend float on top rather than each claiming a ~56 dp row —
        // the old Column layout left the map only the leftover space
        // after the chips row + legend row (on top of the app bar, sync
        // banner, tab row and bottom nav), which is why it read as a
        // small strip in the middle of the screen.
        Box(modifier = Modifier.fillMaxSize()) {
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

            // Floating controls, top-start. Horizontally scrollable —
            // the fixed Row used to clip "N pts" off-screen once the
            // Stations chip carried a count.
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = SCRIM_ALPHA),
                tonalElevation = 3.dp,
            ) {
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
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
                    // historical fillup that carries GPS. Independent of
                    // the mode (which colors the route polylines).
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
                    AssistChip(
                        onClick = { viewModel.refresh() },
                        label = { Text("Refresh") },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                    )
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
            }

            // Legend floats bottom-END on purpose: MapLibre parks its
            // attribution "i" bottom-start, and covering that would hide
            // the required OSM / CARTO credit.
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = SCRIM_ALPHA),
                tonalElevation = 3.dp,
            ) {
                Row(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    when (mode) {
                        HeatmapMode.Single -> {
                            Swatch(Color(0xFFF97316))
                            LegendLabel("all trips, one colour")
                        }
                        HeatmapMode.Density -> {
                            LegendLabel("1×")
                            Swatch(Color(0xFF475569))
                            Swatch(Color(0xFF06B6D4))
                            LegendLabel("3")
                            Swatch(Color(0xFF22C55E))
                            LegendLabel("8")
                            Swatch(Color(0xFFEAB308))
                            LegendLabel("20")
                            Swatch(Color(0xFFF97316))
                            LegendLabel("50")
                            Swatch(Color(0xFFEF4444))
                            LegendLabel("50+ visits")
                        }
                        HeatmapMode.Speed -> {
                            LegendLabel("slow")
                            // 7 HSL stops 0..300° at sat=80% light=50% to
                            // mirror the line colour ramp
                            Swatch(Color(0xFFE53935))   // 0°
                            Swatch(Color(0xFFE5A03A))   // 60°
                            Swatch(Color(0xFF99CC2E))   // 100°
                            Swatch(Color(0xFF22C55E))   // 130°
                            Swatch(Color(0xFF2AB7C5))   // 180°
                            Swatch(Color(0xFF3D7CFF))   // 220°
                            Swatch(Color(0xFFB047D6))   // 280°
                            LegendLabel("fast (~80 mph)")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LegendLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
