package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.model.DeviceRole
import com.example.ui.PhoneBridgeUiState
import com.example.ui.components.AppleSettingsRow
import com.example.ui.components.GlassCard
import com.example.ui.components.InsetDivider
import com.example.ui.components.StatusPill
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LocalAppleColors

@Composable
fun PairingScreen(
    state: PhoneBridgeUiState,
    onUpdateRelayUrl: (String) -> Unit,
    onUpdateDeviceName: (String) -> Unit,
    onInitiatePrimaryPairing: () -> Unit,
    onPollPrimaryPairing: () -> Unit,
    onCompleteSecondaryPairing: (String, String, String, String) -> Unit,
    onRotateKeys: () -> Unit,
    onRevokePairing: () -> Unit,
    modifier: Modifier = Modifier
) {
    val apple = LocalAppleColors.current
    val clipboard = LocalClipboardManager.current
    var relayUrlInput by remember(state.config.relayBaseUrl) { mutableStateOf(state.config.relayBaseUrl) }
    var deviceNameInput by remember(state.config.deviceName) { mutableStateOf(state.config.deviceName) }

    var sessionIdInput by remember { mutableStateOf("") }
    var pinInput by remember { mutableStateOf("") }
    var primaryIdInput by remember { mutableStateOf("") }
    var primarySpakeAInput by remember { mutableStateOf("") }
    var showManualKeys by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("pairing_list"),
        contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // 1. Minimal Bold Header
        item {
            Text(
                text = "Pairing",
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        // 2. Hero Pairing Card (primary focal point)
        if (state.activeRelationship != null) {
            val rel = state.activeRelationship
            item {
                GlassCard(elevated = true, shape = RoundedCornerShape(30.dp)) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            StatusPill(label = "Linked", color = apple.success)
                            Text(
                                text = "Epoch ${rel.keyEpoch}",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                                color = apple.subtleText
                            )
                        }
                        Text(
                            text = rel.peerDeviceName,
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = onRotateKeys,
                                modifier = Modifier.weight(1f).height(50.dp).testTag("rotate_keys_button"),
                                shape = CircleShape
                            ) {
                                Text("Rotate Keys")
                            }
                            Button(
                                onClick = onRevokePairing,
                                modifier = Modifier.weight(1f).height(50.dp).testTag("revoke_pairing_button"),
                                shape = CircleShape,
                                colors = ButtonDefaults.buttonColors(containerColor = apple.danger)
                            ) {
                                Text("Unlink")
                            }
                        }
                    }
                }
            }
        } else if (state.config.role == DeviceRole.PRIMARY) {
            item {
                GlassCard(elevated = true, shape = RoundedCornerShape(30.dp)) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        val session = state.activePairingSession
                        if (session != null) {
                            Text(
                                text = session.pairingSessionId,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily, fontSize = 13.sp),
                                color = apple.subtleText
                            )
                            Text(
                                text = session.sixDigitCode.chunked(3).joinToString("  "),
                                style = MaterialTheme.typography.displayLarge.copy(
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Button(
                                onClick = onPollPrimaryPairing,
                                modifier = Modifier.fillMaxWidth().height(52.dp).testTag("poll_pairing_button"),
                                shape = CircleShape
                            ) {
                                Text("Verify Secondary", style = MaterialTheme.typography.labelLarge)
                            }
                            OutlinedButton(
                                onClick = {
                                    val bundle = "${session.pairingSessionId}|${session.sixDigitCode}|${state.deviceId}|${session.initiatorState.messageAB64}"
                                    clipboard.setText(AnnotatedString(bundle))
                                },
                                modifier = Modifier.fillMaxWidth().height(48.dp).testTag("copy_invite_button"),
                                shape = CircleShape
                            ) {
                                Text("Copy Invite", style = MaterialTheme.typography.labelLarge)
                            }
                        } else {
                            Text(
                                text = "Link Secondary",
                                style = MaterialTheme.typography.headlineMedium
                            )
                            Text(
                                text = "SPAKE2-P256 zero-knowledge pairing",
                                style = MaterialTheme.typography.bodyMedium,
                                color = apple.subtleText
                            )
                            Button(
                                onClick = onInitiatePrimaryPairing,
                                modifier = Modifier.fillMaxWidth().height(52.dp).testTag("initiate_pairing_button"),
                                shape = CircleShape
                            ) {
                                Text("Generate Code", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
        } else {
            item {
                GlassCard(elevated = true, shape = RoundedCornerShape(30.dp)) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Join Primary",
                            style = MaterialTheme.typography.headlineMedium
                        )
                        OutlinedTextField(
                            value = sessionIdInput,
                            onValueChange = { raw ->
                                val parts = raw.trim().split("|")
                                if (parts.size == 4) {
                                    sessionIdInput = parts[0].trim()
                                    pinInput = parts[1].filter(Char::isDigit).take(6)
                                    primaryIdInput = parts[2].trim()
                                    primarySpakeAInput = parts[3].trim()
                                } else {
                                    sessionIdInput = raw
                                }
                            },
                            label = { Text("Session ID or Invite") },
                            singleLine = true,
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.fillMaxWidth().testTag("sec_session_input")
                        )
                        OutlinedTextField(
                            value = pinInput,
                            onValueChange = { if (it.length <= 6) pinInput = it.filter(Char::isDigit) },
                            label = { Text("6-Digit Code") },
                            singleLine = true,
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.fillMaxWidth().testTag("sec_pin_input")
                        )
                        if (showManualKeys) {
                            OutlinedTextField(
                                value = primaryIdInput,
                                onValueChange = { primaryIdInput = it },
                                label = { Text("Primary ID") },
                                singleLine = true,
                                shape = RoundedCornerShape(18.dp),
                                modifier = Modifier.fillMaxWidth().testTag("sec_primary_id_input")
                            )
                            OutlinedTextField(
                                value = primarySpakeAInput,
                                onValueChange = { primarySpakeAInput = it },
                                label = { Text("SPAKE2 Key") },
                                singleLine = true,
                                shape = RoundedCornerShape(18.dp),
                                modifier = Modifier.fillMaxWidth().testTag("sec_spake_a_input")
                            )
                        }
                        Button(
                            onClick = {
                                onCompleteSecondaryPairing(sessionIdInput, pinInput, primaryIdInput, primarySpakeAInput)
                            },
                            enabled = sessionIdInput.isNotBlank() && pinInput.length == 6,
                            modifier = Modifier.fillMaxWidth().height(52.dp).testTag("complete_secondary_pairing_button"),
                            shape = CircleShape
                        ) {
                            Text("Pair Now", style = MaterialTheme.typography.labelLarge)
                        }
                        TextButton(
                            onClick = { showManualKeys = !showManualKeys },
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) {
                            Text(if (showManualKeys) "Hide Manual Keys" else "Manual Keys")
                        }
                    }
                }
            }
        }

        // 3. Compact Glass Relay & Identity Card
        item {
            GlassCard(shape = RoundedCornerShape(28.dp)) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = deviceNameInput,
                        onValueChange = { deviceNameInput = it },
                        label = { Text("Device Name") },
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth().testTag("device_name_input")
                    )
                    OutlinedTextField(
                        value = relayUrlInput,
                        onValueChange = { relayUrlInput = it },
                        label = { Text("Relay HTTPS URL") },
                        placeholder = { Text("https://overstay-carpentry-gulp.ngrok-free.dev") },
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth().testTag("relay_url_input")
                    )
                    OutlinedButton(
                        onClick = {
                            onUpdateDeviceName(deviceNameInput)
                            onUpdateRelayUrl(relayUrlInput)
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp).testTag("save_relay_url_button"),
                        shape = CircleShape
                    ) {
                        Text("Save Endpoint", style = MaterialTheme.typography.labelLarge)
                    }
                }
                InsetDivider()
                AppleSettingsRow(
                    title = "Identity Key",
                    trailingText = state.identityFingerprint.take(16) + "…",
                    monospaceTrailing = true,
                    icon = Icons.Rounded.Fingerprint,
                    iconTint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
