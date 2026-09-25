package com.pitstop.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertRulesTest {
    @Test fun `first check seeds silently`() {
        val (fresh, seen) = AlertRules.newDtcCodes(setOf("P0420"), emptySet(), seeded = false)
        assertTrue(fresh.isEmpty())
        assertEquals(setOf("P0420"), seen)
    }

    @Test fun `a code is announced once`() {
        val (fresh, seen) = AlertRules.newDtcCodes(setOf("P0420", "p0171"), setOf("P0420"), seeded = true)
        assertEquals(listOf("P0171"), fresh)
        val (again, _) = AlertRules.newDtcCodes(setOf("P0171"), seen, seeded = true)
        assertTrue(again.isEmpty())
    }

    @Test fun `reminders notify on entering due-soon and again on going overdue`() {
        val (n1, s1) = AlertRules.reminderTransitions(mapOf("oil" to DueState.DueSoon, "air" to DueState.Ok), emptyMap())
        assertEquals(listOf("oil"), n1)
        val (n2, s2) = AlertRules.reminderTransitions(mapOf("oil" to DueState.DueSoon), s1)
        assertTrue(n2.isEmpty())
        val (n3, _) = AlertRules.reminderTransitions(mapOf("oil" to DueState.Overdue), s2)
        assertEquals(listOf("oil"), n3)
    }

    @Test fun `a reminder that leaves the window is forgotten`() {
        val (_, s) = AlertRules.reminderTransitions(emptyMap(), mapOf("oil" to "Overdue"))
        assertTrue(s.isEmpty())
        val (n, _) = AlertRules.reminderTransitions(mapOf("oil" to DueState.DueSoon), s)
        assertEquals(listOf("oil"), n)
    }
}
