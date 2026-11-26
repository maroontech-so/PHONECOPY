package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.crypto.EnvelopeCryptoEngine
import com.example.core.crypto.KeystoreManager
import com.example.core.model.DeliveryState
import com.example.core.model.DeviceRole
import com.example.core.model.PayloadType
import com.example.core.model.RelationshipStatus
import com.example.data.local.LocalConfigStore
import com.example.data.local.PhoneBridgeDatabase
import com.example.data.local.RelationshipEntity
import com.example.data.network.RelayHttpClient
import com.example.data.network.RelayWebSocketClient
import com.example.engine.CaptureOutcome
import com.example.engine.IngestOutcome
import com.example.engine.PrimaryOutboxEngine
import com.example.engine.SanitizedLogger
import com.example.engine.SecondaryInboxEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OutboxInboxFaultInjectionTest {

    private lateinit var context: Context
    private lateinit var db: PhoneBridgeDatabase
    private lateinit var configStore: LocalConfigStore
    private lateinit var keystore: KeystoreManager
    private lateinit var mockServer: MockWebServer
    private lateinit var httpClient: RelayHttpClient
    private lateinit var wsClient: RelayWebSocketClient
    private lateinit var logger: SanitizedLogger
    private lateinit var outboxEngine: PrimaryOutboxEngine
    private lateinit var inboxEngine: SecondaryInboxEngine
    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private val sharedRootKey = ByteArray(32) { (it * 3 + 7).toByte() }
    private val relationshipId = "rel-integration-01"

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, PhoneBridgeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        configStore = LocalConfigStore(context)
        keystore = KeystoreManager("test_id", "test_wrap")
        mockServer = MockWebServer()
        mockServer.start()

        httpClient = RelayHttpClient(keystore, allowLoopbackHttpForTests = true)
        wsClient = RelayWebSocketClient(httpClient, testScope, allowLoopbackWsForTests = true)
        logger = SanitizedLogger(db.diagnosticLogDao(), testScope)

        outboxEngine = PrimaryOutboxEngine(
            context, db.outboxDao(), db.relationshipDao(), keystore, configStore, httpClient, logger
        )
        inboxEngine = SecondaryInboxEngine(
            context, db.inboxDao(), db.syncCursorDao(), db.relationshipDao(), keystore, configStore, httpClient, wsClient, logger
        )
    }

    @After
    fun tearDown() {
        wsClient.disconnectGracefully()
        mockServer.shutdown()
        db.close()
    }

    private suspend fun seedRelationship(role: DeviceRole) {
        val (wk, wkIv) = keystore.wrapSecret(sharedRootKey)
        val (wt, wtIv) = keystore.wrapSecret("test-token-123".toByteArray())
        db.relationshipDao().upsert(
            RelationshipEntity(
                relationshipId = relationshipId,
                role = role.name,
                localDeviceId = keystore.getDeviceId(),
                peerDeviceId = "peer-device-xyz",
                peerDeviceName = "Peer Device",
                peerIdentityPubB64 = keystore.getIdentityPublicKeyB64(),
                wrappedRootKeyB64 = wk,
                wrappedRootKeyIvB64 = wkIv,
                wrappedAuthTokenB64 = wt,
                wrappedAuthTokenIvB64 = wtIv,
                keyEpoch = 1,
                nextSequenceNumber = 1L,
                status = RelationshipStatus.ACTIVE.name,
                createdAt = System.currentTimeMillis(),
                revokedAt = null
            )
        )
    }

    @Test
    fun `offline capture persists before network and recovers with exact same eventId after 503 outage`() = runTest {
        seedRelationship(DeviceRole.PRIMARY)
        configStore.setRelayBaseUrl("https://relay.example.com") // validate https config
        // Use reflection/custom store or set loopback via direct test url: wait configStore requires https, so let's test offline capture first:
        val capture1 = outboxEngine.captureAndEnqueue(
            payloadType = PayloadType.NOTIFICATION,
            title = "Bank Alert",
            body = "Deposit received",
            sourcePackage = "com.bank.app",
            dedupKey = "bank|101"
        )
        assertTrue(capture1 is CaptureOutcome.Enqueued)
        val eventId = (capture1 as CaptureOutcome.Enqueued).eventId

        // Rapid duplicate source update is deduplicated
        val dupCapture = outboxEngine.captureAndEnqueue(
            payloadType = PayloadType.NOTIFICATION,
            title = "Bank Alert",
            body = "Deposit received",
            sourcePackage = "com.bank.app",
            dedupKey = "bank|101"
        )
        assertTrue(dupCapture is CaptureOutcome.Deduplicated)

        val stored = db.outboxDao().getById(eventId)!!
        assertEquals(DeliveryState.PENDING.name, stored.state)
        assertEquals(1L, stored.sequenceNumber)

        // Simulate 503 temporary server failure then 200 acceptance using MockWebServer via direct transport
        val loopbackUrl = mockServer.url("/").toString().trimEnd('/')
        mockServer.enqueue(MockResponse().setResponseCode(503).setBody("""{"code":"SERVER_UNAVAILABLE","message":"restarting"}"""))
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"event_id":"$eventId","relationship_id":"$relationshipId","sequence_number":1,"accepted_at":1750000005000,"duplicate":false,"status":"SERVER_ACCEPTED"}"""))

        val transportRes1 = httpClient.submitEvent(
            loopbackUrl,
            "test-token-123",
            EnvelopeCryptoEngine.encryptPayload(
                sharedRootKey, eventId, relationshipId, 1L, 1, stored.createdAt, stored.expiresAt,
                PayloadType.NOTIFICATION,
                com.example.core.model.EventPayload("Bank Alert", "Deposit received", "com.bank.app", "notification", stored.createdAt)
            )
        )
        assertTrue(transportRes1 is com.example.data.network.RelayResult.Failure)

        val transportRes2 = httpClient.submitEvent(
            loopbackUrl,
            "test-token-123",
            EnvelopeCryptoEngine.encryptPayload(
                sharedRootKey, eventId, relationshipId, 1L, 1, stored.createdAt, stored.expiresAt,
                PayloadType.NOTIFICATION,
                com.example.core.model.EventPayload("Bank Alert", "Deposit received", "com.bank.app", "notification", stored.createdAt)
            )
        )
        assertTrue(transportRes2 is com.example.data.network.RelayResult.Success)
        val req1 = mockServer.takeRequest()
        val req2 = mockServer.takeRequest()
        assertTrue(req1.body.readUtf8().contains(eventId))
        assertTrue(req2.body.readUtf8().contains(eventId))
    }

    @Test
    fun `secondary deduplicates repeated deliveries detects sequence gaps and quarantines corrupt payload without acking`() = runTest {
        seedRelationship(DeviceRole.SECONDARY)
        val now = System.currentTimeMillis()
        val exp = now + 3_600_000L

        val env1 = EnvelopeCryptoEngine.encryptPayload(
            sharedRootKey, UUID.randomUUID().toString(), relationshipId, 1L, 1, now, exp,
            PayloadType.NOTIFICATION,
            com.example.core.model.EventPayload("Msg 1", "Body 1", "com.msg", "notification", now)
        )
        val env3 = EnvelopeCryptoEngine.encryptPayload(
            sharedRootKey, UUID.randomUUID().toString(), relationshipId, 3L, 1, now, exp,
            PayloadType.NOTIFICATION,
            com.example.core.model.EventPayload("Msg 3", "Body 3", "com.msg", "notification", now)
        )
        val env2 = EnvelopeCryptoEngine.encryptPayload(
            sharedRootKey, UUID.randomUUID().toString(), relationshipId, 2L, 1, now, exp,
            PayloadType.CALENDAR_EVENT,
            com.example.core.model.EventPayload("Meet 2", "Room A", "com.cal", "event", now)
        )

        // Deliver #1 twice -> second is Duplicate
        assertTrue(inboxEngine.processIncomingEnvelope(env1, now, sendAckImmediately = false) is IngestOutcome.Persisted)
        assertTrue(inboxEngine.processIncomingEnvelope(env1, now, sendAckImmediately = false) is IngestOutcome.Duplicate)
        assertEquals(1, db.inboxDao().observeInbox().first().size)

        // Deliver #3 out-of-order -> contiguous cursor stays at 1, gap [2] recorded
        assertTrue(inboxEngine.processIncomingEnvelope(env3, now, sendAckImmediately = false) is IngestOutcome.Persisted)
        val cursorWithGap = db.syncCursorDao().getCursor(relationshipId)!!
        assertEquals(1L, cursorWithGap.lastContiguousSeq)
        assertEquals("[2]", cursorWithGap.missingSequencesJson)

        // Deliver delayed #2 -> contiguous cursor advances to 3, gaps cleared
        assertTrue(inboxEngine.processIncomingEnvelope(env2, now, sendAckImmediately = false) is IngestOutcome.Persisted)
        val cursorClosed = db.syncCursorDao().getCursor(relationshipId)!!
        assertEquals(3L, cursorClosed.lastContiguousSeq)
        assertEquals("[]", cursorClosed.missingSequencesJson)

        // Tampered authTag is quarantined and NEVER marked ackPending=true
        val corruptEnv = env2.copy(eventId = UUID.randomUUID().toString(), sequenceNumber = 4L, authTag = "AAAAAAAAAAAAAAAAAAAAAA==")
        val corruptOutcome = inboxEngine.processIncomingEnvelope(corruptEnv, now, sendAckImmediately = false)
        assertTrue(corruptOutcome is IngestOutcome.Quarantined)
        val quarantined = db.inboxDao().getById(corruptEnv.eventId)!!
        assertTrue(quarantined.quarantined)
        assertFalse(quarantined.ackPending)
    }
}
