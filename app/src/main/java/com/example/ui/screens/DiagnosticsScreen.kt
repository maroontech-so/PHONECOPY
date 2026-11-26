package com.example.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.android.background.OemBatteryDiagnostics
import com.example.core.model.ProtocolConstants
import com.example.ui.PhoneBridgeUiState
import com.example.ui.components.AppleSettingsRow
import com.example.ui.components.GlassCard
import com.example.ui.components.GlassMetricTile
import com.example.ui.components.InsetDivider
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalAppleColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    state: PhoneBridgeUiState,
    onRefreshDiagnostics: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val apple = LocalAppleColors.current
    val perms = state.permissionStatus
    var showGuideSheet by remember { mutableStateOf(false) }
    var showLogsSheet by remember { mutableStateOf(false) }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { onRefreshDiagnostics() }

    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { onRefreshDiagnostics() }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // 1. Minimal Bold Header + Check Pill
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "System",
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                OutlinedButton(
                    onClick = onRefreshDiagnostics,
                    modifier = Modifier.height(48.dp).testTag("refresh_diagnostics_button"),
                    shape = CircleShape
                ) {
                    Text("Check", style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        // 2. 2-Tile Bold Health Overview
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                GlassMetricTile(
                    value = state.relayHealth?.status ?: "Idle",
                    label = "Relay",
                    accentColor = if (state.relayHealth?.status == "OK") apple.success else apple.warning,
                    modifier = Modifier.weight(1f)
                )
                GlassMetricTile(
                    value = "v${ProtocolConstants.PROTOCOL_VERSION}",
                    label = if (state.activeRelationship != null) "AES-GCM" else "Unpaired",
                    accentColor = if (state.activeRelationship != null) apple.success else apple.subtleText,
                    monospaceValue = true,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // 3. Clean Permissions Glass Card
        if (perms != null) {
            item {
                GlassCard(shape = RoundedCornerShape(28.dp)) {
                    AppleSettingsRow(
                        title = "Notification Listener",
                        trailingText = if (perms.notificationListenerGranted) "On" else "Enable",
                        icon = Icons.Rounded.Notifications,
                        iconTint = if (perms.notificationListenerGranted) apple.success else apple.warning,
                        onClick = {
                            context.startActivity(OemBatteryDiagnostics.buildNotificationListenerSettingsIntent())
                        },
                        modifier = Modifier.testTag("perm_notif_listener_row")
                    )
                    InsetDivider()
                    AppleSettingsRow(
                        title = "Post Alerts",
                        trailingText = if (perms.postNotificationsGranted) "On" else "Allow",
                        icon = Icons.Rounded.CheckCircle,
                        iconTint = if (perms.postNotificationsGranted) apple.success else apple.warning,
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                        modifier = Modifier.testTag("perm_post_notif_row")
                    )
                    InsetDivider()
                    AppleSettingsRow(
                        title = "Calendar Sync",
                        trailingText = if (perms.calendarGranted) "On" else "Allow",
                        icon = Icons.Rounded.HealthAndSafety,
                        iconTint = if (perms.calendarGranted) apple.success else apple.indigo,
                        onClick = {
                            calendarPermissionLauncher.launch(
                                arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
                            )
                        },
                        modifier = Modifier.testTag("perm_calendar_row")
                    )
                    InsetDivider()
                    AppleSettingsRow(
                        title = "Battery Mode",
                        trailingText = if (perms.ignoringBatteryOptimizations) "Unrestricted" else "Review",
                        icon = if (perms.ignoringBatteryOptimizations) Icons.Rounded.BatteryChargingFull else Icons.Rounded.BatteryAlert,
                        iconTint = if (perms.ignoringBatteryOptimizations) apple.success else apple.warning,
                        onClick = {
                            context.startActivity(OemBatteryDiagnostics.buildBatterySettingsIntent(context))
                        },
                        modifier = Modifier.testTag("perm_battery_row")
                    )
                }
            }
        }

        // 4. Two Clean Pill Buttons for Details (progressive disclosure)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (perms != null) {
                    OutlinedButton(
                        onClick = { showGuideSheet = true },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = CircleShape
                    ) {
                        Text("OEM Guide")
                    }
                }
                OutlinedButton(
                    onClick = { showLogsSheet = true },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = CircleShape
                ) {
                    Text("Logs (${state.diagnosticLogs.size})")
                }
            }
        }
    }

    if (showGuideSheet && perms != null) {
        ModalBottomSheet(onDismissRequest = { showGuideSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "${perms.manufacturerDisplay} Reliability",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                GlassCard(elevated = true) {
                    perms.guidanceSteps.forEachIndexed { idx, step ->
                        if (idx > 0) InsetDivider()
                        AppleSettingsRow(
                            title = "Step ${idx + 1}",
                            subtitle = step,
                            icon = Icons.Rounded.Warning,
                            iconTint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showLogsSheet) {
        ModalBottomSheet(onDismissRequest = { showLogsSheet = false }) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        text = "Telemetry & Logs",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                item {
                    GlassCard(elevated = true) {
                        AppleSettingsRow("Network", trailingText = state.networkStatus.name, monospaceTrailing = true)
                        InsetDivider()
                        AppleSettingsRow("WebSocket", trailingText = state.wsState.name, monospaceTrailing = true)
                        InsetDivider()
                        AppleSettingsRow("Last Sync", trailingText = formatShortTime(state.config.lastSuccessfulSyncAt), monospaceTrailing = true)
                        InsetDivider()
                        AppleSettingsRow("Last Delivery", trailingText = formatShortTime(state.config.lastSuccessfulDeliveryAt), monospaceTrailing = true)
                    }
                }
                if (state.diagnosticLogs.isEmpty()) {
                    item {
                        GlassCard {
                            AppleSettingsRow("No logs recorded yet")
                        }
                    }
                } else {
                    items(state.diagnosticLogs.take(20), key = { it.id }) { log ->
                        GlassCard(shape = RoundedCornerShape(20.dp)) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "${formatShortTime(log.timestamp)} • ${log.level} • ${log.category}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                                    color = apple.subtleText
                                )
                                Text(
                                    text = log.message,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}
