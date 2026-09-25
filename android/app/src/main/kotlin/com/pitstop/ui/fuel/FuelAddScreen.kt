package com.pitstop.ui.fuel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pitstop.ui.components.DetailTopAppBar
import com.pitstop.ui.components.PitstopTopAppBar
import com.pitstop.ui.components.rememberPitstopListState
import com.pitstop.ui.theme.LocalUnitSystem
import com.pitstop.util.UnitFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Add-fillup form, laid out after the Fuelio reference:
 *
 *  - Vehicle row up top (name + last odo + chevron to switch)
 *  - Outlined fields with leading icons:
 *      Speedometer  →  Odometer (+N since last / error below last)
 *      Fuel pump    →  Volume  | Gas type
 *      Dollar       →  Price   | Total cost
 *      Calendar     →  Date    | Time   (real pickers; default "now")
 *  - Switches: Full tank, Missed previous fillup
 *  - Station: current location, name field, then ONE row of suggestion
 *    chips (the nearest prior station first, then name matches)
 *  - One pinned "Save fillup" button, enabled once the form is valid
 *
 * Every label carries the unit for the user's system (gal / L, mi / km,
 * price per gal / L). Field problems show on the field (isError +
 * supportingText); only a server failure goes to the snackbar.
 *
 * Reused in edit mode from Fillup detail (History route
 * fillup/{editId}/edit): [onBack] non-null switches the brand bar for a
 * back-arrow "Edit fillup" bar, and [onSaved] fires after the PATCH.
 *
 * Bidirectional auto-fill: editing any of {volume, price, total}
 * updates the third (handled in FuelAddViewModel.set* methods).
 */
@Composable
fun FuelAddScreen(
    onBack: (() -> Unit)? = null,
    onSaved: () -> Unit = {},
    viewModel: FuelAddViewModel = hiltViewModel(),
) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(form.errorMessage) {
        form.errorMessage?.let { snackbarHost.showSnackbar(it) }
    }
    LaunchedEffect(form.submittedId) {
        if (form.submittedId == null) return@LaunchedEffect
        if (form.editingId != null) onSaved() else snackbarHost.showSnackbar("Fillup saved")
    }

    FuelAddContent(
        form = form,
        snackbarHost = snackbarHost,
        onBack = onBack,
        onSelectVehicle = viewModel::selectVehicle,
        onOdometer = { v -> viewModel.update { it.copy(odometer = v, odometerAutoFilled = false) } },
        onVolume = viewModel::setVolume,
        onFuelType = viewModel::selectFuelType,
        onPricePerVolume = viewModel::setPricePerVolume,
        onTotal = viewModel::setTotalPrice,
        onDateTime = viewModel::setDateTime,
        onPartial = { p -> viewModel.update { it.copy(partial = p) } },
        onMissed = { m -> viewModel.update { it.copy(isMissed = m) } },
        onNotes = { n -> viewModel.update { it.copy(notes = n) } },
        onStation = { s -> viewModel.update { it.copy(stationName = s) } },
        onRefreshGps = viewModel::refreshGps,
        onSubmit = viewModel::submit,
    )
}

