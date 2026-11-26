package com.example.core.crypto

import android.util.Base64
import com.example.core.model.EventEnvelope
import com.example.core.model.EventPayload
import com.example.core.model.PayloadType
import com.example.core.model.ProtocolConstants
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-End Authenticated Encryption (AES-256-GCM) with HKDF-SHA256 directional subkeys
 * and deterministic unique nonces derived from (keyEpoch || sequenceNumber).
 *
 * The Relay server sees only envelope metadata and opaque ciphertext; it lacks K_root
 * and cannot decrypt or forge payloads or acknowledgements.
 */
object EnvelopeCryptoEngine {

    private const val GCM_TAG_BITS = 128
    private const val GCM_TAG_BYTES = 16
    private const val NONCE_BYTES = 12

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun deriveEventEncryptionKey(rootKey: ByteArray, relationshipId: String, keyEpoch: Int): ByteArray {
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        val info = "PhoneBridge-v1-Enc-P2S|$relationshipId|$keyEpoch".toByteArray(Charsets.UTF_8)
        hkdf.init(HKDFParameters(rootKey, relationshipId.toByteArray(Charsets.UTF_8), info))
        return ByteArray(32).also { hkdf.generateBytes(it, 0, 32) }
    }

    fun deriveAckMacKey(rootKey: ByteArray, relationshipId: String, keyEpoch: Int): ByteArray {
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        val info = "PhoneBridge-v1-Ack-S2P|$relationshipId|$keyEpoch".toByteArray(Charsets.UTF_8)
        hkdf.init(HKDFParameters(rootKey, relationshipId.toByteArray(Charsets.UTF_8), info))
        return ByteArray(32).also { hkdf.generateBytes(it, 0, 32) }
    }

    /**
     * Deterministic 96-bit nonce: 4-byte keyEpoch || 8-byte monotonic sequenceNumber.
     * Guarantees zero nonce reuse across restarts and millions of events for a given keyEpoch.
     */
    fun buildDeterministicNonce(keyEpoch: Int, sequenceNumber: Long): ByteArray {
        require(keyEpoch >= 1) { "keyEpoch must be >= 1" }
        require(sequenceNumber >= 1L) { "sequenceNumber must be >= 1" }
        return ByteBuffer.allocate(NONCE_BYTES)
            .putInt(keyEpoch)
            .putLong(sequenceNumber)
            .array()
    }

    fun buildAssociatedData(
        protocolVersion: Int,
        eventId: String,
        relationshipId: String,
        sequenceNumber: Long,
        keyEpoch: Int,
        createdAtEpochMs: Long,
        expiresAtEpochMs: Long,
        payloadType: PayloadType
    ): ByteArray {
        val canonical = buildString {
            append("PBv1|")
            append(protocolVersion).append('|')
            append(eventId).append('|')
            append(relationshipId).append('|')
            append(sequenceNumber).append('|')
            append(keyEpoch).append('|')
            append(createdAtEpochMs).append('|')
            append(expiresAtEpochMs).append('|')
            append(payloadType.name)
        }
        return canonical.toByteArray(Charsets.UTF_8)
    }

