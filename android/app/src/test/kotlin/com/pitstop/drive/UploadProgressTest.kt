package com.pitstop.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The fraction is what decides between a determinate progress bar and
 * an indeterminate one, so "unknown size" has to stay distinguishable
 * from "zero bytes sent" — a confident 0 % on a payload whose size we
 * haven't measured yet is the sort of false precision this whole change
 * exists to remove.
 */
class UploadProgressTest {

    private fun running(payloadBytes: Long, bytesSent: Long) = UploadProgress.Running(
        reason = "test",
        passStartedAtMs = 0L,
        driveIndex = 1,
        driveTotal = 1,
        uploadedThisPass = 0,
        driveStartedAtMs = 0L,
        driveEndedAtMs = 0L,
        frameCount = 0,
        payloadBytes = payloadBytes,
        bytesSent = bytesSent,
        phase = UploadPhase.Sending,
        phaseSinceMs = 0L,
        priorAttempts = 0,
    )

    @Test
    fun `unknown payload size yields a null fraction`() {
        assertNull(running(payloadBytes = 0L, bytesSent = 0L).fraction)
    }

    @Test
    fun `fraction is the ratio of sent to total`() {
        assertEquals(0.25f, running(payloadBytes = 400L, bytesSent = 100L).fraction!!, 1e-6f)
    }

    @Test
    fun `fraction never exceeds one when the counter overshoots`() {
        // contentLength can disagree with what the sink counts if the
        // body is re-encoded; clamp rather than hand Compose a 1.4f.
        assertEquals(1f, running(payloadBytes = 100L, bytesSent = 140L).fraction!!, 1e-6f)
    }

    @Test
    fun `fraction never goes below zero`() {
        assertEquals(0f, running(payloadBytes = 100L, bytesSent = -5L).fraction!!, 1e-6f)
    }
}