/** Stateless body of [FuelAddScreen], rendered by screenshot tests. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FuelAddContent(
    form: FuelFormState,
    snackbarHost: SnackbarHostState = remember { SnackbarHostState() },
    onBack: (() -> Unit)? = null,
    onSelectVehicle: (String) -> Unit = {},
    onOdometer: (String) -> Unit = {},
    onVolume: (String) -> Unit = {},
    onFuelType: (Int) -> Unit = {},
    onPricePerVolume: (String) -> Unit = {},
    onTotal: (String) -> Unit = {},
    onDateTime: (LocalDateTime?) -> Unit = {},
    onPartial: (Boolean) -> Unit = {},
    onMissed: (Boolean) -> Unit = {},
    onNotes: (String) -> Unit = {},
    onStation: (String) -> Unit = {},
    onRefreshGps: () -> Unit = {},
    onSubmit: () -> Unit = {},
) {
    val system = LocalUnitSystem.current
    val volUnit = UnitFormat.Quantity.VolumeGal.unit(system)
    val distUnit = UnitFormat.Quantity.DistanceMi.unit(system)
    val errors = validateFuelForm(form, distUnit, volUnit)
    // An untouched empty field doesn't nag; it shows its error once the
    // user has typed there or tried to save.
    fun shown(err: String?, value: String) = err?.takeIf { form.showAllErrors || value.isNotBlank() }
    val focus = LocalFocusManager.current
    val next = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next)

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            if (onBack != null) {
                DetailTopAppBar(title = "Edit fillup", onBack = onBack)
            } else {
                PitstopTopAppBar()
            }
        },
        // The one Save. Pinned so it is reachable with the keyboard up
        // and never scrolled away; enabled only when the form is valid.
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Button(
                    onClick = onSubmit,
                    enabled = !errors.any && !form.submitting && !form.loadingEdit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .imePadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .height(48.dp),
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
                        Text(if (form.editingId != null) "Save changes" else "Save fillup")
                    }
                }
            }
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
                distUnit = distUnit,
                enabled = form.editingId == null,
                onSelect = onSelectVehicle,
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Odometer, with the distance since the last fillup as its
                // supporting line — the check a driver does in their head.
                val odoErr = shown(errors.odometer, form.odometer)
                val sinceLast = form.odometer.toDoubleOrNull()?.let { o ->
                    form.lastOdometer?.let { last -> o - last }
                }
                OutlinedTextField(
                    value = form.odometer,
                    onValueChange = onOdometer,
                    label = { Text("Odometer ($distUnit)") },
                    leadingIcon = { Icon(Icons.Filled.Speed, contentDescription = null) },
                    singleLine = true,
                    isError = odoErr != null,
                    supportingText = {
                        when {
                            odoErr != null -> Text(odoErr)
                            sinceLast != null && sinceLast >= 0 ->
                                Text("+${"%,.0f".format(sinceLast)} $distUnit since last fillup")
                            form.lastOdometer != null ->
                                Text("Last fillup: ${"%,.0f".format(form.lastOdometer)} $distUnit")
                        }
                    },
                    keyboardOptions = next,
                    modifier = Modifier.fillMaxWidth(),
                    colors = darkTextFieldColors(),
                )

                // Volume | Gas type
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val volErr = shown(errors.volume, form.volume)
                    OutlinedTextField(
                        value = form.volume,
                        onValueChange = onVolume,
                        label = { Text("Fuel ($volUnit)") },
                        leadingIcon = { Icon(Icons.Filled.LocalGasStation, contentDescription = null) },
                        singleLine = true,
                        isError = volErr != null,
                        supportingText = volErr?.let { { Text(it) } },
                        keyboardOptions = next,
                        modifier = Modifier.weight(1f),
                        colors = darkTextFieldColors(),
                    )
                    FuelTypeDropdown(
                        selected = form.fuelType,
                        onSelect = onFuelType,
                        modifier = Modifier.weight(1f),
                    )
                }

                // Price per volume | Total cost
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = form.pricePerVolume,
                        onValueChange = onPricePerVolume,
                        label = { Text("Price${UnitFormat.perVolumeUnit(system)}") },
                        leadingIcon = { Icon(Icons.Filled.AttachMoney, contentDescription = null) },
                        singleLine = true,
                        keyboardOptions = next,
                        modifier = Modifier.weight(1f),
                        colors = darkTextFieldColors(),
                    )
                    val totalErr = shown(errors.total, form.totalPrice)
                    OutlinedTextField(
                        value = form.totalPrice,
                        onValueChange = onTotal,
                        label = { Text("Total cost") },
                        singleLine = true,
                        isError = totalErr != null,
                        supportingText = totalErr?.let { { Text(it) } },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Decimal,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                        modifier = Modifier.weight(1f),
                        colors = darkTextFieldColors(),
                    )
                }

                DateTimeRow(value = form.dateTime, onChange = onDateTime)

                ToggleRow(label = "Full tank", checked = !form.partial, onCheckedChange = { onPartial(!it) })
                // Missed previous fillup — flag retroactive entries so the
                // MPG recompute skips them (matches Fuelio's chain rule).
                ToggleRow(label = "Missed previous fillup", checked = form.isMissed, onCheckedChange = onMissed)

                // Notes — trip context, gas brand, weather, … shipped
                // verbatim to the backend's fillup row.
                OutlinedTextField(
                    value = form.notes,
                    onValueChange = onNotes,
                    label = { Text("Notes") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    colors = darkTextFieldColors(),
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }

            // ── Station ────────────────────────────────────────────────
            // Edit mode leaves the location alone: it was captured at the
            // pump, and re-locating from the sofa would move it.
            if (form.editingId == null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Station", style = MaterialTheme.typography.titleMedium)
                    StationLocationRow(
                        coords = form.gps?.let { "%.4f, %.4f".format(it.lat, it.lon) }
                            ?: if (form.gpsRefreshing) "Locating…" else "No location fix yet",
                        onRefresh = onRefreshGps,
                    )
                    OutlinedTextField(
                        value = form.stationName,
                        onValueChange = onStation,
                        label = { Text("Station") },
                        placeholder = { Text("Station name") },
                        leadingIcon = { Icon(Icons.Filled.Storefront, contentDescription = null) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Words,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
                        modifier = Modifier.fillMaxWidth(),
                        colors = darkTextFieldColors(),
                    )
                    // Nearest prior station first, then name matches — one
                    // horizontally scrolling row instead of a chip and a
                    // separate fixed row that clipped the fourth name.
                    val nearest = form.nearestPriorStation
                    val chips = buildList {
                        if (nearest != null) add(nearest)
                        addAll(form.stationSuggestions.filter { it != nearest })
                    }.filter { it != form.stationName }
                    if (chips.isNotEmpty()) {
                        LazyRow(state = rememberPitstopListState(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(chips) { s ->
                                SuggestionChip(
                                    onClick = { onStation(s) },
                                    icon = if (s == nearest) {
                                        { Icon(Icons.Outlined.NearMe, contentDescription = "Nearby", modifier = Modifier.size(16.dp)) }
                                    } else {
                                        null
                                    },
                                    label = { Text(s, maxLines = 1) },
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Date + Time fields. Read-only text fields that open the M3 pickers; the
 * value defaults to "Now" (resolved at save time) until the user picks —
 * so a form left open for ten minutes still stamps the real pump time
 * unless it was deliberately back-dated.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimeRow(value: LocalDateTime?, onChange: (LocalDateTime?) -> Unit) {
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    val effective = value ?: LocalDateTime.now()
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PickerField(
            label = "Date",
            text = if (value == null) "Today" else effective.format(DateTimeFormatter.ofPattern("EEE, MMM d")),
            icon = { Icon(Icons.Filled.CalendarMonth, contentDescription = null) },
            onClick = { pickDate = true },
            modifier = Modifier.weight(1f),
        )
        PickerField(
            label = "Time",
            text = if (value == null) "Now" else effective.format(DateTimeFormatter.ofPattern("h:mm a")),
            icon = { Icon(Icons.Filled.Schedule, contentDescription = null) },
            onClick = { pickTime = true },
            modifier = Modifier.weight(1f),
        )
    }
    if (pickDate) {
        // DatePicker works in UTC-midnight millis for the selected DAY.
        val state = rememberDatePickerState(
            initialSelectedDateMillis = effective.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : androidx.compose.material3.SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    utcTimeMillis <= LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            },
        )
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        val day = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                        onChange(LocalDateTime.of(day, effective.toLocalTime()))
                    }
                    pickDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }
    if (pickTime) {
        val state = rememberTimePickerState(
            initialHour = effective.hour,
            initialMinute = effective.minute,
        )
        AlertDialog(
            onDismissRequest = { pickTime = false },
            confirmButton = {
                TextButton(onClick = {
                    onChange(LocalDateTime.of(effective.toLocalDate(), LocalTime.of(state.hour, state.minute)))
                    pickTime = false
                }) { Text("OK") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        onChange(null)
                        pickTime = false
                    }) { Text("Now") }
                    TextButton(onClick = { pickTime = false }) { Text("Cancel") }
                }
            },
            text = { TimePicker(state = state) },
        )
    }
}

/** Read-only field that behaves like a button (opens a picker). */
@Composable
private fun PickerField(
    label: String,
    text: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        OutlinedTextField(
            value = text,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            leadingIcon = icon,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = darkTextFieldColors(),
        )
        // A read-only text field swallows taps without opening anything;
        // this overlay makes the whole field the button.
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(role = Role.Button, onClickLabel = "Change $label") { onClick() }
                .semantics { contentDescription = "$label: $text" },
        )
    }
}

