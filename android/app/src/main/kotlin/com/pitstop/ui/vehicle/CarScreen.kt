package com.pitstop.ui.vehicle

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pitstop.domain.DtcGuide
import com.pitstop.domain.DueState
import com.pitstop.domain.Maintenance
import com.pitstop.domain.MaintenancePreset
import com.pitstop.domain.ReminderItem
import com.pitstop.http.DtcDto
import com.pitstop.http.ExpenseDto
import com.pitstop.ui.components.EmptyState
import com.pitstop.ui.components.LoadErrorState
import com.pitstop.ui.components.PitstopTopAppBar
import com.pitstop.ui.components.SeverityChip
import com.pitstop.ui.components.is24HourClock
import com.pitstop.ui.components.rememberPitstopListState
import com.pitstop.ui.history.CarSection
import com.pitstop.ui.history.HistoryListState
import com.pitstop.ui.history.HistoryViewModel
import com.pitstop.ui.history.ListHeaderLine
import com.pitstop.ui.history.RefreshInfo
import com.pitstop.ui.history.SectionHost
import com.pitstop.ui.history.SectionHostId
import com.pitstop.ui.history.TagChip
import com.pitstop.ui.history.listStates
import com.pitstop.ui.live.LiveScreen
import com.pitstop.ui.theme.LocalUnitSystem
import com.pitstop.ui.theme.ext
import com.pitstop.util.DateLabel
import com.pitstop.util.UnitFormat
import com.pitstop.util.requireActivity
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The Car tab: Live (the existing Live screen, every tile kept), Codes (the
 * trouble-code list with plain-language severity) and Service (reminders,
 * presets, history). Landscape on Live is still the full-screen drive mode.
 */
@Composable
fun CarScreen(
    onOpenBridgeStatus: () -> Unit,
    historyVm: HistoryViewModel = hiltViewModel(LocalContext.current.requireActivity()),
) {
    SectionHost(SectionHostId.Car, historyVm) { nav ->
        val section by historyVm.carSection.collectAsStateWithLifecycle()
        val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (landscape && section == CarSection.Live) {
            // Drive mode owns the whole screen, as the Live tab always did.
            LiveScreen(onOpenBridgeStatus = onOpenBridgeStatus)
            return@SectionHost
        }
        val serviceVm: ServiceViewModel = hiltViewModel(LocalContext.current.requireActivity())
        val ui by historyVm.ui.collectAsStateWithLifecycle()
        val service by serviceVm.ui.collectAsStateWithLifecycle()
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(Unit) { serviceVm.messages.collect { snackbar.showSnackbar(it) } }
        LaunchedEffect(section) {
            when (section) {
                CarSection.Codes -> historyVm.refreshIfStale()
                CarSection.Service -> serviceVm.refreshIfStale()
                CarSection.Live -> Unit
            }
        }
        CarContent(
            section = section,
            onSection = historyVm::selectCarSection,
            snackbarHostState = snackbar,
            live = { LiveScreen(onOpenBridgeStatus = onOpenBridgeStatus, showTopBar = false) },
            codes = {
                CodesList(
                    state = ui.dtcs,
                    lastRefresh = ui.lastRefresh,
                    onRefresh = { historyVm.refresh(forceNetwork = true) },
                    onOpen = nav.openDtc,
                )
            },
            service = {
                ServiceContent(
                    ui = service,
                    onRefresh = { serviceVm.load(force = true) },
                    onAskDone = serviceVm::askDone,
                    onMarkDone = serviceVm::markDone,
                    onAskPreset = serviceVm::askPreset,
                    onConfirmPreset = serviceVm::confirmPreset,
                )
            },
        )
    }
}

