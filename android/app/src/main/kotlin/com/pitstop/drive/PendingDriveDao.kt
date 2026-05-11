package com.pitstop.drive

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Projection over [PendingDrive] that omits [PendingDrive.payloadJson].
 * Reading legacy oversized rows via the full entity throws
 * `CursorWindow` errors at the SQLite layer, so the upload pass works
 * off this metadata-only view and loads the payload from disk (or
 * drops the row) based on [payloadFilePath].
 */
data class PendingDriveMeta(
    val clientDriveUuid: String,
    val vehicleId: String,
    val startedAt: Long,
    val endedAt: Long,
    val incomplete: Boolean,
    val frameCount: Int,
    val attemptCount: Int,
    val lastAttemptAt: Long?,
    val lastError: String?,
    val payloadFilePath: String?,
)

/**
 * Data access for [PendingDrive]. The upload worker consumes
 * [oldestUnackedMeta] in a loop; the History UI observes
 * [observeUnackedCount] for the badge.
 */
@Dao
interface PendingDriveDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(drive: PendingDrive): Long

    @Query(
        "SELECT clientDriveUuid, vehicleId, startedAt, endedAt, incomplete, " +
            "frameCount, attemptCount, lastAttemptAt, lastError, payloadFilePath " +
            "FROM pending_drive WHERE serverAckAt IS NULL " +
            "ORDER BY startedAt ASC LIMIT 1"
    )
    suspend fun oldestUnackedMeta(): PendingDriveMeta?

    @Query("SELECT COUNT(*) FROM pending_drive WHERE serverAckAt IS NULL")
    suspend fun unackedCount(): Int

    @Query("SELECT COUNT(*) FROM pending_drive WHERE serverAckAt IS NULL")
    fun observeUnackedCount(): Flow<Int>

    @Query("SELECT EXISTS(SELECT 1 FROM pending_drive WHERE clientDriveUuid = :uuid)")
    suspend fun exists(uuid: String): Boolean

    @Query(
        "UPDATE pending_drive SET serverAckAt = :ackAt, serverTripId = :tripId, " +
            "lastError = NULL WHERE clientDriveUuid = :uuid"
    )
    suspend fun markAcked(uuid: String, ackAt: Long, tripId: String)

    @Query(
        "UPDATE pending_drive SET attemptCount = attemptCount + 1, " +
            "lastAttemptAt = :at, lastError = :err WHERE clientDriveUuid = :uuid"
    )
    suspend fun bumpAttempt(uuid: String, at: Long, err: String)

    /** Drop server-acked rows older than the retention window. */
    @Query(
        "DELETE FROM pending_drive WHERE serverAckAt IS NOT NULL " +
            "AND serverAckAt < :cutoffMs"
    )
    suspend fun pruneAcked(cutoffMs: Long): Int

    @Query("DELETE FROM pending_drive WHERE clientDriveUuid = :uuid")
    suspend fun deleteByUuid(uuid: String)
}
