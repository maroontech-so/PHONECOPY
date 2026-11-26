package com.example.android.background

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.CalendarContract
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.local.InboxEventEntity
import java.util.TimeZone

object SecondaryNotificationPresenter {
    const val CHANNEL_EVENTS = "phonebridge_events"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_EVENTS,
                context.getString(R.string.channel_bridge_events_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.channel_bridge_events_desc)
            }
            nm.createNotificationChannel(channel)
        }
    }

    fun showIfPermitted(context: Context, event: InboxEventEntity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        ensureChannels(context)

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            event.eventId.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(event.title)
            .setContentText(event.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(event.body))
            .setSubText("Encrypted Bridge • #${event.sequenceNumber}")
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(event.eventId.hashCode(), notification)
        }
    }
}

object CalendarIntegrationHelper {

    fun hasCalendarPermission(context: Context): Boolean {
        val read = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        val write = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
        return read && write
    }

    /**
     * Inserts an event into the user's primary local Android calendar idempotently.
     * Never duplicates if [InboxEventEntity.calendarEventUri] is already recorded.
     */
    fun insertCalendarEventIfPermitted(context: Context, event: InboxEventEntity): String? {
        if (event.calendarEventUri != null) return event.calendarEventUri
        if (!hasCalendarPermission(context)) return null

        val calendarId = findWritableCalendarId(context) ?: return null
        val startMs = event.calendarStartAt ?: (event.createdAt + 3_600_000L)
        val endMs = event.calendarEndAt ?: (startMs + 1_800_000L)

        val values = ContentValues().apply {
            put(CalendarContract.Events.DTSTART, startMs)
            put(CalendarContract.Events.DTEND, endMs)
            put(CalendarContract.Events.TITLE, event.title)
            put(CalendarContract.Events.DESCRIPTION, event.body)
            put(CalendarContract.Events.EVENT_LOCATION, event.calendarLocation ?: "")
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }

        return runCatching {
            context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)?.toString()
        }.getOrNull()
    }

    private fun findWritableCalendarId(context: Context): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY
        )
        val selection = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?"
        val args = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())

        return runCatching {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                selection,
                args,
                "${CalendarContract.Calendars.IS_PRIMARY} DESC"
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else null
            }
        }.getOrNull()
    }
}