    fun encryptPayload(
        rootKey: ByteArray,
        eventId: String,
        relationshipId: String,
        sequenceNumber: Long,
        keyEpoch: Int,
        createdAtEpochMs: Long,
        expiresAtEpochMs: Long,
        payloadType: PayloadType,
        payload: EventPayload
    ): EventEnvelope {
        val plaintext = json.encodeToString(EventPayload.serializer(), payload).toByteArray(Charsets.UTF_8)
        require(plaintext.size <= ProtocolConstants.MAX_ENCRYPTED_PAYLOAD_BYTES) {
            "Plaintext payload exceeds ${ProtocolConstants.MAX_ENCRYPTED_PAYLOAD_BYTES} bytes"
        }

        val encKey = deriveEventEncryptionKey(rootKey, relationshipId, keyEpoch)
        val nonce = buildDeterministicNonce(keyEpoch, sequenceNumber)
        val aad = buildAssociatedData(
            ProtocolConstants.PROTOCOL_VERSION,
            eventId,
            relationshipId,
            sequenceNumber,
            keyEpoch,
            createdAtEpochMs,
            expiresAtEpochMs,
            payloadType
        )

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(encKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(aad)
        val combined = cipher.doFinal(plaintext)

        val ciphertext = combined.copyOfRange(0, combined.size - GCM_TAG_BYTES)
        val authTag = combined.copyOfRange(combined.size - GCM_TAG_BYTES, combined.size)
        val ctHash = sha256Hex(ciphertext)

        return EventEnvelope(
            protocolVersion = ProtocolConstants.PROTOCOL_VERSION,
            eventId = eventId,
            relationshipId = relationshipId,
            sequenceNumber = sequenceNumber,
            keyEpoch = keyEpoch,
            createdAtEpochMs = createdAtEpochMs,
            expiresAtEpochMs = expiresAtEpochMs,
            payloadType = payloadType,
            ciphertext = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
            nonce = Base64.encodeToString(nonce, Base64.NO_WRAP),
            authTag = Base64.encodeToString(authTag, Base64.NO_WRAP),
            ciphertextHash = ctHash
        )
    }

    @Throws(AEADBadTagException::class, IllegalArgumentException::class)
    fun decryptEnvelope(rootKey: ByteArray, envelope: EventEnvelope): EventPayload {
        require(envelope.protocolVersion == ProtocolConstants.PROTOCOL_VERSION) {
            "Unsupported protocol version ${envelope.protocolVersion}"
        }
        val ciphertext = Base64.decode(envelope.ciphertext, Base64.NO_WRAP)
        val nonce = Base64.decode(envelope.nonce, Base64.NO_WRAP)
        val authTag = Base64.decode(envelope.authTag, Base64.NO_WRAP)

        require(nonce.size == NONCE_BYTES) { "Invalid nonce length" }
        require(authTag.size == GCM_TAG_BYTES) { "Invalid auth tag length" }
        require(MessageDigest.isEqual(sha256Hex(ciphertext).toByteArray(), envelope.ciphertextHash.toByteArray())) {
            "Ciphertext integrity hash mismatch"
        }

        val encKey = deriveEventEncryptionKey(rootKey, envelope.relationshipId, envelope.keyEpoch)
        val aad = buildAssociatedData(
            envelope.protocolVersion,
            envelope.eventId,
            envelope.relationshipId,
            envelope.sequenceNumber,
            envelope.keyEpoch,
            envelope.createdAtEpochMs,
            envelope.expiresAtEpochMs,
            envelope.payloadType
        )

        val combined = ByteArray(ciphertext.size + authTag.size)
        System.arraycopy(ciphertext, 0, combined, 0, ciphertext.size)
        System.arraycopy(authTag, 0, combined, ciphertext.size, authTag.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(encKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(aad)
        val plaintext = cipher.doFinal(combined)

        return json.decodeFromString(EventPayload.serializer(), plaintext.toString(Charsets.UTF_8))
    }

    fun computeAckMac(
        rootKey: ByteArray,
        relationshipId: String,
        keyEpoch: Int,
        eventId: String,
        sequenceNumber: Long,
        receivedAtEpochMs: Long
    ): String {
        val ackKey = deriveAckMacKey(rootKey, relationshipId, keyEpoch)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(ackKey, "HmacSHA256"))
        val msg = "ACK|$relationshipId|$eventId|$sequenceNumber|$receivedAtEpochMs".toByteArray(Charsets.UTF_8)
        return Base64.encodeToString(mac.doFinal(msg), Base64.NO_WRAP)
    }

    fun verifyAckMac(
        rootKey: ByteArray,
        relationshipId: String,
        keyEpoch: Int,
        eventId: String,
        sequenceNumber: Long,
        receivedAtEpochMs: Long,
        ackMacB64: String
    ): Boolean {
        val expected = Base64.decode(
            computeAckMac(rootKey, relationshipId, keyEpoch, eventId, sequenceNumber, receivedAtEpochMs),
            Base64.NO_WRAP
        )
        val actual = runCatching { Base64.decode(ackMacB64, Base64.NO_WRAP) }.getOrNull() ?: return false
        return MessageDigest.isEqual(expected, actual)
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
