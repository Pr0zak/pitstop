package com.pitstop.drive

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the uploader is doing to the drive currently in flight.
 *
 * The distinction between [Sending] and [AwaitingServer] is the whole
 * point of the enum: a multi-megabyte drive payload streams out in a
 * few seconds, and then the request sits open for as long as the
 * backend needs to write the hypertable inserts. Collapsing both into
 * one "uploading" state is what made a healthy-but-slow upload look
 * identical to a hang.
 */
enum class UploadPhase { Reading, Sending, AwaitingServer }

/**
 * Why a drain pass ended. Reported so the UI can say something true
 * instead of the old unconditional "Synced N drives".
 */
enum class UploadOutcome {
    /** Queue emptied — nothing left to send. */
    Completed,

    /** Nothing was queued when the pass started. */
    NothingQueued,

    /** Network or 5xx failure; the remaining drives stay queued. */
    NetworkStopped,

    /** The user cancelled mid-pass. */
    Cancelled,

    /** The pass gave up because the head row had already been tried
     *  this pass (see DriveUploader's seenThisPass gate). */
    Stalled,
}

/**
 * Upload state, published process-wide by [UploadProgressBus].
 *
 * Every caller of [DriveUploader.drain] feeds this — the post-seal
 * auto-kick, the WorkManager backstop, the notification action and the
 * History "Sync now" button alike — so the UI reports what the app is
 * actually doing rather than only what the user started from a screen
 * that happens to be open.
 */
sealed interface UploadProgress {

    data object Idle : UploadProgress

    data class Running(
        /** Free-text reason string the drain caller passed in. */
        val reason: String,
        val passStartedAtMs: Long,
        /** 1-based position of the drive in flight within this pass. */
        val driveIndex: Int,
        /** Queue depth when the pass started — the denominator. */
        val driveTotal: Int,
        val uploadedThisPass: Int,
        /** Wall-clock bounds of the drive being sent, for labelling. */
        val driveStartedAtMs: Long,
        val driveEndedAtMs: Long,
        val frameCount: Int,
        /** Size of the on-disk payload. 0 until the file is measured. */
        val payloadBytes: Long,
        val bytesSent: Long,
        val phase: UploadPhase,
        /** When the current [phase] began — lets the UI age a stall. */
        val phaseSinceMs: Long,
        /** How many prior attempts this drive has already had. */
        val priorAttempts: Int,
    ) : UploadProgress {
        /** Null when the payload size isn't known yet, so the UI can
         *  fall back to an indeterminate bar rather than showing a
         *  confident 0 %. */
        val fraction: Float?
            get() = if (payloadBytes > 0L) {
                (bytesSent.toDouble() / payloadBytes.toDouble()).coerceIn(0.0, 1.0).toFloat()
            } else {
                null
            }
    }

    data class Finished(
        val reason: String,
        val finishedAtMs: Long,
        val uploaded: Int,
        /** Drives the server refused (4xx) and we dropped. */
        val rejected: Int,
        /** Still unacked after the pass. */
        val remaining: Int,
        val outcome: UploadOutcome,
        /** Last error string when [outcome] is NetworkStopped. */
        val detail: String? = null,
    ) : UploadProgress
}

/**
 * Singleton holder for [UploadProgress]. Lives for the process, not
 * for any ViewModel, so swiping away from the History tab (which
 * disposes that page and its ViewModel — the pager keeps
 * beyondViewportPageCount at 0) no longer loses the upload's state.
 *
 * Written from two places: [DriveUploader] drives the coarse
 * per-drive state machine, and
 * [com.pitstop.http.DriveUploadProgressInterceptor] folds in the
 * byte counter as OkHttp writes the request body.
 */
@Singleton
class UploadProgressBus @Inject constructor() {

    private val _state = MutableStateFlow<UploadProgress>(UploadProgress.Idle)
    val state: StateFlow<UploadProgress> = _state.asStateFlow()

    fun set(next: UploadProgress) {
        _state.value = next
    }

    /** Apply [block] only while a pass is running. A no-op otherwise,
     *  so a late byte-progress callback from a cancelled request can't
     *  resurrect a finished pass. */
    fun updateRunning(block: (UploadProgress.Running) -> UploadProgress.Running) {
        _state.update { current ->
            if (current is UploadProgress.Running) block(current) else current
        }
    }

    val running: UploadProgress.Running?
        get() = _state.value as? UploadProgress.Running
}
