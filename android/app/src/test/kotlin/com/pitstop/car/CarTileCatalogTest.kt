package com.pitstop.car

import com.pitstop.obd.Pids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The car tile catalogue is what the Settings tab-picker offers. A metric the
 * phone polls but that is missing from [CarTileCatalog.ALL] is simply
 * unreachable on the head unit — which is how the entire emissions block ended
 * up pollable, visible on the Live screen, and unselectable in the car.
 */
class CarTileCatalogTest {

    @Test
    fun `every polled PID can be chosen as a car tile`() {
        val catalog = CarTileCatalog.ALL.map { it.key }.toSet()
        // Odometer is deliberately excluded: an absolute distance in the
        // hundreds of thousands is useless as a glanceable tile, and the
        // value needs the vehicle's offset applied server-side to match the
        // dash, which the car screens do not do.
        val polled = Pids.DEFAULT.map { it.name }.toSet() - "odometer"
        val missing = polled - catalog
        assertEquals(
            "polled but not offerable as a car tile: $missing",
            emptySet<String>(),
            missing,
        )
    }

    @Test
    fun `every tab default resolves to real tiles`() {
        for (tab in CarTileCatalog.CarTab.entries) {
            val resolved = CarTileCatalog.resolveTab(tab, emptyList())
            assertTrue("${tab.title} resolved to nothing", resolved.isNotEmpty())
            assertTrue(
                "${tab.title} exceeds MAX_TILES",
                resolved.size <= CarTileCatalog.MAX_TILES,
            )
        }
    }

    @Test
    fun `tab ids are unique - they key the active tab and the callback`() {
        val ids = CarTileCatalog.CarTab.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `catalog keys are unique`() {
        val keys = CarTileCatalog.ALL.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }
}
