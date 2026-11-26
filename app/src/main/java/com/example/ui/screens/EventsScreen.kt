package com.example.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Outbox
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.model.DeliveryState
import com.example.core.model.DeviceRole
import com.example.data.local.InboxEventEntity
import com.example.data.local.OutboxEventEntity
import com.example.ui.PhoneBridgeUiState
import com.example.ui.components.AppleSegmentedControl
import com.example.ui.components.AppleSettingsRow
import com.example.ui.components.GlassCard
import com.example.ui.components.InsetDivider
import com.example.ui.components.StatusPill
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalAppleColors

private enum class EventFilter { ALL, ACTIVE, DELIVERED }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventsScreen(
    state: PhoneBridgeUiState,
    onMarkRead: (String) -> Unit,
    onAddToCalendar: (String) -> Unit,
    onFlushOutbox: () -> Unit,
    onSyncInbox: () -> Unit,
    modifier: Modifier = Modifier
) {
    val apple = LocalAppleColors.current
    var filter by remember { mutableStateOf(EventFilter.ALL) }
    var selectedOutbox by remember { mutableStateOf<OutboxEventEntity?>(null) }
    var selectedInbox by remember { mutableStateOf<InboxEventEntity?>(null) }

    val isPrimary = state.config.role == DeviceRole.PRIMARY

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        // 1. Minimal Bold Header + Action Pill
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isPrimary) "Outbox" else "Inbox",
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )

                OutlinedButton(
                    onClick = if (isPrimary) onFlushOutbox else onSyncInbox,
                    modifier = Modifier.height(48.dp).testTag("events_action_button"),
                    shape = CircleShape
                ) {
                    Text(if (isPrimary) "Flush" else "Sync", style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        // 2. Glass Filter Pill
        item {
            AppleSegmentedControl(
                options = listOf(
                    EventFilter.ALL to "All",
                    EventFilter.ACTIVE to if (isPrimary) "Queued" else "Unread",
                    EventFilter.DELIVERED to if (isPrimary) "Sent" else "Read"
                ),
                selected = filter,
                onSelect = { filter = it },
                tagPrefix = "events_filter"
            )
        }

        // 3. Clean Glass Event Cards
        if (isPrimary) {
            val filtered = state.outboxEvents.filter { item ->
                when (filter) {
                    EventFilter.ALL -> true
                    EventFilter.ACTIVE -> item.state in setOf("CAPTURED", "PENDING", "SENDING", "RETRY_WAIT")
                    EventFilter.DELIVERED -> item.state in setOf("SERVER_ACCEPTED", "DEVICE_RECEIVED", "READ")
                }
            }

            if (filtered.isEmpty()) {
                item {
                    EmptyEventsCard(
                        icon = Icons.Rounded.Outbox,
                        title = "Clear"
                    )
                }
            } else {
                items(filtered, key = { it.eventId }) { item ->
                    OutboxEventCard(
                        item = item,
                        onClick = { selectedOutbox = item }
                    )
                }
            }
        } else {
            val filtered = state.inboxEvents.filter { item ->
                when (filter) {
                    EventFilter.ALL -> true
                    EventFilter.ACTIVE -> item.state == DeliveryState.DEVICE_RECEIVED.name
                    EventFilter.DELIVERED -> item.state == DeliveryState.READ.name
                }
            }

            if (filtered.isEmpty()) {
                item {
                    EmptyEventsCard(
                        icon = Icons.Rounded.Inbox,
                        title = "No Events"
                    )
                }
            } else {
                items(filtered, key = { it.eventId }) { item ->
                    InboxEventCard(
                        item = item,
                        onClick = { selectedInbox = item }
                    )
                }
            }
        }
    }

    selectedOutbox?.let { outbox ->
        ModalBottomSheet(onDismissRequest = { selectedOutbox = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = outbox.localPreviewTitle,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                GlassCard(elevated = true) {
                    AppleSettingsRow("State", trailingText = outbox.state, monospaceTrailing = true)
                    InsetDivider()
                    AppleSettingsRow("Sequence", trailingText = "#${outbox.sequenceNumber}", monospaceTrailing = true)
                    InsetDivider()
                    AppleSettingsRow("Epoch", trailingText = "v${outbox.keyEpoch}", monospaceTrailing = true)
                    InsetDivider()
                    AppleSettingsRow("Retries", trailingText = "${outbox.retryCount}", monospaceTrailing = true)
                    if (!outbox.errorSummary.isNullOrBlank()) {
                        InsetDivider()
                        AppleSettingsRow("Error", subtitle = outbox.errorSummary, trailingText = outbox.errorCode)
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    selectedInbox?.let { inbox ->
        ModalBottomSheet(onDismissRequest = { selectedInbox = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "#${inbox.sequenceNumber}",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                        color = apple.subtleText
                    )
                    StatusPill(
                        label = if (inbox.ackPending) "ACK Pending" else "Verified",
                        color = if (inbox.ackPending) apple.warning else apple.success
                    )
                }
                Text(
                    text = inbox.title,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = inbox.body,
                    style = MaterialTheme.typography.bodyLarge
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (inbox.state == DeliveryState.DEVICE_RECEIVED.name) {
                        Button(
                            onClick = {
                                onMarkRead(inbox.eventId)
                                selectedInbox = null
                            },
                            modifier = Modifier.weight(1f).height(50.dp).testTag("mark_read_button"),
                            shape = CircleShape
                        ) {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("Mark Read")
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            onAddToCalendar(inbox.eventId)
                            selectedInbox = null
                        },
                        modifier = Modifier.weight(1f).height(50.dp).testTag("add_calendar_button"),
                        shape = CircleShape
                    ) {
                        Icon(Icons.Rounded.CalendarMonth, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(if (inbox.calendarEventUri != null) "Added" else "Calendar")
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun OutboxEventCard(item: OutboxEventEntity, onClick: () -> Unit) {
    val apple = LocalAppleColors.current
    val stateColor = stateColor(item.state)

    GlassCard(
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .clickable(onClick = onClick)
            .testTag("outbox_item_${item.sequenceNumber}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f).padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = item.localPreviewTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "#${item.sequenceNumber} • ${formatShortTime(item.createdAt)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = apple.subtleText,
                    maxLines = 1
                )
            }
            StatusPill(label = shortStateLabel(item.state), color = stateColor)
        }
    }
}

@Composable
private fun InboxEventCard(item: InboxEventEntity, onClick: () -> Unit) {
    val apple = LocalAppleColors.current
    val unread = item.state == DeliveryState.DEVICE_RECEIVED.name

    GlassCard(
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier
            .clickable(onClick = onClick)
            .testTag("inbox_item_${item.sequenceNumber}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f).padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = apple.subtleText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            StatusPill(
                label = if (unread) "New" else "Read",
                color = if (unread) MaterialTheme.colorScheme.primary else apple.success
            )
        }
    }
}

@Composable
private fun EmptyEventsCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String
) {
    val apple = LocalAppleColors.current
    GlassCard(shape = RoundedCornerShape(28.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 44.dp, horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = apple.subtleText,
                modifier = Modifier.size(34.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

private fun shortStateLabel(state: String): String = when (state) {
    "DEVICE_RECEIVED", "READ" -> "Delivered"
    "SERVER_ACCEPTED" -> "Relayed"
    "RETRY_WAIT" -> "Retrying"
    "EXPIRED" -> "Expired"
    "PERMANENT_FAILURE" -> "Failed"
    else -> "Queued"
}

@Composable
private fun stateColor(state: String): Color {
    val apple = LocalAppleColors.current
    return when (state) {
        "DEVICE_RECEIVED", "READ" -> apple.success
        "SERVER_ACCEPTED" -> MaterialTheme.colorScheme.primary
        "CAPTURED", "PENDING", "SENDING", "RETRY_WAIT" -> apple.warning
        "EXPIRED", "PERMANENT_FAILURE" -> apple.danger
        else -> apple.subtleText
    }
}
