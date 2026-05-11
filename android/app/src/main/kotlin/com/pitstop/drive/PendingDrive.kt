package com.pitstop.drive

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One queued drive awaiting upload to `POST /ingest/drive`.
 *
 * Created by [DriveSealer.seal] when the engine-off + presence-gate
 * triple-condition fires; deleted after the server returns 2xx and
 * a 24-hour grace period elapses.
 *
 * Storage model (v0.1.111+): the JSON payload lives on disk at
 * [payloadFilePath]; only metadata is kept in the SQLite row. The
 * old design inlined the payload into [payloadJson] but Android's
 * default CursorWindow caps row reads at ~2 MB — a sealed drive
 * with 40 k frames hit 5.7 MB and became permanently unreadable,
 * stalling the entire upload queue. Files have no such limit, and
 * cleanup is just `file.delete()` after the ack.
 *
 * [payloadJson] is retained as a NOT NULL column (SQLite can't drop
 * NOT NULL via ALTER, and the migration would have to rebuild the
 * table). New rows write the empty string; legacy rows (created
 * before the v1→v2 migration) carry their original inline payload
 * but the uploader drops them rather than try to read.
 */
@Entity(tableName = "pending_drive")
data class PendingDrive(
    @PrimaryKey val clientDriveUuid: String,
    val vehicleId: String,
    val startedAt: Long,        // epoch millis
    val endedAt: Long,          // epoch millis
    val incomplete: Boolean,
    val frameCount: Int,
    /** Legacy inline payload — new rows write "". Always non-null
     *  for schema-compat reasons (see kdoc). */
    val payloadJson: String,
    /** Absolute path to the on-disk JSON payload. Non-null for any
     *  row created by v0.1.111+; null for legacy rows that the
     *  uploader will drop. */
    val payloadFilePath: String? = null,
    val attemptCount: Int = 0,
    val lastAttemptAt: Long? = null,
    val lastError: String? = null,
    val serverAckAt: Long? = null,
    val serverTripId: String? = null,
)
