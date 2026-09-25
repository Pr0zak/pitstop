package com.pitstop.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class FuelTripleTest {
    @Test fun `total then price derives the volume`() {
        val t = FuelTriple().edit(FuelField.Total, "46.74").edit(FuelField.Price, "3.299")
        assertEquals("14.168", t.volume)
        assertEquals(FuelField.Volume, t.derived)
    }

    @Test fun `typing the volume re-derives the field touched longest ago`() {
        // Pinned Total, Price → type Volume → Total is now oldest → derived.
        val t = FuelTriple(total = "46.74", price = "3.299", volume = "14.168")
            .edit(FuelField.Volume, "14")
        assertEquals(listOf(FuelField.Price, FuelField.Volume), t.pinned)
        assertEquals("46.19", t.total)
    }

    @Test fun `volume and total derive the price`() {
        val t = FuelTriple(pinned = listOf(FuelField.Volume, FuelField.Total))
            .edit(FuelField.Volume, "10")
            .edit(FuelField.Total, "35")
        assertEquals(FuelField.Price, t.derived)
        assertEquals("3.5", t.price)
    }

    @Test fun `unusable inputs leave the derived field alone`() {
        val t = FuelTriple(volume = "12.0").edit(FuelField.Total, "abc")
        assertEquals("12.0", t.volume)
        val z = FuelTriple(volume = "12.0").edit(FuelField.Total, "40").edit(FuelField.Price, "0")
        assertEquals("12.0", z.volume)
    }

    @Test fun `re-typing the same field keeps both pins`() {
        val t = FuelTriple().edit(FuelField.Price, "3.1").edit(FuelField.Price, "3.2")
        assertEquals(listOf(FuelField.Total, FuelField.Price), t.pinned)
    }
}
