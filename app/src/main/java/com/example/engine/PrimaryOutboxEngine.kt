package com.example.engine

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.example.android.background.OutboxFlushWorker
import com.example.core.crypto.EnvelopeCryptoEngine
import com.example.core.crypto.KeystoreManager
import com.example.core.model.BridgeErrorCode
import com.example.core.model.DeliveryState
import com.example.core.model.DeliveryStateMachine
import com.example.core.model.EventEnvelope
import com.example.core.model.EventPayload
import com.example.core.model.PayloadType
import com.example.core.model.ProtocolConstants
import com.example.core.model.RelayWsFrame
import com.example.core.model.RetryPolicy
import com.example.data.local.LocalConfigStore
import com.example.data.local.OutboxDao
import com.example.data.local.OutboxEventEntity
import com.example.data.local.RelationshipDao
import com.example.data.network.RelayResult
import com.example.data.network.RelayTransport
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.TimeUnit

sealed class CaptureOutcome {
    data class Enqueued(val eventId: String, val sequenceNumber: Long) : CaptureOutcome()
    data class Deduplicated(val existingEventId: String) : CaptureOutcome()
    data class Failed(val reason: String) : CaptureOutcome()
}

class PrimaryOutboxEngine(
    private val context: Context,
    private val outboxDao: OutboxDao,
    private val relationshipDao: RelationshipDao,
    private val keystoreManager: KeystoreManager,
    private val configStore: LocalConfigStore,
    private val transport: RelayTransport,
    private val logger: SanitizedLogger,
    private val retryPolicy: RetryPolicy = RetryPolicy()
) {
    private val flushMutex = Mutex()

    /**
     * Transactional event capture: encrypts and durably persists into Room outbox BEFORE
     * any network call. Does not depend on network availability.
     */
    suspend fun captureAndEnqueue(
        payloadType: PayloadType,
        title: String,
        body: String,
        sourcePackage: String,
        sourceCategory: String = "notification",
        dedupKey: String? = null,
        priority: Int = 0,
        calendarStartAtMs: Long? = null,
        calendarEndAtMs: Long? = null,
        calendarLocation: String? = null,
        nowMs: Long = System.currentTimeMillis()
    ): CaptureOutcome {
        return runCatching {
            val relationship = relationshipDao.getActiveRelationship()
                ?: return CaptureOutcome.Failed("No active paired Secondary device")

            // Deduplicate rapid source notification updates within 30s window
            if (!dedupKey.isNullOrBlank()) {
                val existing = outboxDao.findRecentDuplicate(dedupKey, nowMs - 30_000L)
                if (existing != null) {
                    logger.info("OUTBOX", "Deduplicated source event seq=${existing.sequenceNumber}")
                    return CaptureOutcome.Deduplicated(existing.eventId)
                }
            }

            val config = configStore.getCurrent()
            val expiresAtMs = nowMs + config.retentionHours * 3_600_000L
            val eventId = UUID.randomUUID().toString()

            val (relSnapshot, seqNumber) = relationshipDao.allocateNextSequenceAndGet(relationship.relationshipId)
            val rootKey = keystoreManager.unwrapSecret(relSnapshot.wrappedRootKeyB64, relSnapshot.wrappedRootKeyIvB64)

            val payload = EventPayload(
                title = title.take(256),
                body = body.take(4096),
                sourcePackage = sourcePackage.take(128),
                sourceCategory = sourceCategory.take(64),
                capturedAtEpochMs = nowMs,
                calendarStartAtEpochMs = calendarStartAtMs,
                calendarEndAtEpochMs = calendarEndAtMs,
                calendarLocation = calendarLocation?.take(256),
                priority = priority
            )

            val envelope = EnvelopeCryptoEngine.encryptPayload(
                rootKey = rootKey,
                eventId = eventId,
                relationshipId = relSnapshot.relationshipId,
                sequenceNumber = seqNumber,
                keyEpoch = relSnapshot.keyEpoch,
                createdAtEpochMs = nowMs,
                expiresAtEpochMs = expiresAtMs,
                payloadType = payloadType,
                payload = payload
            )

            // Persist CAPTURED -> PENDING atomically
            val entity = OutboxEventEntity(
                eventId = eventId,
                relationshipId = relSnapshot.relationshipId,
                sequenceNumber = seqNumber,
                sourceDedupKey = dedupKey,
                eventType = payloadType.name,
                keyEpoch = relSnapshot.keyEpoch,
                encryptedPayloadB64 = envelope.ciphertext,
                nonceB64 = envelope.nonce,
                authTagB64 = envelope.authTag,
                ciphertextHash = envelope.ciphertextHash,
                createdAt = nowMs,
                expiresAt = expiresAtMs,
                state = DeliveryState.PENDING.name,
                priority = priority,
                retryCount = 0,
                nextAttemptAt = nowMs,
                lastAttemptAt = null,
                serverAcceptedAt = null,
                deviceReceivedAt = null,
                errorCode = null,
                errorSummary = null,
                localPreviewTitle = title.take(64)
            )
            outboxDao.insert(entity)
            logger.info("OUTBOX", "Persisted encrypted event seq=$seqNumber type=${payloadType.name}")

            scheduleBackgroundFlush()
            CaptureOutcome.Enqueued(eventId, seqNumber)
        }.getOrElse { t ->
            logger.error("OUTBOX", "Failed local outbox persistence: ${t.javaClass.simpleName}", "TEMP_DATABASE_FAILURE")
            CaptureOutcome.Failed("Local outbox persistence failed: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    /**
     * Drains eligible PENDING and due RETRY_WAIT events safely using atomic Room claiming.
     * Preserves eventId and sequenceNumber across all retries.
     */
    suspend fun flushPendingBatch(nowMs: Long = System.currentTimeMillis()): Int = flushMutex.withLock {
        val relationship = relationshipDao.getActiveRelationship() ?: return 0
        val config = configStore.getCurrent()
        val authToken = keystoreManager.unwrapSecret(
            relationship.wrappedAuthTokenB64,
            relationship.wrappedAuthTokenIvB64
        ).toString(Charsets.UTF_8)

        val claimed = outboxDao.claimEligibleBatch(nowMs, ProtocolConstants.MAX_SYNC_BATCH_SIZE)
        if (claimed.isEmpty()) return 0

        var acceptedCount = 0
        for (item in claimed) {
            val attemptNow = System.currentTimeMillis()
            if (attemptNow >= item.expiresAt) {
                outboxDao.updateRetryOrFailure(
                    eventId = item.eventId,
                    newState = DeliveryState.EXPIRED.name,
                    retryCount = item.retryCount,
                    nextAttemptAt = item.expiresAt,
                    lastAttemptAt = attemptNow,
                    errorCode = BridgeErrorCode.EXPIRED_EVENT.name,
                    errorSummary = "Expired before delivery"
                )
                continue
            }

            val envelope = EventEnvelope(
                protocolVersion = ProtocolConstants.PROTOCOL_VERSION,
                eventId = item.eventId,
                relationshipId = item.relationshipId,
                sequenceNumber = item.sequenceNumber,
                keyEpoch = item.keyEpoch,
                createdAtEpochMs = item.createdAt,
                expiresAtEpochMs = item.expiresAt,
                payloadType = PayloadType.valueOf(item.eventType),
                ciphertext = item.encryptedPayloadB64,
                nonce = item.nonceB64,
                authTag = item.authTagB64,
                ciphertextHash = item.ciphertextHash
            )

            when (val result = transport.submitEvent(config.relayBaseUrl, authToken, envelope)) {
                is RelayResult.Success -> {
                    DeliveryStateMachine.requireTransition(DeliveryState.SENDING, DeliveryState.SERVER_ACCEPTED)
                    outboxDao.markServerAccepted(item.eventId, result.data.acceptedAtEpochMs)
                    configStore.recordConnectionSuccess(attemptNow)
                    configStore.recordDeliverySuccess(attemptNow)
                    acceptedCount++
                    logger.info("OUTBOX", "Relay accepted seq=${item.sequenceNumber} dup=${result.data.duplicate}")
                }
                is RelayResult.Failure -> {
                    val err = result.error
                    val nextRetries = item.retryCount + 1
                    if (retryPolicy.shouldRetry(err, item.retryCount, attemptNow, item.expiresAt)) {
                        val nextAt = retryPolicy.nextAttemptAtMs(nextRetries, attemptNow, item.expiresAt, err.retryAfterMs)
                        DeliveryStateMachine.requireTransition(DeliveryState.SENDING, DeliveryState.RETRY_WAIT)
                        outboxDao.updateRetryOrFailure(
                            eventId = item.eventId,
                            newState = DeliveryState.RETRY_WAIT.name,
                            retryCount = nextRetries,
                            nextAttemptAt = nextAt,
                            lastAttemptAt = attemptNow,
                            errorCode = err.code.name,
                            errorSummary = err.summary
                        )
                        logger.warn("OUTBOX", "Retry scheduled seq=${item.sequenceNumber} count=$nextRetries", err.code.name)
                    } else {
                        val terminalState = if (err.code == BridgeErrorCode.EXPIRED_EVENT || attemptNow >= item.expiresAt) {
                            DeliveryState.EXPIRED
                        } else {
                            DeliveryState.PERMANENT_FAILURE
                        }
                        DeliveryStateMachine.requireTransition(DeliveryState.SENDING, terminalState)
                        outboxDao.updateRetryOrFailure(
                            eventId = item.eventId,
                            newState = terminalState.name,
                            retryCount = nextRetries,
                            nextAttemptAt = attemptNow,
                            lastAttemptAt = attemptNow,
                            errorCode = err.code.name,
                            errorSummary = err.summary
                        )
                        if (err.code == BridgeErrorCode.REVOKED_PAIRING) {
                            relationshipDao.markRevoked(relationship.relationshipId, attemptNow)
                        }
                        logger.error("OUTBOX", "Permanent failure seq=${item.sequenceNumber}", err.code.name)
                    }
                }
            }
        }
        return acceptedCount
    }

    suspend fun handleReceiptFrame(frame: RelayWsFrame) {
        if (frame.type != "RECEIPT") return
        val ack = frame.ack ?: return
        val relationship = relationshipDao.getActiveRelationship() ?: return
        val existing = outboxDao.getById(ack.eventId) ?: return
        val rootKey = keystoreManager.unwrapSecret(relationship.wrappedRootKeyB64, relationship.wrappedRootKeyIvB64)

        val validMac = EnvelopeCryptoEngine.verifyAckMac(
            rootKey = rootKey,
            relationshipId = relationship.relationshipId,
            keyEpoch = existing.keyEpoch,
            eventId = ack.eventId,
            sequenceNumber = ack.sequenceNumber,
            receivedAtEpochMs = ack.receivedAtEpochMs,
            ackMacB64 = ack.ackMacB64
        )
        if (validMac) {
            outboxDao.markDeviceReceived(ack.eventId, ack.receivedAtEpochMs)
            configStore.recordDeliverySuccess(System.currentTimeMillis())
            logger.info("OUTBOX", "Verified Secondary receipt seq=${ack.sequenceNumber}")
        } else {
            logger.warn("CRYPTO", "Rejected receipt with invalid ACK MAC seq=${ack.sequenceNumber}", "AUTH_TAG_INVALID")
        }
    }

    suspend fun runRetentionCleanup(nowMs: Long = System.currentTimeMillis()): Int {
        val expired = outboxDao.expireOverdue(nowMs)
        val purged = outboxDao.purgeOldTerminal(nowMs - 86_400_000L)
        if (expired > 0 || purged > 0) {
            logger.info("OUTBOX", "Retention cleanup: expired=$expired purged=$purged")
        }
        return expired + purged
    }

    fun scheduleBackgroundFlush() {
        runCatching {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val work = OneTimeWorkRequestBuilder<OutboxFlushWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "phonebridge_outbox_flush",
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                work
            )
        }
    }
}
