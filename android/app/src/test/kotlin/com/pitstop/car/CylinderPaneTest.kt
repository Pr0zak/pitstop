package com.pitstop.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CylinderPaneTest {
    private val now = 1_800_000_000_000L

    private fun model(v: Double?, ageMs: Long = 1_000L, misfires: Map<Int, Int> = emptyMap(), idle: String? = null) =
        CylinderModel.of(v, v?.let { now - ageMs }, misfires, now, idle)

    @Test fun allSixFiring() {
        val m = model(6.0)
        assertEquals(6, m.active)
        assertEquals(List(6) { true }, m.firing)
        assertEquals("Live", CylinderPane.status(m))
    }

    @Test fun vcmShutsTheRearBank() {
        val m = model(3.0)
        // Cylinders 1-3 (rear bank) off, 4-6 firing.
        assertEquals(listOf(false, false, false, true, true, true), m.firing)
        assertEquals("Live", CylinderPane.status(m))
    }

    @Test fun staleOrMissingShowsTheReason() {
        assertNull(model(6.0, ageMs = 30_000L, idle = "Engine off").active)
        assertEquals("Engine off", CylinderPane.status(model(6.0, ageMs = 30_000L, idle = "Engine off")))
        assertEquals("Waiting for data", CylinderPane.status(model(null)))
    }

    @Test fun unknownCountHasNoBankMap() {
        val m = model(4.0)
        assertEquals(4, m.active)
        assertNull(m.firing)
    }

    @Test fun ecoShareAccumulatesAndResetsAfterAGap() {
        val t = VcmTracker()
        var ts = now
        t.ingest(6.0, ts)
        ts += 60_000; t.ingest(3.0, ts) // 60 s on 6
        ts += 60_000; t.ingest(6.0, ts) // 60 s on 3
        assertEquals(VcmTracker.Share(60_000, 120_000), t.share())
        assertEquals(50, CylinderPane.sharePct(t.share()))
        t.ingest(6.0, ts) // same sample again: ignored
        assertEquals(VcmTracker.Share(60_000, 120_000), t.share())
        ts += VcmTracker.RESET_GAP_MS + 1; t.ingest(3.0, ts)
        assertNull(t.share())
        assertNull(CylinderPane.sharePct(t.share()))
    }

    @Test fun titlesNeverChange() {
        assertEquals(CylinderPane.TITLES, CylinderPane.rows(model(null), null).map { it.first })
        assertEquals(CylinderPane.TITLES, CylinderPane.rows(model(3.0), VcmTracker.Share(1, 2)).map { it.first })
    }
}