/** Stateless Car shell: shared top bar, section tabs, the section's body. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CarContent(
    section: CarSection,
    onSection: (CarSection) -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    live: @Composable () -> Unit = {},
    codes: @Composable () -> Unit = {},
    service: @Composable () -> Unit = {},
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { PitstopTopAppBar() },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            SecondaryTabRow(selectedTabIndex = section.ordinal) {
                for (s in CarSection.entries) {
                    Tab(selected = s == section, onClick = { onSection(s) }, text = { Text(s.label) })
                }
            }
            Box(Modifier.weight(1f)) {
                when (section) {
                    CarSection.Live -> live()
                    CarSection.Codes -> codes()
                    CarSection.Service -> service()
                }
            }
        }
    }
}

// ── Codes ────────────────────────────────────────────────────────────

/** Trouble codes, each with its plain-language title and severity. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CodesList(
    state: HistoryListState<DtcDto>,
    lastRefresh: RefreshInfo?,
    onRefresh: () -> Unit,
    onOpen: (code: String, vehicleId: String) -> Unit,
) {
    val is24h = is24HourClock()
    // Active first, then most recent.
    val rows = remember(state.data) {
        state.data.sortedWith(compareBy<DtcDto> { it.clearedAt != null }.thenByDescending { it.seenAt })
    }
    PullToRefreshBox(
        isRefreshing = state.loading && state.data.isNotEmpty(),
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            state = rememberPitstopListState(),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "header") {
                ListHeaderLine(
                    info = lastRefresh,
                    loading = state.loading,
                    failed = state.error != null,
                    itemCount = state.data.size,
                    noun = "code",
                    newCount = 0,
                    pendingDrives = 0,
                    syncing = false,
                    onSync = {},
                )
            }
            listStates(
                loading = state.loading,
                error = state.error,
                empty = state.data.isEmpty(),
                filteredEmpty = false,
                what = "trouble codes",
                emptyIcon = Icons.Outlined.CheckCircle,
                emptyTitle = "No trouble codes",
                emptyBody = "Nothing logged in the last year — clean bill of health.",
                onRetry = onRefresh,
            )
            items(rows, key = { it.id }) { dtc ->
                val guide = remember(dtc.code, dtc.description) { DtcGuide.lookup(dtc.code, dtc.description) }
                Card(
                    onClick = { onOpen(dtc.code, dtc.vehicleId) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                dtc.code,
                                style = MaterialTheme.typography.titleMedium,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(Modifier.size(8.dp))
                            if (dtc.clearedAt == null) {
                                SeverityChip(guide.severity)
                                Spacer(Modifier.size(6.dp))
                                TagChip("ACTIVE", MaterialTheme.colorScheme.onErrorContainer, MaterialTheme.colorScheme.errorContainer)
                            } else {
                                TagChip("CLEARED", MaterialTheme.ext.good, MaterialTheme.ext.goodContainer)
                            }
                            Spacer(Modifier.weight(1f))
                            Text(
                                DateLabel.list(dtc.seenAt, withTime = true, grouped = false, is24h = is24h),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(guide.title, style = MaterialTheme.typography.bodyMedium)
                        if (dtc.clearedAt == null) {
                            Text(
                                guide.safeToDrive,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            if (rows.any { it.clearedAt == null }) {
                item(key = "disclaimer") {
                    Text(
                        DtcGuide.DISCLAIMER,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }
    }
}

// ── Service ──────────────────────────────────────────────────────────

/** Reminders, the Scheduled list, presets when empty, and service history. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ServiceContent(
    ui: ServiceUi,
    onRefresh: () -> Unit = {},
    onAskDone: (String?) -> Unit = {},
    onMarkDone: (ReminderItem) -> Unit = {},
    onAskPreset: (MaintenancePreset?) -> Unit = {},
    onConfirmPreset: () -> Unit = {},
) {
    val system = LocalUnitSystem.current
    var showAll by rememberSaveable { mutableStateOf(false) }
    val dist: (Double?) -> String = { v -> vehicleDistance(v, ui.distInMiles, system) }
    PullToRefreshBox(
        isRefreshing = ui.loading && ui.vehicle != null,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                ui.loading && ui.vehicle == null -> Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                ui.error != null && ui.reminders.isEmpty() -> LoadErrorState(what = "service reminders", onRetry = onRefresh)
            }

            ui.stale?.let { st ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.ext.warnContainer, RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = MaterialTheme.ext.warn, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "Last service logged " +
                            (st.behind?.let { "${dist(it)} ago" } ?: "a while ago") +
                            " (${monthYear(st.lastDate)})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.ext.warn,
                    )
                }
            }

            if (ui.reminders.isNotEmpty()) {
                SectionTitle("Reminders")
                for (r in ui.reminders) {
                    ReminderCard(
                        item = r,
                        state = Maintenance.dueState(r, ui.distInMiles),
                        progress = ui.progress[r.expenseId],
                        dist = dist,
                        confirming = ui.confirmingDoneId == r.expenseId,
                        busy = ui.busyId == r.expenseId,
                        onAsk = { onAskDone(r.expenseId) },
                        onCancel = { onAskDone(null) },
                        onConfirm = { onMarkDone(r) },
                    )
                }
            }

            if (ui.scheduled.isNotEmpty()) {
                SectionTitle("Scheduled")
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        ui.scheduled.forEachIndexed { i, e ->
                            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(e.title ?: "Service", style = MaterialTheme.typography.titleSmall)
                                    everyLabel(e, dist)?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Text(
                                    remindLabel(e, dist),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            if (ui.showPresets) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Build, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.size(8.dp))
                            Text("No active reminders", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                        }
                        Text(
                            "Start one from today's odometer" + (ui.currentOdo?.let { " (${dist(it)})" } ?: "") + ":",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (p in Maintenance.PRESETS) {
                                AssistChip(
                                    onClick = { onAskPreset(p) },
                                    enabled = !ui.presetBusy,
                                    leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                    label = { Text("${p.title} · ${presetInterval(p, ui.distInMiles, system)}") },
                                )
                            }
                        }
                        ui.pendingPreset?.let { p ->
                            val interval = if (ui.distInMiles) p.miles else p.km
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    "Remind ${p.title} every ${presetInterval(p, ui.distInMiles, system)}" +
                                        (ui.currentOdo?.let { " — first due at ${dist(it + interval)}" } ?: ""),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                                    TextButton(onClick = { onAskPreset(null) }, enabled = !ui.presetBusy) { Text("Cancel") }
                                    Button(onClick = onConfirmPreset, enabled = !ui.presetBusy) {
                                        Text(if (ui.presetBusy) "Creating…" else "Create reminder")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            SectionTitle("Service history")
            if (ui.history.isEmpty() && !ui.loading) {
                EmptyState(
                    icon = Icons.Outlined.Build,
                    title = "No services logged yet",
                    body = "Services logged on the web or imported from Fuelio show up here.",
                )
            } else {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        val visible = if (showAll) ui.history else ui.history.take(10)
                        visible.forEachIndexed { i, e ->
                            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            HistoryRow(e, ui.categories, dist)
                        }
                        if (ui.history.size > 10) {
                            TextButton(onClick = { showAll = !showAll }) {
                                Text(if (showAll) "Show fewer" else "Show all ${ui.history.size}")
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .semantics { heading() },
    )
}

@Composable
private fun ReminderCard(
    item: ReminderItem,
    state: DueState,
    progress: Double?,
    dist: (Double?) -> String,
    confirming: Boolean,
    busy: Boolean,
    onAsk: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val (fg, bg, label) = when (state) {
        DueState.Overdue -> Triple(MaterialTheme.ext.bad, MaterialTheme.ext.badContainer, "Overdue")
        DueState.DueSoon -> Triple(MaterialTheme.ext.warn, MaterialTheme.ext.warnContainer, "Due soon")
        DueState.Ok -> Triple(MaterialTheme.ext.good, MaterialTheme.ext.goodContainer, "OK")
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium)
                    item.category?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                TagChip(label, fg, bg)
            }
            DueBar(fraction = progress ?: if (state == DueState.Overdue) 1.0 else null, color = fg)
            Text(
                listOfNotNull(
                    deltaLabel(item, dist),
                    item.remindOdo?.let { "due at ${dist(it)}" },
                    item.remindDate?.let { shortDate(it) },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (confirming) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        "Log it as done today at the current odometer and schedule the next one?",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                        TextButton(onClick = onCancel) { Text("Cancel") }
                        Button(onClick = onConfirm) { Text("Mark done") }
                    }
                }
            } else {
                OutlinedButton(onClick = onAsk, enabled = !busy, modifier = Modifier.align(Alignment.End)) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.size(6.dp))
                    Text("Mark done")
                }
            }
        }
    }
}

/** Interval-used bar; an unknown start shows an empty track, not a guess. */
@Composable
private fun DueBar(fraction: Double?, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        if (fraction != null) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.toFloat().coerceIn(0.02f, 1f))
                    .height(6.dp)
                    .background(color),
            )
        }
    }
}

