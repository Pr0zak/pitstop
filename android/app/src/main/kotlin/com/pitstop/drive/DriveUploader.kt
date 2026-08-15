package com.pitstop.drive

import android.content.Context
import com.pitstop.http.PitstopApi
import com.pitstop.log.LogBuffer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
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
 *
 * Progress reporting: every pass publishes to [UploadProgressBus] —
 * which drive is in flight, its position in the queue, how many bytes
 * have gone out, and how the pass ended. The bus is a process-lifetime
 * singleton so a pass started from any entry point (post-seal kick,
 * periodic worker, notification action, History "Sync now") is visible
 * on every surface, and stays visible when the user leaves the screen
 * they started it from.
 */
@Singleton
class DriveUploader @Inject constructor(
    private val dao: PendingDriveDao,
    private val api: PitstopApi,
    private val json: Json,
    private val logs: LogBuffer,
    private val progress: UploadProgressBus,
    @ApplicationContext private val context: Context,
) {
    /**
     * Serialises drain passes. The post-seal kick (bridge scope), the
     * periodic WorkManager backstop, and the manual "Sync now" button
     * can all call drain() concurrently — without a guard two of them
     * could pull the same oldest-unacked row and double-upload over
     * cellular. tryLock + early-out: a pass already in flight will
     * cover the queue, so a concurrent caller can safely skip.
     */
    private val drainMutex = Mutex()

    /**
     * Process-lifetime scope for [requestDrain]. Deliberately NOT a
     * caller's scope: a drain launched on a ViewModel's scope dies
     * when its screen goes away, and the History tab is disposed the
     * moment the pager scrolls off it. That made "tap Sync now, swipe
     * to another tab" silently abort the upload with no feedback.
     */
    private val ownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The coroutine running the pass that currently holds [drainMutex],
     * whoever started it — [requestDrain], the WorkManager backstop or
     * the notification receiver. Captured inside [drain] rather than at
     * launch so [cancelDrain] works for every entry point instead of
     * only the one the UI happens to use.
     */
    @Volatile
    private var currentPassJob: Job? = null

    /**
     * Fire-and-forget drain on the uploader's own scope. Returns
     * immediately; callers watch [UploadProgressBus.state] for the
     * result. Safe to call repeatedly — a pass already running keeps
     * running and the extra call is dropped.
     */
    fun requestDrain(reason: String) {
        if (currentPassJob?.isActive == true) {
            logs.info(
                "DriveUploader: drain already running, letting it finish",
                mapOf("reason" to reason),
            )
            return
        }
        ownScope.launch {
            runCatching { drain(reason) }.onFailure { t ->
                if (t is CancellationException) throw t
                logs.warn(
                    "DriveUploader: requested drain threw",
                    mapOf("err" to (t.message ?: t::class.java.simpleName)),
                )
            }
        }
    }

    /**
     * Abort the in-flight pass. The drive currently being sent is left
     * queued (nothing is acked until the server responds), so a cancel
     * costs at most the bytes already on the wire.
     */
    fun cancelDrain() {
        val job = currentPassJob
        if (job == null || !job.isActive) return
        logs.info("DriveUploader: drain cancelled by user")
        job.cancel()
    }

    /**
     * Drain unacked drives oldest-first until the queue empties or
     * the network fails. Returns the number of drives successfully
     * uploaded in this pass.
     */
    suspend fun drain(reason: String): Int {
        if (!drainMutex.tryLock()) {
            // Deliberately does NOT touch the progress bus: the pass
            // that holds the lock is the one reporting, and overwriting
            // its state with a synthetic "finished, 0 uploaded" is
            // exactly the false "nothing happened" the UI used to show.
            logs.info(
                "DriveUploader: drain already in progress, skipping",
                mapOf("reason" to reason),
            )
            return 0
        }
        try {
            currentPassJob = currentCoroutineContext()[Job]
            return drainLocked(reason)
        } catch (c: CancellationException) {
            progress.set(
                UploadProgress.Finished(
                    reason = reason,
                    finishedAtMs = System.currentTimeMillis(),
                    uploaded = uploadedThisPass,
                    // Derived, not queried: the coroutine is already
                    // cancelled here, so any suspend DAO call would just
                    // throw again and leave the summary saying "0 left".
                    rejected = 0,
                    remaining = (passQueueDepth - uploadedThisPass).coerceAtLeast(0),
                    outcome = UploadOutcome.Cancelled,
                ),
            )
            throw c
        } finally {
            currentPassJob = null
            drainMutex.unlock()
        }
    }

    /** Uploads acked so far in the pass, and the queue depth it began
     *  with. Both are read by the cancel path, which cannot query the
     *  DAO, so the cancelled summary can still be honest about how far
     *  the pass got. */
    @Volatile
    private var uploadedThisPass = 0

    @Volatile
    private var passQueueDepth = 0

    private suspend fun drainLocked(reason: String): Int {
        val passStartedAt = System.currentTimeMillis()
        val unackedAtStart = dao.unackedCount()
        uploadedThisPass = 0
        passQueueDepth = unackedAtStart
        logs.info(
            "DriveUploader: starting",
            mapOf("unacked" to unackedAtStart, "reason" to reason),
        )
        // Orphan-file sweep: delete pending-drives/*.json payloads with
        // no owning DB row and an mtime older than 7d. Covers files left
        // behind by a crash between writeText and dao.insert, or by a row
        // deleted without its file. Bounded by age so an in-flight seal's
        // freshly-written file (insert not yet committed) is never swept.
        runCatching { sweepOrphanPayloads() }.onFailure {
            logs.warn(
                "DriveUploader: orphan sweep failed",
                mapOf("err" to (it.message ?: it::class.java.simpleName)),
            )
        }
        val cutoff = System.currentTimeMillis() - ACK_RETENTION_MS
        val pruned = dao.pruneAcked(cutoff)
        if (pruned > 0) {
            logs.info(
                "DriveUploader: pruned acked rows older than 24h",
                mapOf("count" to pruned),
            )
        }

        if (unackedAtStart == 0) {
            progress.set(
                UploadProgress.Finished(
                    reason = reason,
                    finishedAtMs = System.currentTimeMillis(),
                    uploaded = 0,
                    rejected = 0,
                    remaining = 0,
                    outcome = UploadOutcome.NothingQueued,
                ),
            )
            logs.info("DriveUploader: nothing queued", mapOf("reason" to reason))
            return 0
        }

        // Track rows we've already attempted in this pass — Outcome.Failure
        // doesn't mark the row in any way visible to dao.oldestUnackedMeta(),
        // so without this gate we'd hot-loop forever on the same drive.
        val seenThisPass = mutableSetOf<String>()
        var drained = 0
        var rejected = 0
        var index = 0
        var outcome = UploadOutcome.Completed
        var detail: String? = null
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
                outcome = UploadOutcome.Stalled
                detail = meta.lastError
                break
            }
            seenThisPass.add(meta.clientDriveUuid)

            // Legacy oversized rows (pre-v0.1.111): payload was inlined
            // and is now unreadable for drives over ~2 MB. The live MQTT
            // stream + server-side deriver has the trip; drop the queue
            // row so it stops blocking the head.
            val payloadPath = meta.payloadFilePath
            if (payloadPath == null) {
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

            index += 1
            val now = System.currentTimeMillis()
            progress.set(
                UploadProgress.Running(
                    reason = reason,
                    passStartedAtMs = passStartedAt,
                    driveIndex = index,
                    // A pass can outlive its own starting count when a
                    // drive seals mid-drain; never show "4 of 3".
                    driveTotal = maxOf(unackedAtStart, index),
                    uploadedThisPass = drained,
                    driveStartedAtMs = meta.startedAt,
                    driveEndedAtMs = meta.endedAt,
                    frameCount = meta.frameCount,
                    payloadBytes = runCatching { File(payloadPath).length() }.getOrDefault(0L),
                    bytesSent = 0L,
                    phase = UploadPhase.Reading,
                    phaseSinceMs = now,
                    priorAttempts = meta.attemptCount,
                ),
            )

            when (tryUpload(meta)) {
                Outcome.Success -> {
                    drained += 1
                    uploadedThisPass = drained
                }
                Outcome.Retry -> {
                    val fresh = dao.oldestUnackedMeta()
                    detail = if (fresh?.clientDriveUuid == meta.clientDriveUuid) {
                        fresh.lastError
                    } else {
                        null
                    }
                    logs.info(
                        "DriveUploader: network/server error, stopping pass",
                        mapOf(
                            "client_drive_uuid" to meta.clientDriveUuid,
                            "attempt" to (meta.attemptCount + 1),
                            "drained_this_pass" to drained,
                        ),
                    )
                    outcome = UploadOutcome.NetworkStopped
                    break
                }
                Outcome.Failure -> {
                    // 4xx is the server saying "this payload is broken,
                    // don't retry." Drop the row + on-disk payload so
                    // it stops blocking the head of the queue — without
                    // this, a single bogus drive jams every subsequent
                    // sync attempt with "head row already attempted".
                    val fresh = dao.oldestUnackedMeta()
                    val freshErr = if (fresh?.clientDriveUuid == meta.clientDriveUuid) {
                        fresh.lastError ?: "?"
                    } else "?"
                    logs.warn(
                        "DriveUploader: drive rejected by server, dropping",
                        mapOf(
                            "client_drive_uuid" to meta.clientDriveUuid,
                            "last_error" to freshErr,
                        ),
                    )
                    runCatching { File(payloadPath).delete() }
                    dao.deleteByUuid(meta.clientDriveUuid)
                    rejected += 1
                    detail = freshErr
                }
            }
        }

        val remaining = dao.unackedCount()
        progress.set(
            UploadProgress.Finished(
                reason = reason,
                finishedAtMs = System.currentTimeMillis(),
                uploaded = drained,
                rejected = rejected,
                remaining = remaining,
                outcome = outcome,
                detail = detail,
            ),
        )
        logs.info(
            "DriveUploader: queue drained this pass",
            mapOf(
                "count" to drained,
                "rejected" to rejected,
                "still_unacked" to remaining,
                "outcome" to outcome.name,
            ),
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
        // The interceptor takes over the phase from here (Sending →
        // AwaitingServer as the body finishes). Seed it so the UI never
        // sits on "Reading" while the request is already open.
        progress.updateRunning {
            it.copy(
                phase = UploadPhase.Sending,
                phaseSinceMs = System.currentTimeMillis(),
                payloadBytes = if (it.payloadBytes > 0L) it.payloadBytes else payloadJson.length.toLong(),
            )
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
        } catch (c: CancellationException) {
            throw c
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

    private suspend fun sweepOrphanPayloads() {
        val dir = File(context.filesDir, "pending-drives")
        if (!dir.isDirectory) return
        val files = dir.listFiles() ?: return
        if (files.isEmpty()) return
        val known = dao.allPayloadFilePaths().toHashSet()
        val ageCutoff = System.currentTimeMillis() - ORPHAN_SWEEP_AGE_MS
        var deleted = 0
        for (f in files) {
            if (!f.isFile) continue
            // Skip in-flight .tmp writes and anything a row still owns.
            if (f.name.endsWith(".tmp")) continue
            if (f.absolutePath in known) continue
            if (f.lastModified() > ageCutoff) continue
            if (f.delete()) deleted++
        }
        if (deleted > 0) {
            logs.info(
                "DriveUploader: swept orphan drive payloads",
                mapOf("deleted" to deleted),
            )
        }
    }

    private enum class Outcome { Success, Retry, Failure }

    companion object {
        const val ACK_RETENTION_MS = 24L * 60 * 60 * 1000
        const val ORPHAN_SWEEP_AGE_MS = 7L * 24 * 60 * 60 * 1000
    }
}
