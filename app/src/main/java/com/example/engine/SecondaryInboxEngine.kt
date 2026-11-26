package com.example.engine

import android.content.Context
import com.example.android.background.CalendarIntegrationHelper
import com.example.android.background.SecondaryNotificationPresenter
import com.example.core.crypto.EnvelopeCryptoEngine
import com.example.core.crypto.KeystoreManager
import com.example.core.model.AckItem
import com.example.core.model.AcknowledgeEventsRequest
import com.example.core.model.DeliveryState
import com.example.core.model.EventEnvelope
import com.example.core.model.PayloadType
import com.example.core.model.ProtocolConstants
import com.example.data.local.InboxDao
import com.example.data.local.InboxEventEntity
import com.example.data.local.LocalConfigStore
import com.example.data.local.RelationshipDao
import com.example.data.local.SyncCursorDao
import com.example.data.local.SyncCursorEntity
import com.example.data.network.RelayResult
import com.example.data.network.RelayTransport
import com.example.data.network.RelayWebSocketClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

sealed class IngestOutcome {
    data class Persisted(val eventId: String, val sequenceNumber: Long) : IngestOutcome()
    data class Duplicate(val eventId: String, val sequenceNumber: Long) : IngestOutcome()
    data class Quarantined(val eventId: String, val reason: String) : IngestOutcome()
}

