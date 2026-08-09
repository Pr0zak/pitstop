package com.pitstop.notif

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The value of this detector is in what it does NOT fire on.
 *
 * The OBD-quiet condition it builds on cannot distinguish "engine off" from
 * "dongle hung" — both are BLE-connected with no frames. Alerting on that
 * alone would notify on every single park, which trains the user to swipe
 * the notification away and makes the real event invisible.
 */
class DongleStallDetectorTest {

    private val fresh = 5_000L

    @Test
    fun `fires when OBD is silent but the phone is moving at road speed`() {
        // 45 mph = 20.1 m/s. The engine cannot be off.
        assertTrue(DongleStallDetector.isStalled(true, 20.1, fresh))
    }

    @Test
    fun `does NOT fire when parked - the whole point`() {
        // Engine off in a driveway: OBD goes quiet and the car is stationary.
        assertFalse(DongleStallDetector.isStalled(true, 0.0, fresh))
    }

    @Test
    fun `does NOT fire on GPS jitter while stationary`() {
        // A parked phone reports small non-zero speeds from fix wander.
        assertFalse(DongleStallDetector.isStalled(true, 1.0, fresh))
        assertFalse(DongleStallDetector.isStalled(true, 2.1, fresh))
    }

    @Test
    fun `does NOT fire on a stale fix that merely remembers movement`() {
        // Parked in a garage after a drive: the last fix still says 20 m/s
        // because no newer one can be obtained. Without the age check this
        // would fire on arriving home, every time.
        assertFalse(DongleStallDetector.isStalled(true, 20.1, 60_000L))
    }

    @Test
    fun `does NOT fire when there has never been a GPS fix`() {
        // GPS capture disabled, or permission denied. No evidence of motion
        // means no claim about the dongle.
        assertFalse(DongleStallDetector.isStalled(true, null, 0L))
    }

    @Test
    fun `does NOT fire when OBD is healthy, whatever the speed`() {
        assertFalse(DongleStallDetector.isStalled(false, 30.0, fresh))
    }

    @Test
    fun `threshold boundaries are inclusive on speed and exclusive on age`() {
        assertTrue(DongleStallDetector.isStalled(true, DongleStallDetector.MOVING_MPS, fresh))
        assertFalse(
            DongleStallDetector.isStalled(
                true, 20.0, DongleStallDetector.MAX_FIX_AGE_MS,
            ),
        )
    }
}
