package com.pitstop.ui.live

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.draw.alpha
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
import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.pitstop.ui.components.EmptyState
import com.pitstop.ui.components.PillTone
import com.pitstop.ui.components.PitstopTopAppBar
import com.pitstop.ui.components.StatusPill
import com.pitstop.util.UnitFormat
import kotlin.math.max

/**
 * Tile spec — what label, which metric key, which physical quantity,
 * how many decimals. The screen renders one tile per spec; a missing
 * metric shows "—".
 *
 * The tile carries a [UnitFormat.Quantity], NOT a unit string: the unit
 * label and the value conversion both fall out of the user's
 * imperial/metric preference at render time. A literal unit string here
 * is how the Fuel-rate tile ended up showing L/h to an imperial user.
 */
private data class TileSpec(
    val label: String,
    val key: String,
    val quantity: UnitFormat.Quantity = UnitFormat.Quantity.None,
    val digits: Int = 1,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScreen(
    onOpenHome: () -> Unit = {},
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

    LiveContent(
        metrics, bridgeStatus, brokerConnected, unitSystem, obdAgeS,
        onStartBridge = viewModel::startBridge,
        onOpenHome = onOpenHome,
    )
}

/** Stateless body of [LiveScreen], split out so screenshot tests can render it from fixtures. */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun LiveContent(
    metrics: Map<String, MetricSample>,
    bridgeStatus: com.pitstop.service.BridgeStatus,
    brokerConnected: Boolean,
    unitSystem: String,
    obdAgeS: Long?,
    onStartBridge: () -> Unit = {},
    onOpenHome: () -> Unit = {},
    /** Landscape "drive mode": two big gauges + four tiles, no scroll. */
    driveMode: Boolean =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE,
) {
    // Stale guard: once OBD has been quiet >10 s the numbers are the last
    // reading, not the current one — say so and dim them.
    val stale = metrics.isNotEmpty() && (obdAgeS == null || obdAgeS > 10)
    if (driveMode) {
        DriveModeContent(metrics, unitSystem, obdAgeS, stale)
        return
    }
    // contentWindowInsets is zeroed because MainActivity's outer Scaffold
    // already consumed the system bars; without it this page re-applies
    // the status-bar inset and leaves an empty band on top.
    Scaffold(
        topBar = { PitstopTopAppBar() },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
    ) { padding ->
        if (metrics.isEmpty()) {
            LiveEmptyState(
                phase = bridgeStatus.phase,
                onStartBridge = onStartBridge,
                onOpenHome = onOpenHome,
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // ── Connection pills ──────────────────────────────────────
            // FlowRow: five pills don't fit one row on a 360 dp phone.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            ) {
                val (bleLabel, blePill) = bleStatusOf(bridgeStatus.phase)
                StatusPill(tone = blePill, label = bleLabel, compact = true, subject = "Bluetooth")
                StatusPill(
                    tone = if (brokerConnected) PillTone.Healthy else PillTone.Offline,
                    label = if (brokerConnected) "Broker live" else "Broker off",
                    compact = true,
                    subject = "MQTT",
                )
                val (engineLabel, enginePill) = when (bridgeStatus.engineState) {
                    com.pitstop.service.EngineState.On ->
                        "Engine on" to PillTone.Healthy
                    com.pitstop.service.EngineState.Off ->
                        "Engine off" to PillTone.Offline
                    com.pitstop.service.EngineState.Unknown ->
                        "Engine ?" to PillTone.Neutral
                }
                StatusPill(tone = enginePill, label = engineLabel, compact = true, subject = "Engine")
                // OBD freshness pill (BLE-3): age of the last BLE OBD frame.
                // Healthy <10s / Degraded <60s / Offline otherwise.
                val (obdLabel, obdPill) = when (val age = obdAgeS) {
                    null -> "OBD —" to PillTone.Neutral
                    else -> {
                        val tone = when {
                            age < 10 -> PillTone.Healthy
                            age < 60 -> PillTone.Degraded
                            else -> PillTone.Offline
                        }
                        "OBD ${age}s" to tone
                    }
                }
                StatusPill(tone = obdPill, label = obdLabel, compact = true, subject = "OBD data age")
                if (bridgeStatus.inCar) {
                    StatusPill(tone = PillTone.Healthy, label = "In car", compact = true)
                }
            }

            if (stale) StaleBanner(obdAgeS)
            Column(
                modifier = Modifier.alpha(if (stale) 0.45f else 1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
            // ── Hero gauges: Speed + RPM ──────────────────────────────
            val rpmRaw = metrics["engine_rpm"]?.value
            val speedKphRaw = metrics["vehicle_speed"]?.value
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
                        UnitFormat.Quantity.SpeedKph.convert(speedAnim.toDouble(), unitSystem)
                    },
                    unit = UnitFormat.Quantity.SpeedKph.unit(unitSystem),
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
            InstantEconomyTile(metrics, unitSystem, modifier = Modifier.padding(top = 8.dp))

            // ── Engine ────────────────────────────────────────────────
            // Every tile names a UnitFormat.Quantity; the °C/°F, kPa/psi
            // and g/s ÷ lb/min splits are decided there, not here.
            // Engine load + throttle are unitless % so they pass through.
            LiveSection(
                title = "Engine",
                tiles = listOf(
                    TileSpec("Coolant", "coolant_temp", UnitFormat.Quantity.TempC, 0),
                    TileSpec("Intake", "intake_air_temp", UnitFormat.Quantity.TempC, 0),
                    TileSpec("Engine load", "engine_load", UnitFormat.Quantity.Percent, 0),
                    TileSpec("Throttle", "throttle_position", UnitFormat.Quantity.Percent, 0),
                    TileSpec("MAF", "maf_air_flow", UnitFormat.Quantity.MassFlowGramsPerSec, 1),
                    TileSpec("MAP", "manifold_pressure", UnitFormat.Quantity.PressureKpa, 0),
                    // PID 0x9E. kg/h in both systems on purpose — see
                    // Quantity.MassFlowKgPerHour for why.
                    TileSpec(
                        "Exhaust flow", "engine_exhaust_flow",
                        UnitFormat.Quantity.MassFlowKgPerHour, 1,
                    ),
                ),
                metrics = metrics,
                system = unitSystem,

            )

            // ── Fuel system ───────────────────────────────────────────
            LiveSection(
                title = "Fuel system",
                tiles = listOf(
                    TileSpec("Fuel level", "fuel_level", UnitFormat.Quantity.Percent, 0),
                    // PID 0x9D. The metric is GRAMS PER SECOND; a mass rate
                    // means nothing at a glance, so it renders as a volume
                    // rate — L/h for metric, US gal/h for imperial, exactly
                    // what the web's fmtFuelRateLh() does. The conversion
                    // lives in Quantity.FuelRateGramsPerSec; this tile used
                    // to hardcode "L/h", which was wrong for every imperial
                    // user (i.e. all of them by default).
                    //
                    // Keyed on `engine_fuel_rate`, NOT the legacy `fuel_rate`
                    // — the latter is the WiCAN's broken built-in 0x9D
                    // decoder whose 11,586 historical rows are all 0.000.
                    //
                    // 2 decimals: idle is ~0.43 gph, so 1 decimal quantises
                    // the whole idle/creep range into two steps.
                    TileSpec(
                        "Fuel rate", "engine_fuel_rate",
                        UnitFormat.Quantity.FuelRateGramsPerSec, 2,
                    ),
                    TileSpec("STFT B1", "stft_b1", UnitFormat.Quantity.Percent, 1),
                    TileSpec("LTFT B1", "ltft_b1", UnitFormat.Quantity.Percent, 1),
                    TileSpec("STFT B2", "stft_b2", UnitFormat.Quantity.Percent, 1),
                    TileSpec("LTFT B2", "ltft_b2", UnitFormat.Quantity.Percent, 1),
                    // PID 0x23 — gauge pressure, reads ~3500 kPa on the Pilot.
                    // Same Quantity as MAP, so the imperial toggle gives psi.
                    TileSpec(
                        "Fuel rail", "fuel_rail_pressure",
                        UnitFormat.Quantity.PressureKpa, 0,
                    ),
                    // PID 0x44 — commanded fuel/air EQUIVALENCE ratio (lambda),
                    // not the 14.7:1 mass ratio. 1.000 = stoich, and closed-loop
                    // cruise sits within a percent of it, so 3 decimals is the
                    // minimum that shows any movement at all. A ratio has no
                    // imperial variant.
                    TileSpec("Cmd AFR", "commanded_afr_ratio", UnitFormat.Quantity.Lambda, 3),
                ),
                metrics = metrics,
                system = unitSystem,

            )

            // ── Emissions ─────────────────────────────────────────────
            // Catalyst temps run ~560 °C at cruise; O2 sensor 1 is the
            // pre-cat closed-loop sensor. EGR + evap purge are commanded
            // duty percentages, not measured positions.
            LiveSection(
                title = "Emissions",
                tiles = listOf(
                    TileSpec("Cat B1", "catalyst_temp_b1", UnitFormat.Quantity.TempC, 0),
                    TileSpec("Cat B2", "catalyst_temp_b2", UnitFormat.Quantity.TempC, 0),
                    // PID 0x24, wide-range sensor. Only the LAMBDA field is
                    // shown: the same PID's voltage field is invariant in the
                    // stored data (2 distinct values across 29,103 rows, flat
                    // 2.000 for the last six weeks), so it is left unaliased
                    // at ingest and never reaches a canonical metric name.
                    // 3 decimals — closed-loop lambda lives inside ±5% of 1.
                    TileSpec("O2 S1 λ", "o2_s1_lambda", UnitFormat.Quantity.Lambda, 3),
                    // Both sit near zero for most of a drive — 0 digits would
                    // render a permanent "0".
                    TileSpec("Cmd EGR", "commanded_egr", UnitFormat.Quantity.Percent, 1),
                    TileSpec("Evap purge", "commanded_evap_purge", UnitFormat.Quantity.Percent, 1),
                ),
                metrics = metrics,
                system = unitSystem,

            )

            // ── Electrical ────────────────────────────────────────────
            LiveSection(
                title = "Electrical",
                tiles = listOf(
                    TileSpec("Battery", "control_module_voltage", UnitFormat.Quantity.Volt, 1),
                    TileSpec("Run time", "run_time_since_start", UnitFormat.Quantity.Seconds, 0),
                ),
                metrics = metrics,
                system = unitSystem,

            )

            // ── GPS (from the phone bridge) ───────────────────────────
            // gps_speed is m/s on the wire — metric users want km/h, not
            // the raw SI value, which is why SpeedMps converts on both
            // branches. Altitude follows the same toggle (m ↔ ft), as the
            // Settings blurb has always claimed it did.
            LiveSection(
                title = "Position",
                tiles = listOf(
                    TileSpec("GPS speed", "gps_speed", UnitFormat.Quantity.SpeedMps, 0),
                    TileSpec("Altitude", "gps_alt", UnitFormat.Quantity.AltitudeM, 0),
                    TileSpec("Lat", "gps_lat", UnitFormat.Quantity.Degrees, 5),
                    TileSpec("Lon", "gps_lon", UnitFormat.Quantity.Degrees, 5),
                ),
                metrics = metrics,
                system = unitSystem,

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
    system: String,
    customBody: (@Composable () -> Unit)? = null,
) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(start = 4.dp, top = 12.dp, bottom = 6.dp)
            .semantics { heading() },
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
                        quantity = spec.quantity,
                        digits = spec.digits,
                        system = system,
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
        // fillMaxSize is a no-op in the portrait scroll (unbounded height)
        // and centres the number in landscape drive mode's full-height card.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 18.dp, horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
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
    quantity: UnitFormat.Quantity,
    digits: Int = 1,
    system: String = "imperial",
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
            val display = quantity.number(value, system, digits)
            val unit = quantity.unit(system)
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

@Composable
private fun StaleBanner(obdAgeS: Long?) {
    val age = obdAgeS?.let { s -> if (s >= 60) "${s / 60}m ${s % 60}s" else "${s}s" }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    ) {
        Text(
            if (age != null) "No OBD data for $age — showing the last reading" else "No OBD data yet",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

private fun bleStatusOf(phase: com.pitstop.service.BridgePhase): Pair<String, PillTone> =
    when (phase) {
        com.pitstop.service.BridgePhase.Idle -> "BLE idle" to PillTone.Neutral
        com.pitstop.service.BridgePhase.Scanning -> "BLE scan" to PillTone.Connecting
        com.pitstop.service.BridgePhase.Connecting -> "BLE…" to PillTone.Connecting
        com.pitstop.service.BridgePhase.Connected -> "BLE live" to PillTone.Healthy
        com.pitstop.service.BridgePhase.Disconnected -> "BLE down" to PillTone.Degraded
        com.pitstop.service.BridgePhase.Error -> "BLE error" to PillTone.Offline
    }

/**
 * Nothing has arrived yet. Explains why and offers the fix in place: a
 * Start button while the bridge is idle / errored, otherwise a pointer to
 * Home where the connection detail lives.
 */
@Composable
private fun LiveEmptyState(
    phase: com.pitstop.service.BridgePhase,
    onStartBridge: () -> Unit,
    onOpenHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val idle = phase == com.pitstop.service.BridgePhase.Idle ||
        phase == com.pitstop.service.BridgePhase.Error
    EmptyState(
        icon = Icons.Outlined.Speed,
        title = if (idle) "Bridge isn't running" else "Waiting for the WiCAN",
        body = if (idle) {
            "Start the bridge to stream live engine data from the dongle."
        } else {
            "The bridge is running but no engine data has arrived yet. " +
                "Turn the ignition on, or check the connection on Home."
        },
        actionLabel = if (idle) "Start bridge" else "Open Home",
        onAction = if (idle) onStartBridge else onOpenHome,
        modifier = modifier.fillMaxSize(),
    )
}

/**
 * Instantaneous economy from the ECU fuel rate and road speed — mpg or
 * L/100 km per the unit toggle. Idle / standstill shows the burn rate
 * instead, because "0.2 mpg" at a light is true and useless.
 */
@Composable
private fun InstantEconomyTile(
    metrics: Map<String, MetricSample>,
    system: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val rate = metrics["engine_fuel_rate"]?.value
    val speed = metrics["vehicle_speed"]?.value
    val mpg = UnitFormat.instantMpg(rate, speed)
    val (value, unit) = when {
        mpg != null -> UnitFormat.economyNumber(mpg, system) to UnitFormat.economyUnit(system)
        rate != null -> UnitFormat.Quantity.FuelRateGramsPerSec.number(rate, system, 2) to
            "${UnitFormat.Quantity.FuelRateGramsPerSec.unit(system)} · idle"
        else -> "—" to UnitFormat.economyUnit(system)
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {},
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = if (compact) 8.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "INSTANT",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                value,
                style = if (compact) MaterialTheme.typography.displaySmall else MaterialTheme.typography.displayMedium,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                " $unit",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Landscape "drive mode": what a dash-mounted phone should show. Speed and
 * RPM as two big gauges on the left, four glanceable tiles on the right,
 * nothing that scrolls. Same stale dim as portrait.
 */
@Composable
private fun DriveModeContent(
    metrics: Map<String, MetricSample>,
    system: String,
    obdAgeS: Long?,
    stale: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (stale) StaleBanner(obdAgeS)
        Row(
            modifier = Modifier
                .fillMaxSize()
                .alpha(if (stale) 0.45f else 1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BigGauge(
                label = "Speed",
                value = metrics["vehicle_speed"]?.value?.let {
                    UnitFormat.Quantity.SpeedKph.convert(it, system)
                },
                unit = UnitFormat.Quantity.SpeedKph.unit(system),
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            BigGauge(
                label = "RPM",
                value = metrics["engine_rpm"]?.value,
                unit = "",
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            Column(
                modifier = Modifier.weight(1.1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                InstantEconomyTile(metrics, system, compact = true, modifier = Modifier.weight(1f))
                val tiles = listOf(
                    TileSpec("Coolant", "coolant_temp", UnitFormat.Quantity.TempC, 0),
                    TileSpec("Fuel level", "fuel_level", UnitFormat.Quantity.Percent, 0),
                    TileSpec("Battery", "control_module_voltage", UnitFormat.Quantity.Volt, 1),
                )
                for (spec in tiles) {
                    SmallTile(
                        label = spec.label,
                        value = metrics[spec.key]?.value,
                        quantity = spec.quantity,
                        digits = spec.digits,
                        system = system,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                }
            }
        }
    }
}