@Composable
private fun HistoryRow(e: ExpenseDto, categories: Map<Int, String>, dist: (Double?) -> String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.title ?: "Service", style = MaterialTheme.typography.titleSmall)
                if (Maintenance.isPresetAnchor(e)) {
                    Spacer(Modifier.size(6.dp))
                    TagChip("reminder start", MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceContainerHighest)
                }
            }
            Text(
                listOfNotNull(
                    shortDate(e.expenseDate),
                    e.costTypeId?.let { categories[it] },
                    e.odo?.let { dist(it) },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(UnitFormat.money(e.costValue), style = MaterialTheme.typography.titleSmall)
    }
}

/** A distance stored in the vehicle's unit, shown in the user's. */
internal fun vehicleDistance(v: Double?, distInMiles: Boolean, system: String): String {
    val q = if (distInMiles) UnitFormat.Quantity.DistanceMi else UnitFormat.Quantity.DistanceKm
    val n = q.number(v, system, 0)
    if (n == "—") return n
    val grouped = n.toDoubleOrNull()?.let { "%,.0f".format(it) } ?: n
    return "$grouped ${q.unit(system)}"
}

private fun presetInterval(p: MaintenancePreset, distInMiles: Boolean, system: String): String {
    val every = vehicleDistance(if (distInMiles) p.miles else p.km, distInMiles, system)
    return if (p.months != null) "$every / ${p.months} mo" else every
}

