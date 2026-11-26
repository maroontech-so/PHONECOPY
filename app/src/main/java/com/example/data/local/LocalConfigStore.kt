package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.core.model.DeviceRole
import com.example.core.model.ProtocolConstants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.phoneBridgeDataStore: DataStore<Preferences> by preferencesDataStore(name = "phonebridge_config")

data class LocalBridgeConfig(
    val role: DeviceRole = DeviceRole.PRIMARY,
    val deviceName: String = "Tecno Primary",
    val relayBaseUrl: String = "https://overstay-carpentry-gulp.ngrok-free.dev",
    val retentionHours: Long = ProtocolConstants.DEFAULT_RETENTION_HOURS,
    val realtimeWebSocketEnabled: Boolean = true,
    val autoAddCalendarEvents: Boolean = true,
    val lastSuccessfulConnectionAt: Long? = null,
    val lastSuccessfulSyncAt: Long? = null,
    val lastSuccessfulDeliveryAt: Long? = null
)

class LocalConfigStore(private val context: Context) {

    private object Keys {
        val ROLE = stringPreferencesKey("device_role")
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val RELAY_BASE_URL = stringPreferencesKey("relay_base_url")
        val RETENTION_HOURS = longPreferencesKey("retention_hours")
        val REALTIME_WS_ENABLED = booleanPreferencesKey("realtime_ws_enabled")
        val AUTO_ADD_CALENDAR = booleanPreferencesKey("auto_add_calendar")
        val LAST_CONN_AT = longPreferencesKey("last_conn_at")
        val LAST_SYNC_AT = longPreferencesKey("last_sync_at")
        val LAST_DELIVERY_AT = longPreferencesKey("last_delivery_at")
    }

    val configFlow: Flow<LocalBridgeConfig> = context.phoneBridgeDataStore.data.map { prefs ->
        val roleStr = prefs[Keys.ROLE] ?: DeviceRole.PRIMARY.name
        val role = runCatching { DeviceRole.valueOf(roleStr) }.getOrDefault(DeviceRole.PRIMARY)
        LocalBridgeConfig(
            role = role,
            deviceName = prefs[Keys.DEVICE_NAME] ?: if (role == DeviceRole.PRIMARY) "Tecno Primary" else "Samsung Secondary",
            relayBaseUrl = prefs[Keys.RELAY_BASE_URL] ?: "https://overstay-carpentry-gulp.ngrok-free.dev",
            retentionHours = prefs[Keys.RETENTION_HOURS] ?: ProtocolConstants.DEFAULT_RETENTION_HOURS,
            realtimeWebSocketEnabled = prefs[Keys.REALTIME_WS_ENABLED] ?: true,
            autoAddCalendarEvents = prefs[Keys.AUTO_ADD_CALENDAR] ?: true,
            lastSuccessfulConnectionAt = prefs[Keys.LAST_CONN_AT],
            lastSuccessfulSyncAt = prefs[Keys.LAST_SYNC_AT],
            lastSuccessfulDeliveryAt = prefs[Keys.LAST_DELIVERY_AT]
        )
    }

    suspend fun getCurrent(): LocalBridgeConfig = configFlow.first()

    suspend fun setRole(role: DeviceRole) {
        context.phoneBridgeDataStore.edit { prefs ->
            prefs[Keys.ROLE] = role.name
            if (prefs[Keys.DEVICE_NAME] == null ||
                prefs[Keys.DEVICE_NAME] == "Tecno Primary" ||
                prefs[Keys.DEVICE_NAME] == "Samsung Secondary"
            ) {
                prefs[Keys.DEVICE_NAME] = if (role == DeviceRole.PRIMARY) "Tecno Primary" else "Samsung Secondary"
            }
        }
    }

    suspend fun setDeviceName(name: String) {
        context.phoneBridgeDataStore.edit { it[Keys.DEVICE_NAME] = name.trim().take(48) }
    }

    suspend fun setRelayBaseUrl(url: String) {
        val normalized = url.trim().trimEnd('/')
        require(normalized.startsWith("https://")) {
            "Relay URL must use HTTPS with valid TLS"
        }
        context.phoneBridgeDataStore.edit { it[Keys.RELAY_BASE_URL] = normalized }
    }

    suspend fun setRetentionHours(hours: Long) {
        context.phoneBridgeDataStore.edit { it[Keys.RETENTION_HOURS] = hours.coerceIn(1L, 720L) }
    }

    suspend fun setRealtimeWebSocketEnabled(enabled: Boolean) {
        context.phoneBridgeDataStore.edit { it[Keys.REALTIME_WS_ENABLED] = enabled }
    }

    suspend fun setAutoAddCalendarEvents(enabled: Boolean) {
        context.phoneBridgeDataStore.edit { it[Keys.AUTO_ADD_CALENDAR] = enabled }
    }

    suspend fun recordConnectionSuccess(atMs: Long) {
        context.phoneBridgeDataStore.edit { it[Keys.LAST_CONN_AT] = atMs }
    }

    suspend fun recordSyncSuccess(atMs: Long) {
        context.phoneBridgeDataStore.edit { it[Keys.LAST_SYNC_AT] = atMs }
    }

    suspend fun recordDeliverySuccess(atMs: Long) {
        context.phoneBridgeDataStore.edit { it[Keys.LAST_DELIVERY_AT] = atMs }
    }
}
