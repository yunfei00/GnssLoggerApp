package com.example.gnsslogger.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

object NotificationHelper {

    const val CHANNEL_ID = "gnss_logger_foreground"
    private const val CHANNEL_NAME = "GNSS 采集"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
        }
        mgr.createNotificationChannel(channel)
    }

    fun buildForegroundNotification(
        context: Context,
        fileName: String?,
        recordCount: Long,
    ): Notification {
        ensureChannel(context)
        val text = buildString {
            append("文件: ").append(fileName ?: "-")
            append("\n已写入行数: ").append(recordCount)
        }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("GNSS 采集中")
.setContentText("GNSS 后台采集中")
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
