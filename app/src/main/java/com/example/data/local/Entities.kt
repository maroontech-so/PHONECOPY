package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "outbox_events",
    indices = [
        Index(value = ["relationshipId", "sequenceNumber"], unique = true),
        Index(value = ["state", "nextAttemptAt"]),
        Index(value = ["sourceDedupKey", "createdAt"]),
        Index(value = ["expiresAt"])
    ]
)
data class OutboxEventEntity(
    @PrimaryKey val eventId: String,
    val relationshipId: String,
    val sequenceNumber: Long,
    val sourceDedupKey: String?,
    val eventType: String,
    val keyEpoch: Int,
    val encryptedPayloadB64: String,
    val nonceB64: String,
    val authTagB64: String,
    val ciphertextHash: String,
    val createdAt: Long,
    val expiresAt: Long,
    val state: String,
    val priority: Int,
    val retryCount: Int,
    val nextAttemptAt: Long,
    val lastAttemptAt: Long?,
    val serverAcceptedAt: Long?,
    val deviceReceivedAt: Long?,
    val errorCode: String?,
    val errorSummary: String?,
    val localPreviewTitle: String
)

@Entity(
    tableName = "inbox_events",
    indices = [
        Index(value = ["relationshipId", "sequenceNumber"], unique = true),
        Index(value = ["ackPending"]),
        Index(value = ["receivedAt"]),
        Index(value = ["expiresAt"])
    ]
)
data class InboxEventEntity(
    @PrimaryKey val eventId: String,
    val relationshipId: String,
    val sequenceNumber: Long,
    val eventType: String,
    val title: String,
    val body: String,
    val sourcePackage: String,
    val sourceCategory: String,
    val calendarStartAt: Long?,
    val calendarEndAt: Long?,
    val calendarLocation: String?,
    val calendarEventUri: String?,
    val createdAt: Long,
    val expiresAt: Long,
    val receivedAt: Long,
    val readAt: Long?,
    val state: String,
    val ackPending: Boolean,
    val ackSentAt: Long?,
    val ackMacB64: String?,
    val quarantined: Boolean,
    val failureReason: String?
)

@Entity(tableName = "sync_cursors")
data class SyncCursorEntity(
    @PrimaryKey val relationshipId: String,
    val lastContiguousSeq: Long,
    val highestSeenSeq: Long,
    val lastServerCursor: Long,
    val missingSequencesJson: String,
    val lastSyncAt: Long?,
    val lastSuccessfulWsAt: Long?
)

@Entity(tableName = "relationships")
data class RelationshipEntity(
    @PrimaryKey val relationshipId: String,
    val role: String,
    val localDeviceId: String,
    val peerDeviceId: String,
    val peerDeviceName: String,
    val peerIdentityPubB64: String,
    val wrappedRootKeyB64: String,
    val wrappedRootKeyIvB64: String,
    val wrappedAuthTokenB64: String,
    val wrappedAuthTokenIvB64: String,
    val keyEpoch: Int,
    val nextSequenceNumber: Long,
    val status: String,
    val createdAt: Long,
    val revokedAt: Long?
)

@Entity(
    tableName = "diagnostic_logs",
    indices = [Index(value = ["timestamp"])]
)
data class DiagnosticLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val timestamp: Long,
    val level: String,
    val category: String,
    val errorCode: String?,
    val message: String
)
