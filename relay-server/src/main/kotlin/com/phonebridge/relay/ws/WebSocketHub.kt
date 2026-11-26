package com.phonebridge.relay.ws

import com.phonebridge.relay.AckItem
import com.phonebridge.relay.EventEnvelope
import com.phonebridge.relay.RelayWsFrame
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class WebSocketHub(private val maxPerDevice: Int = 2) {

    data class ClientSession(
        val deviceId: String,
        val role: String,
        val relationshipId: String,
        val session: WebSocketSession
    )

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val sessionsByRelationship = ConcurrentHashMap<String, CopyOnWriteArrayList<ClientSession>>()

    fun register(deviceId: String, role: String, relationshipId: String, session: WebSocketSession): ClientSession? {
        val list = sessionsByRelationship.computeIfAbsent(relationshipId) { CopyOnWriteArrayList() }
        val existingForDevice = list.count { it.deviceId == deviceId }
        if (existingForDevice >= maxPerDevice) {
            return null
        }
        val entry = ClientSession(deviceId, role, relationshipId, session)
        list.add(entry)
        return entry
    }

    fun unregister(entry: ClientSession) {
        sessionsByRelationship[entry.relationshipId]?.remove(entry)
    }

    suspend fun pushEventToSecondary(relationshipId: String, envelope: EventEnvelope) {
        val frame = RelayWsFrame(
            type = "EVENT",
            relationshipId = relationshipId,
            eventId = envelope.eventId,
            envelope = envelope
        )
        val payload = json.encodeToString(RelayWsFrame.serializer(), frame)
        val targets = sessionsByRelationship[relationshipId]?.filter { it.role == "SECONDARY" }.orEmpty()
        for (target in targets) {
            runCatching { target.session.send(Frame.Text(payload)) }
        }
    }

    suspend fun pushReceiptToPrimary(relationshipId: String, ack: AckItem) {
        val frame = RelayWsFrame(
            type = "RECEIPT",
            relationshipId = relationshipId,
            eventId = ack.eventId,
            ack = ack,
            status = "DEVICE_RECEIVED"
        )
        val payload = json.encodeToString(RelayWsFrame.serializer(), frame)
        val targets = sessionsByRelationship[relationshipId]?.filter { it.role == "PRIMARY" }.orEmpty()
        for (target in targets) {
            runCatching { target.session.send(Frame.Text(payload)) }
        }
    }

    suspend fun revokeRelationshipSessions(relationshipId: String) {
        val list = sessionsByRelationship.remove(relationshipId) ?: return
        for (entry in list) {
            runCatching {
                entry.session.close(CloseReason(4403, "Relationship revoked"))
            }
        }
    }

    suspend fun closeAll() {
        val all = sessionsByRelationship.values.flatten()
        sessionsByRelationship.clear()
        for (entry in all) {
            runCatching {
                entry.session.close(CloseReason(CloseReason.Codes.GOING_AWAY, "Server shutting down"))
            }
        }
    }

    fun activeConnectionCount(): Int = sessionsByRelationship.values.sumOf { it.size }
}
