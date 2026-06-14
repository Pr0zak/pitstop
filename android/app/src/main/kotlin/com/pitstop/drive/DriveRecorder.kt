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
     * Opens a new buffer keyed on (vehicleId, startedAtMs).
     *
     * Returns the orphaned prior buffer (if any) along with the
     * fresh one — caller is expected to hand the orphan to
     * [DriveSealer.sealIncomplete] before discarding. Previously we
     * just logged + dropped the orphan, which silently lost drives.
     */
    fun open(vehicleId: String, startedAtMs: Long): OpenResult {
        val prior = _open.value
        val fresh = DriveBuffer(vehicleId, startedAtMs)
        _open.value = fresh
        fresh.addEngineEvent(startedAtMs, "on")
        if (prior != null) {
            logs.warn(
                "DriveRecorder.open: orphan prior buffer detected, will seal as incomplete",
                mapOf(
                    "prior_vehicle" to prior.vehicleId,
                    "prior_started" to prior.startedAtMs,
                    "prior_frames" to prior.frameCount(),
                    "new_vehicle" to vehicleId,
                    "new_started" to startedAtMs,
                ),
            )
        }
        logs.info(
            "DriveRecorder: drive opened",
            mapOf("vehicle_id" to vehicleId, "started_at" to startedAtMs),
        )
        return OpenResult(fresh = fresh, orphan = prior)
    }

    data class OpenResult(val fresh: DriveBuffer, val orphan: DriveBuffer?)

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
        // `kind` ("off" / "quiet" / "ble_lost") classifies WHY the drive
        // sealed and is carried as drive-seal metadata elsewhere. The
        // engine_events.state column is constrained to on/off server-side,
        // so the end event is always "off" — a quiet/ble_lost seal still
        // means the engine stopped. (Writing the raw kind here 500'd the
        // whole drive upload pre-fix.)
        buf.addEngineEvent(endedAtMs, "off")
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
