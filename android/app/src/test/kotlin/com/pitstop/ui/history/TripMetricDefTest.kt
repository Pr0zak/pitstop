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
        "atf_temp",
        "gear_position",
        "active_cylinders",
        "misfire_count_1", "misfire_count_2", "misfire_count_3",
        "misfire_count_4", "misfire_count_5", "misfire_count_6",
        "fuel_level_l",
        "ambient_air_temp_f",
        "stft_b1", "stft_b2", "ltft_b1", "ltft_b2",
        "commanded_evap_purge",
        "tire_pressure_fl", "tire_pressure_fr", "tire_pressure_rl", "tire_pressure_rr",
        "tire_temp_fl", "tire_temp_fr", "tire_temp_rl", "tire_temp_rr",
    )

    /** Charted by the dedicated Tires card (one shared axis), not the timeline. */
    private val tiresCard = com.pitstop.domain.VehicleSignals.Corner.entries
        .flatMap { listOf(it.pressureMetric, it.tempMetric) }.toSet()

    /** Computed on the phone (like the web), not served by the API. */
    private val derived = setOf("acceleration")

    @Test
    fun `charts exactly the metrics the trip endpoint returns`() {
        assertEquals(
            backendWhitelist + derived,
            TRIP_METRICS.map { it.metric }.toSet() + tiresCard,
        )
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
        // exceptions are exhaust mass flow, brake pressure (psi in both,
        // like tire pressures — user preference, web parity) and g.
        val convertible = TRIP_METRICS.filter {
            it.quantity != UnitFormat.Quantity.None &&
                it.quantity != UnitFormat.Quantity.Percent &&
                it.quantity != UnitFormat.Quantity.Volt &&
                it.quantity != UnitFormat.Quantity.Lambda &&
                it.quantity != UnitFormat.Quantity.MassFlowKgPerHour &&
                it.quantity != UnitFormat.Quantity.BrakeBar &&
                it.quantity != UnitFormat.Quantity.GForce
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

    /**
     * Web `GROUP_OF` in TripDetailView.vue — the picker files every series
     * under the same group on both clients (ADR-013 parity).
     */
    @Test
    fun `groups match the web picker`() {
        val web = mapOf(
            "vehicle_speed" to "Drive", "engine_rpm" to "Drive",
            "throttle_position" to "Drive", "engine_load" to "Drive",
            "acceleration" to "Drive", "odometer" to "Drive", "brake_pressure" to "Drive",
            "coolant_temp" to "Engine", "control_module_voltage" to "Engine",
            "fuel_rail_pressure" to "Engine", "active_cylinders" to "Engine",
            "misfire_count_1" to "Engine", "misfire_count_2" to "Engine",
            "misfire_count_3" to "Engine", "misfire_count_4" to "Engine",
            "misfire_count_5" to "Engine", "misfire_count_6" to "Engine",
            "fuel_level" to "Fuel", "engine_fuel_rate" to "Fuel",
            "commanded_afr_ratio" to "Fuel", "o2_s1_lambda" to "Fuel",
            "fuel_level_l" to "Fuel", "stft_b1" to "Fuel", "stft_b2" to "Fuel",
            "ltft_b1" to "Fuel", "ltft_b2" to "Fuel",
            "intake_air_temp" to "Air", "manifold_pressure" to "Air",
            "maf_air_flow" to "Air", "ambient_air_temp_f" to "Air",
            "catalyst_temp_b1" to "Emissions", "catalyst_temp_b2" to "Emissions",
            "engine_exhaust_flow" to "Emissions", "commanded_evap_purge" to "Emissions",
            "atf_temp" to "Transmission", "gear_position" to "Transmission",
        )
        assertEquals(web, TRIP_METRICS.associate { it.metric to it.group.title })
        assertEquals(
            listOf("Drive", "Transmission", "Engine", "Fuel", "Air", "Emissions"),
            MetricGroup.entries.map { it.title },
        )
    }

    private fun def(metric: String) = TRIP_METRICS.first { it.metric == metric }

    @Test
    fun `new series use US units in imperial and convert like the web`() {
        // ATF: °C on the wire.
        assertEquals("ATF (°F)", def("atf_temp").chipLabel("imperial"))
        assertEquals(212.0, def("atf_temp").display(100.0, "imperial"), 1e-9)
        assertEquals(100.0, def("atf_temp").display(100.0, "metric"), 1e-9)
        // Outside: °F on the wire (cluster DID 7028).
        val outside = def("ambient_air_temp_f")
        assertEquals("Outside (°F)", outside.chipLabel("imperial"))
        assertEquals("Outside (°C)", outside.chipLabel("metric"))
        assertEquals(54.0, outside.display(54.0, "imperial"), 1e-9)
        assertEquals(12.222, outside.display(54.0, "metric"), 1e-3)
        // Dash fuel: litres on the wire.
        val dash = def("fuel_level_l")
        assertEquals("Dash fuel (gal)", dash.chipLabel("imperial"))
        assertEquals("Dash fuel (L)", dash.chipLabel("metric"))
        assertEquals(10.0, dash.display(37.854, "imperial"), 1e-3)
        // Brake: bar on the wire, psi in both systems.
        assertEquals("Brake (psi)", def("brake_pressure").chipLabel("metric"))
        assertEquals(1450.38, def("brake_pressure").display(100.0, "metric"), 1e-6)
        // Trims / purge: percent, no conversion.
        assertEquals("STFT B1 (%)", def("stft_b1").chipLabel("imperial"))
        assertEquals("EVAP purge (%)", def("commanded_evap_purge").chipLabel("imperial"))
        assertEquals("Accel (g)", def("acceleration").chipLabel("imperial"))
        assertEquals("Misfire cyl 3", def("misfire_count_3").chipLabel("imperial"))
        assertEquals("Gear", def("gear_position").chipLabel("imperial"))
    }

    @Test
    fun `discrete series are stepped and listed for last-value downsampling`() {
        val expected = setOf("gear_position", "active_cylinders") +
            (1..6).map { "misfire_count_$it" }
        assertEquals(expected, com.pitstop.ui.history.detail.DISCRETE_TRIP_METRICS)
        for (m in expected) assertTrue(m, def(m).step)
        assertTrue(def("brake_pressure").step)
        assertTrue(!def("vehicle_speed").step)
    }

    @Test
    fun `gear draws reverse below park and ticks read as gears`() {
        val gear = def("gear_position")
        assertEquals(-1.0, gear.display(15.0, "imperial"), 0.0)
        assertEquals(4.0, gear.display(4.0, "imperial"), 0.0)
        val tick = gear.tickFormat!!
        assertEquals("R", tick(-1.0))
        assertEquals("P/N", tick(0.0))
        assertEquals("D4", tick(4.0))
        assertEquals("", tick(4.5))
    }

    @Test
    fun `trims and misfires share a range, cylinders are pinned`() {
        fun pts(vararg v: Double) = v.mapIndexed { i, x ->
            com.pitstop.ui.history.detail.TimedPoint(i * 1000L, x)
        }
        val ranges = com.pitstop.ui.history.detail.sharedYRanges(
            listOf(
                def("stft_b1") to pts(-2.0, 3.0),
                def("stft_b2") to pts(-5.0, 7.0),
                def("active_cylinders") to pts(6.0, 6.0),
                def("vehicle_speed") to pts(0.0, 100.0),
            ),
        )
        assertEquals(-5.0 to 7.0, ranges["stft_b1"])
        assertEquals(-5.0 to 7.0, ranges["stft_b2"])
        assertEquals(0.0 to 6.0, ranges["active_cylinders"])
        assertTrue("speed auto-fits", "vehicle_speed" !in ranges)
        assertEquals("misfire", def("misfire_count_1").rangeKey)
        assertEquals(setOf("trim"), listOf("stft_b1", "stft_b2", "ltft_b1", "ltft_b2").map { def(it).rangeKey }.toSet())
    }
}
