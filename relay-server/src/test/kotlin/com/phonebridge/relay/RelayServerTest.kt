package com.phonebridge.relay

import com.phonebridge.relay.db.DatabaseFactory
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID

class RelayServerTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun createInMemoryDb(): DatabaseFactory {
        val hc = HikariConfig().apply {
            jdbcUrl = "jdbc:h2:mem:relay_test_${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
            username = "sa"
            password = ""
            maximumPoolSize = 4
        }
        return DatabaseFactory(HikariDataSource(hc)).apply { migrate() }
    }

    private fun sign(privateKey: java.security.PrivateKey, method: String, path: String, ts: Long, nonce: String, body: ByteArray): String {
        val bodyHash = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
        val canonical = "$method|$path|$ts|$nonce|$bodyHash".toByteArray()
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(privateKey)
        sig.update(canonical)
        return Base64.getEncoder().encodeToString(sig.sign())
    }

    @Test
    fun `full relay pairing submission idempotency sync ack expiration and revocation flow`() = testApplication {
        val db = createInMemoryDb()
        val config = RelayConfig("test", 8443, "jdbc:h2:mem:x", "sa", "", 100, 2, 90)
        application { phoneBridgeRelayModule(config, db) }

        val kpg = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val primaryKp = kpg.generateKeyPair()
        val secondaryKp = kpg.generateKeyPair()
        val primaryPub = Base64.getEncoder().encodeToString(primaryKp.public.encoded)
        val secondaryPub = Base64.getEncoder().encodeToString(secondaryKp.public.encoded)
        val primaryId = "dev_primary_1"
        val secondaryId = "dev_secondary_1"

        // 1. Health endpoint
        val healthResp = client.get("/v1/health")
        assertEquals(HttpStatusCode.OK, healthResp.status)
        assertTrue(healthResp.bodyAsText().contains("\"database_ok\":true"))

        // 2. Initiate Pairing
        val initReq = PairingInitiateRequest(
            deviceId = primaryId,
            deviceName = "Tecno Primary",
            identityPublicKeyB64 = primaryPub,
            spake2MessageAB64 = "SPAKE_A_B64",
            pairingSessionId = "PB-TEST99"
        )
        val initBody = json.encodeToString(PairingInitiateRequest.serializer(), initReq)
        val ts1 = System.currentTimeMillis()
        val n1 = UUID.randomUUID().toString()
        val initHttp = client.post("/v1/pairing/initiate") {
            header("X-Device-Id", primaryId)
            header("X-Timestamp", ts1.toString())
            header("X-Nonce", n1)
            header("X-Signature", sign(primaryKp.private, "POST", "/v1/pairing/initiate", ts1, n1, initBody.toByteArray()))
            setBody(initBody)
        }
        assertEquals(HttpStatusCode.OK, initHttp.status)
        val initRes = json.decodeFromString(PairingInitiateResponse.serializer(), initHttp.bodyAsText())

        // 3. Replay same nonce must fail with 401
        val replayHttp = client.post("/v1/pairing/initiate") {
            header("X-Device-Id", primaryId)
            header("X-Timestamp", ts1.toString())
            header("X-Nonce", n1)
            header("X-Signature", sign(primaryKp.private, "POST", "/v1/pairing/initiate", ts1, n1, initBody.toByteArray()))
            setBody(initBody)
        }
        assertEquals(HttpStatusCode.Unauthorized, replayHttp.status)

        // 4. Complete Pairing as Secondary
        val compReq = PairingCompleteRequest(
            pairingSessionId = "PB-TEST99",
            secondaryDeviceId = secondaryId,
            secondaryDeviceName = "Samsung Secondary",
            secondaryIdentityPublicKeyB64 = secondaryPub,
            spake2MessageBB64 = "SPAKE_B_B64",
            keyConfirmationMacBB64 = "MAC_B",
            identitySignatureBB64 = "SIG_B"
        )
        val compBody = json.encodeToString(PairingCompleteRequest.serializer(), compReq)
        val ts2 = System.currentTimeMillis()
        val n2 = UUID.randomUUID().toString()
        val compHttp = client.post("/v1/pairing/complete") {
            header("X-Device-Id", secondaryId)
            header("X-Timestamp", ts2.toString())
            header("X-Nonce", n2)
            header("X-Signature", sign(secondaryKp.private, "POST", "/v1/pairing/complete", ts2, n2, compBody.toByteArray()))
            setBody(compBody)
        }
        assertEquals(HttpStatusCode.OK, compHttp.status)
        val compRes = json.decodeFromString(PairingCompleteResponse.serializer(), compHttp.bodyAsText())

        // 5. Primary submits encrypted event + duplicate submission check
        val eventId = UUID.randomUUID().toString()
        val env = EventEnvelope(
            eventId = eventId,
            relationshipId = compRes.relationshipId,
            sequenceNumber = 1L,
            createdAtEpochMs = ts2,
            expiresAtEpochMs = ts2 + 3_600_000L,
            payloadType = PayloadType.NOTIFICATION,
            ciphertext = "OPAQUE_CIPHERTEXT_B64",
            nonce = "NONCE_B64",
            authTag = "TAG_B64",
            ciphertextHash = "a".repeat(64)
        )
        val envBody = json.encodeToString(EventEnvelope.serializer(), env)

        val ts3 = System.currentTimeMillis()
        val n3 = UUID.randomUUID().toString()
        val sub1 = client.post("/v1/events") {
            header("Authorization", "Bearer ${initRes.deviceToken}")
            header("X-Device-Id", primaryId)
            header("X-Timestamp", ts3.toString())
            header("X-Nonce", n3)
            header("X-Signature", sign(primaryKp.private, "POST", "/v1/events", ts3, n3, envBody.toByteArray()))
            setBody(envBody)
        }
        assertEquals(HttpStatusCode.OK, sub1.status)
        assertFalse(json.decodeFromString(SubmitEventResponse.serializer(), sub1.bodyAsText()).duplicate)

        // Duplicate submission returns original acceptance with duplicate=true
        val ts4 = System.currentTimeMillis()
        val n4 = UUID.randomUUID().toString()
        val sub2 = client.post("/v1/events") {
            header("Authorization", "Bearer ${initRes.deviceToken}")
            header("X-Device-Id", primaryId)
            header("X-Timestamp", ts4.toString())
            header("X-Nonce", n4)
            header("X-Signature", sign(primaryKp.private, "POST", "/v1/events", ts4, n4, envBody.toByteArray()))
            setBody(envBody)
        }
        assertTrue(json.decodeFromString(SubmitEventResponse.serializer(), sub2.bodyAsText()).duplicate)

        // 6. Secondary cannot submit events (one-way enforcement!)
        val ts5 = System.currentTimeMillis()
        val n5 = UUID.randomUUID().toString()
        val secSubmit = client.post("/v1/events") {
            header("Authorization", "Bearer ${compRes.secondaryToken}")
            header("X-Device-Id", secondaryId)
            header("X-Timestamp", ts5.toString())
            header("X-Nonce", n5)
            header("X-Signature", sign(secondaryKp.private, "POST", "/v1/events", ts5, n5, envBody.toByteArray()))
            setBody(envBody)
        }
        assertEquals(HttpStatusCode.Forbidden, secSubmit.status)

        // 7. Secondary syncs and acknowledges
        val syncReq = json.encodeToString(SyncRequest.serializer(), SyncRequest(relationshipId = compRes.relationshipId, afterSequence = 0L))
        val ts6 = System.currentTimeMillis()
        val n6 = UUID.randomUUID().toString()
        val syncHttp = client.post("/v1/sync") {
            header("Authorization", "Bearer ${compRes.secondaryToken}")
            header("X-Device-Id", secondaryId)
            header("X-Timestamp", ts6.toString())
            header("X-Nonce", n6)
            header("X-Signature", sign(secondaryKp.private, "POST", "/v1/sync", ts6, n6, syncReq.toByteArray()))
            setBody(syncReq)
        }
        assertEquals(HttpStatusCode.OK, syncHttp.status)
        val syncRes = json.decodeFromString(SyncResponse.serializer(), syncHttp.bodyAsText())
        assertEquals(1, syncRes.events.size)
        assertEquals(eventId, syncRes.events.first().eventId)

        // 8. Revoke relationship -> subsequent requests rejected with 403 REVOKED_PAIRING
        val revokeReq = json.encodeToString(RevokeDeviceRequest.serializer(), RevokeDeviceRequest(compRes.relationshipId, secondaryId, "Lost device"))
        val ts7 = System.currentTimeMillis()
        val n7 = UUID.randomUUID().toString()
        val revHttp = client.post("/v1/devices/revoke") {
            header("Authorization", "Bearer ${initRes.deviceToken}")
            header("X-Device-Id", primaryId)
            header("X-Timestamp", ts7.toString())
            header("X-Nonce", n7)
            header("X-Signature", sign(primaryKp.private, "POST", "/v1/devices/revoke", ts7, n7, revokeReq.toByteArray()))
            setBody(revokeReq)
        }
        assertEquals(HttpStatusCode.OK, revHttp.status)

        val ts8 = System.currentTimeMillis()
        val n8 = UUID.randomUUID().toString()
        val afterRevoke = client.post("/v1/sync") {
            header("Authorization", "Bearer ${compRes.secondaryToken}")
            header("X-Device-Id", secondaryId)
            header("X-Timestamp", ts8.toString())
            header("X-Nonce", n8)
            header("X-Signature", sign(secondaryKp.private, "POST", "/v1/sync", ts8, n8, syncReq.toByteArray()))
            setBody(syncReq)
        }
        assertEquals(HttpStatusCode.Forbidden, afterRevoke.status)
    }
}
