package com.pitstop.ui.history.detail

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pitstop.http.FillupDto
import java.time.OffsetDateTime

@Composable
fun FillupDetailScreen(
    modifier: Modifier = Modifier,
    viewModel: FillupDetailViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    when {
        ui.loading && ui.fillup == null -> Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator() }
        ui.error != null && ui.fillup == null -> Box(
            modifier = modifier.fillMaxSize().padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                ui.error ?: "Couldn't load fillup",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ui.fillup == null -> Unit
        else -> Loaded(
            fillup = ui.fillup!!,
            context = ui.context,
            modifier = modifier,
        )
    }
}

@Composable
private fun Loaded(
    fillup: FillupDto,
    context: List<FillupDto>,
    modifier: Modifier,
) {
    val gallons = fillup.fuelVolume
    val total = fillup.priceTotal
    val ppg = fillup.pricePerUnit
        ?: if (gallons != null && total != null && gallons > 0) total / gallons else null
    // Recompute MPG client-side too as a fallback; the backend's
    // recomputed value comes back as `mpg` for list endpoints but
    // /fillups/{id} returns the row shape used by the recompute
    // attachment which may or may not include mpg depending on
    // whether there's a chain to compute from. Trust the server
    // value when present.
    val mpg = fillup.mpg ?: fillup.mpgReported

    // Cost per mile since the previous fillup, computed from the
    // context list (sorted newest-first). The previous fill is the
    // one immediately after this one in chronological list order.
    val costPerMile = remember(fillup, context) {
        computeCostPerMile(fillup, context)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            fmtDateTimeLocal(fillup.fillupDate),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )

        // Hero card — total + key per-fill numbers.
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    HeroCell(
                        label = "Total",
                        value = total?.let { "$%.2f".format(it) } ?: "—",
                        modifier = Modifier.weight(1f),
                    )
                    HeroCell(
                        label = "Gallons",
                        value = gallons?.let { "%.2f".format(it) } ?: "—",
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    HeroCell(
                        label = "$/gal",
                        value = ppg?.let { "$%.3f".format(it) } ?: "—",
                        modifier = Modifier.weight(1f),
                    )
                    HeroCell(
                        label = "MPG",
                        value = mpg?.let { "%.1f".format(it) } ?: "—",
                        modifier = Modifier.weight(1f),
                    )
                    HeroCell(
                        label = "$/mi",
                        value = costPerMile?.let { "$%.3f".format(it) } ?: "—",
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // Secondary card — facts that don't fit the hero.
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
                    add("Odometer" to "%,.0f mi".format(fillup.odo))
                    add("Tank" to if (fillup.isFull) "Full" else "Partial")
                    if (fillup.isMissed) add("Note" to "Marked as missed previous fillup")
                    fillup.city?.takeIf { it.isNotBlank() }?.let {
                        add("Location" to it)
                    }
                    if (fillup.lat != null && fillup.lon != null) {
                        add("GPS" to "%.4f, %.4f".format(fillup.lat, fillup.lon))
                    }
                    fillup.fuelType?.let {
                        add("Fuel type" to (FUEL_TYPE_LABELS[it] ?: "Type $it"))
                    }
                    if (fillup.weatherTempC != null) {
                        val f = (fillup.weatherTempC * 9 / 5 + 32).toInt()
                        val wmo = wmoLabel(fillup.weatherCode)
                        add("Weather" to "${f}°F${wmo?.let { ", $it" } ?: ""}")
                    }
                    fillup.notes?.takeIf { it.isNotBlank() }?.let {
                        add("Notes" to it)
                    }
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
            }
        }

        // MPG trend chart. Only render when at least 3 fills have a
        // valid mpg value — fewer than that makes for a noisy line.
        val mpgSeries = remember(context) {
            context
                .mapNotNull { f ->
                    val m = f.mpg ?: return@mapNotNull null
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
                        "MPG trend (last ${mpgSeries.size} fills)",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    MpgTrendChart(
                        series = mpgSeries,
                        highlightMillis = currentMs,
                        accent = MaterialTheme.colorScheme.primary,
                        grid = MaterialTheme.colorScheme.outlineVariant,
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

@Composable
private fun HeroCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontWeight = FontWeight.Bold,
        )
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
) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp),
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
