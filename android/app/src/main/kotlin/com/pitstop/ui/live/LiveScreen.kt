package com.pitstop.ui.live

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pitstop.service.MetricSample
import kotlin.math.max
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScreen(
    onBack: () -> Unit,
    viewModel: LiveViewModel = hiltViewModel(),
) {
    val metrics by viewModel.latestByMetric.collectAsStateWithLifecycle()

    // 30 fps interpolation tick — animates gauge values toward latest readings.
    val displayValues = remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
    LaunchedEffect(Unit) {
        var lastTickNs = 0L
        while (true) {
            withFrameNanos { now ->
                val dtMs = if (lastTickNs == 0L) 33L else ((now - lastTickNs) / 1_000_000L)
                lastTickNs = now
                val targets = metrics
                if (targets.isEmpty()) return@withFrameNanos
                val current = displayValues.value
                // Smooth toward target with 5 Hz cutoff.
                val factor = min(1.0, dtMs / 200.0)
                displayValues.value = targets.mapValues { (name, sample) ->
                    val cur = current[name] ?: sample.value
                    cur + (sample.value - cur) * factor
                }
            }
            // Cap at ~33 ms = 30 fps; withFrameNanos already throttles to display refresh,
            // but if the target is 120 Hz we add a tiny delay.
            kotlinx.coroutines.delay(33)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Live") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val rpm = displayValues.value["engine_rpm"] ?: metrics["engine_rpm"]?.value
            val speed = displayValues.value["vehicle_speed"] ?: metrics["vehicle_speed"]?.value

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigGauge(
                    label = "RPM",
                    value = rpm,
                    suffix = "",
                    modifier = Modifier.weight(1f),
                )
                BigGauge(
                    label = "Speed",
                    value = speed?.let { it * 0.621371 }, // km/h -> mph (OBD-II PID 0x0D returns km/h)
                    suffix = "mph",
                    modifier = Modifier.weight(1f),
                )
            }

            val secondary = listOf(
                "coolant_temp" to "Coolant °C",
                "intake_air_temp" to "Intake °C",
                "control_module_voltage" to "Volts",
                "throttle_position" to "Throttle %",
                "fuel_level" to "Fuel %",
            )
            secondary.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { (key, label) ->
                        SmallTile(
                            label = label,
                            sample = metrics[key],
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (row.size == 1) Box(modifier = Modifier.weight(1f))
                }
            }

            if (metrics.isEmpty()) {
                Text(
                    "No live data yet — start the bridge service from the Status screen.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BigGauge(
    label: String,
    value: Double?,
    suffix: String,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(
                text = value?.let { "%.0f".format(max(0.0, it)) } ?: "—",
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            if (suffix.isNotEmpty()) {
                Text(suffix, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SmallTile(
    label: String,
    sample: MetricSample?,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                sample?.let { "%.1f".format(it.value) } ?: "—",
                style = MaterialTheme.typography.titleLarge,
            )
        }
    }
}
