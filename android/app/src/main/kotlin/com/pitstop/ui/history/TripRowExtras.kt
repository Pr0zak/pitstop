package com.pitstop.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pitstop.domain.RouteShape
import com.pitstop.http.PitstopApi
import com.pitstop.http.TripDto
import com.pitstop.ui.status.RouteSketch
import com.pitstop.ui.theme.ext
import com.pitstop.util.UnitFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Route outlines for trip-row thumbnails, fetched on first sight and kept
 * for the process (by trip id). At most three fetches run at once, so a
 * fling down a long list doesn't fire a request per row; projection runs
 * on [Dispatchers.Default]. A trip with no GPS caches as "no shape".
 */
class TripThumbs(private val api: PitstopApi) {
    private class Entry(val shape: RouteShape?)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val gate = Semaphore(3)

    /** True once [id] is resolved; [peek] is then authoritative. */
    fun known(id: String): Boolean = cache.containsKey(id)

    fun peek(id: String): RouteShape? = cache[id]?.shape

    suspend fun load(id: String): RouteShape? {
        cache[id]?.let { return it.shape }
        val shape = gate.withPermit {
            cache[id]?.let { return@withPermit it.shape }
            val pts = runCatching { api.getTripRoute(id).points }.getOrNull()
            withContext(Dispatchers.Default) { pts?.let { p -> RouteShape.of(p.map { it.lat to it.lon }, max = 60) } }
                .also { if (pts != null) cache[id] = Entry(it) }
        }
        return shape
    }
}

/** Null in previews / screenshots: rows show the placeholder box. */
val LocalTripThumbs = staticCompositionLocalOf<TripThumbs?> { null }

/** 48 dp route outline; placeholder while loading or without GPS. */
@Composable
internal fun TripThumb(trip: TripDto, modifier: Modifier = Modifier) {
    val thumbs = LocalTripThumbs.current
    val shape by produceState(initialValue = thumbs?.peek(trip.id), trip.id, thumbs) {
        if (thumbs != null && !thumbs.known(trip.id)) value = thumbs.load(trip.id)
    }
    if (shape == null) {
        Box(
            modifier
                .size(48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        )
    } else {
        RouteSketch(shape, modifier.size(48.dp), strokeWidth = 2f, padding = 6f)
    }
}

/**
 * Thin economy bar: fill = trip mpg / (1.25 × the 30-day average), so the
 * average tick always sits at 80 % (web parity); green at or above the
 * average, amber below, muted without an mpg. Without an average the
 * scale falls back to 30 mpg and there's no tick.
 */
@Composable
internal fun TripMpgBar(mpg: Double?, avgMpg: Double?, modifier: Modifier = Modifier) {
    val scale = avgMpg?.let { it * 1.25 } ?: MPG_BAR_MAX
    val frac = mpg?.let { (it / scale).toFloat().coerceIn(0f, 1f) } ?: 0f
    val color = when {
        mpg == null -> MaterialTheme.colorScheme.outline
        avgMpg == null || mpg >= avgMpg -> MaterialTheme.ext.good
        else -> MaterialTheme.ext.warn
    }
    val tick = MaterialTheme.colorScheme.onSurface
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(12.dp)
            .semantics {
                contentDescription = when {
                    mpg == null -> "No economy for this trip"
                    avgMpg == null -> "Economy bar"
                    mpg >= avgMpg -> "At or above your 30-day average"
                    else -> "Below your 30-day average"
                }
            },
    ) {
        Box(
            Modifier
                .offset(y = 4.dp)
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(frac)
                    .background(color),
            )
        }
        if (avgMpg != null) {
            val x = maxWidth * 0.8f
            Box(
                Modifier
                    .offset(x = x - 1.dp)
                    .width(2.dp)
                    .height(12.dp)
                    .background(tick, RoundedCornerShape(1.dp)),
            )
        }
    }
}

internal const val MPG_BAR_MAX = 30.0

/** "Tick = your 30-day average, 23.6 mpg". */
internal fun mpgBarCaption(avgMpg: Double, system: String): String =
    "Tick = your 30-day average, ${UnitFormat.economy(avgMpg, system)}"

/** Idle share of the drive; null without both numbers. */
internal fun idleShare(trip: TripDto): Double? {
    // 0 s is what OBD-less (boat / GPS-only) trips report: nothing to say.
    val idle = trip.idleS?.takeIf { it > 0 } ?: return null
    val dur = trip.durationS?.takeIf { it > 0 } ?: return null
    return idle.toDouble() / dur
}

/** WMO weather code → one word (0 clear, 1–3 cloudy, 45–48 fog, 51–67 rain, 71–77 snow, 80+ showers). */
internal fun weatherWord(code: Int?): String? = when {
    code == null -> null
    code == 0 -> "Clear"
    code <= 3 -> "Cloudy"
    code in 45..48 -> "Fog"
    code in 51..67 -> "Rain"
    code in 71..77 -> "Snow"
    code >= 80 -> "Showers"
    else -> null
}

/**
 * Fast-scroller bubble text per list key: every trip id (and the
 * short-hop row keyed by its first trip) → "Sep 2026", or just "2023"
 * when the list spans more than three years.
 */
internal fun tripDateLabels(groups: List<Pair<TripGroupKey, List<TripDto>>>): Map<Any, String> {
    val zone = java.time.ZoneId.systemDefault()
    val dated = groups.flatMap { it.second }.mapNotNull { t ->
        com.pitstop.domain.FuelCharts.localDate(t.startedAt, zone)?.let { t.id to it }
    }
    if (dated.isEmpty()) return emptyMap()
    val years = dated.maxOf { it.second.year } - dated.minOf { it.second.year }
    val fmt = java.time.format.DateTimeFormatter.ofPattern(if (years > 3) "yyyy" else "MMM yyyy", java.util.Locale.US)
    return buildMap {
        for ((id, d) in dated) {
            val label = d.format(fmt)
            put(id, label)
            put("hops-$id", label)
        }
    }
}
