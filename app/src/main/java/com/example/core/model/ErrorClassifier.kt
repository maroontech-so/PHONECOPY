package com.example.core.model

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.SecureRandom
import javax.net.ssl.SSLException

enum class BridgeErrorCategory {
    TEMPORARY,
    PERMANENT
}

enum class BridgeErrorCode(val category: BridgeErrorCategory) {
    // Temporary failures
    NETWORK_UNAVAILABLE(BridgeErrorCategory.TEMPORARY),
    CONNECTION_TIMEOUT(BridgeErrorCategory.TEMPORARY),
    WEBSOCKET_DISCONNECT(BridgeErrorCategory.TEMPORARY),
    SERVER_UNAVAILABLE(BridgeErrorCategory.TEMPORARY),
    HTTP_5XX(BridgeErrorCategory.TEMPORARY),
    RATE_LIMITED(BridgeErrorCategory.TEMPORARY),
    TEMP_DATABASE_FAILURE(BridgeErrorCategory.TEMPORARY),
    TEMP_AUTH_RENEWAL(BridgeErrorCategory.TEMPORARY),

    // Permanent failures
    INVALID_EVENT(BridgeErrorCategory.PERMANENT),
    UNSUPPORTED_PROTOCOL(BridgeErrorCategory.PERMANENT),
    INVALID_DESTINATION(BridgeErrorCategory.PERMANENT),
    REVOKED_PAIRING(BridgeErrorCategory.PERMANENT),
    EXPIRED_EVENT(BridgeErrorCategory.PERMANENT),
    INVALID_PAYLOAD(BridgeErrorCategory.PERMANENT),
    UNAUTHORIZED_DEVICE(BridgeErrorCategory.PERMANENT),
    INCOMPATIBLE_CRYPTO_RELATIONSHIP(BridgeErrorCategory.PERMANENT),
    DECRYPTION_FAILED(BridgeErrorCategory.PERMANENT),
    AUTH_TAG_INVALID(BridgeErrorCategory.PERMANENT),
    TLS_VALIDATION_FAILED(BridgeErrorCategory.PERMANENT)
}

data class ClassifiedError(
    val code: BridgeErrorCode,
    val summary: String,
    val retryAfterMs: Long? = null
) {
    val isTemporary: Boolean get() = code.category == BridgeErrorCategory.TEMPORARY
    val isPermanent: Boolean get() = code.category == BridgeErrorCategory.PERMANENT
}

object ErrorClassifier {

    fun classifyHttp(statusCode: Int, serverErrorCode: String?, retryAfterSeconds: Long? = null): ClassifiedError {
        val retryAfterMs = retryAfterSeconds?.coerceIn(1L, 3600L)?.times(1000L)
        return when {
            statusCode == 429 -> ClassifiedError(
                code = BridgeErrorCode.RATE_LIMITED,
                summary = "Relay rate limit reached (HTTP 429)",
                retryAfterMs = retryAfterMs ?: 30_000L
            )
            statusCode == 401 -> ClassifiedError(
                code = BridgeErrorCode.UNAUTHORIZED_DEVICE,
                summary = "Device authentication rejected (HTTP 401)"
            )
            statusCode == 403 -> {
                val code = if (serverErrorCode == "REVOKED_PAIRING") {
                    BridgeErrorCode.REVOKED_PAIRING
                } else {
                    BridgeErrorCode.UNAUTHORIZED_DEVICE
                }
                ClassifiedError(code, "Device relationship forbidden or revoked (HTTP 403)")
            }
            statusCode == 410 -> ClassifiedError(
                code = BridgeErrorCode.EXPIRED_EVENT,
                summary = "Event expired on relay (HTTP 410)"
            )
            statusCode == 426 || serverErrorCode == "UNSUPPORTED_PROTOCOL" -> ClassifiedError(
                code = BridgeErrorCode.UNSUPPORTED_PROTOCOL,
                summary = "Unsupported protocol version"
            )
            statusCode in 400..499 -> {
                val code = when (serverErrorCode) {
                    "INVALID_DESTINATION" -> BridgeErrorCode.INVALID_DESTINATION
                    "INVALID_PAYLOAD" -> BridgeErrorCode.INVALID_PAYLOAD
                    "REVOKED_PAIRING" -> BridgeErrorCode.REVOKED_PAIRING
                    "EXPIRED_EVENT" -> BridgeErrorCode.EXPIRED_EVENT
                    else -> BridgeErrorCode.INVALID_EVENT
                }
                ClassifiedError(code, "Permanent client/envelope error (HTTP $statusCode)")
            }
            statusCode in 500..599 -> ClassifiedError(
                code = BridgeErrorCode.HTTP_5XX,
                summary = "Relay server error (HTTP $statusCode)",
                retryAfterMs = retryAfterMs
            )
            else -> ClassifiedError(
                code = BridgeErrorCode.SERVER_UNAVAILABLE,
                summary = "Unexpected HTTP status $statusCode"
            )
        }
    }

    fun classifyThrowable(t: Throwable): ClassifiedError = when (t) {
        is SSLException -> ClassifiedError(
            code = BridgeErrorCode.TLS_VALIDATION_FAILED,
            summary = "TLS certificate validation failed"
        )
        is SocketTimeoutException -> ClassifiedError(
            code = BridgeErrorCode.CONNECTION_TIMEOUT,
            summary = "Connection timed out"
        )
        is UnknownHostException -> ClassifiedError(
            code = BridgeErrorCode.NETWORK_UNAVAILABLE,
            summary = "DNS/network unavailable"
        )
        is IOException -> ClassifiedError(
            code = BridgeErrorCode.NETWORK_UNAVAILABLE,
            summary = "Network I/O error: ${t.javaClass.simpleName}"
        )
        else -> ClassifiedError(
            code = BridgeErrorCode.SERVER_UNAVAILABLE,
            summary = "Transport error: ${t.javaClass.simpleName}"
        )
    }
}

class RetryPolicy(
    private val scheduleMs: List<Long> = DEFAULT_SCHEDULE_MS,
    private val maxRetryCount: Int = 64,
    private val jitterFraction: Double = 0.20,
    private val random: SecureRandom = SecureRandom()
) {
    companion object {
        val DEFAULT_SCHEDULE_MS = listOf(
            2_000L,      // 2s
            5_000L,      // 5s
            15_000L,     // 15s
            30_000L,     // 30s
            60_000L,     // 60s
            120_000L,    // 2m
            300_000L,    // 5m
            900_000L     // 15m cap
        )
    }

    fun shouldRetry(error: ClassifiedError, currentRetryCount: Int, nowMs: Long, expiresAtMs: Long): Boolean {
        if (error.isPermanent) return false
        if (nowMs >= expiresAtMs) return false
        return currentRetryCount < maxRetryCount
    }

    fun nextAttemptAtMs(
        retryCountAfterFailure: Int,
        nowMs: Long,
        expiresAtMs: Long,
        retryAfterMs: Long? = null
    ): Long {
        val index = (retryCountAfterFailure - 1).coerceIn(0, scheduleMs.lastIndex)
        val baseDelay = scheduleMs[index]
        val jitterRange = (baseDelay * jitterFraction).toLong().coerceAtLeast(1L)
        val jitterOffset = (random.nextDouble() * 2.0 - 1.0) * jitterRange
        val jitteredDelay = (baseDelay + jitterOffset.toLong()).coerceAtLeast(1_000L)
        val effectiveDelay = maxOf(jitteredDelay, retryAfterMs ?: 0L)
        return minOf(nowMs + effectiveDelay, expiresAtMs)
    }
}
