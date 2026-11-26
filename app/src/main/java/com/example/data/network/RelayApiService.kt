package com.example.data.network

import com.example.core.crypto.EnvelopeCryptoEngine
import com.example.core.crypto.KeystoreManager
import com.example.core.model.AcknowledgeEventsRequest
import com.example.core.model.AcknowledgeEventsResponse
import com.example.core.model.BridgeErrorCode
import com.example.core.model.ClassifiedError
import com.example.core.model.DeviceStatusResponse
import com.example.core.model.ErrorClassifier
import com.example.core.model.EventEnvelope
import com.example.core.model.HealthResponse
import com.example.core.model.PairingCompleteRequest
import com.example.core.model.PairingCompleteResponse
import com.example.core.model.PairingInitiateRequest
import com.example.core.model.PairingInitiateResponse
import com.example.core.model.PairingSessionLookupResponse
import com.example.core.model.PairingStatusPollResponse
import com.example.core.model.PendingEventsResponse
import com.example.core.model.ProtocolConstants
import com.example.core.model.RelayErrorResponse
import com.example.core.model.RevokeDeviceRequest
import com.example.core.model.SubmitEventResponse
import com.example.core.model.SyncRequest
import com.example.core.model.SyncResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import okhttp3.ConnectionSpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

sealed class RelayResult<out T> {
    data class Success<T>(val data: T) : RelayResult<T>()
    data class Failure(val error: ClassifiedError) : RelayResult<Nothing>()
}

interface RelayTransport {
    suspend fun initiatePairing(baseUrl: String, req: PairingInitiateRequest): RelayResult<PairingInitiateResponse>
    suspend fun lookupPairingSession(baseUrl: String, sessionId: String): RelayResult<PairingSessionLookupResponse>
    suspend fun pollPairingStatus(baseUrl: String, sessionId: String, authToken: String): RelayResult<PairingStatusPollResponse>
    suspend fun completePairing(baseUrl: String, req: PairingCompleteRequest): RelayResult<PairingCompleteResponse>
    suspend fun submitEvent(baseUrl: String, authToken: String, envelope: EventEnvelope): RelayResult<SubmitEventResponse>
    suspend fun getPendingEvents(baseUrl: String, authToken: String, relationshipId: String, cursor: Long, limit: Int): RelayResult<PendingEventsResponse>
    suspend fun sync(baseUrl: String, authToken: String, req: SyncRequest): RelayResult<SyncResponse>
    suspend fun acknowledgeEvents(baseUrl: String, authToken: String, req: AcknowledgeEventsRequest): RelayResult<AcknowledgeEventsResponse>
    suspend fun revokeDevice(baseUrl: String, authToken: String, req: RevokeDeviceRequest): RelayResult<Unit>
    suspend fun getDeviceStatus(baseUrl: String, authToken: String): RelayResult<DeviceStatusResponse>
    suspend fun getHealth(baseUrl: String): RelayResult<HealthResponse>
}

