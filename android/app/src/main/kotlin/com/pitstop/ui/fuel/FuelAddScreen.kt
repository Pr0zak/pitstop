package com.pitstop.ui.fuel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FuelAddScreen(
    viewModel: FuelAddViewModel = hiltViewModel(),
) {
    val form by viewModel.form.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            com.pitstop.ui.components.PitstopTopAppBar(title = "Add fillup")
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
            GpsCard(
                gps = form.gps,
                refreshing = form.gpsRefreshing,
                onRefresh = { viewModel.refreshGps() },
            )

            form.nearestPriorStation?.let { station ->
                Card {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Near a prior fillup", style = MaterialTheme.typography.labelMedium)
                        Text(station, style = MaterialTheme.typography.titleMedium)
                        OutlinedButton(onClick = { viewModel.applyNearestStation() }) {
                            Text("Use this station")
                        }
                    }
                }
            }

            OutlinedTextField(
                value = form.gallons,
                onValueChange = { v -> viewModel.update { it.copy(gallons = v) } },
                label = { Text("Gallons") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.totalPrice,
                onValueChange = { v -> viewModel.update { it.copy(totalPrice = v) } },
                label = { Text("Total price (USD)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.odometer,
                onValueChange = { v -> viewModel.update { it.copy(odometer = v) } },
                label = { Text("Odometer (mi)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Switch(
                    checked = form.partial,
                    onCheckedChange = { v -> viewModel.update { it.copy(partial = v) } },
                )
                Text("Partial fillup")
            }

            OutlinedTextField(
                value = form.stationName,
                onValueChange = { v -> viewModel.update { it.copy(stationName = v) } },
                label = { Text("Station") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (form.stationSuggestions.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    form.stationSuggestions.take(4).forEach { suggestion ->
                        FilterChip(
                            selected = false,
                            onClick = {
                                viewModel.update { it.copy(stationName = suggestion) }
                            },
                            label = { Text(suggestion, maxLines = 1) },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = form.notes,
                onValueChange = { v -> viewModel.update { it.copy(notes = v) } },
                label = { Text("Notes") },
                modifier = Modifier.fillMaxWidth(),
            )

            form.errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            form.submittedId?.let {
                Text("Saved (id: $it)", color = MaterialTheme.colorScheme.primary)
            }

            Button(
                onClick = { viewModel.submit() },
                enabled = !form.submitting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (form.submitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp),
                        strokeWidth = 2.dp,
                    )
                }
                Text(if (form.submitting) "Submitting…" else "Save fillup")
            }
            Spacer(Modifier.padding(vertical = 16.dp))
        }
    }
}

@Composable
private fun GpsCard(
    gps: GpsFix?,
    refreshing: Boolean,
    onRefresh: () -> Unit,
) {
    Card {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Location", style = MaterialTheme.typography.labelMedium)
                if (gps != null) {
                    Text("${"%.5f".format(gps.lat)}, ${"%.5f".format(gps.lon)}")
                    Text(
                        "±${"%.0f".format(gps.accuracyMeters)} m",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        if (refreshing) "Locating…" else "(no fix yet)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onRefresh, enabled = !refreshing) {
                if (refreshing) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh GPS")
                }
            }
        }
    }
}
