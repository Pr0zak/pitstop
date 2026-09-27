package com.pitstop.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class FuelPricesTest {

    private val us = Locale.US

    @Test fun `price keeps two or three decimals as returned`() {
        assertEquals("$3.49", FuelPrices.price(3.49, "USD", us))
        assertEquals("$3.459", FuelPrices.price(3.459, "USD", us))
        assertEquals("$3.00", FuelPrices.price(3.0, "USD", us))
        // Float noise from units + nanos must not grow a third digit.
        assertEquals("$2.99", FuelPrices.price(2.9899999999999998, "USD", us))
    }

    @Test fun `missing price is a dash, never zero`() {
        assertEquals("—", FuelPrices.price(null, null, us))
        assertEquals("—", FuelPrices.price(Double.NaN, "USD", us))
    }

    @Test fun `unknown or absent currency falls back to the locale`() {
        assertEquals("$3.49", FuelPrices.price(3.49, null, us))
        assertEquals("$3.49", FuelPrices.price(3.49, "NOT_A_CODE", us))
    }

    @Test fun `grade maps to the api value`() {
        assertEquals("REGULAR_UNLEADED", FuelGrade.Regular.apiValue)
        assertEquals(FuelGrade.Diesel, FuelGrade.fromApi("DIESEL"))
        assertEquals(FuelGrade.Regular, FuelGrade.fromApi("SOMETHING_NEW"))
    }

    @Test fun `status messages`() {
        fun msg(s: String, detail: String? = null, count: Int = 0, perm: Boolean = true) =
            FuelPrices.statusMessage(NearbyStatus.fromWire(s), detail, count, "3.1 mi", perm)
        assertNull(msg("ok", count = 3))
        assertEquals("No stations within 3.1 mi", msg("ok"))
        assertEquals("Add a Google Places API key in Settings to see live prices", msg("no_key"))
        assertEquals("Monthly lookup limit reached — resets on the 1st", msg("quota_reached"))
        assertEquals("API key not valid", msg("upstream_error", detail = "API key not valid"))
        assertEquals("Google couldn't answer the price lookup", msg("upstream_error", detail = ""))
        assert(msg("no_location", perm = false)!!.contains("allow location"))
        assert(msg("brand_new_status")!!.contains("update pitstop"))
    }

    @Test fun `origin line only for vehicle and home`() {
        assertEquals("Near where the car was parked · 3 h ago", FuelPrices.originLine("vehicle", "3 h ago"))
        assertEquals("Near where the car was parked", FuelPrices.originLine("vehicle", null))
        assertEquals("Near home", FuelPrices.originLine("home", null))
        assertNull(FuelPrices.originLine("device", null))
        assertNull(FuelPrices.originLine(null, null))
    }

    @Test fun `footer says just now only for a fresh uncached lookup`() {
        assertEquals(
            "Prices from Google · 1/900 lookups this month · updated just now",
            FuelPrices.footer(1, 900, cached = false, fetchedAgo = "just now", fetchedAgeMs = 5_000),
        )
        assertEquals(
            "Prices from Google · 12/900 lookups this month · updated 14 min ago",
            FuelPrices.footer(12, 900, cached = true, fetchedAgo = "14 min ago", fetchedAgeMs = 14 * 60_000L),
        )
        // cached=false replayed off the offline HTTP cache two days later.
        assertEquals(
            "Prices from Google · 1/900 lookups this month · updated 2 d ago",
            FuelPrices.footer(1, 900, cached = false, fetchedAgo = "2 d ago", fetchedAgeMs = 2 * 86_400_000L),
        )
        assertEquals("Prices from Google", FuelPrices.footer(null, null, cached = false, fetchedAgo = null, fetchedAgeMs = null))
    }

    @Test fun `you paid line`() {
        assertEquals("You paid $3.29 · Sep 12", FuelPrices.youPaid(3.29, "Sep 12", us))
        assertEquals("You paid $3.299", FuelPrices.youPaid(3.299, null, us))
        assertNull(FuelPrices.youPaid(null, "Sep 12", us))
    }

    @Test fun `blank key is never sent`() {
        assertNull(PlacesKeyPatch.set(""))
        assertNull(PlacesKeyPatch.set("   "))
    }

    @Test fun `set trims and clear sends an explicit null`() {
        // The app's Json (AppModule): encodeDefaults=false must not drop the null.
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = false }
        assertEquals(
            """{"places":{"api_key":"abc123"}}""",
            json.encodeToString(JsonObject.serializer(), PlacesKeyPatch.set("  abc123 ")!!),
        )
        assertEquals(
            """{"places":{"api_key":null}}""",
            json.encodeToString(JsonObject.serializer(), PlacesKeyPatch.clear()),
        )
    }
}
