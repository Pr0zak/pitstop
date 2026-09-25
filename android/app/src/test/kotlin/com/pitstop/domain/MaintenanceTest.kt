package com.pitstop.domain

import com.pitstop.http.ExpenseCategoryDto
import com.pitstop.http.ExpenseDto
import com.pitstop.http.ReminderDto
import com.pitstop.http.RemindersResponse
import com.pitstop.http.VehicleDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MaintenanceTest {
    private val today = LocalDate.parse("2026-09-25")

    private fun exp(
        id: String,
        date: String,
        odo: Double? = null,
        remindOdo: Double? = null,
        remindDate: String? = null,
        cat: Int? = null,
        notes: String? = null,
        template: Boolean = false,
        income: Boolean = false,
    ) = ExpenseDto(
        id = id, vehicleId = "v", expenseDate = date, odo = odo, costTypeId = cat, title = id,
        notes = notes, remindOdo = remindOdo, remindDate = remindDate, isTemplate = template, isIncome = income,
    )

    @Test fun `normalize signs overdue distances and days negative`() {
        val r = Maintenance.normalize(
            RemindersResponse(
                overdue = listOf(ReminderDto(expenseId = "a", title = "Oil", milesOver = 120.0, daysOver = 3)),
                upcoming = listOf(ReminderDto(expenseId = "b", title = null, milesUntil = 250.0, daysUntil = 40)),
            ),
        )
        assertEquals(-120.0, r[0].distanceRemaining!!, 1e-9)
        assertEquals(-3, r[0].daysRemaining)
        assertTrue(r[0].overdue)
        assertEquals("Service", r[1].title)
        assertEquals(250.0, r[1].distanceRemaining!!, 1e-9)
    }

    @Test fun `due state thresholds are 300 mi, 480 km or 14 days`() {
        fun item(d: Double?, days: Int?, over: Boolean = false) =
            ReminderItem("x", "t", null, null, null, null, d, days, over)
        assertEquals(DueState.Overdue, Maintenance.dueState(item(-5.0, null, over = true), true))
        assertEquals(DueState.DueSoon, Maintenance.dueState(item(300.0, null), true))
        assertEquals(DueState.Ok, Maintenance.dueState(item(301.0, null), true))
        assertEquals(DueState.DueSoon, Maintenance.dueState(item(450.0, null), false))
        assertEquals(DueState.DueSoon, Maintenance.dueState(item(2_000.0, 14), true))
        assertEquals(DueState.Ok, Maintenance.dueState(item(2_000.0, 15), true))
        assertEquals(DueState.Ok, Maintenance.dueState(item(null, null), true))
    }

    @Test fun `hasReminder ignores the Fuelio 2011 placeholder date`() {
        assertFalse(Maintenance.hasReminder(exp("a", "2020-01-23", remindOdo = 0.0, remindDate = "2011-01-01")))
        assertTrue(Maintenance.hasReminder(exp("b", "2026-09-01", remindOdo = 81_000.0)))
        assertTrue(Maintenance.hasReminder(exp("c", "2026-09-01", remindDate = "2027-03-01")))
        assertFalse(Maintenance.hasReminder(exp("d", "2026-09-01", remindOdo = 81_000.0, template = true)))
    }

    @Test fun `scheduled lists reminders the endpoint left out`() {
        val listed = listOf(ReminderItem("b", "t", null, null, null, null, 100.0, null, false))
        val sched = Maintenance.scheduled(
            listOf(exp("a", "2026-09-01", remindOdo = 85_000.0), exp("b", "2026-09-01", remindOdo = 80_100.0), exp("c", "2026-09-01")),
            listed,
        )
        assertEquals(listOf("a"), sched.map { it.id })
    }

    @Test fun `service history keeps service categories and uncategorised rows`() {
        val cats = listOf(ExpenseCategoryDto(1, "Service"), ExpenseCategoryDto(6, "Wash"), ExpenseCategoryDto(3, "Repairs"))
        val hist = Maintenance.serviceHistory(
            listOf(
                exp("oil", "2026-01-01", cat = 1),
                exp("wash", "2026-02-01", cat = 6),
                exp("fix", "2026-03-01", cat = 3),
                exp("preset", "2026-04-01", notes = Maintenance.PRESET_NOTE),
                exp("refund", "2026-05-01", cat = 1, income = true),
            ),
            cats,
        )
        assertEquals(listOf("preset", "fix", "oil"), hist.map { it.id })
    }

    @Test fun `preset request mirrors the web body`() {
        val oil = Maintenance.PRESETS.first { it.key == "oil" }
        val body = Maintenance.presetRequest(oil, "veh", 76_612.0, distInMiles = true, today = today)
        assertEquals("Oil change", body.title)
        assertEquals(0.0, body.cost, 0.0)
        assertEquals("2026-09-25", body.expenseDate)
        assertEquals(5_000.0, body.repeatOdo!!, 0.0)
        assertEquals(6.0, body.repeatMonths!!, 0.0)
        assertEquals(81_612.0, body.remindOdo!!, 0.0)
        assertEquals("2027-03-25", body.remindDate)
        assertEquals(Maintenance.PRESET_NOTE, body.notes)

        val air = Maintenance.PRESETS.first { it.key == "air" }
        val metric = Maintenance.presetRequest(air, "veh", null, distInMiles = false, today = today)
        assertEquals(48_000.0, metric.repeatOdo!!, 0.0)
        assertNull(metric.remindOdo)
        assertNull(metric.remindDate)
        assertNull(metric.repeatMonths)
    }

    @Test fun `current odometer converts latest_odo_km to the vehicle unit`() {
        val mi = VehicleDto(id = "v", slug = "s", name = "n", distUnit = 1, latestOdoKm = 100_000.0)
        assertEquals(62_137.0, Maintenance.currentOdo(mi)!!, 0.0)
        val km = mi.copy(distUnit = 0)
        assertEquals(100_000.0, Maintenance.currentOdo(km)!!, 0.0)
        assertNull(Maintenance.currentOdo(mi.copy(latestOdoKm = null)))
    }

    @Test fun `stale service when over a year or 10k miles behind, preset anchors ignored`() {
        val old = listOf(exp("oil", "2020-01-23", odo = 11_719.0), exp("p", "2026-09-01", odo = 80_000.0, notes = Maintenance.PRESET_NOTE))
        val st = Maintenance.staleService(old, 80_103.0, distInMiles = true, today = today)!!
        assertEquals(68_384.0, st.behind!!, 0.0)
        assertEquals("2020-01-23", st.lastDate)
        val fresh = listOf(exp("oil", "2026-06-01", odo = 76_000.0))
        assertNull(Maintenance.staleService(fresh, 80_000.0, distInMiles = true, today = today))
        assertTrue(Maintenance.staleService(fresh, 87_000.0, distInMiles = true, today = today) != null)
    }

    @Test fun `progress is the further of distance and date, overdue is full`() {
        val e = exp("a", "2026-06-25", odo = 75_000.0)
        val r = ReminderItem("a", "Oil", null, currentOdo = 77_500.0, remindOdo = 80_000.0, remindDate = "2026-12-25",
            distanceRemaining = 2_500.0, daysRemaining = 91, overdue = false)
        // distance 50 %; date: 92 of 183 days ≈ 50.3 %
        assertEquals(92.0 / 183.0, Maintenance.progress(r, e, today)!!, 1e-9)
        assertEquals(1.0, Maintenance.progress(r.copy(overdue = true), e, today)!!, 0.0)
        assertNull(Maintenance.progress(r.copy(remindDate = null), exp("a", "2026-06-25"), today))
    }
}
