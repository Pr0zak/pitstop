package com.pitstop.ui.fuel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Field-level validation that gates the single "Save fillup" button. */
class FuelFormValidationTest {

    private fun validate(f: FuelFormState) = validateFuelForm(f, "mi", "gal")

    @Test
    fun `a complete form is valid and the odometer may be blank`() {
        val ok = FuelFormState(volume = "12.4", totalPrice = "40.10", odometer = "")
        assertFalse(validate(ok).any)
    }

    @Test
    fun `volume and total are required and must be numbers`() {
        val e = validate(FuelFormState(volume = "", totalPrice = "abc"))
        assertEquals("Enter the amount in gal", e.volume)
        assertNotNull(e.total)
        assertTrue(validate(FuelFormState(volume = "0", totalPrice = "10")).volume != null)
    }

    @Test
    fun `odometer below the last fillup is an error, equal or above is fine`() {
        val base = FuelFormState(volume = "10", totalPrice = "30", lastOdometer = 76_304.0)
        assertNotNull(validate(base.copy(odometer = "76000")).odometer)
        assertNull(validate(base.copy(odometer = "76304")).odometer)
        assertNull(validate(base.copy(odometer = "76616")).odometer)
    }
}