private fun deltaLabel(r: ReminderItem, dist: (Double?) -> String): String? = listOfNotNull(
    r.distanceRemaining?.let { if (it >= 0) "${dist(it)} left" else "${dist(-it)} over" },
    r.daysRemaining?.let { if (it >= 0) "$it d left" else "${-it} d over" },
).joinToString(" · ").ifEmpty { null }

private fun everyLabel(e: ExpenseDto, dist: (Double?) -> String): String? {
    val parts = listOfNotNull(
        e.repeatOdo?.takeIf { it > 0 }?.let { dist(it) },
        e.repeatMonths?.takeIf { it > 0 }?.let { "${it.toInt()} mo" },
    )
    return if (parts.isEmpty()) null else "every " + parts.joinToString(" / ")
}

private fun remindLabel(e: ExpenseDto, dist: (Double?) -> String): String = listOfNotNull(
    e.remindOdo?.takeIf { it > 0 }?.let { "due at ${dist(it)}" },
    e.remindDate?.takeIf { it >= e.expenseDate.take(10) }?.let { shortDate(it) },
).joinToString(" · ")

private fun shortDate(iso: String): String {
    val d = runCatching { LocalDate.parse(iso.take(10)) }.getOrNull() ?: return iso
    val pattern = if (d.year == LocalDate.now().year) "MMM d" else "MMM d, yyyy"
    return d.format(DateTimeFormatter.ofPattern(pattern))
}

private fun monthYear(iso: String): String =
    runCatching { LocalDate.parse(iso.take(10)).format(DateTimeFormatter.ofPattern("MMM yyyy")) }.getOrDefault(iso)
