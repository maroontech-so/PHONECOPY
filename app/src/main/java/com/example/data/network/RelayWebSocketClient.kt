package com.example.data.network

import com.example.core.model.AckItem
import com.example.core.model.RelayWsFrame
import com.example.core.model.RetryPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicInteger

enum class WebSocketConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    FAILED_PERMANENT
}

class RelayWebSocketClient(
    private val httpClient: RelayHttpClient,
    private val scope: CoroutineScope,
    private val allowLoopbackWsForTests: Boolean = false
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val retryPolicy = RetryPolicy()
    private val reconnectAttempts = AtomicInteger(0)

    private val _state = MutableStateFlow(WebSocketConnectionState.DISCONNECTED)
    val state: StateFlow<WebSocketConnectionState> = _state.asStateFlow()

    private val _incomingFrames = MutableSharedFlow<RelayWsFrame>(extraBufferCapacity = 64)
    val incomingFrames: SharedFlow<RelayWsFrame> = _incomingFrames.asSharedFlow()

    private var activeSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var lastBaseUrl: String? = null
    private var lastAuthToken: String? = null
    private var lastRelationshipId: String? = null
    private var userClosed = false

    @Synchronized
    fun connect(baseUrl: String, authToken: String, relationshipId: String) {
        userClosed = false
        lastBaseUrl = baseUrl
        lastAuthToken = authToken
        lastRelationshipId = relationshipId

        val wsUrl = toSecureWebSocketUrl(baseUrl, relationshipId)
        activeSocket?.close(1000, "Replacing connection")
        reconnectJob?.cancel()
        heartbeatJob?.cancel()

        _state.value = WebSocketConnectionState.CONNECTING

        val signedHeaders = httpClient.buildSignedHeaders("/v1/ws", "GET", ByteArray(0))
        val requestBuilder = Request.Builder()
            .url(wsUrl)
            .header("Authorization", "Bearer $authToken")
            .header("ngrok-skip-browser-warning", "true")
        signedHeaders.forEach { (k, v) -> requestBuilder.header(k, v) }

        activeSocket = httpClient.okHttpClient.newWebSocket(
            requestBuilder.build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    reconnectAttempts.set(0)
                    _state.value = WebSocketConnectionState.CONNECTED
                    startHeartbeat(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val frame = runCatching {
                        json.decodeFromString(RelayWsFrame.serializer(), text)
                    }.getOrNull() ?: return

                    if (frame.type == "PING") {
                        sendFrame(RelayWsFrame(type = "PONG", relationshipId = relationshipId))
                        return
                    }
                    scope.launch(Dispatchers.Default) {
                        _incomingFrames.emit(frame)
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    heartbeatJob?.cancel()
                    if (userClosed) {
                        _state.value = WebSocketConnectionState.DISCONNECTED
                    } else if (code == 4401 || code == 4403) {
                        _state.value = WebSocketConnectionState.FAILED_PERMANENT
                    } else {
                        scheduleReconnect()
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    heartbeatJob?.cancel()
                    if (userClosed) {
                        _state.value = WebSocketConnectionState.DISCONNECTED
                    } else if (response?.code == 401 || response?.code == 403) {
                        _state.value = WebSocketConnectionState.FAILED_PERMANENT
                    } else {
                        scheduleReconnect()
                    }
                }
            }
        )
    }

    fun sendAck(relationshipId: String, ackItem: AckItem): Boolean {
        return sendFrame(
            RelayWsFrame(
                type = "ACK",
                relationshipId = relationshipId,
                eventId = ackItem.eventId,
                ack = ackItem
            )
        )
    }

    fun sendFrame(frame: RelayWsFrame): Boolean {
        val socket = activeSocket ?: return false
        val encoded = json.encodeToString(RelayWsFrame.serializer(), frame)
        return socket.send(encoded)
    }

    fun reconnectIfNeeded() {
        val url = lastBaseUrl ?: return
        val token = lastAuthToken ?: return
        val rel = lastRelationshipId ?: return
        if (!userClosed && _state.value != WebSocketConnectionState.CONNECTED && _state.value != WebSocketConnectionState.CONNECTING) {
            connect(url, token, rel)
        }
    }

    @Synchronized
    fun disconnectGracefully() {
        userClosed = true
        reconnectJob?.cancel()
        heartbeatJob?.cancel()
        activeSocket?.close(1000, "Client shutdown")
        activeSocket = null
        _state.value = WebSocketConnectionState.DISCONNECTED
    }

    private fun startHeartbeat(webSocket: WebSocket) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (_state.value == WebSocketConnectionState.CONNECTED) {
                delay(25_000L)
                if (_state.value == WebSocketConnectionState.CONNECTED) {
                    val ping = json.encodeToString(
                        RelayWsFrame.serializer(),
                        RelayWsFrame(type = "PING", relationshipId = lastRelationshipId)
                    )
                    webSocket.send(ping)
                }
            }
        }
    }

    private fun scheduleReconnect() {
        if (userClosed) return
        val url = lastBaseUrl ?: return
        val token = lastAuthToken ?: return
        val rel = lastRelationshipId ?: return

        _state.value = WebSocketConnectionState.RECONNECTING
        val attempt = reconnectAttempts.incrementAndGet()
        val now = System.currentTimeMillis()
        val nextAt = retryPolicy.nextAttemptAtMs(attempt, now, now + 900_000L)
        val delayMs = (nextAt - now).coerceAtLeast(1_500L)

        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            if (!userClosed) {
                connect(url, token, rel)
            }
        }
    }

    private fun toSecureWebSocketUrl(baseUrl: String, relationshipId: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        return when {
            trimmed.startsWith("https://") -> "wss://${trimmed.removePrefix("https://")}/v1/ws?relationship_id=$relationshipId"
            allowLoopbackWsForTests && trimmed.startsWith("http://") ->
                "ws://${trimmed.removePrefix("http://")}/v1/ws?relationship_id=$relationshipId"
            else -> throw IllegalArgumentException("WebSocket requires WSS over TLS")
        }
    }
}
