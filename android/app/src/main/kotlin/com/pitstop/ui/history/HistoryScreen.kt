package com.pitstop.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pitstop.http.DtcDto
import com.pitstop.http.FillupDto
import com.pitstop.http.TripDto
import com.pitstop.ui.components.PitstopTopAppBar
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val pendingCount by viewModel.pendingCount.collectAsStateWithLifecycle()
    var selectedTab by remember { mutableStateOf(0) }

    Scaffold(topBar = { PitstopTopAppBar() }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Pending drive upload queue — appears only when there's
            // something to ship. Surfaces queue size + "Sync now"
            // button so the user knows what's parked locally.
            if (pendingCount > 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "$pendingCount drive${if (pendingCount == 1) "" else "s"} pending upload",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    AssistChip(
                        onClick = { viewModel.syncNow() },
                        label = { Text("Sync now") },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    )
                }
            }
            SecondaryTabRow(selectedTabIndex = selectedTab) {
                listOf("Trips", "Fillups", "DTCs").forEachIndexed { i, label ->
                    Tab(
                        selected = selectedTab == i,
                        onClick = { selectedTab = i },
                        text = { Text(label) },
                    )
                }
            }
            when (selectedTab) {
                0 -> TripsList(state = ui.trips, onRefresh = viewModel::refresh)
                1 -> FillupsList(state = ui.fillups, onRefresh = viewModel::refresh)
                2 -> DtcsList(state = ui.dtcs, onRefresh = viewModel::refresh)
            }
        }
    }
}

@Composable
private fun <T> ListSurface(
    state: HistoryListState<T>,
    onRefresh: () -> Unit,
    emptyMessage: String,
    item: @Composable (T) -> Unit,
) {
    when {
        state.loading && state.data.isEmpty() -> CenteredSpinner()
        state.error != null && state.data.isEmpty() ->
            CenteredText("Couldn't load: ${state.error}")
        state.data.isEmpty() -> CenteredText(emptyMessage)
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.data, key = { keyOf(it) }) { item(it) }
        }
    }
}

private fun <T> keyOf(value: T): Any = when (value) {
    is TripDto -> value.id
    is FillupDto -> value.id
    is DtcDto -> value.id
    else -> value as Any
}

@Composable
private fun TripsList(state: HistoryListState<TripDto>, onRefresh: () -> Unit) {
    ListSurface(state = state, onRefresh = onRefresh, emptyMessage = "No trips yet — take a drive.") { trip ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = formatTripDate(trip.startedAt),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    val mi = trip.distanceKm?.let { it * 0.621371 }
                    Text(
                        text = mi?.let { "%.1f mi".format(it) } ?: "—",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                val parts = buildList {
                    trip.durationS?.let {
                        add(if (it >= 60) "${it / 60}m ${it % 60}s" else "${it}s")
                    }
                    trip.maxSpeedKph?.let { add("max %.0f mph".format(it * 0.621371)) }
                    trip.maxRpm?.let { add("%.0f rpm".format(it)) }
                    if (trip.dtcCount > 0) add("${trip.dtcCount} DTC")
                }
                if (parts.isNotEmpty()) {
                    Text(
                        text = parts.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun FillupsList(state: HistoryListState<FillupDto>, onRefresh: () -> Unit) {
    ListSurface(state = state, onRefresh = onRefresh, emptyMessage = "No fillups yet.") { f ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = formatFillupDate(f.fillupDate),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = f.priceTotal?.let { "$%.2f".format(it) } ?: "—",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                val parts = buildList {
                    f.fuelVolume?.let { add("%.2f gal".format(it)) }
                    f.pricePerUnit?.let { add("$%.3f/gal".format(it)) }
                    f.mpg?.let { add("%.1f mpg".format(it)) }
                    f.city?.let { add(it) }
                }
                if (parts.isNotEmpty()) {
                    Text(
                        text = parts.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DtcsList(state: HistoryListState<DtcDto>, onRefresh: () -> Unit) {
    ListSurface(state = state, onRefresh = onRefresh, emptyMessage = "No active DTCs — clean bill of health.") { dtc ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = dtc.code,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = formatTripDate(dtc.seenAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                dtc.description?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CenteredSpinner() {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.size(48.dp))
        CircularProgressIndicator()
    }
}

@Composable
private fun CenteredText(text: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// Backend serves timestamps in UTC; convert to the device's local zone
// before formatting so a trip at 20:32Z renders as "3:32PM" in CDT, not
// "8:32PM". Without the withZoneSameInstant() step OffsetDateTime keeps
// its parsed offset and formats the raw UTC fields.
private val LOCAL_ZONE: ZoneId = ZoneId.systemDefault()

private fun formatTripDate(iso: String): String =
    runCatching {
        OffsetDateTime.parse(iso)
            .atZoneSameInstant(LOCAL_ZONE)
            .format(DateTimeFormatter.ofPattern("MMM d, h:mma"))
    }.getOrDefault(iso.take(16))

private fun formatFillupDate(iso: String): String =
    runCatching {
        OffsetDateTime.parse(iso)
            .atZoneSameInstant(LOCAL_ZONE)
            .format(DateTimeFormatter.ofPattern("MMM d"))
    }.getOrElse { iso.take(10) }
