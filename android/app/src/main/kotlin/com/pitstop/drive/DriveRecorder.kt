package com.pitstop.drive

import com.pitstop.log.LogBuffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The in-memory side of phone-canonical batch upload (#117).
 *
 * Architecture role: this is a singleton write-through buffer that
 * mirrors every sample the bridge service publishes (or attempts to
 * publish) to MQTT. It accumulates per-drive; on [open] a new buffer
 * is created keyed on `(vehicleId, startedAtMs)`, on [close] the
 * current buffer is sealed and handed off to the sealer for
 * persistence + upload.
 *
 * Why mirror instead of subscribe? The bridge already has the
 * easiest access to every sample (OBD callback, GPS handler, IMU
 * tick) — adding one call to the recorder is cheaper than reworking
 * the data path through a flow. The MQTT stream becomes
 * best-effort live; this buffer is canonical.
 */
@Singleton
class DriveRecorder @Inject constructor(
    private val logs: LogBuffer,
) {
    /** Current buffer when a drive is open; null otherwise. */
    private val _open = MutableStateFlow<DriveBuffer?>(null)
    val open: StateFlow<DriveBuffer?> = _open.asStateFlow()

    /**
     * Engine-on transition (or first OBD frame after a long quiet).
     * Opens a new buffer keyed on (vehicleId, startedAtMs). If a
     * buffer is already open for a different drive, the prior one is
     * sealed as `incomplete` first.
     */
    fun open(vehicleId: String, startedAtMs: Long): DriveBuffer {
        val prior = _open.value
        if (prior != null) {
            logs.warn(
                "DriveRecorder.open: prior buffer still open; will seal as incomplete",
                mapOf(
                    "prior_vehicle" to prior.vehicleId,
                    "prior_started" to prior.startedAtMs,
                    "new_vehicle" to vehicleId,
                    "new_started" to startedAtMs,
                ),
            )
        }
        val fresh = DriveBuffer(vehicleId, startedAtMs)
        _open.value = fresh
        fresh.addEngineEvent(startedAtMs, "on")
        logs.info(
            "DriveRecorder: drive opened",
            mapOf("vehicle_id" to vehicleId, "started_at" to startedAtMs),
        )
        return fresh
    }

    /** Convenience accessor for the publish path. */
    fun current(): DriveBuffer? = _open.value

    /**
     * Engine-off transition (or the triple-gate firing). Returns the
     * sealed buffer or null if nothing was open. The sealer should
     * call [DriveBuffer.seal] and persist before clearing the open
     * slot.
     */
    fun close(endedAtMs: Long, kind: String = "off"): DriveBuffer? {
        val buf = _open.value ?: return null
        buf.addEngineEvent(endedAtMs, kind)
        _open.value = null
        logs.info(
            "DriveRecorder: drive closed",
            mapOf(
                "vehicle_id" to buf.vehicleId,
                "started_at" to buf.startedAtMs,
                "ended_at" to endedAtMs,
                "frames" to buf.frameCount(),
            ),
        )
        return buf
    }

    /**
     * Force-close without an engine_off event. Used by orphan-recovery
     * on app start when we find an open drive with no matching close.
     */
    fun closeIncomplete(endedAtMs: Long): DriveBuffer? {
        val buf = _open.value ?: return null
        _open.value = null
        logs.warn(
            "DriveRecorder: drive sealed as incomplete (no engine_off)",
            mapOf(
                "vehicle_id" to buf.vehicleId,
                "started_at" to buf.startedAtMs,
                "ended_at" to endedAtMs,
                "frames" to buf.frameCount(),
            ),
        )
        return buf
    }
}
