package com.phonebridge.relay.auth

import com.phonebridge.relay.db.DatabaseFactory
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlin.math.abs

data class AuthenticatedDevice(
    val deviceId: String,
    val role: String,
    val relationshipId: String?
)

class DeviceAuthException(val errorCode: String, val httpStatus: Int, message: String) : RuntimeException(message)

class DeviceAuthService(private val db: DatabaseFactory) {

    private val random = SecureRandom()

    fun generateOpaqueToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun sha256Hex(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * Verifies ECDSA P-256 request signature + replay nonce + bearer credential hash + revocation state.
     */
    fun authenticateRequest(
        deviceIdHeader: String?,
        timestampHeader: String?,
        nonceHeader: String?,
        signatureHeader: String?,
        authHeader: String?,
        method: String,
        pathWithoutQuery: String,
        bodyBytes: ByteArray,
        requireToken: Boolean = true
    ): AuthenticatedDevice {
        val deviceId = deviceIdHeader?.takeIf { it.isNotBlank() }
            ?: throw DeviceAuthException("UNAUTHORIZED_DEVICE", 401, "Missing X-Device-Id")
        val ts = timestampHeader?.toLongOrNull()
            ?: throw DeviceAuthException("UNAUTHORIZED_DEVICE", 401, "Missing or invalid X-Timestamp")
        val nonce = nonceHeader?.takeIf { it.length in 8..120 }
            ?: throw DeviceAuthException("UNAUTHORIZED_DEVICE", 401, "Missing or invalid X-Nonce")
        val signatureB64 = signatureHeader?.takeIf { it.isNotBlank() }
            ?: throw DeviceAuthException("UNAUTHORIZED_DEVICE", 401, "Missing X-Signature")

        val now = System.currentTimeMillis()
        if (abs(now - ts) > 300_000L) {
            throw DeviceAuthException("UNAUTHORIZED_DEVICE", 401, "Request timestamp outside allowed window")
        }

        val bodyHash = sha256Hex(bodyBytes)
        val canonical = "$method|$pathWithoutQuery|$ts|$nonce|$bodyHash".toByteArray(Charsets.UTF_8)

        return db.inTransaction { conn ->
            // Replay protection: atomic single-use nonce insert
            val insertedNonce = runCatching {
                conn.prepareStatement(
                    "INSERT INTO auth_nonces (nonce, device_id, expires_at) VALUES (?, ?, ?)"
                ).use { ps ->
                    ps.setString(1, nonce)
                    ps.setString(2, deviceId)
                    ps.setLong(3, now + 600_000L)
                    ps.executeUpdate()
                }
            }.getOrDefault(0)
            if (insertedNonce != 1) {
                throw DeviceAuthException("UNAUTHORIZED_DEVICE", 401, "Replay nonce rejected")
            }

            if (!requireToken) {
                return@inTransaction AuthenticatedDevice(deviceId, "UNBOUND", null)
            }

            val bearer = authHeader?.removePrefix("Bearer ")?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: throw DeviceAuthException("UNAUTHORIZED_DEVICE", 401, "Missing Bearer token")

            val tokenHash = sha256Hex(bearer.toByteArray(Charsets.UTF_8))

            val (role, pubKeyB64, revokedAt, relId, credExpiresAt, credRevokedAt) = conn.prepareStatement(
                """
                SELECT d.role, d.identity_public_key, d.revoked_at,
                       c.relationship_id, c.expires_at, c.revoked_at
                FROM device_credentials c
                JOIN devices d ON d.device_id = c.device_id
                WHERE c.device_id = ? AND c.token_hash_sha256 = ?
                """
            ).use { ps ->
                ps.setString(1, deviceId)
                ps.setString(2, tokenHash)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) {
                        throw DeviceAuthException("UNAUTHORIZED_DEVICE", 401, "Invalid device credential")
                    }
                    AuthRow(
                        role = rs.getString(1),
                        pubKey = rs.getString(2),
                        deviceRevokedAt = rs.getObject(3) as? Long,
                        relationshipId = rs.getString(4),
                        credExpiresAt = rs.getLong(5),
                        credRevokedAt = rs.getObject(6) as? Long
                    )
                }
            }

            if (revokedAt != null || credRevokedAt != null) {
                throw DeviceAuthException("REVOKED_PAIRING", 403, "Device credential revoked")
            }
            if (now > credExpiresAt) {
                throw DeviceAuthException("UNAUTHORIZED_DEVICE", 401, "Device credential expired")
            }
            if (!verifyEcdsaSignature(pubKeyB64, canonical, signatureB64)) {
                throw DeviceAuthException("UNAUTHORIZED_DEVICE", 401, "Invalid ECDSA request signature")
            }

            AuthenticatedDevice(deviceId, role, relId)
        }
    }

    fun verifyEcdsaSignature(pubKeyB64: String, data: ByteArray, signatureB64: String): Boolean {
        return runCatching {
            val pubBytes = Base64.getDecoder().decode(pubKeyB64)
            val pubKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(pubBytes))
            val sigBytes = Base64.getDecoder().decode(signatureB64)
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initVerify(pubKey)
            sig.update(data)
            sig.verify(sigBytes)
        }.getOrDefault(false)
    }

    private data class AuthRow(
        val role: String,
        val pubKey: String,
        val deviceRevokedAt: Long?,
        val relationshipId: String?,
        val credExpiresAt: Long,
        val credRevokedAt: Long?
    )
}
