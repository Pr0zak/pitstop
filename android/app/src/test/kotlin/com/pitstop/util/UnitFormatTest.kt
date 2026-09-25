package com.pitstop.util

import com.pitstop.util.UnitFormat.Quantity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Guards the imperial/metric split for every converted telemetry
 * quantity. The bug this file exists to prevent: a tile that hardcodes
 * the canonical (metric) unit string and its conversion, so an imperial
 * user reads "1.6 L/h" where the web shows "0.43 gph".
 */
class UnitFormatTest {

    private val eps = 1e-3

    @Test
    fun `fuel rate converts g per s to L per h for metric`() {
        // 0.34 g/s idle burn → 0.34 * 3600 / 749.9 = 1.632 L/h
        assertEquals(1.632, Quantity.FuelRateGramsPerSec.convert(0.34, "metric"), eps)
        assertEquals("L/h", Quantity.FuelRateGramsPerSec.unit("metric"))
    }

    @Test
    fun `fuel rate converts g per s to US gallons per hour for imperial`() {
        // 1.632 L/h * 0.264172 = 0.431 gph
        assertEquals(0.431, Quantity.FuelRateGramsPerSec.convert(0.34, "imperial"), eps)
        assertEquals("gph", Quantity.FuelRateGramsPerSec.unit("imperial"))
    }

    @Test
    fun `fuel rate imperial is not the metric number relabelled`() {
        val metric = Quantity.FuelRateGramsPerSec.convert(0.34, "metric")
        val imperial = Quantity.FuelRateGramsPerSec.convert(0.34, "imperial")
        assertNotEquals(metric, imperial, 0.1)
    }

    @Test
    fun `temperature converts celsius to fahrenheit for imperial only`() {
        assertEquals(1040.0, Quantity.TempC.convert(560.0, "imperial"), eps)
        assertEquals(560.0, Quantity.TempC.convert(560.0, "metric"), eps)
        assertEquals("°F", Quantity.TempC.unit("imperial"))
        assertEquals("°C", Quantity.TempC.unit("metric"))
    }

    @Test
    fun `pressure converts kPa to psi for imperial`() {
        // Fuel rail sits near 3500 kPa on the Pilot.
        assertEquals(507.633, Quantity.PressureKpa.convert(3500.0, "imperial"), eps)
        assertEquals(3500.0, Quantity.PressureKpa.convert(3500.0, "metric"), eps)
    }

    @Test
    fun `gps speed leaves m per s in neither unit system`() {
        // 26.8224 m/s = 60 mph = 96.56 km/h. A metric user must see
        // km/h, not the raw SI value the location provider hands us.
        assertEquals(60.0, Quantity.SpeedMps.convert(26.8224, "imperial"), 1e-2)
        assertEquals(96.56, Quantity.SpeedMps.convert(26.8224, "metric"), 1e-2)
        assertEquals("mph", Quantity.SpeedMps.unit("imperial"))
        assertEquals("km/h", Quantity.SpeedMps.unit("metric"))
    }

    @Test
    fun `vehicle speed converts kph to mph for imperial`() {
        assertEquals(62.137, Quantity.SpeedKph.convert(100.0, "imperial"), eps)
        assertEquals(100.0, Quantity.SpeedKph.convert(100.0, "metric"), eps)
    }

    @Test
    fun `altitude converts metres to feet for imperial`() {
        assertEquals(328.084, Quantity.AltitudeM.convert(100.0, "imperial"), eps)
        assertEquals(100.0, Quantity.AltitudeM.convert(100.0, "metric"), eps)
    }

    @Test
    fun `maf converts grams per second to pounds per minute for imperial`() {
        assertEquals(1.323, Quantity.MassFlowGramsPerSec.convert(10.0, "imperial"), eps)
        assertEquals(10.0, Quantity.MassFlowGramsPerSec.convert(10.0, "metric"), eps)
    }

    @Test
    fun `exhaust flow stays kg per h in both systems by design`() {
        // Deliberate: no imperial exhaust-flow convention a driver
        // would recognise. Asserted so the choice is a decision, not
        // an oversight someone "fixes" by accident.
        assertEquals(19.6, Quantity.MassFlowKgPerHour.convert(19.6, "imperial"), eps)
        assertEquals("kg/h", Quantity.MassFlowKgPerHour.unit("imperial"))
        assertEquals("kg/h", Quantity.MassFlowKgPerHour.unit("metric"))
    }

