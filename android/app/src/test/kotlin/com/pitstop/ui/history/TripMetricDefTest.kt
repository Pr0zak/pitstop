package com.pitstop.ui.history

import com.pitstop.ui.history.detail.TRIP_METRICS
import com.pitstop.util.UnitFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trip-chart metric catalog. Mirrors the backend's
 * `_TRIP_SAMPLE_METRICS` whitelist in `api/trips.py` — if the two drift,
 * the phone either draws a chip that can never have data or silently
 * drops a series the API is already paying to send.
 */
class TripMetricDefTest {

    /** Keep in sync with backend `_TRIP_SAMPLE_METRICS`. */
    private val backendWhitelist = setOf(
        "vehicle_speed",
        "engine_rpm",
        "coolant_temp",
        "throttle_position",
        "maf_air_flow",
        "manifold_pressure",
        "engine_load",
        "control_module_voltage",
        "fuel_level",
        "intake_air_temp",
        "engine_fuel_rate",
        "engine_exhaust_flow",
        "catalyst_temp_b1",
        "catalyst_temp_b2",
        "commanded_afr_ratio",
        "o2_s1_lambda",
        "fuel_rail_pressure",
    )

    @Test
    fun `charts exactly the metrics the trip endpoint returns`() {
        assertEquals(backendWhitelist, TRIP_METRICS.map { it.metric }.toSet())
    }

    @Test
    fun `metric keys are unique`() {
        assertEquals(TRIP_METRICS.size, TRIP_METRICS.map { it.metric }.toSet().size)
    }

    @Test
    fun `only speed and rpm start visible`() {
        assertEquals(
            listOf("vehicle_speed", "engine_rpm"),
            TRIP_METRICS.filter { it.defaultVisible }.map { it.metric },
        )
    }

    @Test
    fun `chip labels carry the active unit system's unit`() {
        val speed = TRIP_METRICS.first { it.metric == "vehicle_speed" }
        assertEquals("Speed (mph)", speed.chipLabel("imperial"))
        assertEquals("Speed (km/h)", speed.chipLabel("metric"))

        val fuelRate = TRIP_METRICS.first { it.metric == "engine_fuel_rate" }
        assertEquals("Fuel rate (gph)", fuelRate.chipLabel("imperial"))
        assertEquals("Fuel rate (L/h)", fuelRate.chipLabel("metric"))

        // Unitless series get no empty parens.
        val rpm = TRIP_METRICS.first { it.metric == "engine_rpm" }
        assertEquals("RPM", rpm.chipLabel("imperial"))
    }

    @Test
    fun `no series hardcodes a canonical unit that ignores the toggle`() {
        // Every temp / pressure / speed / flow series must name a
        // Quantity that actually differs between systems; the only
        // deliberate exception is exhaust mass flow.
        val convertible = TRIP_METRICS.filter {
            it.quantity != UnitFormat.Quantity.None &&
                it.quantity != UnitFormat.Quantity.Percent &&
                it.quantity != UnitFormat.Quantity.Volt &&
                it.quantity != UnitFormat.Quantity.Lambda &&
                it.quantity != UnitFormat.Quantity.MassFlowKgPerHour
        }
        assertTrue(convertible.isNotEmpty())
        for (def in convertible) {
            assertTrue(
                "${def.metric} renders the same unit in both systems",
                def.quantity.unit("imperial") != def.quantity.unit("metric"),
            )
        }
    }

    @Test
    fun `lambda series keep enough decimals to show movement`() {
        for (metric in listOf("commanded_afr_ratio", "o2_s1_lambda")) {
            assertEquals(3, TRIP_METRICS.first { it.metric == metric }.digits)
        }
    }
}
