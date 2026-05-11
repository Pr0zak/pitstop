package com.pitstop.drive

import com.pitstop.http.PitstopApi
import com.pitstop.log.LogBuffer
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pure drain logic — shared by [DriveSealer]'s immediate kick (called
 * from the bridge service scope right after persist) and
 * [DriveUploadWorker]'s periodic backstop.
 *
 * The DriveSealer path is the primary, reliable trigger; WorkManager
 * has proven flaky for "fire immediately after a transient event"
 * in this codebase (the OneTimeWork from kickWorker never logged in
 * v0.1.102 testing — Hilt assisted-inject likely silently failing).
 * Keeping the worker around as a safety net but not relying on it.
 *
 * Storage model (v0.1.111+): payloads live on disk; the SQLite row
 * carries metadata + a file path. Loading the payload no longer
 * goes through the CursorWindow, so multi-megabyte drives upload
 * cleanly. Legacy rows from before the v1→v2 migration carry their
 * payload inline and aren't readable above ~2 MB; those rows are
 * dropped here (the server has the same trip via the live MQTT
 * stream + post-processed deriver, so no data is actually lost).
 */
@Singleton
class DriveUploader @Inject constructor(
    private val dao: PendingDriveDao,
    private val api: PitstopApi,
    private val json: Json,
    private val logs: LogBuffer,
) {
    /**
     * Drain unacked drives oldest-first until the queue empties or
     * the network fails. Returns the number of drives successfully
     * uploaded in this pass.
     */
    suspend fun drain(reason: String): Int {
        val unackedAtStart = dao.unackedCount()
        logs.info(
            "DriveUploader: starting",
            mapOf("unacked" to unackedAtStart, "reason" to reason),
        )
        val cutoff = System.currentTimeMillis() - ACK_RETENTION_MS
        val pruned = dao.pruneAcked(cutoff)
        if (pruned > 0) {
            logs.info(
                "DriveUploader: pruned acked rows older than 24h",
                mapOf("count" to pruned),
            )
        }

        // Track rows we've already attempted in this pass — Outcome.Failure
        // doesn't mark the row in any way visible to dao.oldestUnackedMeta(),
        // so without this gate we'd hot-loop forever on the same drive.
        val seenThisPass = mutableSetOf<String>()
        var drained = 0
        while (true) {
            val meta = dao.oldestUnackedMeta() ?: break
            if (meta.clientDriveUuid in seenThisPass) {
                logs.info(
                    "DriveUploader: stopping pass (head row already attempted)",
                    mapOf(
                        "client_drive_uuid" to meta.clientDriveUuid,
                        "drained_this_pass" to drained,
                    ),
                )
                break
            }
            seenThisPass.add(meta.clientDriveUuid)

            // Legacy oversized rows (pre-v0.1.111): payload was inlined
            // and is now unreadable for drives over ~2 MB. The live MQTT
            // stream + server-side deriver has the trip; drop the queue
            // row so it stops blocking the head.
            if (meta.payloadFilePath == null) {
                logs.warn(
                    "DriveUploader: dropping legacy oversize row (pre-v0.1.111)",
                    mapOf(
                        "client_drive_uuid" to meta.clientDriveUuid,
                        "frame_count" to meta.frameCount,
                    ),
                )
                dao.deleteByUuid(meta.clientDriveUuid)
                continue
            }

            when (tryUpload(meta)) {
                Outcome.Success -> drained += 1
                Outcome.Retry -> {
                    logs.info(
                        "DriveUploader: network/server error, stopping pass",
                        mapOf(
                            "client_drive_uuid" to meta.clientDriveUuid,
                            "attempt" to (meta.attemptCount + 1),
                            "drained_this_pass" to drained,
                        ),
                    )
                    return drained
                }
                Outcome.Failure -> {
                    val fresh = dao.oldestUnackedMeta()
                    val freshErr = if (fresh?.clientDriveUuid == meta.clientDriveUuid) {
                        fresh.lastError ?: "?"
                    } else "?"
                    logs.warn(
                        "DriveUploader: drive rejected by server, skipping",
                        mapOf(
                            "client_drive_uuid" to meta.clientDriveUuid,
                            "last_error" to freshErr,
                        ),
                    )
                }
            }
        }

        logs.info(
            "DriveUploader: queue drained this pass",
            mapOf("count" to drained, "still_unacked" to dao.unackedCount()),
        )
        return drained
    }

    private suspend fun tryUpload(meta: PendingDriveMeta): Outcome {
        val now = System.currentTimeMillis()
        val path = meta.payloadFilePath!!  // guarded by null-check in drain()
        val payloadJson = try {
            File(path).readText()
        } catch (t: Throwable) {
            dao.bumpAttempt(meta.clientDriveUuid, now, "payload file read: ${t.message}")
            return Outcome.Failure
        }
        val dto = try {
            json.decodeFromString<DriveUploadDto>(payloadJson)
        } catch (t: Throwable) {
            dao.bumpAttempt(meta.clientDriveUuid, now, "deserialise: ${t.message}")
            return Outcome.Failure
        }
        return try {
            val resp = api.postDrive(dto)
            dao.markAcked(meta.clientDriveUuid, now, resp.tripId)
            // Server has the data — release the on-disk copy now. The
            // row stays around for the 24-hour audit window but won't
            // be re-uploaded.
            runCatching { File(path).delete() }
            logs.info(
                "DriveUploader: upload accepted",
                mapOf(
                    "client_drive_uuid" to dto.clientDriveUuid,
                    "trip_id" to resp.tripId,
                    "duplicate" to resp.duplicate,
                    "frame_count_accepted" to resp.frameCountAccepted,
                ),
            )
            Outcome.Success
        } catch (t: retrofit2.HttpException) {
            val msg = "http ${t.code()}: ${t.message()}"
            dao.bumpAttempt(meta.clientDriveUuid, now, msg)
            if (t.code() in 400..499) Outcome.Failure else Outcome.Retry
        } catch (t: java.io.IOException) {
            dao.bumpAttempt(meta.clientDriveUuid, now, "io: ${t.message}")
            Outcome.Retry
        } catch (t: Throwable) {
            dao.bumpAttempt(meta.clientDriveUuid, now, "unexpected: ${t.message}")
            Outcome.Retry
        }
    }

    private enum class Outcome { Success, Retry, Failure }

    companion object {
        const val ACK_RETENTION_MS = 24L * 60 * 60 * 1000
    }
}
