package com.pitstop.ui.history

import com.pitstop.http.TripDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

private val CHICAGO: ZoneId = ZoneId.of("America/Chicago")
private val TODAY: LocalDate = LocalDate.of(2026, 8, 1)

private fun trip(
    startedAt: String,
    distanceKm: Double? = 16.09344,  // = 10.0 mi
    durationS: Int? = 3600,
): TripDto = TripDto(
    id = "t-$startedAt",
    vehicleId = "v1",
    startedAt = startedAt,
    distanceKm = distanceKm,
    durationS = durationS,
)

class TripStatsTest {

    @Test
    fun `week totals sum distance and duration inside the 7-day window`() {
        val stats = computeTripStats(
            trips = listOf(
                trip("2026-08-01T09:00:00-05:00"),
                trip("2026-07-30T09:00:00-05:00"),
                trip("2026-07-26T09:00:00-05:00"),   // exactly 6 days back — included
            ),
            today = TODAY,
            zone = CHICAGO,
        )
        assertEquals(30.0, stats.weekDistanceMi, 0.01)
        assertEquals(3 * 3600L, stats.weekDurationS)
    }

    @Test
    fun `trips older than the week window are excluded from totals but may still chart`() {
        val stats = computeTripStats(
            trips = listOf(
                trip("2026-08-01T09:00:00-05:00"),
                trip("2026-07-24T09:00:00-05:00"),   // 8 days back: chart yes, week no
            ),
            today = TODAY,
            zone = CHICAGO,
        )
        assertEquals(10.0, stats.weekDistanceMi, 0.01)
        assertEquals(10.0, stats.days.first { it.date == LocalDate.of(2026, 7, 24) }.distanceMi, 0.01)
    }

    @Test
    fun `average speed is total distance over total time, not a mean of trip averages`() {
        val stats = computeTripStats(
            trips = listOf(
                // 60 mi in 1 h, then 1 mi in 2 h → 61 mi / 3 h ≈ 20.3 mph.
                // A mean of per-trip averages would give (60 + 0.5) / 2 =
                // 30.25 mph — far enough apart to catch the wrong formula.
                trip("2026-08-01T09:00:00-05:00", distanceKm = 96.56064, durationS = 3600),
                trip("2026-08-01T12:00:00-05:00", distanceKm = 1.609344, durationS = 7200),
            ),
            today = TODAY,
            zone = CHICAGO,
        )
        // 61 mi over 3 h
        assertEquals(61.0 / 3.0, stats.weekAvgMph!!, 0.01)
    }

    @Test
    fun `average speed is null when nothing has a duration`() {
        val stats = computeTripStats(
            trips = listOf(trip("2026-08-01T09:00:00-05:00", durationS = 0)),
            today = TODAY,
            zone = CHICAGO,
        )
        assertNull(stats.weekAvgMph)
    }

    /** The v0.1.181 bug: a late-evening local drive is the NEXT day in
     *  UTC, so bucketing by UTC date puts it in the wrong column. */
    @Test
    fun `late evening local trips bucket to the local date, not the UTC date`() {
        // 22:00 on Jul 31 in Chicago = 03:00 Aug 1 UTC.
        val stats = computeTripStats(
            trips = listOf(trip("2026-07-31T22:00:00-05:00")),
            today = TODAY,
            zone = CHICAGO,
        )
        val jul31 = stats.days.first { it.date == LocalDate.of(2026, 7, 31) }
        val aug1 = stats.days.first { it.date == LocalDate.of(2026, 8, 1) }
        assertEquals(10.0, jul31.distanceMi, 0.01)
        assertEquals(0.0, aug1.distanceMi, 0.01)
    }

    @Test
    fun `chart always spans fourteen consecutive days ending today`() {
        val stats = computeTripStats(listOf(trip("2026-08-01T09:00:00-05:00")), TODAY, CHICAGO)
        assertEquals(14, stats.days.size)
        assertEquals(LocalDate.of(2026, 7, 19), stats.days.first().date)
        assertEquals(TODAY, stats.days.last().date)
        // Strictly increasing, no gaps.
        stats.days.zipWithNext { a, b -> assertEquals(a.date.plusDays(1), b.date) }
    }

    @Test
    fun `null distance and unparseable timestamps are survivable`() {
        val stats = computeTripStats(
            trips = listOf(
                trip("2026-08-01T09:00:00-05:00", distanceKm = null),
                trip("not-a-timestamp"),
            ),
            today = TODAY,
            zone = CHICAGO,
        )
        assertEquals(0.0, stats.weekDistanceMi, 0.01)
        assertTrue(stats.isEmpty)
    }

    @Test
    fun `empty input is empty so the UI hides the card`() {
        assertTrue(computeTripStats(emptyList(), TODAY, CHICAGO).isEmpty)
    }

    @Test
    fun `duration formats as hours and minutes`() {
        assertEquals("0m", formatDuration(0))
        assertEquals("9m", formatDuration(9 * 60))
        assertEquals("1h 0m", formatDuration(3600))
        assertEquals("9h 47m", formatDuration(9 * 3600 + 47 * 60))
    }
}
