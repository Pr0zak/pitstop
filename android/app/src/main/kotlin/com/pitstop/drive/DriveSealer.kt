package com.pitstop.drive

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.pitstop.log.LogBuffer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists a sealed [DriveBuffer] into the Room queue and kicks off
 * the upload worker.
 *
 * The triple-gate evaluation (inCar=false ≥30s + engine_off + OBD
 * quiet ≥60s) lives in the bridge service — it has direct access to
 * the signals. The sealer is the "I have a finalised drive; persist
 * it and try to upload" entry point.
 *
 * Idempotency: the Room insert uses OnConflictStrategy.IGNORE, and
 * the server's idempotency ledger keys on the same UUID, so calling
 * seal twice for the same drive is safe at both layers.
 */
@Singleton
class DriveSealer @Inject constructor(
    private val recorder: DriveRecorder,
    private val dao: PendingDriveDao,
    private val json: Json,
    private val logs: LogBuffer,
    @ApplicationContext private val context: Context,
) {
    private val _lastSealedAt = MutableStateFlow<Long?>(null)
    val lastSealedAt: StateFlow<Long?> = _lastSealedAt.asStateFlow()

    /**
     * Seal the currently-open drive. Pulls from [DriveRecorder.close]
     * with the actual end timestamp and `kind="off"` for normal seals
     * or `kind="quiet"` when the OBD-quiet gate fires without an
     * explicit engine_off (rare but happens when the car kills the
     * 12V rail before the WiCAN can send LWT).
     *
     * Returns the inserted [PendingDrive] or null if nothing was open.
     */
    suspend fun seal(
        endedAtMs: Long,
        deviceId: String,
        kind: String = "off",
    ): PendingDrive? {
        val buf = recorder.close(endedAtMs, kind) ?: run {
            logs.debug(
                "DriveSealer.seal: nothing to seal (no open buffer)",
                mapOf("ended_at" to endedAtMs),
            )
            return null
        }
        return persist(buf, endedAtMs, incomplete = false, deviceId = deviceId)
    }

    /**
     * Orphan-buffer recovery: called on app start when we find an
     * open drive marker that didn't get a clean close (phone crashed
     * mid-drive). Stamp the trip incomplete=true so the user knows
     * the stats may be partial.
     */
    suspend fun sealIncomplete(deviceId: String): PendingDrive? {
        val now = System.currentTimeMillis()
        val buf = recorder.closeIncomplete(now) ?: return null
        return persist(buf, now, incomplete = true, deviceId = deviceId)
    }

    private suspend fun persist(
        buf: DriveBuffer,
        endedAtMs: Long,
        incomplete: Boolean,
        deviceId: String,
    ): PendingDrive {
        val dto = buf.seal(endedAtMs, incomplete, deviceId)
        val payloadJson = json.encodeToString(dto)
        val row = PendingDrive(
            clientDriveUuid = dto.clientDriveUuid,
            vehicleId = dto.vehicleId,
            startedAt = buf.startedAtMs,
            endedAt = endedAtMs,
            incomplete = incomplete,
            frameCount = dto.frameCount,
            payloadJson = payloadJson,
        )
        dao.insert(row)
        _lastSealedAt.value = endedAtMs
        logs.info(
            "DriveSealer: drive persisted",
            mapOf(
                "client_drive_uuid" to dto.clientDriveUuid,
                "vehicle_id" to dto.vehicleId,
                "started_at" to buf.startedAtMs,
                "ended_at" to endedAtMs,
                "frame_count" to dto.frameCount,
                "incomplete" to incomplete,
                "payload_bytes" to payloadJson.length,
            ),
        )
        kickWorker()
        return row
    }

    /**
     * Enqueue a one-shot upload pass. The worker drains the queue
     * until it hits a network error or runs out of rows; the
     * periodic worker is the 4h backstop.
     */
    fun kickWorker() {
        val request = OneTimeWorkRequestBuilder<DriveUploadWorker>()
            .addTag(DriveUploadWorker.TAG)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                DriveUploadWorker.UNIQUE_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
    }
}
