package com.example.android.background

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

enum class DeviceVendorProfile {
    SAMSUNG_ONE_UI,
    TECNO_HIOS,
    GENERIC_ANDROID
}

data class PermissionAndBatteryStatus(
    val vendorProfile: DeviceVendorProfile,
    val manufacturerDisplay: String,
    val notificationListenerGranted: Boolean,
    val postNotificationsGranted: Boolean,
    val calendarGranted: Boolean,
    val ignoringBatteryOptimizations: Boolean,
    val guidanceSteps: List<String>
)

object OemBatteryDiagnostics {

    fun detectVendor(): DeviceVendorProfile {
        val mfg = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        return when {
            mfg.contains("samsung") || brand.contains("samsung") -> DeviceVendorProfile.SAMSUNG_ONE_UI
            mfg.contains("tecno") || brand.contains("tecno") ||
                mfg.contains("infinix") || mfg.contains("itel") -> DeviceVendorProfile.TECNO_HIOS
            else -> DeviceVendorProfile.GENERIC_ANDROID
        }
    }

    fun inspect(context: Context): PermissionAndBatteryStatus {
        val vendor = detectVendor()
        val listenerGranted = NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)

        val postNotifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val calendarGranted = CalendarIntegrationHelper.hasCalendarPermission(context)

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val ignoringBattery = pm.isIgnoringBatteryOptimizations(context.packageName)

        val guidance = when (vendor) {
            DeviceVendorProfile.TECNO_HIOS -> listOf(
                "HiOS Battery Lab: Set PhoneBridge to 'Unrestricted' under Settings > Battery > App Power Management.",
                "HiOS Auto-Start: Enable PhoneBridge in Phone Master > App Management > Auto-Start Management so reboot recovery runs.",
                "Lock in Recents: Swipe down on PhoneBridge in the recent apps switcher to prevent HiOS aggressive background kill."
            )
            DeviceVendorProfile.SAMSUNG_ONE_UI -> listOf(
                "One UI Battery: Set PhoneBridge to 'Unrestricted' in Settings > Apps > PhoneBridge > Battery.",
                "Sleeping Apps: Ensure PhoneBridge is NOT listed in Device Care > Battery > Background usage limits > Sleeping or Deep sleeping apps.",
                "Adaptive Battery: Keep WorkManager sync enabled; One UI restricts unused apps after 3 days unless excluded."
            )
            DeviceVendorProfile.GENERIC_ANDROID -> listOf(
                "Battery Optimization: Set PhoneBridge battery usage to 'Unrestricted' to avoid Doze delays on WebSocket reconnects.",
                "Notification Access: Enable PhoneBridge Capture on your Primary device under Special App Access.",
                "Background Recovery: WorkManager automatically flushes queued events and syncs missed items on network transitions."
            )
        }

        return PermissionAndBatteryStatus(
            vendorProfile = vendor,
            manufacturerDisplay = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            notificationListenerGranted = listenerGranted,
            postNotificationsGranted = postNotifGranted,
            calendarGranted = calendarGranted,
            ignoringBatteryOptimizations = ignoringBattery,
            guidanceSteps = guidance
        )
    }

    fun buildNotificationListenerSettingsIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun buildBatterySettingsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
