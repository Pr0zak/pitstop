package com.pitstop.ui.live

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pitstop.service.MetricSample
import com.pitstop.ui.components.PillState
import com.pitstop.ui.components.StatusPill
import kotlin.math.max

/**
 * Tile spec — what label, which metric key, what unit, optional formatter.
 * The screen renders one tile per spec; a missing metric shows "—".
 */
private data class TileSpec(
    val label: String,
    val key: String,
    val unit: String = "",
    val digits: Int = 1,
    val formatter: ((Double) -> String)? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScreen(
    viewModel: LiveViewModel = hiltViewModel(),
) {
    val metrics by viewModel.latestByMetric.collectAsStateWithLifecycle()
    val bridgeStatus by viewModel.status.collectAsStateWithLifecycle()
    val brokerConnected by viewModel.brokerConnected.collectAsStateWithLifecycle()
    val unitSystem by viewModel.unitSystem.collectAsStateWithLifecycle()
    val obdAgeS by viewModel.obdAgeS.collectAsStateWithLifecycle()

    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Smooth gauge movement is animated on JUST the two hero gauges via
    // animateFloatAsState (below). The old approach allocated a whole map
    // every 33 ms and recomposed the entire column even when converged /
    // engine-off — replaced to cut idle CPU + recomposition churn.

    Scaffold(
        topBar = { com.pitstop.ui.components.PitstopTopAppBar() },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // ── Connection pills ──────────────────────────────────────
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            ) {
                val (bleLabel, blePill) = bleStatusOf(bridgeStatus.phase)
                StatusPill(state = blePill, label = bleLabel, compact = true)
                StatusPill(
                    state = if (brokerConnected) PillState.Healthy else PillState.Offline,
                    label = if (brokerConnected) "Broker live" else "Broker off",
                    compact = true,
                )
                val (engineLabel, enginePill) = when (bridgeStatus.engineState) {
                    com.pitstop.service.EngineState.On ->
                        "Engine on" to PillState.Healthy
                    com.pitstop.service.EngineState.Off ->
                        "Engine off" to PillState.Offline
                    com.pitstop.service.EngineState.Unknown ->
                        "Engine ?" to PillState.Neutral
                }
                StatusPill(state = enginePill, label = engineLabel, compact = true)
                // OBD freshness pill (BLE-3): age of the last BLE OBD frame.
                // Healthy <10s / Degraded <60s / Offline otherwise.
                val (obdLabel, obdPill) = when (val age = obdAgeS) {
                    null -> "OBD —" to PillState.Neutral
                    else -> {
                        val state = when {
                            age < 10 -> PillState.Healthy
                            age < 60 -> PillState.Degraded
                            else -> PillState.Offline
                        }
                        "OBD ${age}s" to state
                    }
                }
                StatusPill(state = obdPill, label = obdLabel, compact = true)
                if (bridgeStatus.inCar) {
                    StatusPill(
                        state = PillState.Healthy,
                        label = "In car",
                        compact = true,
                    )
                }
            }

            // ── Hero gauges: Speed + RPM ──────────────────────────────
            val rpmRaw = metrics["engine_rpm"]?.value
            val speedKphRaw = metrics["vehicle_speed"]?.value
            val isImperial = unitSystem == "imperial"
            // animateFloatAsState gives smooth needle movement without the
            // per-frame map allocation the old 30 fps loop did. Animate the
            // raw value and only render "—" when the source metric is null.
            val rpmAnim by animateFloatAsState(
                targetValue = (rpmRaw ?: 0.0).toFloat(),
                animationSpec = tween(durationMillis = 250),
                label = "rpm",
            )
            val speedAnim by animateFloatAsState(
                targetValue = (speedKphRaw ?: 0.0).toFloat(),
                animationSpec = tween(durationMillis = 250),
                label = "speed",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigGauge(
                    label = "Speed",
                    value = speedKphRaw?.let {
                        val v = speedAnim.toDouble()
                        if (isImperial) v * 0.621371 else v
                    },
                    unit = if (isImperial) "mph" else "km/h",
                    digits = 0,
                    modifier = Modifier.weight(1f),
                )
                BigGauge(
                    label = "RPM",
                    value = rpmRaw?.let { rpmAnim.toDouble() },
                    unit = "",
                    digits = 0,
                    modifier = Modifier.weight(1f),
                )
            }

            // ── Engine ────────────────────────────────────────────────
            // Temperature + pressure tiles use unit-aware formatters
            // (com.pitstop.util.UnitFormat). Engine load + throttle
            // are unitless % so they pass through.
            LiveSection(
                title = "Engine",
                tiles = listOf(
                    TileSpec(
                        "Coolant", "coolant_temp",
                        unit = if (isImperial) "°F" else "°C", digits = 0,
                        formatter = { v -> com.pitstop.util.UnitFormat.temp(v, unitSystem, 0)
                            .substringBefore(" ") },
                    ),
                    TileSpec(
                        "Intake", "intake_air_temp",
                        unit = if (isImperial) "°F" else "°C", digits = 0,
                        formatter = { v -> com.pitstop.util.UnitFormat.temp(v, unitSystem, 0)
                            .substringBefore(" ") },
                    ),
                    TileSpec("Engine load", "engine_load", "%", 0),
                    TileSpec("Throttle", "throttle_position", "%", 0),
                    TileSpec(
                        "MAF", "maf_air_flow",
                        unit = if (isImperial) "lb/min" else "g/s", digits = 1,
                        formatter = { v -> com.pitstop.util.UnitFormat.mafGramsPerSec(v, unitSystem, 1)
                            .substringBefore(" ") },
                    ),
                    TileSpec(
                        "MAP", "manifold_pressure",
                        unit = if (isImperial) "psi" else "kPa", digits = 0,
                        formatter = { v -> com.pitstop.util.UnitFormat.pressureKpa(v, unitSystem, 0)
                            .substringBefore(" ") },
                    ),
                ),
                metrics = metrics,
            )

            // ── Fuel system ───────────────────────────────────────────
            LiveSection(
                title = "Fuel system",
                tiles = listOf(
                    TileSpec("Fuel level", "fuel_level", "%", 0),
                    TileSpec("STFT B1", "stft_b1", "%", 1),
                    TileSpec("LTFT B1", "ltft_b1", "%", 1),
                    TileSpec("STFT B2", "stft_b2", "%", 1),
                    TileSpec("LTFT B2", "ltft_b2", "%", 1),
                ),
                metrics = metrics,
            )

            // ── Electrical ────────────────────────────────────────────
            LiveSection(
                title = "Electrical",
                tiles = listOf(
                    TileSpec("Battery", "control_module_voltage", "V", 1),
                    TileSpec("Run time", "run_time_since_start", "s", 0),
                ),
                metrics = metrics,
            )

            // ── GPS (from the phone bridge) ───────────────────────────
            LiveSection(
                title = "Position",
                tiles = listOf(
                    TileSpec(
                        "GPS speed", "gps_speed", "mph", 0,
                        formatter = { v -> "%.0f".format(v * 2.23694) }, // m/s → mph
                    ),
                    TileSpec("Altitude", "gps_alt", "m", 0),
                    TileSpec("Lat", "gps_lat", "°", 5),
                    TileSpec("Lon", "gps_lon", "°", 5),
                ),
                metrics = metrics,
            )

            if (metrics.isEmpty()) {
                Text(
                    "No live data yet — start the bridge service from Settings.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp, start = 4.dp),
                )
            }

            Box(modifier = Modifier.padding(bottom = 24.dp))
        }
    }
}

