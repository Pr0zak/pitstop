package com.pitstop.drive

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.pitstop.data.SettingsRepository
import com.pitstop.log.LogBuffer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
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
    private val uploader: DriveUploader,
    private val json: Json,
    private val logs: LogBuffer,
    private val settings: SettingsRepository,
    @ApplicationContext private val context: Context,
) {
    private val _lastSealedAt = MutableStateFlow<Long?>(null)
    val lastSealedAt: StateFlow<Long?> = _lastSealedAt.asStateFlow()

    /** Own scope for the immediate-drain kick. Survives across the
     *  bridge service's restart since the singleton holds it. */
    private val ownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
     * Orphan-buffer recovery: called when DriveRecorder.open()
     * finds a prior buffer still open (engine_off never fired) OR
     * on app-start crash recovery. The caller passes the orphaned
     * [DriveBuffer] directly — we use its [DriveBuffer.startedAtMs]
     * as the drive start and `now` as the ended_at. Stamped
     * `incomplete=true` so the trip row badges accordingly.
     */
    suspend fun sealOrphan(orphan: DriveBuffer, deviceId: String): PendingDrive? {
        val now = System.currentTimeMillis()
        // The orphan's last engine event might not be there — add one
        // synthetic 'off' at now so the payload's engine_events bookend.
        orphan.addEngineEvent(now, "off")
        return persist(orphan, now, incomplete = true, deviceId = deviceId)
    }

    /** Per-app-install directory holding queued drive payloads on disk. */
    private fun payloadDir(): File =
        File(context.filesDir, "pending-drives").apply { mkdirs() }

    private fun payloadFile(uuid: String): File =
        File(payloadDir(), "$uuid.json")

    private suspend fun persist(
        buf: DriveBuffer,
        endedAtMs: Long,
        incomplete: Boolean,
        deviceId: String,
    ): PendingDrive? {
        // Defensive: ended_at must be after started_at AND the drive needs
        // enough substance to be worth uploading. The v0.1.157 STOPPED
        // handler fired engine-off in the same OBD burst that fired
        // engine-on, producing 3-frame drives with ended_at < started_at
        // that the backend then rejected with HTTP 400 and got stuck at
        // the head of the queue. Drop these at the source.
        if (endedAtMs <= buf.startedAtMs || buf.frameCount() < 5) {
            logs.warn(
                "DriveSealer: refusing implausible drive (would jam queue)",
                mapOf(
                    "started_at" to buf.startedAtMs,
                    "ended_at" to endedAtMs,
                    "frame_count" to buf.frameCount(),
                ),
            )
            return null
        }
        val dto = buf.seal(endedAtMs, incomplete, deviceId)
        val payloadJson = json.encodeToString(dto)
        // Write to disk before inserting. Inlining into the SQLite row
        // breaks down on big drives — Android's default CursorWindow
        // caps row reads at ~2 MB, so a 5+ MB row becomes unreadable
        // (CursorWindow IllegalStateException) and stalls the queue.
        val file = payloadFile(dto.clientDriveUuid)
        // The file-write + dao.insert pair must be atomic w.r.t.
        // cancellation: seal() is launched on the bridge service scope,
        // which onDestroy cancels. A cancel landing between writeText and
        // insert would lose the drive AND orphan the file. NonCancellable
        // guarantees the pair completes once started. Within it, write to
        // a .tmp and rename (atomic on the same filesystem) so a crash
        // mid-write never leaves a half-written payload the uploader
        // would then ship.
        val row = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(payloadJson)
            if (!tmp.renameTo(file)) {
                // Fallback if rename fails (eg. target exists on some FS
                // semantics) — overwrite directly.
                file.writeText(payloadJson)
                tmp.delete()
            }
            val r = PendingDrive(
                clientDriveUuid = dto.clientDriveUuid,
                vehicleId = dto.vehicleId,
                startedAt = buf.startedAtMs,
                endedAt = endedAtMs,
                incomplete = incomplete,
                frameCount = dto.frameCount,
                payloadJson = "",
                payloadFilePath = file.absolutePath,
            )
            dao.insert(r)
            r
        }
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
                "payload_file" to file.absolutePath,
            ),
        )
        kickWorker()
        return row
    }

    /**
     * Kick an immediate upload pass on our own scope. Previously this
     * went through WorkManager.OneTimeWork but the kicks never
     * actually executed — Hilt assisted-inject for the worker was
     * silently failing despite the periodic worker's logs working.
     * Direct scope.launch is more reliable for the "fire right after
     * seal" case; the WorkManager periodic + on-demand "Sync now"
     * button still call into the same [DriveUploader.drain].
     *
     * When [force] is false (the default — used by the post-seal
     * auto-kick) and the user has enabled manual-sync mode, this
     * no-ops; drives stay in the local queue until an explicit user
     * action calls back in with force=true (History "Sync now" button,
     * notification "Sync now" action).
     */
    fun kickWorker(force: Boolean = false, reason: String = "kicked after seal") {
        ownScope.launch {
            if (!force) {
                val manualOnly = runCatching { settings.settings.first().manualSyncOnly }
                    .getOrDefault(false)
                if (manualOnly) {
                    logs.info(
                        "DriveSealer.kickWorker: manual-sync mode — drive sealed, " +
                            "awaiting manual upload",
                    )
                    return@launch
                }
            }
            // Hand off to the uploader's own scope rather than draining
            // on ours: the pass then reports through UploadProgressBus
            // like every other entry point, and the user can cancel it
            // from the UI. This scope only does the manual-mode gate.
            uploader.requestDrain(reason)
        }
    }
}
