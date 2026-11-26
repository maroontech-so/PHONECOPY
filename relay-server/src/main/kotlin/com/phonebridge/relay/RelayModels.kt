package com.phonebridge.relay

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

object ProtocolConstants {
    const val PROTOCOL_VERSION = 1
    const val MAX_ENCRYPTED_PAYLOAD_BYTES = 65_536
    const val MAX_SYNC_BATCH_SIZE = 50
    const val PAIRING_TTL_MS = 300_000L
    const val MAX_PAIRING_ATTEMPTS = 5
}

@Serializable
enum class PayloadType {
    NOTIFICATION,
    CALENDAR_EVENT,
    HEARTBEAT_TEST
}

@Serializable
data class EventEnvelope(
    @SerialName("protocol_version") val protocolVersion: Int = ProtocolConstants.PROTOCOL_VERSION,
    @SerialName("event_id") val eventId: String,
    @SerialName("relationship_id") val relationshipId: String,
    @SerialName("sequence_number") val sequenceNumber: Long,
    @SerialName("key_epoch") val keyEpoch: Int = 1,
    @SerialName("created_at") val createdAtEpochMs: Long,
    @SerialName("expires_at") val expiresAtEpochMs: Long,
    @SerialName("payload_type") val payloadType: PayloadType,
    val ciphertext: String,
    val nonce: String,
    @SerialName("authentication_tag") val authTag: String,
    @SerialName("ciphertext_hash") val ciphertextHash: String
)

@Serializable
data class PairingInitiateRequest(
    @SerialName("protocol_version") val protocolVersion: Int = ProtocolConstants.PROTOCOL_VERSION,
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_name") val deviceName: String,
    @SerialName("identity_public_key") val identityPublicKeyB64: String,
    @SerialName("spake2_message_a") val spake2MessageAB64: String,
    @SerialName("pairing_session_id") val pairingSessionId: String
)

@Serializable
data class PairingInitiateResponse(
    @SerialName("pairing_session_id") val pairingSessionId: String,
    @SerialName("expires_at") val expiresAtEpochMs: Long,
    @SerialName("device_token") val deviceToken: String
)

@Serializable
data class PairingCompleteRequest(
    @SerialName("protocol_version") val protocolVersion: Int = ProtocolConstants.PROTOCOL_VERSION,
    @SerialName("pairing_session_id") val pairingSessionId: String,
    @SerialName("secondary_device_id") val secondaryDeviceId: String,
    @SerialName("secondary_device_name") val secondaryDeviceName: String,
    @SerialName("secondary_identity_public_key") val secondaryIdentityPublicKeyB64: String,
    @SerialName("spake2_message_b") val spake2MessageBB64: String,
    @SerialName("key_confirmation_mac_b") val keyConfirmationMacBB64: String,
    @SerialName("identity_signature_b") val identitySignatureBB64: String
)

@Serializable
data class PairingCompleteResponse(
    @SerialName("relationship_id") val relationshipId: String,
    @SerialName("primary_device_id") val primaryDeviceId: String,
    @SerialName("primary_device_name") val primaryDeviceName: String,
    @SerialName("primary_identity_public_key") val primaryIdentityPublicKeyB64: String,
    @SerialName("spake2_message_a") val spake2MessageAB64: String,
    @SerialName("secondary_token") val secondaryToken: String,
    @SerialName("key_epoch") val keyEpoch: Int
)

@Serializable
data class PairingStatusPollResponse(
    @SerialName("pairing_session_id") val pairingSessionId: String,
    val completed: Boolean,
    @SerialName("relationship_id") val relationshipId: String? = null,
    @SerialName("secondary_device_id") val secondaryDeviceId: String? = null,
    @SerialName("secondary_device_name") val secondaryDeviceName: String? = null,
    @SerialName("secondary_identity_public_key") val secondaryIdentityPublicKeyB64: String? = null,
    @SerialName("spake2_message_b") val spake2MessageBB64: String? = null,
    @SerialName("key_confirmation_mac_b") val keyConfirmationMacBB64: String? = null,
    @SerialName("identity_signature_b") val identitySignatureBB64: String? = null
)

