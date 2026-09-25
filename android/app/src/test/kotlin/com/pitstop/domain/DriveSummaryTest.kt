package com.pitstop.domain

import com.pitstop.http.TripBaselineDto
import com.pitstop.http.TripDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveSummaryTest {
    private fun t(id: String, km: Double?, start: String = "2026-09-25T10:00:00Z") =
        TripDto(id = id, vehicleId = "v", startedAt = start, distanceKm = km, durationS = 1860, fuelUsedL = km?.let { it / 10 })

    @Test fun `short hops are not announced`() {
        val plans = DriveSummary.plan(2, listOf(t("a", 0.4), t("b", 27.4)))
        assertEquals(listOf("b"), plans.map { (it as DriveSummaryPlan.Single).trip.id })
    }

    @Test fun `up to three drives get one notification each, oldest first`() {
        val plans = DriveSummary.plan(3, listOf(t("c", 5.0, "2026-09-25T12:00:00Z"), t("a", 5.0, "2026-09-25T08:00:00Z"), t("b", 5.0, "2026-09-25T10:00:00Z")))
        assertEquals(listOf("a", "b", "c"), plans.map { (it as DriveSummaryPlan.Single).trip.id })
    }

    @Test fun `a backlog drain collapses into one summary`() {
        val trips = listOf(t("a", 10.0), t("b", 20.0), t("c", 0.2), t("d", 12.0))
        val plans = DriveSummary.plan(4, trips)
        assertEquals(1, plans.size)
        val batch = plans[0] as DriveSummaryPlan.Batch
        assertEquals(4, batch.count)
        assertEquals(42.2, batch.totalKm, 1e-9)
        assertTrue(DriveSummary.batchTitle(batch, "imperial").startsWith("4 drives synced · "))
    }

    @Test fun `nothing uploaded or nothing resolved means nothing to say`() {
        assertTrue(DriveSummary.plan(0, listOf(t("a", 10.0))).isEmpty())
        assertTrue(DriveSummary.plan(2, emptyList()).isEmpty())
    }

    @Test fun `body compares duration with the usual when the baseline is real`() {
        val base = TripBaselineDto(bucketLabel = "10-30 mi", sampleSize = 42, sufficient = true, avgDurationS = 1590.0, avgMpg = 21.0)
        val body = DriveSummary.body(t("a", 27.4), base)
        assertEquals("31 min · +17% vs your usual for 10-30 mi", body)
        assertEquals("31 min", DriveSummary.body(t("a", 27.4), base.copy(sufficient = false)))
        assertNull(DriveSummary.durationDeltaPct(t("a", 27.4), null))
    }

    @Test fun `title carries distance and economy`() {
        assertEquals("Drive: 17.0 mi · 23.5 mpg", DriveSummary.title(t("a", 27.4).copy(fuelUsedL = 2.74), "imperial"))
    }
}
