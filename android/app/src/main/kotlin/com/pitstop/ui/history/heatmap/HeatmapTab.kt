package com.pitstop.ui.history.heatmap

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SmallFloatingActionButton
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
import com.pitstop.ui.components.EmptyState
import com.pitstop.ui.components.LoadErrorState
import com.pitstop.ui.theme.ChartPalette
import com.pitstop.ui.theme.LocalUnitSystem
import com.pitstop.util.UnitFormat

/** Translucent backing for the floating controls so they stay legible
 *  over both bright route lines and the near-black basemap. */
private const val SCRIM_ALPHA = 0.82f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeatmapTab(viewModel: HeatmapViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val showStations by viewModel.showStations.collectAsStateWithLifecycle()
    val system = LocalUnitSystem.current

    // No pull-to-refresh: the map consumes every drag, so the pull could
    // only ever fire from a sliver of edge. Refresh is the FAB instead.
    //
    // The map owns the ENTIRE tab area, edge to edge; controls and legend
    // float on top rather than each claiming a row.
    Box(modifier = Modifier.fillMaxSize()) {
        when {
            state.error != null && state.points.isEmpty() ->
                LoadErrorState(what = "the map", onRetry = viewModel::refresh)
            state.points.isEmpty() && state.loading -> CenteredSpinner()
            state.points.isEmpty() -> EmptyState(
                icon = Icons.Outlined.Map,
                title = "No GPS data yet",
                body = "Routes appear here once a drive with GPS uploads.",
            )
            else -> MapLibreHeatmapView(
                points = state.points,
                mode = mode,
                stations = if (showStations) state.stations else emptyList(),
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Floating controls, top-start: the three colour modes are ONE
        // choice, so they are a segmented row; Stations is an independent
        // overlay toggle, so it is its own chip beside it.
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
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val modes = listOf(
                    HeatmapMode.Density to "Density",
                    HeatmapMode.Speed to "Speed",
                    HeatmapMode.Single to "Single",
                )
                SingleChoiceSegmentedButtonRow {
                    modes.forEachIndexed { i, (m, label) ->
                        SegmentedButton(
                            selected = mode == m,
                            onClick = { viewModel.setMode(m) },
                            shape = SegmentedButtonDefaults.itemShape(index = i, count = modes.size),
                            label = { Text(label) },
                        )
                    }
                }
                // Overlays a fuel-pump dot at every historical fillup that
                // carries GPS. Independent of the mode above.
                FilterChip(
                    selected = showStations,
                    onClick = { viewModel.toggleStations() },
                    label = {
                        Text(
                            if (state.stations.isEmpty()) "Stations"
                            else "Stations (${state.stations.size})",
                        )
                    },
                    enabled = state.stations.isNotEmpty(),
                )
            }
        }

        // Refresh — the reliable path, since the map eats pull gestures.
        // Sits bottom-end above the legend so a thumb reaches it.
        SmallFloatingActionButton(
            onClick = { viewModel.refresh() },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 12.dp, bottom = 64.dp),
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            if (state.loading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh map")
            }
        }

        // Legend floats bottom-END on purpose: MapLibre parks its
        // attribution "i" bottom-start, and covering that would hide
        // the required OpenFreeMap / OpenMapTiles / OpenStreetMap credit.
        // The point count lives here too — it describes what is drawn.
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
                        Swatch(ChartPalette.accent)
                        LegendLabel("all trips, one colour")
                    }
                    HeatmapMode.Density -> {
                        val stops = listOf("1×", "3", "8", "20", "50", "50+ visits")
                        ChartPalette.density.forEachIndexed { i, c ->
                            if (i == 0) LegendLabel(stops[0])
                            Swatch(c)
                            if (i > 0) LegendLabel(stops[i])
                        }
                    }
                    HeatmapMode.Speed -> {
                        LegendLabel("slow")
                        ChartPalette.speedRamp.forEach { Swatch(it) }
                        LegendLabel(
                            "fast (~${UnitFormat.Quantity.SpeedKph.format(130.0, system, 0)})",
                        )
                    }
                }
                if (state.points.isNotEmpty()) {
                    LegendLabel(
                        " · ${UnitFormat.count(state.total.toLong())} pts" +
                            if (state.stride > 1) " (every ${state.stride})" else "",
                    )
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

