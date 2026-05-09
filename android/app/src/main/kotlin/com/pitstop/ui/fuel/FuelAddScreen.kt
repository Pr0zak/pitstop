package com.pitstop.ui.fuel

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Add-fillup screen redesigned to match the Fuelio reference layout the
 * user shared. Pattern:
 *
 *  - CenterAlignedTopAppBar with "Refueling" title + check action (save)
 *  - Vehicle row up top (mark + name + last odo + chevron to switch)
 *  - Outlined fields with leading icons:
 *      Speedometer  →  Odometer + "Last value: X mi" hint
 *      Fuel pump    →  Gas (gal)  | Gas type
 *      Dollar       →  Price/gal  | Total cost
 *      Calendar     →  Date       | Time
 *  - Switches: Full tank, Set up tank level
 *  - Gas Station section: location + favourites entry
 *
 * Bidirectional auto-fill: editing any of {gallons, price/gal, total}
 * updates the third (handled in FuelAddViewModel.set* methods).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FuelAddScreen(
    viewModel: FuelAddViewModel = hiltViewModel(),
) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(form.errorMessage) {
        form.errorMessage?.let { snackbarHost.showSnackbar(it) }
    }
    LaunchedEffect(form.submittedId) {
        form.submittedId?.let { snackbarHost.showSnackbar("Fillup saved") }
    }

    val now = remember { Instant.now() }
    val zone = remember { ZoneId.systemDefault() }
    val dateStr = remember {
        DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(zone).format(now)
    }
    val timeStr = remember {
        DateTimeFormatter.ofPattern("hh:mm a").withZone(zone).format(now)
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "Refueling",
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    // Reset/clear the form back to defaults — same hand
                    // gesture as Fuelio's back-arrow but framed as "discard
                    // this draft" since there's no real back stack with the
                    // pager-based navigation.
                    IconButton(onClick = { viewModel.update { FuelFormState() } }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Discard",
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.submit() },
                        enabled = !form.submitting,
                    ) {
                        if (form.submitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = "Save fillup",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // ── Vehicle row (read-only for now; multi-vehicle picker is a
            //    follow-up — pulls from the configured vehicleSlug)
            VehicleHeaderRow(name = "Pilot", lastOdometer = form.lastOdometer)

            // Form body
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Odometer
                OutlinedTextField(
                    value = form.odometer,
                    onValueChange = { v -> viewModel.update { it.copy(odometer = v) } },
                    label = { Text("Odometer (mi)") },
                    leadingIcon = { Icon(Icons.Filled.Speed, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                    colors = darkTextFieldColors(),
                )
                form.lastOdometer?.let {
                    Text(
                        "Last value: ${"%,.0f".format(it)} mi",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }

                // Gas (gal) | Gas type — paired row
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = form.gallons,
                        onValueChange = { v -> viewModel.setGallons(v) },
                        label = { Text("Gas (gal)") },
                        leadingIcon = { Icon(Icons.Filled.LocalGasStation, contentDescription = null) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                        colors = darkTextFieldColors(),
                    )
                    OutlinedTextField(
                        value = "Regular (87)",
                        onValueChange = { /* read-only stub for now */ },
                        readOnly = true,
                        label = { Text("Gas type") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = darkTextFieldColors(),
                    )
                }

                // Price/gal | Total cost
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = form.pricePerGallon,
                        onValueChange = { v -> viewModel.setPricePerGallon(v) },
                        label = { Text("Price/gal") },
                        leadingIcon = { Icon(Icons.Filled.AttachMoney, contentDescription = null) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                        colors = darkTextFieldColors(),
                    )
                    OutlinedTextField(
                        value = form.totalPrice,
                        onValueChange = { v -> viewModel.setTotalPrice(v) },
                        label = { Text("Total cost") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                        colors = darkTextFieldColors(),
                    )
                }

                // Date | Time (read-only stubs — picker is a follow-up; the
                // backend uses Instant.now() at submit time)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = dateStr,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Date") },
                        leadingIcon = { Icon(Icons.Filled.CalendarMonth, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = darkTextFieldColors(),
                    )
                    OutlinedTextField(
                        value = timeStr,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Time") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = darkTextFieldColors(),
                    )
                }

                Spacer(Modifier.height(4.dp))

                // Full tank Switch
                ToggleRow(
                    label = "Full tank",
                    checked = !form.partial,
                    onCheckedChange = { v -> viewModel.update { it.copy(partial = !v) } },
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }

            // ── Gas Station section ────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "Gas Station",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = form.stationName.isNotBlank() || form.gps != null,
                        onCheckedChange = { /* visual-only; the data is whatever the user has filled */ },
                    )
                }

                // Current location row
                StationLocationRow(
                    coords = form.gps?.let {
                        "%.4f, %.4f".format(it.lat, it.lon)
                    } ?: if (form.gpsRefreshing) "Locating…" else "(no fix yet)",
                    onRefresh = { viewModel.refreshGps() },
                )

                // Station name + nearest-prior shortcut
                OutlinedTextField(
                    value = form.stationName,
                    onValueChange = { v -> viewModel.update { it.copy(stationName = v) } },
                    label = { Text("Station") },
                    placeholder = { Text("Add to favorites") },
                    leadingIcon = { Icon(Icons.Filled.Star, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = darkTextFieldColors(),
                )

                form.nearestPriorStation?.let { nearest ->
                    androidx.compose.material3.AssistChip(
                        onClick = { viewModel.applyNearestStation() },
                        label = { Text("Use nearest: $nearest") },
                    )
                }

                if (form.stationSuggestions.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        form.stationSuggestions.take(4).forEach { s ->
                            androidx.compose.material3.AssistChip(
                                onClick = {
                                    viewModel.update { it.copy(stationName = s) }
                                },
                                label = { Text(s, maxLines = 1) },
                            )
                        }
                    }
                }
            }

            // Explicit Save button at the bottom of the form. The
            // check action up in the TopAppBar still works (and saves
            // a tap when keyboard is visible), but a labelled primary
            // button at the natural end of the scroll is the obvious
            // affordance — fixes the "how do I save this?" feedback.
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            ) {
                androidx.compose.material3.Button(
                    onClick = { viewModel.submit() },
                    enabled = !form.submitting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (form.submitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.size(8.dp))
                        Text("Saving…")
                    } else {
                        Icon(Icons.Filled.Check, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Save fillup")
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun VehicleHeaderRow(name: String, lastOdometer: Double?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Avatar/mark — small ink circle as a placeholder for the per-vehicle
        // photo/avatar Fuelio shows. When we add per-vehicle artwork the same
        // slot accepts AsyncImage cleanly.
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.LocalGasStation,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            lastOdometer?.let {
                Text(
                    "${"%,.0f".format(it)} mi",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun StationLocationRow(coords: String, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.LocationOn,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            coords,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRefresh) {
            Icon(Icons.Filled.Refresh, contentDescription = "Refresh GPS")
        }
    }
}

/**
 * Shared OutlinedTextField colour set tuned for the dark theme. The
 * Material 3 default uses a too-bright surface tint on the unfocused
 * container; we drop to surfaceContainerHigh to match the Fuelio
 * reference's flat dark inputs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun darkTextFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    focusedIndicatorColor = MaterialTheme.colorScheme.primary,
    unfocusedIndicatorColor = MaterialTheme.colorScheme.outlineVariant,
)
