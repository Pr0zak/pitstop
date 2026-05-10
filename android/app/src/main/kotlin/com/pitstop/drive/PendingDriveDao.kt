package com.pitstop.drive

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data access for [PendingDrive]. The upload worker consumes
 * [oldestUnacked] in a loop; the History UI observes
 * [observeUnacked] / [observeAllRecent] to render the queue.
 */
@Dao
interface PendingDriveDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(drive: PendingDrive): Long

    @Query("SELECT * FROM pending_drive WHERE serverAckAt IS NULL ORDER BY startedAt ASC LIMIT 1")
    suspend fun oldestUnacked(): PendingDrive?

    @Query("SELECT * FROM pending_drive WHERE serverAckAt IS NULL ORDER BY startedAt ASC")
    fun observeUnacked(): Flow<List<PendingDrive>>

    @Query("SELECT COUNT(*) FROM pending_drive WHERE serverAckAt IS NULL")
    suspend fun unackedCount(): Int

    @Query("SELECT COUNT(*) FROM pending_drive WHERE serverAckAt IS NULL")
    fun observeUnackedCount(): Flow<Int>

    @Query("SELECT * FROM pending_drive ORDER BY startedAt DESC LIMIT :limit")
    fun observeAllRecent(limit: Int = 20): Flow<List<PendingDrive>>

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
