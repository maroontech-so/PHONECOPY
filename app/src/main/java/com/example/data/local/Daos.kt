package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.core.model.DeliveryState
import com.example.core.model.DeliveryStateMachine
import kotlinx.coroutines.flow.Flow

@Dao
interface OutboxDao {
    @Query("SELECT * FROM outbox_events ORDER BY sequenceNumber DESC")
    fun observeAll(): Flow<List<OutboxEventEntity>>

    @Query("SELECT COUNT(*) FROM outbox_events WHERE state IN ('CAPTURED', 'PENDING', 'SENDING', 'RETRY_WAIT')")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM outbox_events WHERE state = 'SERVER_ACCEPTED'")
    fun observeUnacknowledgedByDeviceCount(): Flow<Int>

    @Query("SELECT COALESCE(SUM(retryCount), 0) FROM outbox_events WHERE state IN ('RETRY_WAIT', 'SENDING', 'PENDING')")
    fun observeActiveRetryCount(): Flow<Int>

    @Query("SELECT * FROM outbox_events WHERE eventId = :eventId")
    suspend fun getById(eventId: String): OutboxEventEntity?

    @Query(
        "SELECT * FROM outbox_events WHERE sourceDedupKey = :dedupKey AND createdAt >= :sinceMs AND state != 'EXPIRED' AND state != 'PERMANENT_FAILURE' LIMIT 1"
    )
    suspend fun findRecentDuplicate(dedupKey: String, sinceMs: Long): OutboxEventEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: OutboxEventEntity)

    @Query(
        """
        SELECT * FROM outbox_events
        WHERE (state = 'PENDING' OR (state = 'RETRY_WAIT' AND nextAttemptAt <= :nowMs))
          AND expiresAt > :nowMs
        ORDER BY priority DESC, sequenceNumber ASC
        LIMIT :limit
        """
    )
    suspend fun findEligibleForSend(nowMs: Long, limit: Int): List<OutboxEventEntity>

    @Query(
        """
        UPDATE outbox_events
        SET state = :newState, lastAttemptAt = :attemptAt
        WHERE eventId = :eventId AND state = :expectedState
        """
    )
    suspend fun compareAndUpdateState(
        eventId: String,
        expectedState: String,
        newState: String,
        attemptAt: Long
    ): Int

    @Query(
        """
        UPDATE outbox_events
        SET state = :newState,
            retryCount = :retryCount,
            nextAttemptAt = :nextAttemptAt,
            lastAttemptAt = :lastAttemptAt,
            errorCode = :errorCode,
            errorSummary = :errorSummary
        WHERE eventId = :eventId
        """
    )
    suspend fun updateRetryOrFailure(
        eventId: String,
        newState: String,
        retryCount: Int,
        nextAttemptAt: Long,
        lastAttemptAt: Long,
        errorCode: String?,
        errorSummary: String?
    )

    @Query(
        """
        UPDATE outbox_events
        SET state = 'SERVER_ACCEPTED', serverAcceptedAt = :acceptedAt, errorCode = NULL, errorSummary = NULL
        WHERE eventId = :eventId AND state IN ('PENDING', 'SENDING', 'RETRY_WAIT')
        """
    )
    suspend fun markServerAccepted(eventId: String, acceptedAt: Long)

    @Query(
        """
        UPDATE outbox_events
        SET state = 'DEVICE_RECEIVED', deviceReceivedAt = :receivedAt
        WHERE eventId = :eventId AND state IN ('SENDING', 'SERVER_ACCEPTED', 'PENDING', 'RETRY_WAIT')
        """
    )
    suspend fun markDeviceReceived(eventId: String, receivedAt: Long)

    @Query(
        """
        UPDATE outbox_events
        SET state = 'PENDING'
        WHERE state = 'SENDING' AND (lastAttemptAt IS NULL OR lastAttemptAt < :staleBeforeMs)
        """
    )
    suspend fun recoverStaleSending(staleBeforeMs: Long): Int

    @Query(
        """
        UPDATE outbox_events
        SET state = 'EXPIRED', errorCode = 'EXPIRED_EVENT', errorSummary = 'Exceeded retention deadline'
        WHERE expiresAt <= :nowMs AND state IN ('CAPTURED', 'PENDING', 'SENDING', 'RETRY_WAIT', 'SERVER_ACCEPTED')
        """
    )
    suspend fun expireOverdue(nowMs: Long): Int

    @Query("DELETE FROM outbox_events WHERE expiresAt < :purgeBeforeMs AND state IN ('EXPIRED', 'DEVICE_RECEIVED', 'READ', 'PERMANENT_FAILURE')")
    suspend fun purgeOldTerminal(purgeBeforeMs: Long): Int

    @Query("SELECT * FROM outbox_events WHERE state IN ('PENDING', 'RETRY_WAIT') ORDER BY priority DESC, sequenceNumber ASC")
    suspend fun listPendingOrWaiting(): List<OutboxEventEntity>

    @Transaction
    suspend fun claimEligibleBatch(nowMs: Long, limit: Int): List<OutboxEventEntity> {
        expireOverdue(nowMs)
        recoverStaleSending(nowMs - 60_000L)
        val candidates = findEligibleForSend(nowMs, limit)
        val claimed = mutableListOf<OutboxEventEntity>()
        for (item in candidates) {
            val currentState = DeliveryState.valueOf(item.state)
            if (DeliveryStateMachine.canTransition(currentState, DeliveryState.SENDING)) {
                val updated = compareAndUpdateState(
                    eventId = item.eventId,
                    expectedState = item.state,
                    newState = DeliveryState.SENDING.name,
                    attemptAt = nowMs
                )
                if (updated == 1) {
                    claimed.add(item.copy(state = DeliveryState.SENDING.name, lastAttemptAt = nowMs))
                }
            }
        }
        return claimed
    }
}

