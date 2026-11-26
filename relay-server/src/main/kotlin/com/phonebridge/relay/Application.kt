package com.phonebridge.relay

import com.phonebridge.relay.auth.DeviceAuthException
import com.phonebridge.relay.auth.DeviceAuthService
import com.phonebridge.relay.db.DatabaseFactory
import com.phonebridge.relay.db.RelayRepository
import com.phonebridge.relay.ratelimit.RateLimiter
import com.phonebridge.relay.ws.WebSocketHub
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.time.Duration

fun main() {
    val config = RelayConfig.fromEnv()
    val dbFactory = DatabaseFactory.createFromConfig(config)
    dbFactory.migrate()

    val server = embeddedServer(Netty, port = config.port) {
        phoneBridgeRelayModule(config, dbFactory)
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        server.stop(gracePeriodMillis = 3_000, timeoutMillis = 10_000)
        dbFactory.close()
    })

    server.start(wait = true)
}

fun Application.phoneBridgeRelayModule(
    config: RelayConfig,
    dbFactory: DatabaseFactory
) {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val authService = DeviceAuthService(dbFactory)
    val repo = RelayRepository(dbFactory, authService, config.credentialTtlDays)
    val rateLimiter = RateLimiter(config.rateLimitPerMinute)
    val wsHub = WebSocketHub(config.maxWsConnectionsPerDevice)
    val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Periodic expiration job deleting expired ciphertexts & nonces
    bgScope.launch {
        while (isActive) {
            runCatching { repo.purgeExpired() }
            delay(60_000L)
        }
    }

    install(ContentNegotiation) {
        json(json)
    }

    install(DefaultHeaders) {
        header("X-Content-Type-Options", "nosniff")
        header("X-Frame-Options", "DENY")
        header("Cache-Control", "no-store")
    }

    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(25)
        timeout = Duration.ofSeconds(15)
        maxFrameSize = (ProtocolConstants.MAX_ENCRYPTED_PAYLOAD_BYTES * 2).toLong()
    }

    install(StatusPages) {
        exception<DeviceAuthException> { call, ex ->
            call.respond(
                HttpStatusCode.fromValue(ex.httpStatus),
                RelayErrorResponse(ex.errorCode, ex.message ?: "Authentication error")
            )
        }
        exception<IllegalArgumentException> { call, ex ->
            call.respond(
                HttpStatusCode.BadRequest,
                RelayErrorResponse("INVALID_PAYLOAD", ex.message ?: "Invalid request")
            )
        }
        exception<Throwable> { call, _ ->
            call.respond(
                HttpStatusCode.InternalServerError,
                RelayErrorResponse("SERVER_UNAVAILABLE", "Internal relay error")
            )
        }
    }

    fun checkRateLimit(call: ApplicationCall, key: String): Boolean {
        if (!rateLimiter.allow(key)) {
            return false
        }
        return true
    }

    routing {
        get("/v1/health") {
            val dbOk = dbFactory.healthCheck()
            val status = if (dbOk) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
            call.respond(
                status,
                HealthResponse(
                    status = if (dbOk) "OK" else "DEGRADED",
                    protocolVersion = ProtocolConstants.PROTOCOL_VERSION,
                    databaseOk = dbOk,
                    activeWsConnections = wsHub.activeConnectionCount(),
                    serverTimeEpochMs = System.currentTimeMillis()
                )
            )
        }

        get("/v1/metrics") {
            call.respond(repo.getMetrics(wsHub.activeConnectionCount(), rateLimiter.rejectedCount.get()))
        }

        post("/v1/pairing/initiate") {
            val raw = call.receiveText()
            val callerIp = call.request.header("X-Forwarded-For") ?: "local"
            if (!checkRateLimit(call, "pair:$callerIp")) {
                call.respond(HttpStatusCode.TooManyRequests, RelayErrorResponse("RATE_LIMITED", "Too many pairing attempts", 60))
                return@post
            }
            authService.authenticateRequest(
                call.request.header("X-Device-Id"),
                call.request.header("X-Timestamp"),
                call.request.header("X-Nonce"),
                call.request.header("X-Signature"),
                null,
                "POST",
                "/v1/pairing/initiate",
                raw.toByteArray(),
                requireToken = false
            )
            val req = json.decodeFromString(PairingInitiateRequest.serializer(), raw)
            call.respond(HttpStatusCode.OK, repo.initiatePairing(req))
        }

        post("/v1/pairing/complete") {
            val raw = call.receiveText()
            val callerIp = call.request.header("X-Forwarded-For") ?: "local"
            if (!checkRateLimit(call, "pair:$callerIp")) {
                call.respond(HttpStatusCode.TooManyRequests, RelayErrorResponse("RATE_LIMITED", "Too many pairing attempts", 60))
                return@post
            }
            authService.authenticateRequest(
                call.request.header("X-Device-Id"),
                call.request.header("X-Timestamp"),
                call.request.header("X-Nonce"),
                call.request.header("X-Signature"),
                null,
                "POST",
                "/v1/pairing/complete",
                raw.toByteArray(),
                requireToken = false
            )
            val req = json.decodeFromString(PairingCompleteRequest.serializer(), raw)
            call.respond(HttpStatusCode.OK, repo.completePairing(req))
        }

        get("/v1/pairing/status/{sessionId}") {
            val sessionId = call.parameters["sessionId"] ?: throw IllegalArgumentException("Missing sessionId")
            val device = authService.authenticateRequest(
                call.request.header("X-Device-Id"),
                call.request.header("X-Timestamp"),
                call.request.header("X-Nonce"),
                call.request.header("X-Signature"),
                call.request.header("Authorization"),
                "GET",
                call.request.path(),
                ByteArray(0)
            )
            call.respond(HttpStatusCode.OK, repo.pollPairingStatus(sessionId, device.deviceId))
        }

        post("/v1/events") {
            val raw = call.receiveText()
            val device = authService.authenticateRequest(
                call.request.header("X-Device-Id"),
                call.request.header("X-Timestamp"),
                call.request.header("X-Nonce"),
                call.request.header("X-Signature"),
                call.request.header("Authorization"),
                "POST",
                "/v1/events",
                raw.toByteArray()
            )
            if (!checkRateLimit(call, "dev:${device.deviceId}")) {
                call.respond(HttpStatusCode.TooManyRequests, RelayErrorResponse("RATE_LIMITED", "Device rate limit exceeded", 15))
                return@post
            }
            val envelope = json.decodeFromString(EventEnvelope.serializer(), raw)
            val result = repo.submitEncryptedEvent(device.deviceId, envelope)
            if (!result.duplicate) {
                wsHub.pushEventToSecondary(envelope.relationshipId, envelope)
            }
            call.respond(HttpStatusCode.OK, result)
        }

        get("/v1/events/pending") {
            val device = authService.authenticateRequest(
                call.request.header("X-Device-Id"),
                call.request.header("X-Timestamp"),
                call.request.header("X-Nonce"),
                call.request.header("X-Signature"),
                call.request.header("Authorization"),
                "GET",
                "/v1/events/pending",
                ByteArray(0)
            )
            val relId = call.request.queryParameters["relationship_id"]
                ?: device.relationshipId
                ?: throw IllegalArgumentException("Missing relationship_id")
            val cursor = call.request.queryParameters["cursor"]?.toLongOrNull() ?: 0L
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: ProtocolConstants.MAX_SYNC_BATCH_SIZE
            val sync = repo.getPendingOrSync(device.deviceId, relId, cursor, limit)
            call.respond(
                HttpStatusCode.OK,
                PendingEventsResponse(
                    relationshipId = sync.relationshipId,
                    events = sync.events,
                    nextCursor = sync.nextCursor,
                    hasMore = sync.hasMore,
                    serverTimeEpochMs = sync.serverTimeEpochMs
                )
            )
        }

        post("/v1/sync") {
            val raw = call.receiveText()
            val device = authService.authenticateRequest(
                call.request.header("X-Device-Id"),
                call.request.header("X-Timestamp"),
                call.request.header("X-Nonce"),
                call.request.header("X-Signature"),
                call.request.header("Authorization"),
                "POST",
                "/v1/sync",
                raw.toByteArray()
            )
            val req = json.decodeFromString(SyncRequest.serializer(), raw)
            call.respond(HttpStatusCode.OK, repo.getPendingOrSync(device.deviceId, req.relationshipId, req.afterSequence, req.limit))
        }

        post("/v1/events/acknowledge") {
            val raw = call.receiveText()
            val device = authService.authenticateRequest(
                call.request.header("X-Device-Id"),
                call.request.header("X-Timestamp"),
                call.request.header("X-Nonce"),
                call.request.header("X-Signature"),
                call.request.header("Authorization"),
                "POST",
                "/v1/events/acknowledge",
                raw.toByteArray()
            )
            val req = json.decodeFromString(AcknowledgeEventsRequest.serializer(), raw)
            val acked = repo.acknowledgeEvents(device.deviceId, req.relationshipId, req.acknowledgements)
            for (item in req.acknowledgements.filter { it.eventId in acked }) {
                wsHub.pushReceiptToPrimary(req.relationshipId, item)
            }
            call.respond(
                HttpStatusCode.OK,
                AcknowledgeEventsResponse(
                    acknowledgedEventIds = acked,
                    serverTimeEpochMs = System.currentTimeMillis()
                )
            )
        }

        post("/v1/devices/revoke") {
            val raw = call.receiveText()
            val device = authService.authenticateRequest(
                call.request.header("X-Device-Id"),
                call.request.header("X-Timestamp"),
                call.request.header("X-Nonce"),
                call.request.header("X-Signature"),
                call.request.header("Authorization"),
                "POST",
                "/v1/devices/revoke",
                raw.toByteArray()
            )
            val req = json.decodeFromString(RevokeDeviceRequest.serializer(), raw)
            repo.revokeRelationship(device.deviceId, req.relationshipId, req.targetDeviceId, req.reason)
            wsHub.revokeRelationshipSessions(req.relationshipId)
            call.respond(HttpStatusCode.OK, mapOf("status" to "REVOKED"))
        }

        get("/v1/devices/status") {
            val device = authService.authenticateRequest(
                call.request.header("X-Device-Id"),
                call.request.header("X-Timestamp"),
                call.request.header("X-Nonce"),
                call.request.header("X-Signature"),
                call.request.header("Authorization"),
                "GET",
                "/v1/devices/status",
                ByteArray(0)
            )
            call.respond(HttpStatusCode.OK, repo.getDeviceStatus(device.deviceId, device.relationshipId))
        }

        webSocket("/v1/ws") {
            val device = try {
                authService.authenticateRequest(
                    call.request.header("X-Device-Id"),
                    call.request.header("X-Timestamp"),
                    call.request.header("X-Nonce"),
                    call.request.header("X-Signature"),
                    call.request.header("Authorization"),
                    call.request.httpMethod.value,
                    "/v1/ws",
                    ByteArray(0)
                )
            } catch (ex: DeviceAuthException) {
                close(CloseReason(4401, ex.errorCode))
                return@webSocket
            }

            val relId = call.request.queryParameters["relationship_id"] ?: device.relationshipId
            if (relId == null) {
                close(CloseReason(4403, "No relationship"))
                return@webSocket
            }

            val entry = wsHub.register(device.deviceId, device.role, relId, this)
            if (entry == null) {
                close(CloseReason(4429, "Too many concurrent WebSocket connections"))
                return@webSocket
            }

            try {
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        val wsFrame = runCatching {
                            json.decodeFromString(RelayWsFrame.serializer(), frame.readText())
                        }.getOrNull() ?: continue

                        when (wsFrame.type) {
                            "PING" -> send(Frame.Text(json.encodeToString(RelayWsFrame.serializer(), RelayWsFrame(type = "PONG", relationshipId = relId))))
                            "ACK" -> {
                                val ack = wsFrame.ack ?: continue
                                if (device.role == "SECONDARY") {
                                    val acked = repo.acknowledgeEvents(device.deviceId, relId, listOf(ack))
                                    if (acked.isNotEmpty()) {
                                        wsHub.pushReceiptToPrimary(relId, ack)
                                    }
                                }
                            }
                        }
                    }
                }
            } finally {
                wsHub.unregister(entry)
            }
        }
    }
}
