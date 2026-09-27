package com.pitstop.http

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decodes payloads shaped exactly like the backend's (synthetic values),
 * with the app's own Json config — kotlinx-serialization is strict, and a
 * type mismatch blanks the whole card.
 */
class NearbyPricesDtoTest {

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = false }

    @Test fun `ok response decodes with nulls where the server sends them`() {
        val body = """
            {"status":"ok","detail":null,"grade":"REGULAR_UNLEADED","radius_m":5000,
             "origin":{"lat":40.0,"lon":-90.0,"source":"device","as_of":null},
             "fetched_at":"2026-09-27T01:18:05.684392+00:00","cached":false,
             "usage":{"month_calls":1,"monthly_cap":900},
             "stations":[
              {"place_id":"far_cheap","name":"Station far_cheap","address":"far_cheap Main St","lat":40.03,"lon":-90.0,
               "distance_m":3336,"price":2.99,"currency":"USD","price_updated_at":"2026-09-26T14:05:00+00:00",
               "other_prices":[],"my_last_price":null,"my_last_date":null,"my_fillup_count":0,
               "maps_url":"https://www.google.com/maps/search/?api=1&query=40.030000,-90.000000&query_place_id=far_cheap"},
              {"place_id":"diesel_only","name":null,"address":null,"lat":40.003,"lon":-90.0,
               "distance_m":334,"price":null,"currency":null,"price_updated_at":null,
               "other_prices":[{"grade":"DIESEL","price":3.79,"currency":"USD","updated_at":"2026-09-26T14:05:00+00:00"}],
               "my_last_price":3.29,"my_last_date":"2026-09-12T00:00:00+00:00","my_fillup_count":1,"maps_url":"..."}]}
        """.trimIndent()
        val dto = json.decodeFromString(NearbyPricesDto.serializer(), body)
        assertEquals("ok", dto.status)
        assertEquals(5000, dto.radiusM)
        assertEquals("device", dto.origin?.source)
        assertFalse(dto.cached)
        assertEquals(900, dto.usage?.monthlyCap)
        // Server order is kept.
        assertEquals(listOf("far_cheap", "diesel_only"), dto.stations.map { it.placeId })
        val cheap = dto.stations[0]
        assertEquals(3336, cheap.distanceM)
        assertEquals(2.99, cheap.price!!, 0.0)
        assertNull(cheap.myLastPrice)
        val unpriced = dto.stations[1]
        assertNull(unpriced.price)
        assertNull(unpriced.name)
        assertEquals(3.29, unpriced.myLastPrice!!, 0.0)
        assertEquals("DIESEL", unpriced.otherPrices.single().grade)
    }

    @Test fun `no_location has a null origin and fetched_at`() {
        val body = """
            {"status":"no_location","detail":null,"grade":"PREMIUM","radius_m":5000,"origin":null,
             "fetched_at":null,"cached":false,"usage":{"month_calls":0,"monthly_cap":900},"stations":[]}
        """.trimIndent()
        val dto = json.decodeFromString(NearbyPricesDto.serializer(), body)
        assertEquals("no_location", dto.status)
        assertNull(dto.origin)
        assertNull(dto.fetchedAt)
        assertTrue(dto.stations.isEmpty())
    }

    @Test fun `vehicle origin carries as_of`() {
        val body = """
            {"status":"upstream_error","detail":"API key not valid","grade":"REGULAR_UNLEADED","radius_m":5000,
             "origin":{"lat":40.0,"lon":-90.0,"source":"vehicle","as_of":"2026-09-26T20:00:00+00:00"},
             "fetched_at":null,"cached":false,"usage":{"month_calls":3,"monthly_cap":900},"stations":[]}
        """.trimIndent()
        val dto = json.decodeFromString(NearbyPricesDto.serializer(), body)
        assertEquals("vehicle", dto.origin?.source)
        assertEquals("2026-09-26T20:00:00+00:00", dto.origin?.asOf)
        assertEquals("API key not valid", dto.detail)
    }

    @Test fun `settings decode with and without places`() {
        val withPlaces = """
            {"ha":{"enabled":false,"url":null,"token_set":false,"discovery_prefix":"homeassistant","per_pid_toggles":{}},
             "home":{"lat":null,"lon":null},
             "places":{"key_set":true,"month_calls":1,"monthly_cap":900},
             "disk_alert_pct":70,"retention_readings_days":null,"retention_logs_days":30,"retention_logs_debug_days":7}
        """.trimIndent()
        val p = json.decodeFromString(ServerSettingsDto.serializer(), withPlaces).places!!
        assertTrue(p.keySet)
        assertEquals(1, p.monthCalls)
        assertEquals(900, p.monthlyCap)

        // A backend from before ADR-026.
        val old = """{"ha":{"enabled":false},"home":{"lat":null,"lon":null},"disk_alert_pct":70}"""
        assertNull(json.decodeFromString(ServerSettingsDto.serializer(), old).places)
    }
}
