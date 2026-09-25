package com.pitstop.ui.history

import com.pitstop.http.TripDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripRowsTest {

    private fun t(id: String, km: Double?, fuelL: Double? = null) =
        TripDto(id = id, vehicleId = "v", startedAt = "2026-09-25T10:00:00Z", distanceKm = km, fuelUsedL = fuelL)

    @Test fun `runs of two or more short hops fold`() {
        val rows = foldShortHops(listOf(t("a", 12.0), t("b", 0.2), t("c", 0.3), t("d", 0.1), t("e", 9.0)))
        assertEquals(3, rows.size)
        assertEquals("a", (rows[0] as TripRow.Single).trip.id)
        val hops = rows[1] as TripRow.ShortHops
        assertEquals(listOf("b", "c", "d"), hops.trips.map { it.id })
        assertEquals(0.6, hops.distanceKm, 1e-9)
        assertEquals("hops-b", hops.key)
        assertEquals("e", (rows[2] as TripRow.Single).trip.id)
    }

    @Test fun `a lone short hop stays a normal row`() {
        val rows = foldShortHops(listOf(t("a", 12.0), t("b", 0.2), t("c", 5.0)))
        assertTrue(rows.all { it is TripRow.Single })
    }

    @Test fun `trailing run folds and unknown distance never folds`() {
        val rows = foldShortHops(listOf(t("a", null), t("b", 0.2), t("c", 0.47)))
        assertEquals(2, rows.size)
        assertTrue(rows[0] is TripRow.Single)
        assertEquals(2, (rows[1] as TripRow.ShortHops).trips.size)
    }

    @Test fun `threshold is 0_3 mi`() {
        assertTrue(isShortHop(t("a", 0.47)))
        assertTrue(!isShortHop(t("a", 0.48)))
    }

    @Test fun `trip mpg is absent rather than zero`() {
        assertNull(tripMpg(t("a", 20.0, null)))
        assertNull(tripMpg(t("a", 20.0, 0.0)))
        assertNull(tripMpg(t("a", 0.1, 0.05)))
        assertNull(tripMpg(t("a", null, 1.0)))
        // 27.4 km / 2.6 L = 17.03 mi / 0.687 gal ≈ 24.8 mpg
        assertEquals(24.8, tripMpg(t("a", 27.4, 2.6))!!, 0.05)
    }

    @Test fun `group summary sums the rows and hides unmeasured fuel`() {
        val withFuel = tripGroupTotals(listOf(t("a", 16.09344, 3.785411784), t("b", 16.09344, null)))
        assertEquals("2 trips · 20.0 mi · 1.0 gal", tripGroupSummary(withFuel, "imperial"))
        val noFuel = tripGroupTotals(listOf(t("a", 1.609344)))
        assertEquals("1 trip · 1.0 mi", tripGroupSummary(noFuel, "imperial"))
        assertEquals("1 trip · 1.6 km", tripGroupSummary(noFuel, "metric"))
    }

    @Test fun `economy verdict`() {
        assertEquals(true, com.pitstop.ui.history.detail.economyVerdict(24.8, 21.2))
        assertEquals(false, com.pitstop.ui.history.detail.economyVerdict(18.0, 21.2))
        assertNull(com.pitstop.ui.history.detail.economyVerdict(21.3, 21.2))
        assertNull(com.pitstop.ui.history.detail.economyVerdict(24.8, null))
        assertNull(com.pitstop.ui.history.detail.economyVerdict(null, 21.2))
    }
}
