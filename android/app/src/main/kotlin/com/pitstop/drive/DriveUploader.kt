package com.pitstop.drive

import com.pitstop.http.PitstopApi
import com.pitstop.log.LogBuffer
import kotlinx.serialization.json.Json
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

        var drained = 0
        while (true) {
            val row = dao.oldestUnacked() ?: break
            val outcome = tryUpload(row)
            when (outcome) {
                Outcome.Success -> drained += 1
                Outcome.Retry -> {
                    logs.info(
                        "DriveUploader: network/server error, stopping pass",
                        mapOf(
                            "client_drive_uuid" to row.clientDriveUuid,
                            "attempt" to (row.attemptCount + 1),
                            "drained_this_pass" to drained,
                        ),
                    )
                    return drained
                }
                Outcome.Failure -> {
                    logs.warn(
                        "DriveUploader: drive rejected by server, skipping",
                        mapOf(
                            "client_drive_uuid" to row.clientDriveUuid,
                            "last_error" to (row.lastError ?: "?"),
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

    private suspend fun tryUpload(row: PendingDrive): Outcome {
        val now = System.currentTimeMillis()
        val dto = try {
            json.decodeFromString<DriveUploadDto>(row.payloadJson)
        } catch (t: Throwable) {
            dao.bumpAttempt(row.clientDriveUuid, now, "deserialise: ${t.message}")
            return Outcome.Failure
        }
        return try {
            val resp = api.postDrive(dto)
            dao.markAcked(row.clientDriveUuid, now, resp.tripId)
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
            dao.bumpAttempt(row.clientDriveUuid, now, msg)
            if (t.code() in 400..499) Outcome.Failure else Outcome.Retry
        } catch (t: java.io.IOException) {
            dao.bumpAttempt(row.clientDriveUuid, now, "io: ${t.message}")
            Outcome.Retry
        } catch (t: Throwable) {
            dao.bumpAttempt(row.clientDriveUuid, now, "unexpected: ${t.message}")
            Outcome.Retry
        }
    }

    private enum class Outcome { Success, Retry, Failure }

    companion object {
        const val ACK_RETENTION_MS = 24L * 60 * 60 * 1000
    }
}
