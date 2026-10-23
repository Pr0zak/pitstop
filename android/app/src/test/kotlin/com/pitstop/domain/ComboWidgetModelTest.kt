package com.pitstop.domain

import com.pitstop.widget.ComboWidgetRender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComboWidgetModelTest {
    private val now = 1_800_000_000_000L
    private val tires = TireWidgetModel.of(
        mapOf(
            "tire_pressure_fl" to (213.0 to now - 4 * 60_000L),
            "tire_pressure_fr" to (196.0 to now - 4 * 60_000L),
        ),
        now, "imperial",
    )

    @Test
    fun `fresh halves show the older age and are not stale`() {
        val m = ComboWidgetRender.modelOf(61.0, now - 2 * 60_000L, "240 mi", tires, now - 4 * 60_000L, now)
        assertEquals("4m ago", m.age)
        assertFalse(m.stale)
        assertFalse(m.empty)
    }

    @Test
    fun `a day-old fuel half makes the widget stale and shows its age`() {
        val m = ComboWidgetRender.modelOf(61.0, now - 30L * 3_600_000L, null, tires, now - 4 * 60_000L, now)
        assertTrue(m.stale)
        assertTrue(m.fuelStale)
        assertEquals("1d ago", m.age)
    }

    @Test
    fun `only tires known is not empty, nothing known is`() {
        assertFalse(ComboWidgetRender.modelOf(null, null, null, tires, now - 60_000L, now).empty)
        val none = ComboWidgetRender.modelOf(null, null, null, TireWidgetModel.of(emptyMap(), now, "imperial"), null, now)
        assertTrue(none.empty)
        assertNull(none.age)
    }

    @Test
    fun `ageMs is the older half's age and the chip label is compact`() {
        val m = ComboWidgetRender.modelOf(61.0, now - 3L * 3_600_000L, null, tires, now - 4 * 60_000L, now)
        assertEquals(3L * 3_600_000L, m.ageMs)
        assertTrue(m.ageMs!! >= ComboWidgetRender.AGE_SHOWN_AFTER_MS)
        assertFalse(m.stale)
        assertEquals("3h", ComboWidgetRender.compactAge(m.ageMs!!))
        assertEquals("45m", ComboWidgetRender.compactAge(45 * 60_000L))
        assertEquals("1d", ComboWidgetRender.compactAge(26L * 3_600_000L))
    }
}