class SecondaryInboxEngine(
    private val context: Context,
    private val inboxDao: InboxDao,
    private val syncCursorDao: SyncCursorDao,
    private val relationshipDao: RelationshipDao,
    private val keystoreManager: KeystoreManager,
    private val configStore: LocalConfigStore,
    private val transport: RelayTransport,
    private val wsClient: RelayWebSocketClient,
    private val logger: SanitizedLogger
) {
    private val syncMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Explicit 6-step Secondary delivery pipeline:
     * 1. Relationship authentication check
     * 2. Envelope validation
     * 3. AEAD decryption + AAD integrity check
     * 4. Payload validation
     * 5. Transactional deduplication & local persistence
     * 6. Authenticated acknowledgement ONLY AFTER local persistence
     */
    suspend fun processIncomingEnvelope(
        envelope: EventEnvelope,
        nowMs: Long = System.currentTimeMillis(),
        sendAckImmediately: Boolean = true
    ): IngestOutcome {
        val relationship = relationshipDao.getActiveRelationship()
        if (relationship == null || relationship.relationshipId != envelope.relationshipId) {
            logger.warn("INBOX", "Rejected envelope for unknown/revoked relationship", "UNAUTHORIZED_DEVICE")
            return IngestOutcome.Quarantined(envelope.eventId, "Unknown or revoked relationship")
        }

        if (envelope.protocolVersion != ProtocolConstants.PROTOCOL_VERSION) {
            quarantineEnvelope(envelope, nowMs, "Unsupported protocol v${envelope.protocolVersion}")
            return IngestOutcome.Quarantined(envelope.eventId, "Unsupported protocol version")
        }

        // Check existing persisted record for fast idempotent duplicate handling
        val existing = inboxDao.getById(envelope.eventId)
        if (existing != null) {
            if (sendAckImmediately && existing.ackPending && existing.ackMacB64 != null) {
                flushPendingAcks()
            }
            logger.info("INBOX", "Idempotent duplicate ignored seq=${envelope.sequenceNumber}")
            return IngestOutcome.Duplicate(envelope.eventId, envelope.sequenceNumber)
        }

        // Decrypt & verify AAD + auth tag
        val rootKey = keystoreManager.unwrapSecret(relationship.wrappedRootKeyB64, relationship.wrappedRootKeyIvB64)
        val payload = try {
            EnvelopeCryptoEngine.decryptEnvelope(rootKey, envelope)
        } catch (t: Throwable) {
            val reason = "AEAD decryption/validation failed: ${t.javaClass.simpleName}"
            quarantineEnvelope(envelope, nowMs, reason)
            logger.error("CRYPTO", "Quarantined corrupt/tampered envelope seq=${envelope.sequenceNumber}", "AUTH_TAG_INVALID")
            return IngestOutcome.Quarantined(envelope.eventId, reason)
        }

        if (payload.title.isBlank() && payload.body.isBlank()) {
            quarantineEnvelope(envelope, nowMs, "Empty payload content")
            return IngestOutcome.Quarantined(envelope.eventId, "Invalid empty payload")
        }

        val ackMac = EnvelopeCryptoEngine.computeAckMac(
            rootKey = rootKey,
            relationshipId = envelope.relationshipId,
            keyEpoch = envelope.keyEpoch,
            eventId = envelope.eventId,
            sequenceNumber = envelope.sequenceNumber,
            receivedAtEpochMs = nowMs
        )

        val entity = InboxEventEntity(
            eventId = envelope.eventId,
            relationshipId = envelope.relationshipId,
            sequenceNumber = envelope.sequenceNumber,
            eventType = envelope.payloadType.name,
            title = payload.title,
            body = payload.body,
            sourcePackage = payload.sourcePackage,
            sourceCategory = payload.sourceCategory,
            calendarStartAt = payload.calendarStartAtEpochMs,
            calendarEndAt = payload.calendarEndAtEpochMs,
            calendarLocation = payload.calendarLocation,
            calendarEventUri = null,
            createdAt = envelope.createdAtEpochMs,
            expiresAt = envelope.expiresAtEpochMs,
            receivedAt = nowMs,
            readAt = null,
            state = DeliveryState.DEVICE_RECEIVED.name,
            ackPending = true,
            ackSentAt = null,
            ackMacB64 = ackMac,
            quarantined = false,
            failureReason = null
        )

        val insertedRowId = inboxDao.insertIgnore(entity)
        if (insertedRowId == -1L) {
            return IngestOutcome.Duplicate(envelope.eventId, envelope.sequenceNumber)
        }

        advanceCursorWithGapDetection(envelope.relationshipId, envelope.sequenceNumber, nowMs)
        configStore.recordDeliverySuccess(nowMs)

        // Present notification or add calendar reminder locally
        val config = configStore.getCurrent()
        if (envelope.payloadType == PayloadType.CALENDAR_EVENT && config.autoAddCalendarEvents) {
            val uri = CalendarIntegrationHelper.insertCalendarEventIfPermitted(context, entity)
            if (uri != null) {
                inboxDao.updateCalendarUri(entity.eventId, uri)
            }
        }
        if (envelope.payloadType != PayloadType.HEARTBEAT_TEST) {
            SecondaryNotificationPresenter.showIfPermitted(context, entity)
        }

        // Send authenticated ACK only AFTER durable local Room persistence
        if (sendAckImmediately) {
            val ackItem = AckItem(envelope.eventId, envelope.sequenceNumber, nowMs, ackMac)
            wsClient.sendAck(envelope.relationshipId, ackItem)
            flushPendingAcks()
        }

        logger.info("INBOX", "Persisted & acked seq=${envelope.sequenceNumber} type=${envelope.payloadType.name}")
        return IngestOutcome.Persisted(envelope.eventId, envelope.sequenceNumber)
    }

    /**
     * Cursor-based paginated HTTPS synchronization fallback.
     * Safe to invoke repeatedly; never advances cursor past unprocessed events.
     */
    suspend fun syncFromRelay(): Int = syncMutex.withLock {
        val relationship = relationshipDao.getActiveRelationship() ?: return 0
        val config = configStore.getCurrent()
        val authToken = keystoreManager.unwrapSecret(
            relationship.wrappedAuthTokenB64,
            relationship.wrappedAuthTokenIvB64
        ).toString(Charsets.UTF_8)

        var cursorState = syncCursorDao.getCursor(relationship.relationshipId) ?: SyncCursorEntity(
            relationshipId = relationship.relationshipId,
            lastContiguousSeq = 0L,
            highestSeenSeq = 0L,
            lastServerCursor = 0L,
            missingSequencesJson = "[]",
            lastSyncAt = null,
            lastSuccessfulWsAt = null
        )

        var afterSeq = cursorState.lastContiguousSeq
        var totalProcessed = 0
        var hasMore = true

        while (hasMore) {
            val res = transport.sync(
                baseUrl = config.relayBaseUrl,
                authToken = authToken,
                req = com.example.core.model.SyncRequest(
                    relationshipId = relationship.relationshipId,
                    afterSequence = afterSeq,
                    limit = ProtocolConstants.MAX_SYNC_BATCH_SIZE
                )
            )

            when (res) {
                is RelayResult.Success -> {
                    val now = System.currentTimeMillis()
                    configStore.recordConnectionSuccess(now)

                    // Advance past permanently expired events on relay so cursor doesn't stall
                    if (res.data.expiredSequenceFloor > cursorState.lastContiguousSeq) {
                        advanceCursorPastExpiredFloor(relationship.relationshipId, res.data.expiredSequenceFloor, now)
                    }

                    for (env in res.data.events) {
                        val outcome = processIncomingEnvelope(env, nowMs = now, sendAckImmediately = false)
                        if (outcome is IngestOutcome.Persisted) {
                            totalProcessed++
                        }
                    }

                    flushPendingAcks()

                    cursorState = syncCursorDao.getCursor(relationship.relationshipId) ?: cursorState
                    syncCursorDao.upsert(
                        cursorState.copy(
                            lastServerCursor = maxOf(cursorState.lastServerCursor, res.data.nextCursor),
                            lastSyncAt = now
                        )
                    )
                    configStore.recordSyncSuccess(now)

                    hasMore = res.data.hasMore && res.data.nextCursor > afterSeq
                    afterSeq = res.data.nextCursor
                }
                is RelayResult.Failure -> {
                    logger.warn("SYNC", "HTTPS sync interrupted: ${res.error.summary}", res.error.code.name)
                    hasMore = false
                }
            }
        }
        return totalProcessed
    }

    suspend fun flushPendingAcks() {
        val relationship = relationshipDao.getActiveRelationship() ?: return
        val config = configStore.getCurrent()
        val authToken = keystoreManager.unwrapSecret(
            relationship.wrappedAuthTokenB64,
            relationship.wrappedAuthTokenIvB64
        ).toString(Charsets.UTF_8)

        val pending = inboxDao.listPendingAcks(ProtocolConstants.MAX_SYNC_BATCH_SIZE)
        if (pending.isEmpty()) return

        val ackItems = pending.mapNotNull { item ->
            val mac = item.ackMacB64 ?: return@mapNotNull null
            AckItem(
                eventId = item.eventId,
                sequenceNumber = item.sequenceNumber,
                receivedAtEpochMs = item.receivedAt,
                ackMacB64 = mac
            )
        }
        if (ackItems.isEmpty()) return

        when (val res = transport.acknowledgeEvents(
            baseUrl = config.relayBaseUrl,
            authToken = authToken,
            req = AcknowledgeEventsRequest(
                relationshipId = relationship.relationshipId,
                acknowledgements = ackItems
            )
        )) {
            is RelayResult.Success -> {
                if (res.data.acknowledgedEventIds.isNotEmpty()) {
                    inboxDao.markAcknowledged(res.data.acknowledgedEventIds, System.currentTimeMillis())
                }
            }
            is RelayResult.Failure -> {
                logger.warn("INBOX", "Deferred ACK batch (${ackItems.size})", res.error.code.name)
            }
        }
    }

    suspend fun markRead(eventId: String) {
        inboxDao.markRead(eventId, System.currentTimeMillis())
    }

    suspend fun addToCalendar(eventId: String): Boolean {
        val item = inboxDao.getById(eventId) ?: return false
        val uri = CalendarIntegrationHelper.insertCalendarEventIfPermitted(context, item) ?: return false
        inboxDao.updateCalendarUri(eventId, uri)
        return true
    }

    suspend fun runRetentionCleanup(nowMs: Long = System.currentTimeMillis()): Int {
        return inboxDao.purgeExpired(nowMs)
    }

    private suspend fun quarantineEnvelope(envelope: EventEnvelope, nowMs: Long, reason: String) {
        val q = InboxEventEntity(
            eventId = envelope.eventId,
            relationshipId = envelope.relationshipId,
            sequenceNumber = envelope.sequenceNumber,
            eventType = envelope.payloadType.name,
            title = "Quarantined Event",
            body = "Verification failed; not acknowledged to relay.",
            sourcePackage = "quarantine",
            sourceCategory = "security",
            calendarStartAt = null,
            calendarEndAt = null,
            calendarLocation = null,
            calendarEventUri = null,
            createdAt = envelope.createdAtEpochMs,
            expiresAt = envelope.expiresAtEpochMs,
            receivedAt = nowMs,
            readAt = null,
            state = DeliveryState.PERMANENT_FAILURE.name,
            ackPending = false, // NEVER falsely acknowledge invalid/tampered payload
            ackSentAt = null,
            ackMacB64 = null,
            quarantined = true,
            failureReason = reason
        )
        inboxDao.insertIgnore(q)
    }

    private suspend fun advanceCursorWithGapDetection(relationshipId: String, arrivedSeq: Long, nowMs: Long) {
        val current = syncCursorDao.getCursor(relationshipId) ?: SyncCursorEntity(
            relationshipId = relationshipId,
            lastContiguousSeq = 0L,
            highestSeenSeq = 0L,
            lastServerCursor = 0L,
            missingSequencesJson = "[]",
            lastSyncAt = null,
            lastSuccessfulWsAt = null
        )

        val gaps = runCatching {
            json.decodeFromString(ListSerializer(Long.serializer()), current.missingSequencesJson)
        }.getOrDefault(emptyList()).toMutableSet()

        var contiguous = current.lastContiguousSeq
        var highest = current.highestSeenSeq

        if (arrivedSeq == contiguous + 1L) {
            contiguous = arrivedSeq
            gaps.remove(contiguous)
            while (contiguous < highest && !gaps.contains(contiguous + 1L)) {
                contiguous++
            }
            highest = maxOf(highest, contiguous)
        } else if (arrivedSeq > contiguous + 1L) {
            for (missing in (contiguous + 1L) until arrivedSeq) {
                if (missing > highest) {
                    gaps.add(missing)
                }
            }
            gaps.remove(arrivedSeq)
            highest = maxOf(highest, arrivedSeq)
        } else {
            gaps.remove(arrivedSeq)
        }

        syncCursorDao.upsert(
            current.copy(
                lastContiguousSeq = contiguous,
                highestSeenSeq = highest,
                missingSequencesJson = json.encodeToString(ListSerializer(Long.serializer()), gaps.sorted()),
                lastSuccessfulWsAt = nowMs
            )
        )
    }

    private suspend fun advanceCursorPastExpiredFloor(relationshipId: String, expiredFloor: Long, nowMs: Long) {
        val current = syncCursorDao.getCursor(relationshipId) ?: return
        val gaps = runCatching {
            json.decodeFromString(ListSerializer(Long.serializer()), current.missingSequencesJson)
        }.getOrDefault(emptyList()).filter { it > expiredFloor }.toMutableSet()

        var contiguous = maxOf(current.lastContiguousSeq, expiredFloor)
        val highest = maxOf(current.highestSeenSeq, contiguous)
        while (contiguous < highest && !gaps.contains(contiguous + 1L)) {
            contiguous++
        }
        syncCursorDao.upsert(
            current.copy(
                lastContiguousSeq = contiguous,
                highestSeenSeq = maxOf(current.highestSeenSeq, contiguous),
                missingSequencesJson = json.encodeToString(ListSerializer(Long.serializer()), gaps.sorted()),
                lastSyncAt = nowMs
            )
        )
    }
}