@Dao
interface InboxDao {
    @Query("SELECT * FROM inbox_events WHERE quarantined = 0 ORDER BY sequenceNumber DESC")
    fun observeInbox(): Flow<List<InboxEventEntity>>

    @Query("SELECT * FROM inbox_events WHERE quarantined = 1 ORDER BY receivedAt DESC")
    fun observeQuarantined(): Flow<List<InboxEventEntity>>

    @Query("SELECT COUNT(*) FROM inbox_events WHERE ackPending = 1 AND quarantined = 0")
    fun observeAckPendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM inbox_events WHERE state = 'DEVICE_RECEIVED' AND quarantined = 0")
    fun observeUnreadCount(): Flow<Int>

    @Query("SELECT * FROM inbox_events WHERE eventId = :eventId")
    suspend fun getById(eventId: String): InboxEventEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(event: InboxEventEntity): Long

    @Query("SELECT * FROM inbox_events WHERE ackPending = 1 AND quarantined = 0 ORDER BY sequenceNumber ASC LIMIT :limit")
    suspend fun listPendingAcks(limit: Int): List<InboxEventEntity>

    @Query("UPDATE inbox_events SET ackPending = 0, ackSentAt = :ackedAt WHERE eventId IN (:eventIds)")
    suspend fun markAcknowledged(eventIds: List<String>, ackedAt: Long)

    @Query("UPDATE inbox_events SET state = 'READ', readAt = :readAt WHERE eventId = :eventId AND state = 'DEVICE_RECEIVED'")
    suspend fun markRead(eventId: String, readAt: Long)

    @Query("UPDATE inbox_events SET calendarEventUri = :uri WHERE eventId = :eventId")
    suspend fun updateCalendarUri(eventId: String, uri: String)

    @Query("DELETE FROM inbox_events WHERE expiresAt < :nowMs")
    suspend fun purgeExpired(nowMs: Long): Int
}

@Dao
interface SyncCursorDao {
    @Query("SELECT * FROM sync_cursors WHERE relationshipId = :relationshipId")
    fun observeCursor(relationshipId: String): Flow<SyncCursorEntity?>

    @Query("SELECT * FROM sync_cursors WHERE relationshipId = :relationshipId")
    suspend fun getCursor(relationshipId: String): SyncCursorEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(cursor: SyncCursorEntity)
}

@Dao
interface RelationshipDao {
    @Query("SELECT * FROM relationships WHERE status = 'ACTIVE' ORDER BY createdAt DESC LIMIT 1")
    fun observeActiveRelationship(): Flow<RelationshipEntity?>

    @Query("SELECT * FROM relationships WHERE status = 'ACTIVE' ORDER BY createdAt DESC LIMIT 1")
    suspend fun getActiveRelationship(): RelationshipEntity?

    @Query("SELECT * FROM relationships WHERE relationshipId = :relationshipId")
    suspend fun getById(relationshipId: String): RelationshipEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(relationship: RelationshipEntity)

    @Query("UPDATE relationships SET nextSequenceNumber = nextSequenceNumber + 1 WHERE relationshipId = :relationshipId")
    suspend fun incrementSequenceNumber(relationshipId: String)

    @Query("UPDATE relationships SET status = 'REVOKED', revokedAt = :revokedAt WHERE relationshipId = :relationshipId")
    suspend fun markRevoked(relationshipId: String, revokedAt: Long)

    @Query(
        """
        UPDATE relationships
        SET wrappedRootKeyB64 = :wrappedKeyB64, wrappedRootKeyIvB64 = :wrappedKeyIvB64, keyEpoch = :newEpoch
        WHERE relationshipId = :relationshipId
        """
    )
    suspend fun updateRotatedKey(
        relationshipId: String,
        wrappedKeyB64: String,
        wrappedKeyIvB64: String,
        newEpoch: Int
    )

    @Transaction
    suspend fun allocateNextSequenceAndGet(relationshipId: String): Pair<RelationshipEntity, Long> {
        val current = getById(relationshipId)
            ?: error("Active relationship not found")
        require(current.status == "ACTIVE") { "Relationship is revoked" }
        val allocatedSeq = current.nextSequenceNumber
        incrementSequenceNumber(relationshipId)
        return current to allocatedSeq
    }
}

@Dao
interface DiagnosticLogDao {
    @Query("SELECT * FROM diagnostic_logs ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<DiagnosticLogEntity>>

    @Insert
    suspend fun insert(log: DiagnosticLogEntity)

    @Query("DELETE FROM diagnostic_logs WHERE id NOT IN (SELECT id FROM diagnostic_logs ORDER BY timestamp DESC LIMIT 250)")
    suspend fun trimOld()
}
