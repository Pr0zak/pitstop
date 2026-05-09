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
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
            // Brand-only TopAppBar matching the rest of the primary tabs
            // (Home, Live, Settings). Save lives in the actions slot;
            // there's no back arrow because there's no back stack on a
            // primary pager tab — the bottom NavigationBar + horizontal
            // swipe handle navigation. Removed the previous discard
            // icon: it wiped the loaded vehicle list as a side effect
            // and confused the user.
            CenterAlignedTopAppBar(
                title = {
                    androidx.compose.foundation.layout.Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "pitstop",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
                windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
                actions = {
                    androidx.compose.material3.TextButton(
                        onClick = { viewModel.submit() },
                        enabled = !form.submitting,
                    ) {
                        if (form.submitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            Text(
                                "Save",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelLarge,
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
            // Vehicle picker — dropdown of all known vehicles, defaults to
            // the bridge's configured slug. Submitting overrides the
            // vehicle for this fillup only; the bridge stays on its slug.
            VehiclePickerRow(
                vehicles = form.vehicles,
                selectedSlug = form.selectedVehicleSlug,
                lastOdometer = form.lastOdometer,
                onSelect = viewModel::selectVehicle,
            )

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
                    FuelTypeDropdown(
                        selected = form.fuelType,
                        onSelect = viewModel::selectFuelType,
                        modifier = Modifier.weight(1f),
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

                // Missed previous fillup — flag retroactive entries so
                // the MPG recompute skips them (matches Fuelio's chain
                // rule). Default off — the common case is a current fill.
                ToggleRow(
                    label = "Missed previous fillup",
                    checked = form.isMissed,
                    onCheckedChange = { v -> viewModel.update { it.copy(isMissed = v) } },
                )

                // Notes — multi-line; for trip context, gas brand,
                // weather, etc. Free-form text shipped verbatim to
                // the backend's fillup row.
                OutlinedTextField(
                    value = form.notes,
                    onValueChange = { v -> viewModel.update { it.copy(notes = v) } },
                    label = { Text("Notes") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    minLines = 2,
                    colors = darkTextFieldColors(),
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
                    AssistChip(
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
                            AssistChip(
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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            ) {
                Button(
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

/**
 * Vehicle row + picker dropdown. Renders the selected vehicle's name +
 * last odo by default; tapping the chevron opens a DropdownMenu with
 * every vehicle from /api/vehicles, marking inactive ones with a
 * subtle "(archived)" suffix. Switching the selection scopes the
 * pending fillup to that vehicle without touching the bridge config.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VehiclePickerRow(
    vehicles: List<VehicleOption>,
    selectedSlug: String,
    lastOdometer: Double?,
    onSelect: (String) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val selected = vehicles.firstOrNull { it.slug == selectedSlug }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clickable(
                onClick = { menuOpen = vehicles.isNotEmpty() },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
            Text(
                selected?.name ?: "(no vehicle)",
                style = MaterialTheme.typography.titleMedium,
            )
            lastOdometer?.let {
                Text(
                    "${"%,.0f".format(it)} mi",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            Icons.Filled.ArrowDropDown,
            contentDescription = "Switch vehicle",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            for (v in vehicles) {
                DropdownMenuItem(
                    text = {
                        Row {
                            Text(v.name)
                            if (!v.active) {
                                Text(
                                    "  archived",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    onClick = {
                        onSelect(v.slug)
                        menuOpen = false
                    },
                )
            }
        }
    }
}

/**
 * Gas-type dropdown — Fuelio's enum-ish codes (Regular 87, Premium, Diesel,
 * E85, ...). The label maps both ways: display label in the trigger,
 * label list in the menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FuelTypeDropdown(
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val label = FUEL_TYPES.firstOrNull { it.code == selected }?.label ?: "Regular (87)"
    Box(modifier = modifier) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Gas type") },
            trailingIcon = {
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                )
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open = true },
            colors = darkTextFieldColors(),
        )
        // Invisible click target on top of the OutlinedTextField — the
        // readOnly variant doesn't intercept taps reliably across all
        // versions, so we layer a Box that opens the menu.
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable { open = true },
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
        ) {
            for (ft in FUEL_TYPES) {
                DropdownMenuItem(
                    text = { Text(ft.label) },
                    onClick = {
                        onSelect(ft.code)
                        open = false
                    },
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
