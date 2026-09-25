package com.pitstop.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The car tile is the one place a driver reads telemetry while moving, so
 * every state it can be in is spelled out here: fresh, warned, ageing,
 * stale, and empty-with-a-reason.
 */
class CarTileRenderTest {

    private val coolant = CarTileCatalog.byKey("coolant_temp")!!
    private val battery = CarTileCatalog.byKey("control_module_voltage")!!
    private val rpm = CarTileCatalog.byKey("engine_rpm")!!

    @Test
    fun `fresh slow metric carries its trend arrow`() {
        val r = renderCarTile(86.0, coolant, "metric", TrendDir.Up, ageS = 2)
        assertEquals("86 °C ▲", r.text)
        assertFalse(r.stale)
        assertEquals(TileWarn.None, r.warn)
    }

    @Test
    fun `fast metrics never show an arrow`() {
        assertEquals("1850", renderCarTile(1850.0, rpm, "metric", TrendDir.Up, ageS = 1).text)
    }

    @Test
    fun `hot coolant says so in words and is severe`() {
        val r = renderCarTile(112.0, coolant, "metric", TrendDir.Steady, ageS = 1)
        assertEquals("112 °C HOT", r.text)
        assertEquals(TileWarn.Severe, r.warn)
    }

    @Test
    fun `battery out of range is a caution both ways`() {
        assertEquals("11.6 V LOW", renderCarTile(11.6, battery, "metric", TrendDir.Steady, 1).text)
        assertEquals(TileWarn.Caution, renderCarTile(15.4, battery, "metric", TrendDir.Steady, 1).warn)
        assertEquals(TileWarn.None, renderCarTile(14.1, battery, "metric", TrendDir.Steady, 1).warn)
    }

    @Test
    fun `age shows after ten seconds in five second buckets then stale`() {
        assertEquals("86 °C ▲", renderCarTile(86.0, coolant, "metric", TrendDir.Up, ageS = 9).text)
        val ageing = renderCarTile(86.0, coolant, "metric", TrendDir.Up, ageS = 27)
        assertEquals("86 °C · 25s", ageing.text)
        assertTrue(ageing.stale)
        assertEquals("86 °C · stale", renderCarTile(86.0, coolant, "metric", TrendDir.Up, ageS = 75).text)
    }

    @Test
    fun `no value shows the reason, or a dash without one`() {
        assertEquals("Engine off", renderCarTile(null, coolant, "metric", TrendDir.Steady, null, "Engine off").text)
        assertEquals("—", renderCarTile(null, coolant, "metric", TrendDir.Steady, null).text)
    }

    @Test
    fun `warn bounds are canonical even when displaying imperial`() {
        // 110 °C = 230 °F — still HOT.
        assertEquals("230 °F HOT", renderCarTile(110.0, coolant, "imperial", TrendDir.Steady, 1).text)
    }

    @Test
    fun `drive defaults drop speed and status replaces diag`() {
        assertFalse("vehicle_speed" in CarTileCatalog.DEFAULT_HOME)
        assertTrue("session" in CarTileCatalog.CarScreenKind.DEFAULT_TABS)
        assertFalse("diag" in CarTileCatalog.CarScreenKind.DEFAULT_TABS)
    }
}
