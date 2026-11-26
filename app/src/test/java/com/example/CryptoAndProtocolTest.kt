package com.example

import com.example.core.crypto.EnvelopeCryptoEngine
import com.example.core.crypto.KeystoreManager
import com.example.core.crypto.Spake2PairingEngine
import com.example.core.model.BridgeErrorCode
import com.example.core.model.DeliveryState
import com.example.core.model.DeliveryStateMachine
import com.example.core.model.ErrorClassifier
import com.example.core.model.EventPayload
import com.example.core.model.PayloadType
import com.example.core.model.RetryPolicy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import javax.crypto.AEADBadTagException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CryptoAndProtocolTest {

    @Test
    fun `spake2 derives identical root keys for matching PIN and rejects wrong PIN`() {
        val primaryKeystore = KeystoreManager("id_p", "wrap_p")
        val secondaryKeystore = KeystoreManager("id_s", "wrap_s")
        val pId = primaryKeystore.getDeviceId()
        val sId = secondaryKeystore.getDeviceId()
        val sessionId = "PB-TEST01"
        val pin = "482910"

        val initState = Spake2PairingEngine.startInitiator(sessionId, pId, pin)
        val (msgB, respKeys) = Spake2PairingEngine.completeResponder(
            sessionId, pId, sId, pin, initState.messageAB64
        )
        val initKeys = Spake2PairingEngine.finishInitiator(initState, sId, msgB)

        assertArrayEquals(initKeys.rootKey, respKeys.rootKey)
        assertTrue(Spake2PairingEngine.constantTimeEqualsB64(initKeys.macB, respKeys.macB))
        assertTrue(Spake2PairingEngine.constantTimeEqualsB64(initKeys.macA, respKeys.macA))

        // Wrong 6-digit PIN produces completely different key & fails confirmation
        val (wrongMsgB, wrongKeys) = Spake2PairingEngine.completeResponder(
            sessionId, pId, sId, "999999", initState.messageAB64
        )
        val initMismatch = Spake2PairingEngine.finishInitiator(initState, sId, wrongMsgB)
        assertFalse(Spake2PairingEngine.constantTimeEqualsB64(initMismatch.macB, wrongKeys.macB))
    }

    @Test
    fun `envelope AEAD encrypts decrypts and detects tampering or AAD spoofing`() {
        val rootKey = ByteArray(32) { (it + 1).toByte() }
        val eventId = UUID.randomUUID().toString()
        val now = 1_750_000_000_000L
        val payload = EventPayload(
            title = "Flight Reminder",
            body = "Gate B12 departs in 45m",
            sourcePackage = "com.android.calendar",
            capturedAtEpochMs = now
        )

        val envelope = EnvelopeCryptoEngine.encryptPayload(
            rootKey = rootKey,
            eventId = eventId,
            relationshipId = "rel-abc-123",
            sequenceNumber = 42L,
            keyEpoch = 1,
            createdAtEpochMs = now,
            expiresAtEpochMs = now + 3_600_000L,
            payloadType = PayloadType.CALENDAR_EVENT,
            payload = payload
        )

        val decrypted = EnvelopeCryptoEngine.decryptEnvelope(rootKey, envelope)
        assertEquals(payload.title, decrypted.title)
        assertEquals(payload.body, decrypted.body)

        // Relay cannot read plaintext in ciphertext
        assertFalse(envelope.ciphertext.contains("Flight Reminder"))

        // Tampering with sequenceNumber breaks AAD authentication
        val spoofedSeq = envelope.copy(sequenceNumber = 43L)
        var aadRejected = false
        try {
            EnvelopeCryptoEngine.decryptEnvelope(rootKey, spoofedSeq)
        } catch (_: AEADBadTagException) {
            aadRejected = true
        }
        assertTrue(aadRejected)

        // Deterministic nonce is strictly unique across sequences and epochs
        val n1 = EnvelopeCryptoEngine.buildDeterministicNonce(1, 1L)
        val n2 = EnvelopeCryptoEngine.buildDeterministicNonce(1, 2L)
        val n3 = EnvelopeCryptoEngine.buildDeterministicNonce(2, 1L)
        assertNotEquals(n1.toList(), n2.toList())
        assertNotEquals(n1.toList(), n3.toList())
    }

    @Test
    fun `delivery state machine prevents invalid state transitions`() {
        assertTrue(DeliveryStateMachine.canTransition(DeliveryState.CAPTURED, DeliveryState.PENDING))
        assertTrue(DeliveryStateMachine.canTransition(DeliveryState.SENDING, DeliveryState.SERVER_ACCEPTED))
        assertTrue(DeliveryStateMachine.canTransition(DeliveryState.SERVER_ACCEPTED, DeliveryState.DEVICE_RECEIVED))
        assertTrue(DeliveryStateMachine.canTransition(DeliveryState.DEVICE_RECEIVED, DeliveryState.READ))

        assertFalse(DeliveryStateMachine.canTransition(DeliveryState.CAPTURED, DeliveryState.READ))
        assertFalse(DeliveryStateMachine.canTransition(DeliveryState.READ, DeliveryState.PENDING))
        assertFalse(DeliveryStateMachine.canTransition(DeliveryState.PERMANENT_FAILURE, DeliveryState.SENDING))
        assertFalse(DeliveryStateMachine.canTransition(DeliveryState.EXPIRED, DeliveryState.SERVER_ACCEPTED))
    }

    @Test
    fun `error classifier and retry policy respect permanent errors and exponential backoff`() {
        val permanent = ErrorClassifier.classifyHttp(403, "REVOKED_PAIRING")
        assertTrue(permanent.isPermanent)
        assertEquals(BridgeErrorCode.REVOKED_PAIRING, permanent.code)

        val temp = ErrorClassifier.classifyHttp(429, null, retryAfterSeconds = 45L)
        assertTrue(temp.isTemporary)
        assertEquals(45_000L, temp.retryAfterMs)

        val policy = RetryPolicy()
        val now = 1_000_000L
        val exp = now + 3_600_000L
        assertFalse(policy.shouldRetry(permanent, 0, now, exp))
        assertTrue(policy.shouldRetry(temp, 0, now, exp))

        val nextAt = policy.nextAttemptAtMs(1, now, exp, temp.retryAfterMs)
        assertTrue(nextAt >= now + 45_000L)
    }
}
