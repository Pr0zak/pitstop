package com.pitstop.domain

import com.pitstop.http.RoutePointDto
import com.pitstop.http.TripDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class ParkedTest {
    private fun p(t: Int, lat: Double, lon: Double, acc: Double? = 8.0) =
        RoutePointDto(t = "2026-09-25T18:%02d:00Z".format(t), lat = lat, lon = lon, accuracyM = acc)

    private fun trip(id: String, start: String, end: String? = null) =
        TripDto(id = id, vehicleId = "v", startedAt = start, endedAt = end)

    @Test fun `latest trip is the newest by start`() {
        val trips = listOf(trip("a", "2026-09-24T10:00:00Z"), trip("b", "2026-09-25T09:00:00Z"), trip("c", "2026-09-23T10:00:00Z"))
        assertEquals("b", Parked.latestTrip(trips)!!.id)
    }

    @Test fun `picks the last accurate fix near the end`() {
        val route = listOf(p(1, 40.0, -83.0), p(2, 40.001, -83.001), p(3, 40.002, -83.002, acc = 400.0))
        val pt = Parked.pickPoint(route)!!
        assertEquals(40.001, pt.lat, 1e-9)
    }

    @Test fun `falls back to the last fix when none is accurate`() {
        val route = listOf(p(1, 40.0, -83.0, acc = 300.0), p(2, 40.5, -83.5, acc = null))
        assertEquals(40.5, Parked.pickPoint(route)!!.lat, 1e-9)
    }

    @Test fun `null island and empty routes give no spot`() {
        assertNull(Parked.pickPoint(listOf(p(1, 0.0, 0.0))))
        assertNull(Parked.pickPoint(emptyList()))
    }

    @Test fun `no spot while driving, else trip end time`() {
        val t = trip("b", "2026-09-25T17:40:00Z", end = "2026-09-25T18:13:00Z")
        val route = listOf(p(10, 40.0, -83.0))
        assertNull(Parked.spot(t, route, driveActive = true))
        val s = Parked.spot(t, route, driveActive = false)!!
        assertEquals("b", s.tripId)
        assertEquals(Instant.parse("2026-09-25T18:13:00Z").toEpochMilli(), s.sinceMs)
    }

    @Test fun `since falls back to the fix time when the trip has no end`() {
        val t = trip("b", "2026-09-25T17:40:00Z")
        val s = Parked.spot(t, listOf(p(12, 40.0, -83.0)), driveActive = false)!!
        assertEquals(Instant.parse("2026-09-25T18:12:00Z").toEpochMilli(), s.sinceMs)
    }

    @Test fun `geo uri carries a labelled pin`() {
        val s = ParkedSpot("t", 40.1, -83.2, null)
        assertEquals("geo:40.1,-83.2?q=40.1,-83.2(Car)", Parked.geoUri(s))
    }
}
