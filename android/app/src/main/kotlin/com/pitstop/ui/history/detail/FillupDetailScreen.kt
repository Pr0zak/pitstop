package com.pitstop.ui.history.detail

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pitstop.http.FillupDto
import com.pitstop.ui.components.DetailTopAppBar
import com.pitstop.ui.components.is24HourClock
import com.pitstop.ui.components.LoadErrorState
import com.pitstop.ui.components.OverflowAction
import com.pitstop.ui.theme.LocalUnitSystem
import com.pitstop.ui.theme.ext
import com.pitstop.util.DateLabel
import com.pitstop.util.UnitFormat
import java.time.OffsetDateTime

@Composable
fun FillupDetailScreen(
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FillupDetailViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(ui.deleted) { if (ui.deleted) onDeleted() }
    LaunchedEffect(ui.message) {
        ui.message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }
    val fillup = ui.fillup
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            DetailTopAppBar(
                title = fillup?.let { DateLabel.detailTitle(it.fillupDate, is24HourClock()) } ?: "Fillup",
                onBack = onBack,
                overflow = if (fillup == null) emptyList() else listOf(
                    OverflowAction("Edit fillup", Icons.Filled.Edit, onClick = onEdit),
                    OverflowAction("Delete fillup", Icons.Filled.Delete, destructive = true) {
                        confirmDelete = true
                    },
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        modifier = modifier,
    ) { padding ->
        val inner = Modifier.padding(padding)
        when {
            ui.loading && fillup == null -> Box(inner.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            fillup == null -> LoadErrorState(what = "this fillup", onRetry = viewModel::refresh, modifier = inner)
            else -> FillupDetailContent(fillup = fillup, context = ui.context, modifier = inner)
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this fillup?") },
            text = { Text("Economy for this tank and the next is recalculated. This can't be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        viewModel.delete()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/** Stateless body of [FillupDetailScreen], rendered by screenshot tests. */
@Composable
internal fun FillupDetailContent(
    fillup: FillupDto,
    context: List<FillupDto>,
    modifier: Modifier = Modifier,
) {
    val system = LocalUnitSystem.current
    val ctx = LocalContext.current
    val gallons = fillup.fuelVolume
    val total = fillup.priceTotal
    val ppg = fillup.pricePerUnit
        ?: if (gallons != null && total != null && gallons > 0) total / gallons else null
    // Trust the server's recomputed mpg; fall back to Fuelio's reported one.
    val mpg = fillup.mpg ?: fillup.mpgReported

    // Cost per mile since the previous fillup, computed from the
    // context list (sorted newest-first).
    val costPerMile = remember(fillup, context) {
        computeCostPerMile(fillup, context)
    }
    // Deltas vs this vehicle's recent average — the only coloured numbers
    // on the card (the hero itself is a neutral surface).
    val deltas = remember(fillup, context) { fillupDeltas(fillup, context) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Hero: one big total, then the four per-fill figures on one row.
        // A normal surface, not a tinted one: colour is reserved for the
        // two comparisons (economy and price vs your average).
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(Modifier.semantics(mergeDescendants = true) {}) {
                    Text(
                        "Total",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        UnitFormat.money(total),
                        style = MaterialTheme.typography.displayMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HeroCell(
                        label = if (system == "imperial") "Gallons" else "Litres",
                        value = UnitFormat.Quantity.VolumeGal.number(gallons, system, 2),
                        modifier = Modifier.weight(1f),
                    )
                    HeroCell(
                        label = "Price${UnitFormat.perVolumeUnit(system)}",
                        value = UnitFormat.money(UnitFormat.pricePerVolumeValue(ppg, system), 3),
                        // Paying more than usual is the bad direction.
                        delta = deltas.pricePct?.let { DeltaText(it, good = it < 0) },
                        modifier = Modifier.weight(1f),
                    )
                    HeroCell(
                        label = if (system == "imperial") "MPG" else "L/100km",
                        value = UnitFormat.economyNumber(mpg, system),
                        // Arrow follows the DISPLAYED number (L/100 km falls when
                        // economy improves); colour follows better / worse.
                        delta = deltas.mpgPct?.let { d ->
                            DeltaText(if (UnitFormat.economyHigherIsBetter(system)) d else -d, good = d > 0)
                        },
                        modifier = Modifier.weight(1f),
                    )
                    HeroCell(
                        label = "Cost${UnitFormat.perDistanceUnit(system)}",
                        value = UnitFormat.money(UnitFormat.costPerDistanceValue(costPerMile, system), 3),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // Facts that don't fit the hero.
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                val rows = buildList<Pair<String, String>> {
                    add("Odometer" to UnitFormat.odometerMi(fillup.odo, system))
                    add("Tank" to if (fillup.isFull) "Full" else "Partial")
                    if (fillup.isMissed) add("Note" to "Marked as missed previous fillup")
                    fillup.fuelType?.let {
                        add("Fuel type" to (FUEL_TYPE_LABELS[it] ?: "Type $it"))
                    }
                    if (fillup.weatherTempC != null) {
                        val t = UnitFormat.Quantity.TempC.format(fillup.weatherTempC, system, 0)
                        val wmo = wmoLabel(fillup.weatherCode)
                        add("Weather" to "$t${wmo?.let { ", $it" } ?: ""}")
                    }
                    fillup.notes?.takeIf { it.isNotBlank() }?.let { add("Notes" to it) }
                }
                for ((i, kv) in rows.withIndex()) {
                    if (i > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 6.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            kv.first,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            kv.second,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                // Location opens the maps app at the pump. A row, not raw
                // coordinates: "Columbus · 40.0, -83.0" is for machines.
                if (fillup.lat != null && fillup.lon != null) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 6.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    val label = fillup.city?.takeIf { it.isNotBlank() } ?: "Fillup location"
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable(onClickLabel = "Open in maps") {
                                val uri = android.net.Uri.parse(
                                    "geo:${fillup.lat},${fillup.lon}?q=${fillup.lat},${fillup.lon}(" +
                                        android.net.Uri.encode(label) + ")",
                                )
                                runCatching {
                                    ctx.startActivity(
                                        android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.Place,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            label,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    fillup.city?.takeIf { it.isNotBlank() }?.let {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 6.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                        Text(it, style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
        }

        // Economy trend chart. Only render when at least 3 fills have a
        // valid mpg value — fewer than that makes for a noisy line.
        val mpgSeries = remember(context, system) {
            context
                .mapNotNull { f ->
                    val m = UnitFormat.economyValue(f.mpg, system) ?: return@mapNotNull null
                    val tMs = runCatching { OffsetDateTime.parse(f.fillupDate).toInstant().toEpochMilli() }
                        .getOrNull() ?: return@mapNotNull null
                    TimedPoint(tMs, m)
                }
                .sortedBy { it.tMillis }
        }
        if (mpgSeries.size >= 3) {
            val currentMs = runCatching {
                OffsetDateTime.parse(fillup.fillupDate).toInstant().toEpochMilli()
            }.getOrNull()
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "${if (system == "imperial") "MPG" else "L/100 km"} trend (last ${mpgSeries.size} fills)",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() },
                    )
                    MpgTrendChart(
                        series = mpgSeries,
                        highlightMillis = currentMs,
                        accent = MaterialTheme.colorScheme.primary,
                        grid = MaterialTheme.colorScheme.outlineVariant,
                        description = "Economy over the last ${mpgSeries.size} fills, from " +
                            "${"%.1f".format(mpgSeries.minOf { it.value })} to " +
                            "${"%.1f".format(mpgSeries.maxOf { it.value })} ${UnitFormat.economyUnit(system)}",
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

private fun computeCostPerMile(
    fillup: FillupDto,
    context: List<FillupDto>,
): Double? {
    val total = fillup.priceTotal ?: return null
    // Sort newest-first; find the entry immediately older than this
    // one. Use odometer diff to compute miles since.
    val sorted = context.sortedByDescending { it.fillupDate }
    val idx = sorted.indexOfFirst { it.id == fillup.id }
    val prevOdo = when {
        idx >= 0 && idx + 1 < sorted.size -> sorted[idx + 1].odo
        else -> sorted.firstOrNull { it.id != fillup.id && it.odo < fillup.odo }?.odo
    } ?: return null
    val miles = fillup.odo - prevOdo
    return if (miles > 0) total / miles else null
}

/** A signed change for a hero cell: [pct] drives the arrow, [good] the colour. */
private data class DeltaText(val pct: Double, val good: Boolean)

/** Percent deltas vs the average of the OTHER fills in [context]. */
internal data class FillupDeltas(val mpgPct: Double?, val pricePct: Double?)

internal fun fillupDeltas(fillup: FillupDto, context: List<FillupDto>): FillupDeltas {
    val others = context.filter { it.id != fillup.id }
    val mpg = fillup.mpg ?: fillup.mpgReported
    val avgMpg = others.filter { it.isFull && !it.isMissed }.mapNotNull { it.mpg }.filter { it > 0 }
        .takeIf { it.isNotEmpty() }?.average()
    val ppg = fillup.pricePerUnit
    val avgPpg = others.mapNotNull { it.pricePerUnit }.filter { it > 0 }.takeIf { it.isNotEmpty() }?.average()
    return FillupDeltas(
        mpgPct = if (mpg != null && avgMpg != null) (mpg - avgMpg) / avgMpg * 100.0 else null,
        pricePct = if (ppg != null && avgPpg != null) (ppg - avgPpg) / avgPpg * 100.0 else null,
    )
}

@Composable
private fun HeroCell(label: String, value: String, modifier: Modifier = Modifier, delta: DeltaText? = null) {
    Column(modifier = modifier.semantics(mergeDescendants = true) {}) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        if (delta != null && kotlin.math.abs(delta.pct) >= 0.5) {
            Text(
                "${if (delta.pct > 0) "▲" else "▼"} ${"%.0f".format(kotlin.math.abs(delta.pct))}% vs avg",
                style = MaterialTheme.typography.labelSmall,
                color = if (delta.good) MaterialTheme.ext.good else MaterialTheme.ext.bad,
                maxLines = 1,
            )
        }
    }
}

/**
 * Specialised mini chart: one MPG series with a highlight dot for the
 * currently-viewed fillup. Simpler than the trip-detail line chart
 * (fixed Y range tied to the data's own min/max, no chip toggles).
 */
@Composable
private fun MpgTrendChart(
    series: List<TimedPoint>,
    highlightMillis: Long?,
    accent: Color,
    grid: Color,
    description: String,
) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .semantics { contentDescription = description },
    ) {
        val w = size.width
        val h = size.height
        val left = 12f
        val right = w - 12f
        val top = 12f
        val bottom = h - 24f
        val plotW = (right - left).coerceAtLeast(1f)
        val plotH = (bottom - top).coerceAtLeast(1f)

        drawLine(
            color = grid,
            start = Offset(left, bottom),
            end = Offset(right, bottom),
            strokeWidth = 1f,
        )

        if (series.size < 2) return@Canvas
        val tMin = series.first().tMillis.toDouble()
        val tMax = series.last().tMillis.toDouble()
        val tRange = (tMax - tMin).coerceAtLeast(1.0)
        var yMin = series.minOf { it.value }
        var yMax = series.maxOf { it.value }
        if (yMin == yMax) { yMin -= 1; yMax += 1 }
        val yRange = (yMax - yMin).coerceAtLeast(1e-6)

        val path = Path()
        var started = false
        for (p in series) {
            val xN = (p.tMillis - tMin) / tRange
            val yN = (p.value - yMin) / yRange
            val x = left + (xN * plotW).toFloat()
            val y = bottom - (yN * plotH).toFloat()
            if (!started) {
                path.moveTo(x, y)
                started = true
            } else {
                path.lineTo(x, y)
            }
        }
        drawPath(
            path = path,
            color = accent,
            style = Stroke(width = 2.5f),
        )

        // Point dots — small for context, large for the highlighted
        // current fillup so the user immediately sees where this
        // fill sits on the trend.
        for (p in series) {
            val xN = (p.tMillis - tMin) / tRange
            val yN = (p.value - yMin) / yRange
            val x = left + (xN * plotW).toFloat()
            val y = bottom - (yN * plotH).toFloat()
            val isHighlight = highlightMillis != null &&
                kotlin.math.abs(p.tMillis - highlightMillis) < 1000
            if (isHighlight) {
                drawCircle(color = accent, radius = 6f, center = Offset(x, y))
            } else {
                drawCircle(color = accent.copy(alpha = 0.5f), radius = 2.5f, center = Offset(x, y))
            }
        }
    }
}

// Fuelio's fuel-type code map — small subset matching what
// FuelAddScreen knows about. Unknown codes fall through to "Type N".
private val FUEL_TYPE_LABELS = mapOf(
    1 to "Regular (87)",
    2 to "Plus (89)",
    3 to "Premium (91+)",
    4 to "Diesel",
    5 to "E85",
)
