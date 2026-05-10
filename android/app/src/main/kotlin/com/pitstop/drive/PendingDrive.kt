package com.pitstop.drive

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One queued drive awaiting upload to `POST /ingest/drive`.
 *
 * Created by [DriveSealer.seal] when the engine-off + presence-gate
 * triple-condition fires; deleted by [DriveUploadWorker] after the
 * server returns 2xx and a 24-hour grace period elapses (so the user
 * can audit the queue / divergence between phone and server before
 * the row disappears).
 *
 * `payloadJson` is the full request body as a JSON string. We don't
 * gzip in storage — SQLite + WAL handles compression well enough for
 * the few-MB-per-drive sizes, and reading + writing gzip would add
 * complexity that hides nothing useful. The phone storage usage at
 * typical drive cadence is well under any binding constraint.
 */
@Entity(tableName = "pending_drive")
data class PendingDrive(
    @PrimaryKey val clientDriveUuid: String,
    val vehicleId: String,
    val startedAt: Long,        // epoch millis
    val endedAt: Long,          // epoch millis
    val incomplete: Boolean,
    val frameCount: Int,
    val payloadJson: String,
    val attemptCount: Int = 0,
    val lastAttemptAt: Long? = null,
    val lastError: String? = null,
    val serverAckAt: Long? = null,
    val serverTripId: String? = null,
)
