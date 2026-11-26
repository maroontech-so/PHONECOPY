package com.example

import android.app.Application
import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.android.background.InboxSyncWorker
import com.example.android.background.RetentionCleanupWorker
import com.example.android.background.SecondaryNotificationPresenter
import com.example.core.crypto.KeystoreManager
import com.example.core.model.DeviceRole
import com.example.data.local.LocalConfigStore
import com.example.data.local.PhoneBridgeDatabase
import com.example.data.network.RelayHttpClient
import com.example.data.network.RelayWebSocketClient
import com.example.engine.ConnectivityMonitor
import com.example.engine.PairingEngine
import com.example.engine.PrimaryOutboxEngine
import com.example.engine.SanitizedLogger
import com.example.engine.SecondaryInboxEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database = PhoneBridgeDatabase.getInstance(appContext)
    val configStore = LocalConfigStore(appContext)
    val keystoreManager = KeystoreManager()
    val logger = SanitizedLogger(database.diagnosticLogDao(), appScope)

    val httpClient = RelayHttpClient(keystoreManager)
    val wsClient = RelayWebSocketClient(httpClient, appScope)

    val outboxEngine = PrimaryOutboxEngine(
        context = appContext,
        outboxDao = database.outboxDao(),
        relationshipDao = database.relationshipDao(),
        keystoreManager = keystoreManager,
        configStore = configStore,
        transport = httpClient,
        logger = logger
    )

    val inboxEngine = SecondaryInboxEngine(
        context = appContext,
        inboxDao = database.inboxDao(),
        syncCursorDao = database.syncCursorDao(),
        relationshipDao = database.relationshipDao(),
        keystoreManager = keystoreManager,
        configStore = configStore,
        transport = httpClient,
        wsClient = wsClient,
        logger = logger
    )

    val pairingEngine = PairingEngine(
        keystoreManager = keystoreManager,
        relationshipDao = database.relationshipDao(),
        configStore = configStore,
        transport = httpClient,
        logger = logger
    )

    val connectivityMonitor = ConnectivityMonitor(appContext) {
        onNetworkRestored()
    }

    init {
        connectivityMonitor.start()
        observeWebSocketFrames()
    }

    private fun observeWebSocketFrames() {
        appScope.launch {
            wsClient.incomingFrames.collect { frame ->
                val config = configStore.getCurrent()
                when {
                    frame.type == "EVENT" && frame.envelope != null && config.role == DeviceRole.SECONDARY -> {
                        inboxEngine.processIncomingEnvelope(frame.envelope)
                    }
                    frame.type == "RECEIPT" && config.role == DeviceRole.PRIMARY -> {
                        outboxEngine.handleReceiptFrame(frame)
                    }
                }
            }
        }
    }

    fun onNetworkRestored() {
        appScope.launch {
            val rel = database.relationshipDao().getActiveRelationship() ?: return@launch
            val config = configStore.getCurrent()
            if (config.realtimeWebSocketEnabled) {
                val token = keystoreManager.unwrapSecret(rel.wrappedAuthTokenB64, rel.wrappedAuthTokenIvB64)
                    .toString(Charsets.UTF_8)
                wsClient.connect(config.relayBaseUrl, token, rel.relationshipId)
            }
            if (config.role == DeviceRole.PRIMARY) {
                outboxEngine.flushPendingBatch()
            } else {
                inboxEngine.syncFromRelay()
            }
        }
    }

    fun connectWebSocketIfConfigured() {
        appScope.launch {
            val rel = database.relationshipDao().getActiveRelationship() ?: return@launch
            val config = configStore.configFlow.first()
            if (config.realtimeWebSocketEnabled) {
                val token = keystoreManager.unwrapSecret(rel.wrappedAuthTokenB64, rel.wrappedAuthTokenIvB64)
                    .toString(Charsets.UTF_8)
                wsClient.connect(config.relayBaseUrl, token, rel.relationshipId)
            }
        }
    }

    fun scheduleRecoveryWorkers() {
        runCatching {
            val wm = WorkManager.getInstance(appContext)
            val connectedConstraint = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val syncWork = PeriodicWorkRequestBuilder<InboxSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(connectedConstraint)
                .build()

            wm.enqueueUniquePeriodicWork(
                "phonebridge_periodic_sync",
                ExistingPeriodicWorkPolicy.KEEP,
                syncWork
            )

            val retentionWork = PeriodicWorkRequestBuilder<RetentionCleanupWorker>(12, TimeUnit.HOURS)
                .build()

            wm.enqueueUniquePeriodicWork(
                "phonebridge_retention_cleanup",
                ExistingPeriodicWorkPolicy.KEEP,
                retentionWork
            )
        }
    }
}

class PhoneBridgeApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        SecondaryNotificationPresenter.ensureChannels(this)
        container.scheduleRecoveryWorkers()
    }
}
