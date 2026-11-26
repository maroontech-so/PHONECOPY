package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.PhoneBridgeApplication
import com.example.android.background.OemBatteryDiagnostics
import com.example.android.background.PermissionAndBatteryStatus
import com.example.core.model.DeviceRole
import com.example.core.model.HealthResponse
import com.example.core.model.PayloadType
import com.example.data.local.DiagnosticLogEntity
import com.example.data.local.InboxEventEntity
import com.example.data.local.LocalBridgeConfig
import com.example.data.local.OutboxEventEntity
import com.example.data.local.RelationshipEntity
import com.example.data.local.SyncCursorEntity
import com.example.data.network.RelayResult
import com.example.data.network.WebSocketConnectionState
import com.example.engine.ActivePairingSession
import com.example.engine.CaptureOutcome
import com.example.engine.NetworkStatus
import com.example.engine.PairingOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PhoneBridgeUiState(
    val config: LocalBridgeConfig = LocalBridgeConfig(),
    val deviceId: String = "",
    val identityFingerprint: String = "",
    val activeRelationship: RelationshipEntity? = null,
    val outboxEvents: List<OutboxEventEntity> = emptyList(),
    val inboxEvents: List<InboxEventEntity> = emptyList(),
    val quarantinedEvents: List<InboxEventEntity> = emptyList(),
    val pendingOutboxCount: Int = 0,
    val unackedByDeviceCount: Int = 0,
    val activeRetryCount: Int = 0,
    val inboxUnreadCount: Int = 0,
    val ackPendingCount: Int = 0,
    val syncCursor: SyncCursorEntity? = null,
    val wsState: WebSocketConnectionState = WebSocketConnectionState.DISCONNECTED,
    val networkStatus: NetworkStatus = NetworkStatus.OFFLINE,
    val relayHealth: HealthResponse? = null,
    val permissionStatus: PermissionAndBatteryStatus? = null,
    val diagnosticLogs: List<DiagnosticLogEntity> = emptyList(),
    val activePairingSession: ActivePairingSession? = null,
    val statusBanner: String? = null,
    val isBusy: Boolean = false
)

class PhoneBridgeViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as PhoneBridgeApplication).container
    private val db = container.database

    private val _relayHealth = MutableStateFlow<HealthResponse?>(null)
    private val _permissionStatus = MutableStateFlow(OemBatteryDiagnostics.inspect(application))
    private val _activeSession = MutableStateFlow<ActivePairingSession?>(null)
    private val _statusBanner = MutableStateFlow<String?>(null)
    private val _isBusy = MutableStateFlow(false)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val syncCursorFlow = db.relationshipDao().observeActiveRelationship().flatMapLatest { rel ->
        if (rel == null) flowOf(null) else db.syncCursorDao().observeCursor(rel.relationshipId)
    }

    val uiState: StateFlow<PhoneBridgeUiState> = combine(
        container.configStore.configFlow,
        db.relationshipDao().observeActiveRelationship(),
        db.outboxDao().observeAll(),
        db.inboxDao().observeInbox(),
        db.inboxDao().observeQuarantined(),
        db.outboxDao().observePendingCount(),
        db.outboxDao().observeUnacknowledgedByDeviceCount(),
        db.outboxDao().observeActiveRetryCount(),
        db.inboxDao().observeUnreadCount(),
        db.inboxDao().observeAckPendingCount(),
        syncCursorFlow,
        container.wsClient.state,
        container.connectivityMonitor.status,
        _relayHealth,
        _permissionStatus,
        db.diagnosticLogDao().observeRecent(80),
        _activeSession,
        _statusBanner,
        _isBusy
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        PhoneBridgeUiState(
            config = values[0] as LocalBridgeConfig,
            deviceId = container.keystoreManager.getDeviceId(),
            identityFingerprint = container.keystoreManager.getHumanFingerprint(),
            activeRelationship = values[1] as RelationshipEntity?,
            outboxEvents = values[2] as List<OutboxEventEntity>,
            inboxEvents = values[3] as List<InboxEventEntity>,
            quarantinedEvents = values[4] as List<InboxEventEntity>,
            pendingOutboxCount = values[5] as Int,
            unackedByDeviceCount = values[6] as Int,
            activeRetryCount = values[7] as Int,
            inboxUnreadCount = values[8] as Int,
            ackPendingCount = values[9] as Int,
            syncCursor = values[10] as SyncCursorEntity?,
            wsState = values[11] as WebSocketConnectionState,
            networkStatus = values[12] as NetworkStatus,
            relayHealth = values[13] as HealthResponse?,
            permissionStatus = values[14] as PermissionAndBatteryStatus?,
            diagnosticLogs = values[15] as List<DiagnosticLogEntity>,
            activePairingSession = values[16] as ActivePairingSession?,
            statusBanner = values[17] as String?,
            isBusy = values[18] as Boolean
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PhoneBridgeUiState(
            deviceId = container.keystoreManager.getDeviceId(),
            identityFingerprint = container.keystoreManager.getHumanFingerprint(),
            permissionStatus = _permissionStatus.value
        )
    )

    fun clearBanner() {
        _statusBanner.value = null
    }

    fun setRole(role: DeviceRole) {
        viewModelScope.launch {
            container.configStore.setRole(role)
            _statusBanner.value = "Switched role to ${if (role == DeviceRole.PRIMARY) "Primary (Tecno)" else "Secondary (Samsung)"}"
        }
    }

    fun updateDeviceName(name: String) {
        viewModelScope.launch {
            container.configStore.setDeviceName(name)
        }
    }

    fun updateRelayUrl(url: String) {
        viewModelScope.launch {
            runCatching {
                container.configStore.setRelayBaseUrl(url)
                _statusBanner.value = "Relay URL saved (TLS enforced)"
            }.onFailure {
                _statusBanner.value = it.message ?: "Relay URL must start with https://"
            }
        }
    }

    fun setRealtimeWebSocket(enabled: Boolean) {
        viewModelScope.launch {
            container.configStore.setRealtimeWebSocketEnabled(enabled)
            if (enabled) {
                container.connectWebSocketIfConfigured()
            } else {
                container.wsClient.disconnectGracefully()
            }
        }
    }

    fun setAutoAddCalendar(enabled: Boolean) {
        viewModelScope.launch {
            container.configStore.setAutoAddCalendarEvents(enabled)
        }
    }

    fun setRetentionHours(hours: Long) {
        viewModelScope.launch {
            container.configStore.setRetentionHours(hours)
        }
    }

    fun refreshDiagnostics() {
        _permissionStatus.value = OemBatteryDiagnostics.inspect(getApplication())
        viewModelScope.launch {
            val url = container.configStore.getCurrent().relayBaseUrl
            when (val res = container.httpClient.getHealth(url)) {
                is RelayResult.Success -> _relayHealth.value = res.data
                is RelayResult.Failure -> _statusBanner.value = "Relay health check: ${res.error.summary}"
            }
        }
    }

    fun captureAndQueueEvent(
        payloadType: PayloadType,
        title: String,
        body: String,
        sourcePackage: String = "com.example.manual"
    ) {
        viewModelScope.launch {
            _isBusy.value = true
            when (val outcome = container.outboxEngine.captureAndEnqueue(
                payloadType = payloadType,
                title = title,
                body = body,
                sourcePackage = sourcePackage,
                priority = if (payloadType == PayloadType.CALENDAR_EVENT) 1 else 0
            )) {
                is CaptureOutcome.Enqueued -> {
                    _statusBanner.value = "Encrypted & queued #${outcome.sequenceNumber} in outbox"
                    container.outboxEngine.flushPendingBatch()
                }
                is CaptureOutcome.Deduplicated -> {
                    _statusBanner.value = "Deduplicated duplicate event"
                }
                is CaptureOutcome.Failed -> {
                    _statusBanner.value = outcome.reason
                }
            }
            _isBusy.value = false
        }
    }

    fun flushOutboxNow() {
        viewModelScope.launch {
            _isBusy.value = true
            val sent = container.outboxEngine.flushPendingBatch()
            _statusBanner.value = if (sent > 0) "Delivered $sent event(s) to relay" else "Outbox flush completed"
            _isBusy.value = false
        }
    }

    fun syncInboxNow() {
        viewModelScope.launch {
            _isBusy.value = true
            val count = container.inboxEngine.syncFromRelay()
            container.inboxEngine.flushPendingAcks()
            _statusBanner.value = if (count > 0) "Synced $count new encrypted event(s)" else "Inbox is up to date"
            _isBusy.value = false
        }
    }

    fun markEventRead(eventId: String) {
        viewModelScope.launch {
            container.inboxEngine.markRead(eventId)
        }
    }

    fun addEventToCalendar(eventId: String) {
        viewModelScope.launch {
            val added = container.inboxEngine.addToCalendar(eventId)
            _statusBanner.value = if (added) {
                "Added event to Android Calendar"
            } else {
                "Grant Calendar permission in Diagnostics to add events"
            }
        }
    }

    fun initiatePrimaryPairing() {
        viewModelScope.launch {
            _isBusy.value = true
            when (val outcome = container.pairingEngine.initiatePrimaryPairing()) {
                is PairingOutcome.Initiated -> {
                    _activeSession.value = outcome.session
                    _statusBanner.value = "Pairing session active. Enter code on Secondary."
                }
                is PairingOutcome.Failed -> _statusBanner.value = outcome.error
                else -> Unit
            }
            _isBusy.value = false
        }
    }

    fun pollPrimaryPairingCompletion() {
        val session = _activeSession.value ?: return
        viewModelScope.launch {
            _isBusy.value = true
            when (val outcome = container.pairingEngine.pollPrimaryCompletion(session)) {
                is PairingOutcome.Paired -> {
                    _activeSession.value = null
                    _statusBanner.value = "Paired with ${outcome.relationship.peerDeviceName}"
                    container.connectWebSocketIfConfigured()
                }
                is PairingOutcome.WaitingForPeer -> _statusBanner.value = outcome.message
                is PairingOutcome.Failed -> _statusBanner.value = outcome.error
                else -> Unit
            }
            _isBusy.value = false
        }
    }

    fun completeSecondaryPairing(
        pairingSessionId: String,
        sixDigitCode: String,
        primaryDeviceId: String,
        primarySpake2MsgAB64: String
    ) {
        viewModelScope.launch {
            _isBusy.value = true
            when (val outcome = container.pairingEngine.completeSecondaryPairing(
                pairingSessionId = pairingSessionId,
                sixDigitCode = sixDigitCode,
                primaryDeviceId = primaryDeviceId,
                primarySpake2MsgAB64 = primarySpake2MsgAB64
            )) {
                is PairingOutcome.Paired -> {
                    _statusBanner.value = "Paired securely with ${outcome.relationship.peerDeviceName}"
                    container.connectWebSocketIfConfigured()
                }
                is PairingOutcome.Failed -> _statusBanner.value = outcome.error
                else -> Unit
            }
            _isBusy.value = false
        }
    }

    fun rotateKeys() {
        viewModelScope.launch {
            val epoch = container.pairingEngine.rotateEncryptionKey()
            _statusBanner.value = if (epoch != null) "Keys rotated to epoch $epoch" else "No active pairing"
        }
    }

    fun revokePairing() {
        viewModelScope.launch {
            container.wsClient.disconnectGracefully()
            val revoked = container.pairingEngine.revokeActivePairing()
            _activeSession.value = null
            _statusBanner.value = if (revoked) "Pairing revoked and credentials invalidated" else "No active pairing"
        }
    }
}
