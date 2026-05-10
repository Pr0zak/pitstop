package com.pitstop.drive

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.pitstop.http.PitstopApi
import com.pitstop.log.LogBuffer
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.serialization.json.Json

/**
 * Pulls oldest unacknowledged drive from [PendingDriveDao], POSTs to
 * `/ingest/drive`, marks acked on success.
 *
 * Backoff: relies on WorkManager's built-in exponential backoff for
 * unique workers. On HTTP 5xx / network failure we [Result.retry];
 * on 4xx we [Result.failure] (don't retry — that drive is broken).
 * On HTTP 2xx we mark acked + loop to drain the next row in the
 * same worker invocation.
 *
 * No queue cap — drives accumulate indefinitely until upload
 * succeeds. The History tab surfaces queue size so the user can
 * see pending count + size at a glance.
 *
 * Pruning: after acked, rows stay 24h so the user can audit the
 * server's reported frame_count vs the phone's. Pruning runs at the
 * top of each invocation.
 */
@HiltWorker
class DriveUploadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val dao: PendingDriveDao,
    private val api: PitstopApi,
    private val json: Json,
    private val logs: LogBuffer,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // Step 1: prune server-acked rows older than 24h. Cheap — one
        // SQL DELETE, no row reads. Keeps the table from growing
        // without bound across years of driving.
        val cutoff = System.currentTimeMillis() - ACK_RETENTION_MS
        val pruned = dao.pruneAcked(cutoff)
        if (pruned > 0) {
            logs.debug(
                "DriveUploadWorker: pruned acked rows older than 24h",
                mapOf("count" to pruned),
            )
        }

        // Step 2: drain unacked rows oldest-first. One worker pass
        // keeps trying until the network fails or the queue empties.
        var drained = 0
        while (true) {
            val row = dao.oldestUnacked() ?: break
            val outcome = tryUpload(row)
            when (outcome) {
                Outcome.Success -> {
                    drained += 1
                }
                Outcome.Retry -> {
                    logs.info(
                        "DriveUploadWorker: network/server error, retrying later",
                        mapOf(
                            "client_drive_uuid" to row.clientDriveUuid,
                            "attempt" to (row.attemptCount + 1),
                            "drained_this_pass" to drained,
                        ),
                    )
                    return if (drained > 0) Result.success() else Result.retry()
                }
                Outcome.Failure -> {
                    // Client-side error (4xx). The drive itself is
                    // broken; bumpAttempt records the failure but
                    // we move on to the next row.
                    logs.warn(
                        "DriveUploadWorker: drive rejected by server, skipping",
                        mapOf(
                            "client_drive_uuid" to row.clientDriveUuid,
                            "last_error" to (row.lastError ?: "?"),
                        ),
                    )
                    // Move on — leave the row in place so the user can
                    // see it in the queue. The Sync-now button can
                    // retry manually.
                }
            }
        }

        logs.debug(
            "DriveUploadWorker: queue drained this pass",
            mapOf("count" to drained),
        )
        return Result.success()
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
                "DriveUploadWorker: upload accepted",
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
            // 4xx → broken payload; don't retry. 5xx → retry.
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
        const val TAG = "drive-upload"
        const val UNIQUE_NAME = "drive-upload-unique"
        const val ACK_RETENTION_MS = 24L * 60 * 60 * 1000
    }
}