/**
 * Section pattern for Live: small caps header + 3-up tile grid. Tighter
 * than SettingsSection (no card wrapper, less vertical padding) so a
 * driver glance can take in 5-6 rows at once.
 */
@Composable
private fun LiveSection(
    title: String,
    tiles: List<TileSpec>,
    metrics: Map<String, MetricSample>,
    customBody: (@Composable () -> Unit)? = null,
) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 6.dp),
    )
    if (tiles.isNotEmpty()) {
        // 3-up grid for tighter density on a phone screen.
        tiles.chunked(3).forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 6.dp),
            ) {
                row.forEach { spec ->
                    val sample = metrics[spec.key]
                    SmallTile(
                        label = spec.label,
                        value = sample?.value,
                        unit = spec.unit,
                        digits = spec.digits,
                        formatter = spec.formatter,
                        modifier = Modifier.weight(1f),
                    )
                }
                // Pad incomplete rows so weights line up.
                repeat(3 - row.size) { Box(modifier = Modifier.weight(1f)) }
            }
        }
    }
    customBody?.invoke()
}

@Composable
private fun BigGauge(
    label: String,
    value: Double?,
    unit: String,
    digits: Int = 0,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 18.dp, horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value?.let { "%.${digits}f".format(max(0.0, it)) } ?: "—",
                style = MaterialTheme.typography.displayLarge,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (unit.isNotEmpty()) {
                Text(
                    unit,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SmallTile(
    label: String,
    value: Double?,
    unit: String = "",
    digits: Int = 1,
    formatter: ((Double) -> String)? = null,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val display = when {
                value == null || value.isNaN() -> "—"
                formatter != null -> formatter(value)
                else -> "%.${digits}f".format(value)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    display,
                    style = MaterialTheme.typography.titleLarge,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                )
                if (unit.isNotEmpty() && display != "—") {
                    Text(
                        " $unit",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun bleStatusOf(phase: com.pitstop.service.BridgePhase): Pair<String, PillState> =
    when (phase) {
        com.pitstop.service.BridgePhase.Idle -> "BLE idle" to PillState.Neutral
        com.pitstop.service.BridgePhase.Scanning -> "BLE scan" to PillState.Connecting
        com.pitstop.service.BridgePhase.Connecting -> "BLE…" to PillState.Connecting
        com.pitstop.service.BridgePhase.Connected -> "BLE live" to PillState.Healthy
        com.pitstop.service.BridgePhase.Disconnected -> "BLE down" to PillState.Degraded
        com.pitstop.service.BridgePhase.Error -> "BLE error" to PillState.Offline
    }