class RelayHttpClient(
    private val keystoreManager: KeystoreManager,
    private val allowLoopbackHttpForTests: Boolean = false,
    customClient: OkHttpClient? = null
) : RelayTransport {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    val okHttpClient: OkHttpClient = customClient ?: OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .connectionSpecs(
            if (allowLoopbackHttpForTests) {
                listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT)
            } else {
                listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.RESTRICTED_TLS)
            }
        )
        .retryOnConnectionFailure(false) // Managed explicitly by RetryPolicy
        .build()

    override suspend fun initiatePairing(baseUrl: String, req: PairingInitiateRequest): RelayResult<PairingInitiateResponse> =
        post(baseUrl, "/v1/pairing/initiate", null, req, PairingInitiateRequest.serializer(), PairingInitiateResponse.serializer())

    override suspend fun lookupPairingSession(baseUrl: String, sessionId: String): RelayResult<PairingSessionLookupResponse> =
        get(baseUrl, "/v1/pairing/session/$sessionId", null, PairingSessionLookupResponse.serializer())

    override suspend fun pollPairingStatus(baseUrl: String, sessionId: String, authToken: String): RelayResult<PairingStatusPollResponse> =
        get(baseUrl, "/v1/pairing/status/$sessionId", authToken, PairingStatusPollResponse.serializer())

    override suspend fun completePairing(baseUrl: String, req: PairingCompleteRequest): RelayResult<PairingCompleteResponse> =
        post(baseUrl, "/v1/pairing/complete", null, req, PairingCompleteRequest.serializer(), PairingCompleteResponse.serializer())

    override suspend fun submitEvent(baseUrl: String, authToken: String, envelope: EventEnvelope): RelayResult<SubmitEventResponse> =
        post(baseUrl, "/v1/events", authToken, envelope, EventEnvelope.serializer(), SubmitEventResponse.serializer())

    override suspend fun getPendingEvents(
        baseUrl: String,
        authToken: String,
        relationshipId: String,
        cursor: Long,
        limit: Int
    ): RelayResult<PendingEventsResponse> =
        get(
            baseUrl,
            "/v1/events/pending?relationship_id=$relationshipId&cursor=$cursor&limit=$limit",
            authToken,
            PendingEventsResponse.serializer()
        )

    override suspend fun sync(baseUrl: String, authToken: String, req: SyncRequest): RelayResult<SyncResponse> =
        post(baseUrl, "/v1/sync", authToken, req, SyncRequest.serializer(), SyncResponse.serializer())

    override suspend fun acknowledgeEvents(
        baseUrl: String,
        authToken: String,
        req: AcknowledgeEventsRequest
    ): RelayResult<AcknowledgeEventsResponse> =
        post(baseUrl, "/v1/events/acknowledge", authToken, req, AcknowledgeEventsRequest.serializer(), AcknowledgeEventsResponse.serializer())

    override suspend fun revokeDevice(baseUrl: String, authToken: String, req: RevokeDeviceRequest): RelayResult<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching {
                validateBaseUrl(baseUrl)
                val bodyStr = json.encodeToString(RevokeDeviceRequest.serializer(), req)
                val request = buildSignedRequest(baseUrl, "/v1/devices/revoke", "POST", authToken, bodyStr)
                okHttpClient.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) {
                        RelayResult.Success(Unit)
                    } else {
                        parseFailure(resp.code, resp.body?.string(), resp.header("Retry-After")?.toLongOrNull())
                    }
                }
            }.getOrElse { RelayResult.Failure(ErrorClassifier.classifyThrowable(it)) }
        }
    }

    override suspend fun getDeviceStatus(baseUrl: String, authToken: String): RelayResult<DeviceStatusResponse> =
        get(baseUrl, "/v1/devices/status", authToken, DeviceStatusResponse.serializer())

    override suspend fun getHealth(baseUrl: String): RelayResult<HealthResponse> =
        get(baseUrl, "/v1/health", null, HealthResponse.serializer())

    private suspend fun <Req, Resp> post(
        baseUrl: String,
        path: String,
        authToken: String?,
        payload: Req,
        reqSerializer: KSerializer<Req>,
        respSerializer: KSerializer<Resp>
    ): RelayResult<Resp> = withContext(Dispatchers.IO) {
        runCatching {
            validateBaseUrl(baseUrl)
            val bodyStr = json.encodeToString(reqSerializer, payload)
            require(bodyStr.toByteArray().size <= ProtocolConstants.MAX_ENCRYPTED_PAYLOAD_BYTES * 2) {
                "Request exceeds transport payload limit"
            }
            val request = buildSignedRequest(baseUrl, path, "POST", authToken, bodyStr)
            okHttpClient.newCall(request).execute().use { resp ->
                val respBody = resp.body?.string()
                if (resp.isSuccessful && respBody != null) {
                    RelayResult.Success(json.decodeFromString(respSerializer, respBody))
                } else {
                    parseFailure(resp.code, respBody, resp.header("Retry-After")?.toLongOrNull())
                }
            }
        }.getOrElse { RelayResult.Failure(ErrorClassifier.classifyThrowable(it)) }
    }

    private suspend fun <Resp> get(
        baseUrl: String,
        path: String,
        authToken: String?,
        respSerializer: KSerializer<Resp>
    ): RelayResult<Resp> = withContext(Dispatchers.IO) {
        runCatching {
            validateBaseUrl(baseUrl)
            val request = buildSignedRequest(baseUrl, path, "GET", authToken, null)
            okHttpClient.newCall(request).execute().use { resp ->
                val respBody = resp.body?.string()
                if (resp.isSuccessful && respBody != null) {
                    RelayResult.Success(json.decodeFromString(respSerializer, respBody))
                } else {
                    parseFailure(resp.code, respBody, resp.header("Retry-After")?.toLongOrNull())
                }
            }
        }.getOrElse { RelayResult.Failure(ErrorClassifier.classifyThrowable(it)) }
    }

    fun buildSignedHeaders(pathWithoutQuery: String, method: String, bodyBytes: ByteArray): Map<String, String> {
        val deviceId = keystoreManager.getDeviceId()
        val timestamp = System.currentTimeMillis().toString()
        val nonce = UUID.randomUUID().toString()
        val bodyHash = EnvelopeCryptoEngine.sha256Hex(bodyBytes)
        val canonical = "$method|$pathWithoutQuery|$timestamp|$nonce|$bodyHash".toByteArray(Charsets.UTF_8)
        val signature = keystoreManager.sign(canonical)
        return mapOf(
            "X-Protocol-Version" to ProtocolConstants.PROTOCOL_VERSION.toString(),
            "X-Device-Id" to deviceId,
            "X-Timestamp" to timestamp,
            "X-Nonce" to nonce,
            "X-Signature" to signature
        )
    }

    private fun buildSignedRequest(
        baseUrl: String,
        pathWithQuery: String,
        method: String,
        authToken: String?,
        bodyStr: String?
    ): Request {
        val normalizedBase = baseUrl.trim().trimEnd('/')
        val pathOnly = pathWithQuery.substringBefore('?')
        val bodyBytes = bodyStr?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val headers = buildSignedHeaders(pathOnly, method, bodyBytes)

        val builder = Request.Builder()
            .url("$normalizedBase$pathWithQuery")
            .header("ngrok-skip-browser-warning", "true")
        headers.forEach { (k, v) -> builder.header(k, v) }
        if (!authToken.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $authToken")
        }
        if (method == "POST") {
            builder.post((bodyStr ?: "").toRequestBody(jsonMediaType))
        } else {
            builder.get()
        }
        return builder.build()
    }

    private fun validateBaseUrl(baseUrl: String) {
        val trimmed = baseUrl.trim()
        val isHttps = trimmed.startsWith("https://")
        val isTestLoopback = allowLoopbackHttpForTests &&
            (trimmed.startsWith("http://localhost") || trimmed.startsWith("http://127.0.0.1"))
        require(isHttps || isTestLoopback) {
            "Insecure transport rejected: Relay URL must use HTTPS"
        }
    }

    private fun parseFailure(statusCode: Int, rawBody: String?, retryAfterHeader: Long?): RelayResult.Failure {
        val parsed = rawBody?.let {
            runCatching { json.decodeFromString(RelayErrorResponse.serializer(), it) }.getOrNull()
        }
        return RelayResult.Failure(
            ErrorClassifier.classifyHttp(
                statusCode = statusCode,
                serverErrorCode = parsed?.code,
                retryAfterSeconds = parsed?.retryAfterSeconds ?: retryAfterHeader
            )
        )
    }
}
