package com.example.android.background

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.example.PhoneBridgeApplication
import com.example.core.model.DeviceRole
import com.example.core.model.PayloadType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Captures selected notifications on the Primary (Tecno) device and persists them
 * immediately into the transactional Room outbox regardless of network connectivity.
 */
class PhoneBridgeNotificationListenerService : NotificationListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        if (sbn.packageName == packageName) return
        if ((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) return
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()

        if (title.isBlank() && text.isBlank()) return

        val app = applicationContext as? PhoneBridgeApplication ?: return
        val container = app.container

        serviceScope.launch {
            val config = container.configStore.getCurrent()
            if (config.role != DeviceRole.PRIMARY) return@launch

            val dedupKey = "${sbn.packageName}|${sbn.id}|${title.hashCode()}|${text.hashCode()}"
            val category = notification.category ?: "notification"
            val payloadType = if (category == Notification.CATEGORY_EVENT || category == Notification.CATEGORY_REMINDER) {
                PayloadType.CALENDAR_EVENT
            } else {
                PayloadType.NOTIFICATION
            }
            val priority = if (payloadType == PayloadType.CALENDAR_EVENT) 1 else 0

            container.outboxEngine.captureAndEnqueue(
                payloadType = payloadType,
                title = title.ifBlank { sbn.packageName },
                body = text.ifBlank { title },
                sourcePackage = sbn.packageName,
                sourceCategory = category,
                dedupKey = dedupKey,
                priority = priority
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
