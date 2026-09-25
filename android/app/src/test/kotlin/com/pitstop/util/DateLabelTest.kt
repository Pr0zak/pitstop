package com.pitstop.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

/** The shared list / title date rules (web-identical). "Now" is Fri 2026-09-25 14:00 in Chicago. */
class DateLabelTest {

    private val zone = ZoneId.of("America/Chicago")
    private val now = ZonedDateTime.of(2026, 9, 25, 14, 0, 0, 0, zone)

    private fun list(iso: String, withTime: Boolean = true, grouped: Boolean = true, is24h: Boolean = false) =
        DateLabel.list(iso, withTime, grouped, is24h, now = now, zone = zone, locale = Locale.US)

    @Test fun `today inside a group is time only`() {
        // 11:33Z = 6:33 AM CDT
        assertEquals("6:33 AM", list("2026-09-25T11:33:00Z"))
    }

    @Test fun `yesterday inside a group is time only`() {
        assertEquals("6:32 PM", list("2026-09-24T23:32:00Z"))
    }

    @Test fun `ungrouped today and yesterday carry the day word`() {
        assertEquals("Today 6:33 AM", list("2026-09-25T11:33:00Z", grouped = false))
        assertEquals("Yesterday 6:32 PM", list("2026-09-24T23:32:00Z", grouped = false))
    }

    @Test fun `utc stamp past midnight is still local today`() {
        // 00:25Z on the 26th is 7:25 PM on the 25th in Chicago.
        assertEquals("Today 7:25 PM", list("2026-09-26T00:25:00Z", grouped = false))
    }

    @Test fun `past week is weekday plus time`() {
        assertEquals("Tue 6:32 PM", list("2026-09-22T23:32:00Z"))
        assertEquals("Sat 9:05 AM", list("2026-09-19T14:05:00Z"))
    }

    @Test fun `seven days back drops the ambiguous weekday`() {
        assertEquals("Sep 18, 6:32 PM", list("2026-09-18T23:32:00Z"))
    }

    @Test fun `same year is month day with time only for trips`() {
        assertEquals("Sep 7, 6:32 PM", list("2026-09-07T23:32:00Z"))
        assertEquals("Sep 7", list("2026-09-07T23:32:00Z", withTime = false))
        assertEquals("Jan 3", list("2026-01-03T15:00:00Z", withTime = false))
    }

    @Test fun `older years carry the year and never a time`() {
        assertEquals("Sep 7, 2025", list("2025-09-07T23:32:00Z"))
        assertEquals("Sep 7, 2025", list("2025-09-07T23:32:00Z", withTime = false))
    }

    @Test fun `24 hour clock follows the system setting`() {
        assertEquals("18:32", list("2026-09-24T23:32:00Z", is24h = true))
        assertEquals("Tue 06:05", list("2026-09-22T11:05:00Z", is24h = true))
    }

    @Test fun `fillup times in the past week also get a weekday`() {
        assertEquals("Tue 6:32 PM", list("2026-09-22T23:32:00Z", withTime = false))
    }

    @Test fun `offset-less and date-only values parse`() {
        assertEquals("Sep 7, 6:32 PM", list("2026-09-07T23:32:00"))
        assertEquals("Sep 7", list("2026-09-07", withTime = false))
    }

    @Test fun `garbage falls back to the raw prefix`() {
        assertEquals("not-a-date", list("not-a-date"))
    }

    @Test fun `detail title`() {
        assertEquals(
            "Fri, Sep 25 · 6:33 AM",
            DateLabel.detailTitle("2026-09-25T11:33:00Z", is24h = false, now = now, zone = zone, locale = Locale.US),
        )
        assertEquals(
            "Sun, Sep 7, 2025 · 18:32",
            DateLabel.detailTitle("2025-09-07T23:32:00Z", is24h = true, now = now, zone = zone, locale = Locale.US),
        )
        assertEquals("—", DateLabel.detailTitle(null, is24h = false, now = now, zone = zone))
    }
}