    @Test
    fun `dimensionless quantities are identical in both systems`() {
        for (q in listOf(
            Quantity.None, Quantity.Percent, Quantity.Volt,
            Quantity.Rpm, Quantity.Seconds, Quantity.Degrees, Quantity.Lambda,
        )) {
            assertEquals(q.name, 0.987, q.convert(0.987, "imperial"), 0.0)
            assertEquals(q.name, 0.987, q.convert(0.987, "metric"), 0.0)
            assertEquals(q.name, q.unit("metric"), q.unit("imperial"))
        }
    }

    @Test
    fun `missing values render as an em dash rather than a number`() {
        assertEquals("—", Quantity.TempC.number(null, "imperial", 0))
        assertEquals("—", Quantity.TempC.number(Double.NaN, "imperial", 0))
        assertEquals("—", Quantity.TempC.format(null, "imperial", 0))
        assertEquals("—", Quantity.FuelRateGramsPerSec.format(Double.NaN, "metric", 2))
    }

    @Test
    fun `format appends the display unit and number does not`() {
        assertEquals("0.43 gph", Quantity.FuelRateGramsPerSec.format(0.34, "imperial", 2))
        assertEquals("0.43", Quantity.FuelRateGramsPerSec.number(0.34, "imperial", 2))
        // Unitless quantities never get a trailing space.
        assertEquals("1500", Quantity.None.format(1500.0, "imperial", 0))
    }

    @Test
    fun `an unknown unit system falls back to metric`() {
        assertEquals(560.0, Quantity.TempC.convert(560.0, ""), eps)
        assertEquals("°C", Quantity.TempC.unit("nonsense"))
    }

    @Test
    fun `distance and odometer follow the unit system`() {
        assertEquals("12.4 mi", UnitFormat.distanceKm(20.0, "imperial"))
        assertEquals("20.0 km", UnitFormat.distanceKm(20.0, "metric"))
        assertEquals("1,000 mi", UnitFormat.odometerMi(1000.0, "imperial"))
        assertEquals("1,609 km", UnitFormat.odometerMi(1000.0, "metric"))
    }

    @Test
    fun `fillup volume converts US gallons to litres for metric`() {
        assertEquals("10.00 gal", UnitFormat.volumeGal(10.0, "imperial"))
        assertEquals("37.85 L", UnitFormat.volumeGal(10.0, "metric"))
    }

    @Test
    fun `economy inverts to L per 100 km and flips the better direction`() {
        assertEquals("23.5 mpg", UnitFormat.economy(23.5, "imperial"))
        // 235.215 / 23.5 = 10.01
        assertEquals("10.0 L/100km", UnitFormat.economy(23.5, "metric"))
        assertEquals(true, UnitFormat.economyHigherIsBetter("imperial"))
        assertEquals(false, UnitFormat.economyHigherIsBetter("metric"))
        assertEquals("—", UnitFormat.economy(0.0, "metric"))
    }

    @Test
    fun `instant economy needs motion and a fuel rate`() {
        // 2 g/s at 100 km/h: 9.6 L/h -> 10.42 km/L -> 24.5 mpg
        assertEquals(24.51, UnitFormat.instantMpg(2.0, 100.0)!!, 0.05)
        assertEquals(null, UnitFormat.instantMpg(2.0, 1.0))
        assertEquals(null, UnitFormat.instantMpg(null, 80.0))
    }

    @Test
    fun `prices convert per volume and per distance`() {
        val us = java.util.Locale.US
        assertEquals("$3.785/gal", UnitFormat.pricePerVolume(3.785, "imperial", 3, us))
        assertEquals("$1.000/L", UnitFormat.pricePerVolume(3.785411784, "metric", 3, us))
        assertEquals("$0.161/mi", UnitFormat.costPerDistance(0.161, "imperial", 3, us))
        assertEquals("$0.100/km", UnitFormat.costPerDistance(0.1609344, "metric", 3, us))
        assertEquals("$1,234.50", UnitFormat.money(1234.5, 2, us))
        assertEquals("18,402", UnitFormat.count(18_402, us))
    }
}