@Serializable
data class SubmitEventResponse(
    @SerialName("event_id") val eventId: String,
    @SerialName("relationship_id") val relationshipId: String,
    @SerialName("sequence_number") val sequenceNumber: Long,
    @SerialName("accepted_at") val acceptedAtEpochMs: Long,
    val duplicate: Boolean = false,
    val status: String = "SERVER_ACCEPTED"
)

@Serializable
data class PendingEventsResponse(
    @SerialName("relationship_id") val relationshipId: String,
    val events: List<EventEnvelope>,
    @SerialName("next_cursor") val nextCursor: Long,
    @SerialName("has_more") val hasMore: Boolean,
    @SerialName("server_time") val serverTimeEpochMs: Long
)

@Serializable
data class SyncRequest(
    @SerialName("protocol_version") val protocolVersion: Int = ProtocolConstants.PROTOCOL_VERSION,
    @SerialName("relationship_id") val relationshipId: String,
    @SerialName("after_sequence") val afterSequence: Long,
    val limit: Int = ProtocolConstants.MAX_SYNC_BATCH_SIZE
)

@Serializable
data class SyncResponse(
    @SerialName("relationship_id") val relationshipId: String,
    val events: List<EventEnvelope>,
    @SerialName("next_cursor") val nextCursor: Long,
    @SerialName("has_more") val hasMore: Boolean,
    @SerialName("highest_server_sequence") val highestServerSequence: Long,
    @SerialName("expired_sequence_floor") val expiredSequenceFloor: Long = 0L,
    @SerialName("server_time") val serverTimeEpochMs: Long
)

@Serializable
data class AckItem(
    @SerialName("event_id") val eventId: String,
    @SerialName("sequence_number") val sequenceNumber: Long,
    @SerialName("received_at") val receivedAtEpochMs: Long,
    @SerialName("ack_mac") val ackMacB64: String
)

@Serializable
data class AcknowledgeEventsRequest(
    @SerialName("protocol_version") val protocolVersion: Int = ProtocolConstants.PROTOCOL_VERSION,
    @SerialName("relationship_id") val relationshipId: String,
    val acknowledgements: List<AckItem>
)

@Serializable
data class AcknowledgeEventsResponse(
    @SerialName("acknowledged_event_ids") val acknowledgedEventIds: List<String>,
    @SerialName("server_time") val serverTimeEpochMs: Long
)

@Serializable
data class RevokeDeviceRequest(
    @SerialName("relationship_id") val relationshipId: String,
    @SerialName("target_device_id") val targetDeviceId: String,
    val reason: String
)

@Serializable
data class DeviceStatusResponse(
    @SerialName("device_id") val deviceId: String,
    @SerialName("relationship_id") val relationshipId: String? = null,
    val status: String,
    @SerialName("pending_count") val pendingCount: Int = 0,
    @SerialName("server_time") val serverTimeEpochMs: Long
)

@Serializable
data class HealthResponse(
    val status: String,
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("database_ok") val databaseOk: Boolean,
    @SerialName("active_ws_connections") val activeWsConnections: Int = 0,
    @SerialName("server_time") val serverTimeEpochMs: Long
)

@Serializable
data class RelayMetricsResponse(
    @SerialName("active_relationships") val activeRelationships: Int,
    @SerialName("pending_encrypted_events") val pendingEncryptedEvents: Int,
    @SerialName("active_ws_connections") val activeWsConnections: Int,
    @SerialName("expired_events_purged") val expiredEventsPurged: Long,
    @SerialName("rate_limit_rejections") val rateLimitRejections: Long
)

@Serializable
data class RelayErrorResponse(
    val code: String,
    val message: String,
    @SerialName("retry_after_seconds") val retryAfterSeconds: Long? = null
)

@Serializable
data class RelayWsFrame(
    val type: String,
    val envelope: EventEnvelope? = null,
    val ack: AckItem? = null,
    @SerialName("relationship_id") val relationshipId: String? = null,
    @SerialName("event_id") val eventId: String? = null,
    val status: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
