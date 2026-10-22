package com.pitstop.ui.history

import com.pitstop.http.TripTireCornerDto
import com.pitstop.http.TripTiresDto
import com.pitstop.ui.history.detail.tireSummaryRows
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripTiresTest {

    private val tires = TripTiresDto(
        corners = listOf(
            TripTireCornerDto("fl", 213.0, 220.0, 212.0, 221.0, 18.0, 29.0),
            TripTireCornerDto("fr", 196.0, 203.0, 195.0, 204.0, 18.0, 30.0),
            TripTireCornerDto("rl", 209.0, 216.0, 208.0, 216.0, 17.0, 27.0),
            TripTireCornerDto("rr", null, null, null, null, null, null),
        ),
        lowCorners = listOf("fr"),
        lowDeltaPsi = 2.0,
    )

    @Test
    fun `rows read start to end psi with warm-up and the server low flag`() {
        val rows = tireSummaryRows(tires, "imperial")
        assertEquals(listOf("FL", "FR", "RL", "RR"), rows.map { it.label })
        assertEquals("31 → 32 psi", rows[0].pressure)
        assertEquals("28 → 29 psi", rows[1].pressure)
        assertTrue(rows[1].low)
        assertFalse(rows[0].low)
        assertEquals("64 → 84 °F", rows[0].warmUp)
    }

    @Test
    fun `a corner with no reading is a dash, never zero, and psi stays psi in metric`() {
        val rows = tireSummaryRows(tires, "metric")
        assertEquals("—", rows[3].pressure)
        assertNull(rows[3].warmUp)
        assertEquals("31 → 32 psi", rows[0].pressure)
        assertEquals("18 → 29 °C", rows[0].warmUp)
    }

    @Test
    fun `placard line comes from the server verdict on the start pressure`() {
        val withPlacard = tires.copy(
            corners = tires.corners.map {
                when (it.corner) {
                    "fr" -> it.copy(placardKpa = 241.3, belowPlacardPsi = 6.6, belowSpec = true)
                    "fl" -> it.copy(placardKpa = 241.3, belowPlacardPsi = 4.1, belowSpec = true)
                    "rl" -> it.copy(placardKpa = 241.3, belowPlacardPsi = 10.0, belowSpec = true, underInflated = true)
                    else -> it
                }
            },
            placardFrontKpa = 241.3, placardRearKpa = 241.3,
        )
        val rows = tireSummaryRows(withPlacard, "imperial")
        assertEquals("of 35 · −4", rows[0].placard)
        assertEquals("of 35 · −7", rows[1].placard)
        assertTrue(rows[1].belowSpec)
        assertEquals("of 35 · under −10", rows[2].placard)
        assertTrue(rows[2].underInflated)
        assertNull(rows[3].placard) // RR: no reading, no verdict
        assertNull(tireSummaryRows(tires, "imperial")[0].placard) // no placard at all
    }

    @Test
    fun `falls back to the shared 2-psi rule when the server list is empty`() {
        val rows = tireSummaryRows(tires.copy(lowCorners = emptyList()), "imperial")
        assertEquals(listOf("FR"), rows.filter { it.low }.map { it.label })
    }

    @Test
    fun `decodes the backend _tire_summary shape strictly`() {
        // Mirrors backend _tire_summary + _brake_summary JSON (ints stay ints).
        val json = Json { ignoreUnknownKeys = true }
        val body = """
            {"id":"t","vehicle_id":"v","started_at":"2026-10-21T12:00:00Z",
             "brake_events":12,"hard_brake_events":1,"max_brake_bar":88.0,"braking_s":41.5,
             "tires":{"corners":[{"corner":"fl","pressure_start_kpa":213.0,"pressure_end_kpa":220.0,
               "pressure_min_kpa":212.0,"pressure_max_kpa":221.0,"temp_start_c":18.0,"temp_end_c":29.0}],
               "low_corners":["fr"],"low_delta_psi":2.0,"placard_front_kpa":241.3,"placard_rear_kpa":null,
               "under_inflated_fraction":0.25,"below_spec_psi":3.0}}
        """.trimIndent()
        val t = json.decodeFromString(com.pitstop.http.TripDetailDto.serializer(), body)
        assertEquals(12, t.brakeEvents)
        assertEquals(1, t.hardBrakeEvents)
        assertEquals(88.0, t.maxBrakeBar!!, 0.0)
        assertEquals(41.5, t.brakingS!!, 0.0)
        assertEquals(listOf("fr"), t.tires!!.lowCorners)
        // No brake samples -> backend sends nulls; no tires -> null.
        val none = json.decodeFromString(
            com.pitstop.http.TripDetailDto.serializer(),
            """{"id":"t","vehicle_id":"v","started_at":"x","brake_events":null,"tires":null}""",
        )
        assertNull(none.brakeEvents)
        assertNull(none.tires)
    }
}

class BrakingLineTest {
    private fun trip(n: Int?, h: Int?, max: Double?, s: Double?) = com.pitstop.http.TripDetailDto(
        id = "t", vehicleId = "v", startedAt = "x",
        brakeEvents = n, hardBrakeEvents = h, maxBrakeBar = max, brakingS = s,
    )

    @Test
    fun `web-parity stats row`() {
        assertEquals(
            "12 presses · 1 hard (≥870 psi) · max 1349 psi · 42s on pedal",
            com.pitstop.ui.history.detail.brakingLine(trip(12, 1, 93.0, 41.5)),
        )
        assertEquals("1 press · 0 hard (≥870 psi) · max 1189 psi · 2s on pedal",
            com.pitstop.ui.history.detail.brakingLine(trip(1, 0, 82.0, 2.0)))
    }
}
