package com.localcast.poc.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.localcast.poc.MainActivity

/**
 * M4: Builds the ongoing notification for [StreamingForegroundService] with an
 * Open Session tap intent and an explicit Stop Sharing action button (Spec Section 8).
 */
object StreamingNotification {

    const val CHANNEL_ID = "localcast_active_session_channel"
    const val NOTIFICATION_ID = 4101
    const val ACTION_STOP_STREAMING = "com.localcast.poc.action.STOP_STREAMING"

    fun ensureChannelCreated(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "LocalCast Active Session",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Visible status indicator while LocalCast shares or receives local Wi-Fi media"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun buildOngoingNotification(
        context: Context,
        title: String,
        contentText: String
    ): Notification {
        ensureChannelCreated(context)

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            context,
            100,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopServiceIntent = Intent(context, StreamingForegroundService::class.java).apply {
            action = ACTION_STOP_STREAMING
        }
        val stopPendingIntent = PendingIntent.getService(
            context,
            101,
            stopServiceIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle(title)
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(openPendingIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Stop Sharing",
                stopPendingIntent
            )
            .build()
    }
}
