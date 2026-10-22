package com.pitstop.ui.history

import com.pitstop.ui.history.detail.TRIP_METRICS
import com.pitstop.util.UnitFormat
import org.junit.Assert.assertEquals
import com.pitstop.ui.history.detail.MetricGroup
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
        "odometer",
        "brake_pressure",
        "tire_pressure_fl", "tire_pressure_fr", "tire_pressure_rl", "tire_pressure_rr",
        "tire_temp_fl", "tire_temp_fr", "tire_temp_rl", "tire_temp_rr",
    )

    /** Charted by the dedicated Tires card (one shared axis), not the timeline. */
    private val tiresCard = com.pitstop.domain.VehicleSignals.Corner.entries
        .flatMap { listOf(it.pressureMetric, it.tempMetric) }.toSet()

    @Test
    fun `charts exactly the metrics the trip endpoint returns`() {
        assertEquals(backendWhitelist, TRIP_METRICS.map { it.metric }.toSet() + tiresCard)
        assertTrue(TRIP_METRICS.none { it.metric in tiresCard })
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
        // Quantity that actually differs between systems; the deliberate
        // exceptions are exhaust mass flow and brake pressure (bar in both,
        // as the VSA module and the web report it).
        val convertible = TRIP_METRICS.filter {
            it.quantity != UnitFormat.Quantity.None &&
                it.quantity != UnitFormat.Quantity.Percent &&
                it.quantity != UnitFormat.Quantity.Volt &&
                it.quantity != UnitFormat.Quantity.Lambda &&
                it.quantity != UnitFormat.Quantity.MassFlowKgPerHour &&
                it.quantity != UnitFormat.Quantity.Bar
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

    @Test
    fun `every metric is filed under a group`() {
        // The picker renders one section per MetricGroup and skips empty
        // ones, so a metric can only be reached through its group. There
        // is no "ungrouped" bucket — a new metric with no group would
        // silently land in Core rather than being unreachable, so this
        // asserts the grouping is deliberate rather than defaulted.
        val byGroup = TRIP_METRICS.groupBy { it.group }
        for (group in MetricGroup.entries) {
            assertTrue(
                "MetricGroup.$group has no metrics — it would render an empty header",
                byGroup[group]?.isNotEmpty() == true,
            )
        }
    }

    @Test
    fun `odometer charts as a distance, not a raw number`() {
        // The API subtracts vehicles.odometer_offset_km before sending, so
        // these values are dash-aligned; the client must only convert
        // units on top of that and never re-apply an offset of its own.
        val odo = TRIP_METRICS.first { it.metric == "odometer" }
        assertEquals(UnitFormat.Quantity.DistanceKm, odo.quantity)
        assertEquals("Odometer (mi)", odo.chipLabel("imperial"))
        assertEquals("Odometer (km)", odo.chipLabel("metric"))
        assertEquals(0, odo.digits)
    }
}
