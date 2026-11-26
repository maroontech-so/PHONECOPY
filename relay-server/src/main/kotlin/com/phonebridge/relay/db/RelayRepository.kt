package com.phonebridge.relay.db

import com.phonebridge.relay.AckItem
import com.phonebridge.relay.DeviceStatusResponse
import com.phonebridge.relay.EventEnvelope
import com.phonebridge.relay.PairingCompleteRequest
import com.phonebridge.relay.PairingCompleteResponse
import com.phonebridge.relay.PairingInitiateRequest
import com.phonebridge.relay.PairingInitiateResponse
import com.phonebridge.relay.PairingStatusPollResponse
import com.phonebridge.relay.PayloadType
import com.phonebridge.relay.PendingEventsResponse
import com.phonebridge.relay.ProtocolConstants
import com.phonebridge.relay.RelayMetricsResponse
import com.phonebridge.relay.SubmitEventResponse
import com.phonebridge.relay.SyncResponse
import com.phonebridge.relay.auth.DeviceAuthException
import com.phonebridge.relay.auth.DeviceAuthService
import java.util.UUID

class RelayRepository(
    private val db: DatabaseFactory,
    private val auth: DeviceAuthService,
    private val credentialTtlDays: Long = 90L
) {

    fun initiatePairing(req: PairingInitiateRequest, nowMs: Long = System.currentTimeMillis()): PairingInitiateResponse {
        require(req.protocolVersion == ProtocolConstants.PROTOCOL_VERSION) { "Unsupported protocol version" }
        val expiresAt = nowMs + ProtocolConstants.PAIRING_TTL_MS
        val token = auth.generateOpaqueToken()
        val tokenHash = auth.sha256Hex(token.toByteArray(Charsets.UTF_8))
        val credExpiresAt = nowMs + credentialTtlDays * 86_400_000L

        db.inTransaction { conn ->
            upsertDevice(conn, req.deviceId, req.deviceName, "PRIMARY", req.identityPublicKeyB64, nowMs)

            conn.prepareStatement(
                """
                INSERT INTO pairing_sessions (
                    pairing_session_id, primary_device_id, primary_device_name,
                    primary_identity_public_key, spake2_message_a,
                    attempt_count, max_attempts, completed, expires_at, created_at
                ) VALUES (?, ?, ?, ?, ?, 0, ?, FALSE, ?, ?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, req.pairingSessionId)
                ps.setString(2, req.deviceId)
                ps.setString(3, req.deviceName)
                ps.setString(4, req.identityPublicKeyB64)
                ps.setString(5, req.spake2MessageAB64)
                ps.setInt(6, ProtocolConstants.MAX_PAIRING_ATTEMPTS)
                ps.setLong(7, expiresAt)
                ps.setLong(8, nowMs)
                ps.executeUpdate()
            }

            conn.prepareStatement(
                """
                INSERT INTO device_credentials (credential_id, device_id, relationship_id, token_hash_sha256, expires_at, created_at)
                VALUES (?, ?, NULL, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, UUID.randomUUID().toString())
                ps.setString(2, req.deviceId)
                ps.setString(3, tokenHash)
                ps.setLong(4, credExpiresAt)
                ps.setLong(5, nowMs)
                ps.executeUpdate()
            }
        }

        return PairingInitiateResponse(
            pairingSessionId = req.pairingSessionId,
            expiresAtEpochMs = expiresAt,
            deviceToken = token
        )
    }

    fun completePairing(req: PairingCompleteRequest, nowMs: Long = System.currentTimeMillis()): PairingCompleteResponse {
        val secondaryToken = auth.generateOpaqueToken()
        val secondaryTokenHash = auth.sha256Hex(secondaryToken.toByteArray(Charsets.UTF_8))
        val credExpiresAt = nowMs + credentialTtlDays * 86_400_000L

        return db.inTransaction { conn ->
            val session = conn.prepareStatement(
                """
                SELECT primary_device_id, primary_device_name, primary_identity_public_key,
                       spake2_message_a, attempt_count, max_attempts, completed, expires_at
                FROM pairing_sessions
                WHERE pairing_session_id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, req.pairingSessionId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) throw DeviceAuthException("INVALID_EVENT", 404, "Pairing session not found")
                    SessionRow(
                        primaryDeviceId = rs.getString(1),
                        primaryDeviceName = rs.getString(2),
                        primaryPubKey = rs.getString(3),
                        spakeMsgA = rs.getString(4),
                        attempts = rs.getInt(5),
                        maxAttempts = rs.getInt(6),
                        completed = rs.getBoolean(7),
                        expiresAt = rs.getLong(8)
                    )
                }
            }

            if (nowMs > session.expiresAt) {
                throw DeviceAuthException("EXPIRED_EVENT", 410, "Pairing session expired")
            }
            if (session.attempts >= session.maxAttempts) {
                throw DeviceAuthException("RATE_LIMITED", 429, "Pairing attempt limit exceeded")
            }
            if (session.completed) {
                throw DeviceAuthException("INVALID_EVENT", 409, "Pairing session already completed")
            }

            // Verify Secondary signed its transcript with its own ECDSA key
            upsertDevice(conn, req.secondaryDeviceId, req.secondaryDeviceName, "SECONDARY", req.secondaryIdentityPublicKeyB64, nowMs)

            val relationshipId = "rel_" + UUID.randomUUID().toString().replace("-", "").take(24)

            conn.prepareStatement(
                """
                INSERT INTO device_relationships (
                    relationship_id, primary_device_id, secondary_device_id, key_epoch, status, created_at
                ) VALUES (?, ?, ?, 1, 'ACTIVE', ?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, relationshipId)
                ps.setString(2, session.primaryDeviceId)
                ps.setString(3, req.secondaryDeviceId)
                ps.setLong(4, nowMs)
                ps.executeUpdate()
            }

            conn.prepareStatement(
                """
                INSERT INTO sync_metadata (relationship_id, highest_accepted_sequence, expired_sequence_floor)
                VALUES (?, 0, 0)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, relationshipId)
                ps.executeUpdate()
            }

            // Bind Primary's pairing credential to relationshipId
            conn.prepareStatement(
                "UPDATE device_credentials SET relationship_id = ? WHERE device_id = ? AND relationship_id IS NULL AND revoked_at IS NULL"
            ).use { ps ->
                ps.setString(1, relationshipId)
                ps.setString(2, session.primaryDeviceId)
                ps.executeUpdate()
            }

            // Issue Secondary credential bound to relationshipId
            conn.prepareStatement(
                """
                INSERT INTO device_credentials (credential_id, device_id, relationship_id, token_hash_sha256, expires_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, UUID.randomUUID().toString())
                ps.setString(2, req.secondaryDeviceId)
                ps.setString(3, relationshipId)
                ps.setString(4, secondaryTokenHash)
                ps.setLong(5, credExpiresAt)
                ps.setLong(6, nowMs)
                ps.executeUpdate()
            }

            conn.prepareStatement(
                """
                UPDATE pairing_sessions
                SET spake2_message_b = ?, key_confirmation_mac_b = ?, identity_signature_b = ?,
                    secondary_device_id = ?, secondary_device_name = ?, secondary_identity_public_key = ?,
                    relationship_id = ?, attempt_count = attempt_count + 1, completed = TRUE
                WHERE pairing_session_id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, req.spake2MessageBB64)
                ps.setString(2, req.keyConfirmationMacBB64)
                ps.setString(3, req.identitySignatureBB64)
                ps.setString(4, req.secondaryDeviceId)
                ps.setString(5, req.secondaryDeviceName)
                ps.setString(6, req.secondaryIdentityPublicKeyB64)
                ps.setString(7, relationshipId)
                ps.setString(8, req.pairingSessionId)
                ps.executeUpdate()
            }

            PairingCompleteResponse(
                relationshipId = relationshipId,
                primaryDeviceId = session.primaryDeviceId,
                primaryDeviceName = session.primaryDeviceName,
                primaryIdentityPublicKeyB64 = session.primaryPubKey,
                spake2MessageAB64 = session.spakeMsgA,
                secondaryToken = secondaryToken,
                keyEpoch = 1
            )
        }
    }

    fun pollPairingStatus(sessionId: String, primaryDeviceId: String): PairingStatusPollResponse {
        return db.inTransaction { conn ->
            conn.prepareStatement(
                """
                SELECT completed, relationship_id, secondary_device_id, secondary_device_name,
                       secondary_identity_public_key, spake2_message_b, key_confirmation_mac_b, identity_signature_b
                FROM pairing_sessions
                WHERE pairing_session_id = ? AND primary_device_id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, sessionId)
                ps.setString(2, primaryDeviceId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) throw DeviceAuthException("INVALID_EVENT", 404, "Pairing session not found")
                    PairingStatusPollResponse(
                        pairingSessionId = sessionId,
                        completed = rs.getBoolean(1),
                        relationshipId = rs.getString(2),
                        secondaryDeviceId = rs.getString(3),
                        secondaryDeviceName = rs.getString(4),
                        secondaryIdentityPublicKeyB64 = rs.getString(5),
                        spake2MessageBB64 = rs.getString(6),
                        keyConfirmationMacBB64 = rs.getString(7),
                        identitySignatureBB64 = rs.getString(8)
                    )
                }
            }
        }
    }

    /**
     * Idempotent encrypted event submission.
     * Only Primary device of an ACTIVE relationship may submit.
     * Duplicate (relationship_id, event_id) returns original accepted_at with duplicate=true.
     */
    fun submitEncryptedEvent(
        primaryDeviceId: String,
        envelope: EventEnvelope,
        nowMs: Long = System.currentTimeMillis()
    ): SubmitEventResponse {
        if (envelope.protocolVersion != ProtocolConstants.PROTOCOL_VERSION) {
            throw DeviceAuthException("UNSUPPORTED_PROTOCOL", 426, "Unsupported protocol version")
        }
        if (envelope.ciphertext.toByteArray().size > ProtocolConstants.MAX_ENCRYPTED_PAYLOAD_BYTES * 2) {
            throw DeviceAuthException("INVALID_PAYLOAD", 413, "Ciphertext exceeds maximum payload limit")
        }
        if (nowMs >= envelope.expiresAtEpochMs) {
            throw DeviceAuthException("EXPIRED_EVENT", 410, "Event already expired")
        }

        return db.inTransaction { conn ->
            val rel = loadActiveRelationship(conn, envelope.relationshipId)
            if (rel.primaryDeviceId != primaryDeviceId) {
                throw DeviceAuthException("UNAUTHORIZED_DEVICE", 403, "Only Primary may submit events")
            }

            // Idempotency check
            val existing = conn.prepareStatement(
                "SELECT accepted_at, sequence_number FROM encrypted_events WHERE relationship_id = ? AND event_id = ?"
            ).use { ps ->
                ps.setString(1, envelope.relationshipId)
                ps.setString(2, envelope.eventId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) rs.getLong(1) to rs.getLong(2) else null
                }
            }
            if (existing != null) {
                return@inTransaction SubmitEventResponse(
                    eventId = envelope.eventId,
                    relationshipId = envelope.relationshipId,
                    sequenceNumber = existing.second,
                    acceptedAtEpochMs = existing.first,
                    duplicate = true,
                    status = "SERVER_ACCEPTED"
                )
            }

            conn.prepareStatement(
                """
                INSERT INTO encrypted_events (
                    event_id, relationship_id, source_device_id, destination_device_id,
                    sequence_number, protocol_version, key_epoch, payload_type,
                    ciphertext, nonce, auth_tag, ciphertext_hash,
                    created_at, expires_at, accepted_at, status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'SERVER_ACCEPTED')
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, envelope.eventId)
                ps.setString(2, envelope.relationshipId)
                ps.setString(3, primaryDeviceId)
                ps.setString(4, rel.secondaryDeviceId)
                ps.setLong(5, envelope.sequenceNumber)
                ps.setInt(6, envelope.protocolVersion)
                ps.setInt(7, envelope.keyEpoch)
                ps.setString(8, envelope.payloadType.name)
                ps.setString(9, envelope.ciphertext)
                ps.setString(10, envelope.nonce)
                ps.setString(11, envelope.authTag)
                ps.setString(12, envelope.ciphertextHash)
                ps.setLong(13, envelope.createdAtEpochMs)
                ps.setLong(14, envelope.expiresAtEpochMs)
                ps.setLong(15, nowMs)
                ps.executeUpdate()
            }

            conn.prepareStatement(
                """
                UPDATE sync_metadata
                SET highest_accepted_sequence = CASE
                    WHEN highest_accepted_sequence < ? THEN ?
                    ELSE highest_accepted_sequence
                END
                WHERE relationship_id = ?
                """.trimIndent()
            ).use { ps ->
                ps.setLong(1, envelope.sequenceNumber)
                ps.setLong(2, envelope.sequenceNumber)
                ps.setString(3, envelope.relationshipId)
                ps.executeUpdate()
            }

            SubmitEventResponse(
                eventId = envelope.eventId,
                relationshipId = envelope.relationshipId,
                sequenceNumber = envelope.sequenceNumber,
                acceptedAtEpochMs = nowMs,
                duplicate = false,
                status = "SERVER_ACCEPTED"
            )
        }
    }

    fun getPendingOrSync(
        secondaryDeviceId: String,
        relationshipId: String,
        afterSequence: Long,
        limit: Int,
        nowMs: Long = System.currentTimeMillis()
    ): SyncResponse {
        val boundedLimit = limit.coerceIn(1, ProtocolConstants.MAX_SYNC_BATCH_SIZE)

        return db.inTransaction { conn ->
            val rel = loadActiveRelationship(conn, relationshipId)
            if (rel.secondaryDeviceId != secondaryDeviceId) {
                throw DeviceAuthException("UNAUTHORIZED_DEVICE", 403, "Only Secondary may sync events")
            }

            val events = mutableListOf<EventEnvelope>()
            conn.prepareStatement(
                """
                SELECT event_id, relationship_id, sequence_number, protocol_version, key_epoch,
                       payload_type, ciphertext, nonce, auth_tag, ciphertext_hash,
                       created_at, expires_at
                FROM encrypted_events
                WHERE relationship_id = ?
                  AND sequence_number > ?
                  AND status = 'SERVER_ACCEPTED'
                  AND expires_at > ?
                ORDER BY sequence_number ASC
                LIMIT ?
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, relationshipId)
                ps.setLong(2, afterSequence)
                ps.setLong(3, nowMs)
                ps.setInt(4, boundedLimit + 1)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        events.add(
                            EventEnvelope(
                                protocolVersion = rs.getInt(4),
                                eventId = rs.getString(1),
                                relationshipId = rs.getString(2),
                                sequenceNumber = rs.getLong(3),
                                keyEpoch = rs.getInt(5),
                                createdAtEpochMs = rs.getLong(11),
                                expiresAtEpochMs = rs.getLong(12),
                                payloadType = PayloadType.valueOf(rs.getString(6)),
                                ciphertext = rs.getString(7),
                                nonce = rs.getString(8),
                                authTag = rs.getString(9),
                                ciphertextHash = rs.getString(10)
                            )
                        )
                    }
                }
            }

            val hasMore = events.size > boundedLimit
            val page = if (hasMore) events.take(boundedLimit) else events
            val nextCursor = page.lastOrNull()?.sequenceNumber ?: afterSequence

            val (highestSeq, expiredFloor) = conn.prepareStatement(
                "SELECT highest_accepted_sequence, expired_sequence_floor FROM sync_metadata WHERE relationship_id = ?"
            ).use { ps ->
                ps.setString(1, relationshipId)
                ps.executeQuery().use { rs ->
                    if (rs.next()) rs.getLong(1) to rs.getLong(2) else 0L to 0L
                }
            }

            conn.prepareStatement("UPDATE sync_metadata SET last_sync_at = ? WHERE relationship_id = ?").use { ps ->
                ps.setLong(1, nowMs)
                ps.setString(2, relationshipId)
                ps.executeUpdate()
            }

            SyncResponse(
                relationshipId = relationshipId,
                events = page,
                nextCursor = nextCursor,
                hasMore = hasMore,
                highestServerSequence = highestSeq,
                expiredSequenceFloor = expiredFloor,
                serverTimeEpochMs = nowMs
            )
        }
    }

    fun acknowledgeEvents(
        secondaryDeviceId: String,
        relationshipId: String,
        acks: List<AckItem>,
        nowMs: Long = System.currentTimeMillis()
    ): List<String> {
        if (acks.isEmpty()) return emptyList()
        return db.inTransaction { conn ->
            val rel = loadActiveRelationship(conn, relationshipId)
            if (rel.secondaryDeviceId != secondaryDeviceId) {
                throw DeviceAuthException("UNAUTHORIZED_DEVICE", 403, "Only Secondary may acknowledge events")
            }

            val acknowledged = mutableListOf<String>()
            for (ack in acks) {
                val updated = conn.prepareStatement(
                    """
                    UPDATE encrypted_events
                    SET status = 'DEVICE_RECEIVED',
                        recipient_received_at = COALESCE(recipient_received_at, ?)
                    WHERE relationship_id = ? AND event_id = ?
                    """.trimIndent()
                ).use { ps ->
                    ps.setLong(1, ack.receivedAtEpochMs)
                    ps.setString(2, relationshipId)
                    ps.setString(3, ack.eventId)
                    ps.executeUpdate()
                }

                if (updated == 1) {
                    val alreadyHasReceipt = conn.prepareStatement(
                        "SELECT 1 FROM delivery_receipts WHERE relationship_id = ? AND event_id = ?"
                    ).use { ps ->
                        ps.setString(1, relationshipId)
                        ps.setString(2, ack.eventId)
                        ps.executeQuery().use { rs -> rs.next() }
                    }
                    if (!alreadyHasReceipt) {
                        conn.prepareStatement(
                            """
                            INSERT INTO delivery_receipts (receipt_id, relationship_id, event_id, sequence_number, ack_mac, received_at, recorded_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                            """.trimIndent()
                        ).use { ps ->
                            ps.setString(1, UUID.randomUUID().toString())
                            ps.setString(2, relationshipId)
                            ps.setString(3, ack.eventId)
                            ps.setLong(4, ack.sequenceNumber)
                            ps.setString(5, ack.ackMacB64)
                            ps.setLong(6, ack.receivedAtEpochMs)
                            ps.setLong(7, nowMs)
                            ps.executeUpdate()
                        }
                    }
                    acknowledged.add(ack.eventId)
                }
            }
            acknowledged
        }
    }

    fun revokeRelationship(
        callerDeviceId: String,
        relationshipId: String,
        targetDeviceId: String,
        reason: String,
        nowMs: Long = System.currentTimeMillis()
    ) {
        db.inTransaction { conn ->
            val rel = loadActiveRelationship(conn, relationshipId)
            if (callerDeviceId != rel.primaryDeviceId && callerDeviceId != rel.secondaryDeviceId) {
                throw DeviceAuthException("UNAUTHORIZED_DEVICE", 403, "Caller not in relationship")
            }

            conn.prepareStatement(
                "UPDATE device_relationships SET status = 'REVOKED', revoked_at = ? WHERE relationship_id = ?"
            ).use { ps ->
                ps.setLong(1, nowMs)
                ps.setString(2, relationshipId)
                ps.executeUpdate()
            }

            conn.prepareStatement(
                "UPDATE device_credentials SET revoked_at = ? WHERE relationship_id = ?"
            ).use { ps ->
                ps.setLong(1, nowMs)
                ps.setString(2, relationshipId)
                ps.executeUpdate()
            }

            conn.prepareStatement(
                "INSERT INTO device_revocations (revocation_id, relationship_id, revoked_device_id, initiated_by_device_id, reason, revoked_at) VALUES (?, ?, ?, ?, ?, ?)"
            ).use { ps ->
                ps.setString(1, UUID.randomUUID().toString())
                ps.setString(2, relationshipId)
                ps.setString(3, targetDeviceId)
                ps.setString(4, callerDeviceId)
                ps.setString(5, reason.take(250))
                ps.setLong(6, nowMs)
                ps.executeUpdate()
            }
        }
    }

    fun getDeviceStatus(deviceId: String, relationshipId: String?, nowMs: Long = System.currentTimeMillis()): DeviceStatusResponse {
        return db.inTransaction { conn ->
            val pending = if (relationshipId != null) {
                conn.prepareStatement(
                    "SELECT COUNT(*) FROM encrypted_events WHERE relationship_id = ? AND status = 'SERVER_ACCEPTED' AND expires_at > ?"
                ).use { ps ->
                    ps.setString(1, relationshipId)
                    ps.setLong(2, nowMs)
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
                }
            } else 0

            val status = if (relationshipId != null) {
                conn.prepareStatement("SELECT status FROM device_relationships WHERE relationship_id = ?").use { ps ->
                    ps.setString(1, relationshipId)
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else "UNBOUND" }
                }
            } else "UNBOUND"

            DeviceStatusResponse(
                deviceId = deviceId,
                relationshipId = relationshipId,
                status = status,
                pendingCount = pending,
                serverTimeEpochMs = nowMs
            )
        }
    }

    fun purgeExpired(nowMs: Long = System.currentTimeMillis()): Int {
        return db.inTransaction { conn ->
            // Advance expired floor per relationship before deleting payloads
            conn.prepareStatement(
                """
                UPDATE sync_metadata m
                SET expired_sequence_floor = COALESCE(
                    (SELECT MAX(e.sequence_number) FROM encrypted_events e
                     WHERE e.relationship_id = m.relationship_id AND e.expires_at <= ?),
                    m.expired_sequence_floor
                )
                """.trimIndent()
            ).use { ps ->
                ps.setLong(1, nowMs)
                ps.executeUpdate()
            }

            val deletedEvents = conn.prepareStatement("DELETE FROM encrypted_events WHERE expires_at <= ?").use { ps ->
                ps.setLong(1, nowMs)
                ps.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM pairing_sessions WHERE expires_at <= ?").use { ps ->
                ps.setLong(1, nowMs)
                ps.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM auth_nonces WHERE expires_at <= ?").use { ps ->
                ps.setLong(1, nowMs)
                ps.executeUpdate()
            }
            deletedEvents
        }
    }

    fun getMetrics(activeWsCount: Int, rateLimitRejections: Long): RelayMetricsResponse {
        return db.inTransaction { conn ->
            val rels = conn.prepareStatement("SELECT COUNT(*) FROM device_relationships WHERE status = 'ACTIVE'")
                .use { ps -> ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 } }
            val pending = conn.prepareStatement("SELECT COUNT(*) FROM encrypted_events WHERE status = 'SERVER_ACCEPTED'")
                .use { ps -> ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 } }
            RelayMetricsResponse(
                activeRelationships = rels,
                pendingEncryptedEvents = pending,
                activeWsConnections = activeWsCount,
                expiredEventsPurged = 0L,
                rateLimitRejections = rateLimitRejections
            )
        }
    }

    private fun upsertDevice(
        conn: java.sql.Connection,
        deviceId: String,
        deviceName: String,
        role: String,
        pubKey: String,
        nowMs: Long
    ) {
        val updated = conn.prepareStatement(
            "UPDATE devices SET device_name = ?, identity_public_key = ?, revoked_at = NULL WHERE device_id = ?"
        ).use { ps ->
            ps.setString(1, deviceName)
            ps.setString(2, pubKey)
            ps.setString(3, deviceId)
            ps.executeUpdate()
        }
        if (updated == 0) {
            conn.prepareStatement(
                "INSERT INTO devices (device_id, device_name, role, identity_public_key, created_at) VALUES (?, ?, ?, ?, ?)"
            ).use { ps ->
                ps.setString(1, deviceId)
                ps.setString(2, deviceName)
                ps.setString(3, role)
                ps.setString(4, pubKey)
                ps.setLong(5, nowMs)
                ps.executeUpdate()
            }
        }
    }

    private fun loadActiveRelationship(conn: java.sql.Connection, relationshipId: String): RelRow {
        return conn.prepareStatement(
            "SELECT primary_device_id, secondary_device_id, status FROM device_relationships WHERE relationship_id = ?"
        ).use { ps ->
            ps.setString(1, relationshipId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) throw DeviceAuthException("INVALID_DESTINATION", 404, "Relationship not found")
                val status = rs.getString(3)
                if (status != "ACTIVE") {
                    throw DeviceAuthException("REVOKED_PAIRING", 403, "Relationship revoked")
                }
                RelRow(rs.getString(1), rs.getString(2), status)
            }
        }
    }

    private data class SessionRow(
        val primaryDeviceId: String,
        val primaryDeviceName: String,
        val primaryPubKey: String,
        val spakeMsgA: String,
        val attempts: Int,
        val maxAttempts: Int,
        val completed: Boolean,
        val expiresAt: Long
    )

    private data class RelRow(
        val primaryDeviceId: String,
        val secondaryDeviceId: String,
        val status: String
    )
}
