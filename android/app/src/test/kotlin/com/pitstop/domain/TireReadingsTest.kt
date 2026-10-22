package com.pitstop.domain

import com.pitstop.http.MetricLatestDto
import com.pitstop.http.VehicleDto
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.1.257 on the phone: the tire widget said "no readings yet" and Home had
 * no Tires section although the server had fresh readings. Cause: one
 * `{"metric":"tire","value":"fr"}` row in /readings/latest — a TEXT value —
 * failed the strict `Double?` decode, so the whole list was dropped.
 * Fixtures are the real 2026-10-22 payload (tire rows + that row; vehicle
 * ids replaced), with the Truck listed before the Pilot.
 */
class TireReadingsTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private fun res(name: String) =
        requireNotNull(javaClass.classLoader!!.getResource("fixtures/$name")).readText()

    private val now = java.time.Instant.parse("2026-10-22T00:40:00Z").toEpochMilli()

    private fun latest(): List<MetricLatestDto> =
        json.decodeFromString(ListSerializer(MetricLatestDto.serializer()), res("readings_latest_2026-10-22.json"))

    private fun asMap(rows: List<MetricLatestDto>) = rows.mapNotNull { r ->
        val v = r.value ?: return@mapNotNull null
        val t = com.pitstop.util.DateLabel.epochMs(r.time) ?: return@mapNotNull null
        r.metric to (v to t)
    }.toMap()

    @Test
    fun `the real payload decodes - a text value is null, not a failure`() {
        val rows = latest()
        assertNull(rows.first { it.metric == "tire" }.value)
        assertEquals(220.0, rows.first { it.metric == "tire_pressure_fl" }.value!!, 0.0)
    }

    @Test
    fun `truck-first vehicle list picks the selected Pilot, never the first`() {
        val vehicles = json.decodeFromString(ListSerializer(VehicleDto.serializer()), res("vehicles_truck_first.json"))
        assertEquals("truck", vehicles.first().slug)
        val picked = TireReadings.pickVehicle(vehicles, { it.slug }, viewSlug = "pilot19", configuredSlug = "pilot19")!!
        assertEquals("pilot19", picked.slug)
        assertEquals(241.3, picked.tirePlacardFrontKpa!!, 0.0)
        // No in-app selection: the bridge's configured slug, still not the first.
        assertEquals("pilot19", TireReadings.pickVehicle(vehicles, { it.slug }, null, "pilot19")!!.slug)
        assertNull(TireReadings.pickVehicle(vehicles, { it.slug }, null, ""))
    }

    @Test
    fun `server only (fresh install) shows all four corners`() {
        val m = TireWidgetModel.of(TireReadings.merge(emptyMap(), asMap(latest())), now, "imperial")
        assertFalse(m.empty)
        assertEquals(listOf("32", "29", "30", "29"), m.cells.map { it.psi })
        assertEquals("6m ago", m.age)
    }

    @Test
    fun `snapshot only (offline) still shows the last readings`() {
        val snapshot = asMap(latest()).filterKeys { it.startsWith("tire_") }
        val m = TireWidgetModel.of(TireReadings.merge(snapshot, emptyMap(), emptyMap()), now, "imperial")
        assertFalse(m.empty)
        assertTrue(m.cells.none { it.psi == "—" })
    }

    @Test
    fun `days-old readings are still shown, dimmed, with their age`() {
        val old = asMap(latest()).mapValues { (_, v) -> v.first to v.second - 3L * 86_400_000L }
        val m = TireWidgetModel.of(TireReadings.merge(old), now, "imperial")
        assertTrue(m.stale)
        assertEquals("3d ago", m.age)
        assertEquals("32", m.cells.first().psi)
    }

    @Test
    fun `a corner missing from the newest set keeps its older value`() {
        val snapshot = mapOf("tire_pressure_rr" to (205.0 to now - 86_400_000L))
        val server = asMap(latest()).filterKeys { it != "tire_pressure_rr" }
        val merged = TireReadings.merge(snapshot, server)
        assertEquals(205.0, merged["tire_pressure_rr"]!!.first, 0.0)
        assertEquals(220.0, merged["tire_pressure_fl"]!!.first, 0.0)
        // And a newer value always wins over the snapshot.
        val newer = TireReadings.merge(mapOf("tire_pressure_fl" to (210.0 to 0L)), server)
        assertEquals(220.0, newer["tire_pressure_fl"]!!.first, 0.0)
    }

    @Test
    fun `a 0 kPa sentinel never displaces a real reading`() {
        val merged = TireReadings.merge(
            mapOf("tire_pressure_fr" to (203.0 to now - 60_000L)),
            mapOf("tire_pressure_fr" to (0.0 to now)),
        )
        assertEquals(203.0, merged["tire_pressure_fr"]!!.first, 0.0)
    }
}
