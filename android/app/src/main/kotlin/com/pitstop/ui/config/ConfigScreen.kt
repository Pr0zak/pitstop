package com.pitstop.ui.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pitstop.ble.ScannedDevice

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(
    onBack: () -> Unit,
    viewModel: ConfigViewModel = hiltViewModel(),
) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val scanResults by viewModel.scanResults.collectAsStateWithLifecycle()
    val scanning by viewModel.isScanning.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Configuration") },
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
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "First-run note: this app reaches your pitstop broker over LAN. " +
                    "Off-network use needs Tailscale on this phone with \"Use Tailscale subnets\" enabled.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionHeader("Broker")
            OutlinedTextField(
                value = form.brokerUrl,
                onValueChange = { v -> viewModel.update { it.copy(brokerUrl = v) } },
                label = { Text("Broker URL") },
                placeholder = { Text("tcp://10.0.0.x:1883") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.mqttUser,
                onValueChange = { v -> viewModel.update { it.copy(mqttUser = v) } },
                label = { Text("MQTT username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            SecretField(
                label = "MQTT password",
                value = form.mqttPassword,
                onValueChange = { v -> viewModel.update { it.copy(mqttPassword = v) } },
            )

            SectionHeader("Vehicle")
            OutlinedTextField(
                value = form.vehicleSlug,
                onValueChange = { v -> viewModel.update { it.copy(vehicleSlug = v) } },
                label = { Text("Vehicle slug") },
                placeholder = { Text("e.g. my-car") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            SectionHeader("Pitstop API")
            OutlinedTextField(
                value = form.apiBaseUrl,
                onValueChange = { v -> viewModel.update { it.copy(apiBaseUrl = v) } },
                label = { Text("API base URL") },
                placeholder = { Text("http://10.0.0.x:8000") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            SecretField(
                label = "Ingest token",
                value = form.ingestToken,
                onValueChange = { v -> viewModel.update { it.copy(ingestToken = v) } },
            )

            SectionHeader("BLE device")
            Card {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = form.bleDeviceName ?: form.bleDeviceMac ?: "(none picked)",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    form.bleDeviceMac?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.padding(vertical = 4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                if (scanning) viewModel.stopScan() else viewModel.startScan()
                            },
                        ) {
                            Text(if (scanning) "Stop scan" else "Scan")
                        }
                    }
                    if (scanResults.isNotEmpty()) {
                        Spacer(Modifier.padding(vertical = 4.dp))
                        HorizontalDivider()
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp),
                        ) {
                            items(scanResults, key = { it.mac }) { device ->
                                ScanRow(device = device, onClick = { viewModel.pickDevice(device) })
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.padding(vertical = 4.dp))
            Button(
                onClick = { viewModel.save() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (form.saved) "Saved" else "Save")
            }
            Spacer(Modifier.padding(vertical = 16.dp))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun SecretField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (visible) "Hide" else "Show",
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ScanRow(device: ScannedDevice, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.padding(end = 8.dp)) {
            Text(
                device.name ?: "(unknown)",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "${device.mac}  •  ${device.rssi} dBm",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = onClick) { Text("Pick") }
    }
}