/**
 * Vehicle row + picker dropdown. Renders the selected vehicle's name +
 * last odo by default; tapping opens a DropdownMenu with every vehicle
 * from /api/vehicles, marking inactive ones "archived". Switching scopes
 * the pending fillup to that vehicle without touching the bridge config.
 */
@Composable
private fun VehiclePickerRow(
    vehicles: List<VehicleOption>,
    selectedSlug: String,
    lastOdometer: Double?,
    distUnit: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val selected = vehicles.firstOrNull { it.slug == selectedSlug }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled && vehicles.isNotEmpty(), onClickLabel = "Switch vehicle") {
                menuOpen = true
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
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
                    "${"%,.0f".format(it)} $distUnit at last fillup",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (enabled) {
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
 * E85, ...).
 */
@Composable
private fun FuelTypeDropdown(
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val label = FUEL_TYPES.firstOrNull { it.code == selected }?.label ?: "Regular (87)"
    Box(modifier = modifier) {
        PickerField(
            label = "Gas type",
            text = label,
            icon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
            onClick = { open = true },
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
            .padding(vertical = 2.dp),
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
            Icon(Icons.Filled.Refresh, contentDescription = "Refresh location")
        }
    }
}

/**
 * Shared OutlinedTextField colour set tuned for the dark theme. The
 * Material 3 default uses a too-bright surface tint on the unfocused
 * container; we drop to surfaceContainerHigh to match the Fuelio
 * reference's flat dark inputs.
 */
@Composable
private fun darkTextFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    focusedIndicatorColor = MaterialTheme.colorScheme.primary,
    unfocusedIndicatorColor = MaterialTheme.colorScheme.outlineVariant,
)
