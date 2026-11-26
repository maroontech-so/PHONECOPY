package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.core.model.DeviceRole
import com.example.core.model.PayloadType
import com.example.data.network.WebSocketConnectionState
import com.example.ui.PhoneBridgeUiState
import com.example.ui.components.AppleSegmentedControl
import com.example.ui.components.AppleSettingsRow
import com.example.ui.components.GlassCard
import com.example.ui.components.GlassMetricTile
import com.example.ui.components.InsetDivider
import com.example.ui.components.StatusPill
import com.example.ui.theme.LocalAppleColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverviewScreen(
    state: PhoneBridgeUiState,
    onSelectRole: (DeviceRole) -> Unit,
    onCaptureEvent: (PayloadType, String, String) -> Unit,
    onFlushOutbox: () -> Unit,
    onSyncInbox: () -> Unit,
    onToggleRealtimeWs: (Boolean) -> Unit,
    onToggleAutoCalendar: (Boolean) -> Unit,
    onNavigateToPairing: () -> Unit,
    modifier: Modifier = Modifier
) {
    val apple = LocalAppleColors.current
    var showCaptureSheet by remember { mutableStateOf(false) }
    val isPrimary = state.config.role == DeviceRole.PRIMARY
    val paired = state.activeRelationship != null

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // 1. Minimal Bold Header
        item {
            Text(
                text = "Bridge",
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        // 2. Role Segmented Pill
        item {
            AppleSegmentedControl(
                options = listOf(
                    DeviceRole.PRIMARY to "Primary",
                    DeviceRole.SECONDARY to "Secondary"
                ),
                selected = state.config.role,
                onSelect = onSelectRole,
                tagPrefix = "role_switch"
            )
        }

        // 3. Hero Glass Card
        item {
            GlassCard(
                elevated = true,
                shape = RoundedCornerShape(30.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        StatusPill(
                            label = if (paired) "E2EE Active" else "Unpaired",
                            color = if (paired) apple.success else apple.warning
                        )

                        val wsLabel = when (state.wsState) {
                            WebSocketConnectionState.CONNECTED -> "Live"
                            WebSocketConnectionState.CONNECTING -> "Connecting"
                            WebSocketConnectionState.RECONNECTING -> "Reconnecting"
                            WebSocketConnectionState.FAILED_PERMANENT -> "Auth Error"
                            WebSocketConnectionState.DISCONNECTED -> "Standby"
                        }
                        val wsColor = when (state.wsState) {
                            WebSocketConnectionState.CONNECTED -> apple.success
                            WebSocketConnectionState.CONNECTING,
                            WebSocketConnectionState.RECONNECTING -> apple.warning
                            WebSocketConnectionState.FAILED_PERMANENT -> apple.danger
                            WebSocketConnectionState.DISCONNECTED -> apple.subtleText
                        }
                        StatusPill(label = wsLabel, color = wsColor)
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = state.activeRelationship?.let {
                                if (isPrimary) "To ${it.peerDeviceName}" else "From ${it.peerDeviceName}"
                            } ?: if (isPrimary) "Tecno Primary" else "Samsung Secondary",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = state.activeRelationship?.let {
                                "AES-256-GCM • Epoch ${it.keyEpoch}"
                            } ?: "End-to-end encrypted one-way bridge",
                            style = MaterialTheme.typography.bodyMedium,
                            color = apple.subtleText
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (!paired) {
                            Button(
                                onClick = onNavigateToPairing,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(52.dp)
                                    .testTag("overview_pair_button"),
                                shape = CircleShape
                            ) {
                                Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Pair Devices", style = MaterialTheme.typography.labelLarge)
                            }
                        } else if (isPrimary) {
                            Button(
                                onClick = { showCaptureSheet = true },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(52.dp)
                                    .testTag("capture_event_button"),
                                shape = CircleShape
                            ) {
                                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Send Event", style = MaterialTheme.typography.labelLarge)
                            }
                            OutlinedButton(
                                onClick = onFlushOutbox,
                                modifier = Modifier
                                    .height(52.dp)
                                    .testTag("flush_outbox_button"),
                                shape = CircleShape
                            ) {
                                Icon(Icons.Rounded.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                        } else {
                            Button(
                                onClick = onSyncInbox,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(52.dp)
                                    .testTag("sync_inbox_button"),
                                shape = CircleShape
                            ) {
                                Icon(Icons.Rounded.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Sync Now", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
        }

        // 4. Two Bold Glass Stat Tiles
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (isPrimary) {
                    GlassMetricTile(
                        value = "${state.pendingOutboxCount}",
                        label = "Queued",
                        accentColor = apple.warning,
                        modifier = Modifier.weight(1f)
                    )
                    GlassMetricTile(
                        value = "${state.unackedByDeviceCount}",
                        label = "In Flight",
                        accentColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    GlassMetricTile(
                        value = "${state.inboxUnreadCount}",
                        label = "Unread",
                        accentColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    GlassMetricTile(
                        value = "#${state.syncCursor?.lastContiguousSeq ?: 0L}",
                        label = "Cursor",
                        accentColor = apple.success,
                        monospaceValue = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // 5. Compact Glass Transport Switch
        item {
            GlassCard {
                AppleSettingsRow(
                    title = "Realtime Link",
                    icon = Icons.Rounded.Sync,
                    iconTint = MaterialTheme.colorScheme.primary,
                    trailingContent = {
                        Switch(
                            checked = state.config.realtimeWebSocketEnabled,
                            onCheckedChange = onToggleRealtimeWs,
                            modifier = Modifier.testTag("toggle_realtime_ws")
                        )
                    }
                )
                if (!isPrimary) {
                    InsetDivider()
                    AppleSettingsRow(
                        title = "Auto Calendar",
                        icon = Icons.Rounded.CalendarMonth,
                        iconTint = apple.indigo,
                        trailingContent = {
                            Switch(
                                checked = state.config.autoAddCalendarEvents,
                                onCheckedChange = onToggleAutoCalendar,
                                modifier = Modifier.testTag("toggle_auto_calendar")
                            )
                        }
                    )
                }
            }
        }
    }

    if (showCaptureSheet) {
        CaptureEventBottomSheet(
            onDismiss = { showCaptureSheet = false },
            onSubmit = { type, title, body ->
                showCaptureSheet = false
                onCaptureEvent(type, title, body)
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CaptureEventBottomSheet(
    onDismiss: () -> Unit,
    onSubmit: (PayloadType, String, String) -> Unit
) {
    var payloadType by remember { mutableStateOf(PayloadType.NOTIFICATION) }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "New Encrypted Event",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            AppleSegmentedControl(
                options = listOf(
                    PayloadType.NOTIFICATION to "Alert",
                    PayloadType.CALENDAR_EVENT to "Calendar"
                ),
                selected = payloadType,
                onSelect = { payloadType = it },
                tagPrefix = "capture_type"
            )

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("capture_title_input")
            )

            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                label = { Text("Details") },
                minLines = 3,
                maxLines = 4,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("capture_body_input")
            )

            Button(
                onClick = {
                    if (title.isNotBlank() || body.isNotBlank()) {
                        onSubmit(
                            payloadType,
                            title.ifBlank { "Bridged Event" },
                            body.ifBlank { title }
                        )
                    }
                },
                enabled = title.isNotBlank() || body.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("capture_submit_button"),
                shape = CircleShape
            ) {
                Text("Encrypt & Send", style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

fun formatShortTime(epochMs: Long?): String {
    if (epochMs == null) return "Never"
    val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    return fmt.format(Date(epochMs))
}
