package com.pitstop.ui.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pitstop.http.TripDto
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

private val ACCENT = Color(0xFFF97316)
private val BAR_IDLE = Color(0xFF3A3F47)

/** Days in the bar chart, and in the headline totals window. */
private const val CHART_DAYS = 14
private const val WEEK_DAYS = 7

private const val MI_PER_KM = 0.621371

/**
 * Stat strip above the Trips list: distance, drive time and average
 * speed for the last 7 days, over a 14-day per-day distance chart.
 *
 * Distance/speed are rendered in miles/mph to match the trip cards
 * below, which hardcode imperial (the app's UnitFormat helper covers
 * speed/temp but has no distance case).
 */
@Composable
fun TripStatsHeader(
    trips: List<TripDto>,
    modifier: Modifier = Modifier,
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone),
) {
    val stats = remember(trips, today, zone) { computeTripStats(trips, today, zone) }
    if (stats.isEmpty) return

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                StatCell(fmt0(stats.weekDistanceMi), "mi", "this week")
                StatCell(formatDuration(stats.weekDurationS), "", "drive time")
                StatCell(
                    stats.weekAvgMph?.let { fmt0(it) } ?: "—",
                    if (stats.weekAvgMph != null) "mph" else "",
                    "avg",
                )
            }

            Text(
                "Distance, last $CHART_DAYS days",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
            DayBars(
                days = stats.days,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 3.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                AxisLabel(stats.days.firstOrNull()?.date?.let { dowLabel(it) } ?: "")
                AxisLabel(stats.days.lastOrNull()?.date?.let { dowLabel(it) } ?: "")
            }
        }
    }
}

@Composable
private fun StatCell(value: String, unit: String, caption: String) {
    Column(horizontalAlignment = Alignment.Start) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (unit.isNotEmpty()) {
                Text(
                    " $unit",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        }
        Text(
            caption,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AxisLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** One bar per day. Zero-distance days still draw a faint stub so the
 *  gaps read as "no driving" rather than as missing data. */
@Composable
private fun DayBars(days: List<DayDistance>, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        if (days.isEmpty()) return@Canvas
        val maxV = days.maxOf { it.distanceMi }
        val slot = size.width / days.size
        val barW = slot * 0.62f
        val gap = (slot - barW) / 2f
        days.forEachIndexed { i, d ->
            val frac = if (maxV > 0.0) (d.distanceMi / maxV).toFloat() else 0f
            // Floor of 2 px so an empty day is still visibly a day.
            val h = (frac * size.height).coerceAtLeast(2f)
            drawRect(
                color = if (d.distanceMi > 0.0) ACCENT else BAR_IDLE,
                topLeft = Offset(slot * i + gap, size.height - h),
                size = Size(barW, h),
            )
        }
    }
}

// ── Stat computation (pure — unit-tested in TripStatsTest) ────────────

data class DayDistance(val date: LocalDate, val distanceMi: Double)

data class TripStats(
    val weekDistanceMi: Double = 0.0,
    val weekDurationS: Long = 0L,
    val weekAvgMph: Double? = null,
    val days: List<DayDistance> = emptyList(),
) {
    /** Nothing to show when no trip in the charted window carried a
     *  distance — an all-zero card is just noise. */
    val isEmpty: Boolean
        get() = days.none { it.distanceMi > 0.0 } && weekDistanceMi <= 0.0
}

/**
 * Aggregate trips into the header's numbers.
 *
 * Trip timestamps are bucketed by LOCAL date via `atZoneSameInstant` —
 * never by the UTC date. A 22:00 local drive is the next day in UTC,
 * which is exactly the bug that put trips in the wrong bucket in
 * v0.1.181.
 */
fun computeTripStats(
    trips: List<TripDto>,
    today: LocalDate,
    zone: ZoneId,
): TripStats {
    val chartStart = today.minusDays((CHART_DAYS - 1).toLong())
    val weekStart = today.minusDays((WEEK_DAYS - 1).toLong())

    val perDay = HashMap<LocalDate, Double>()
    var weekMi = 0.0
    var weekDur = 0L

    for (t in trips) {
        val date = localDateOf(t.startedAt, zone) ?: continue
        val mi = (t.distanceKm ?: 0.0) * MI_PER_KM
        if (!date.isBefore(chartStart) && !date.isAfter(today)) {
            perDay[date] = (perDay[date] ?: 0.0) + mi
        }
        if (!date.isBefore(weekStart) && !date.isAfter(today)) {
            weekMi += mi
            weekDur += (t.durationS ?: 0).toLong()
        }
    }

    val days = (0 until CHART_DAYS).map { i ->
        val d = chartStart.plusDays(i.toLong())
        DayDistance(d, perDay[d] ?: 0.0)
    }

    // Distance-over-time, not the mean of per-trip averages: a 2-minute
    // crawl out of the driveway shouldn't weigh as much as a 2-hour
    // highway run.
    val avg = if (weekDur > 0L) weekMi / (weekDur / 3600.0) else null

    return TripStats(
        weekDistanceMi = weekMi,
        weekDurationS = weekDur,
        weekAvgMph = avg,
        days = days,
    )
}

/** Parse an ISO-8601 trip timestamp to its LOCAL calendar date. Returns
 *  null on anything unparseable so one bad row can't sink the header. */
internal fun localDateOf(iso: String, zone: ZoneId): LocalDate? = runCatching {
    OffsetDateTime.parse(iso).atZoneSameInstant(zone).toLocalDate()
}.getOrNull()

internal fun formatDuration(seconds: Long): String {
    if (seconds <= 0L) return "0m"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

private fun dowLabel(d: LocalDate): String =
    d.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())

private fun fmt0(v: Double): String = String.format("%.0f", v)
