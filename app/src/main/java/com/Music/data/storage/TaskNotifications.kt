package com.Music.data.storage

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.Music.MainActivity
import com.Music.R
import com.Music.player.LibraryTaskService

/**
 * Ongoing notification for library operations that can take a while (bulk
 * playlist edits, bulk deletes, restore…). Backed by [LibraryTaskService] so the
 * work survives the app being closed, and non-dismissable: the only exit is the
 * Cancel action.
 */
class TaskNotifications(private val context: Context) {

    private val manager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init { runCatching { ensureChannel() } }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Library tasks",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = "Progress for playlist edits, deletes and restores"
                        setShowBadge(false)
                    }
                )
            }
        }
    }

    fun buildForegroundNotification(title: String): Notification =
        baseBuilder()
            .setContentTitle(title)
            .setContentText("Preparing…")
            .setProgress(0, 0, true)
            .addAction(CANCEL_ICON, "Cancel", cancelIntent())
            .build()

    fun update(title: String, phase: String, done: Int, total: Int, cancelling: Boolean) {
        val safeTotal = total.coerceAtLeast(1)
        val progress = done.coerceIn(0, safeTotal)
        val percent = progress * 100 / safeTotal
        runCatching {
            manager.notify(
                NOTIFICATION_ID,
                baseBuilder()
                    .setContentTitle(if (cancelling) "Cancelling…" else title)
                    .setContentText(if (cancelling) "Stopping after the current song" else phase)
                    .setSubText("$percent% • $progress/$safeTotal")
                    .setProgress(100, percent, false)
                    .addAction(CANCEL_ICON, "Cancel", cancelIntent())
                    .build()
            )
        }
    }

    fun finish() {
        runCatching { manager.cancel(NOTIFICATION_ID) }
    }

    private fun cancelIntent(): PendingIntent {
        val intent = Intent(context, LibraryTaskService::class.java)
            .setAction(LibraryTaskService.ACTION_CANCEL)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(context, CANCEL_REQUEST_CODE, intent, flags)
        } else {
            PendingIntent.getService(context, CANCEL_REQUEST_CODE, intent, flags)
        }
    }

    private fun contentIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun baseBuilder(): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(contentIntent())
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)

    companion object {
        private const val CHANNEL_ID = "muse_library_tasks"
        private const val CANCEL_REQUEST_CODE = 11
        private val CANCEL_ICON = R.drawable.ic_launcher_foreground

        /** Shared by [LibraryTaskService.startForeground] and progress updates. */
        val NOTIFICATION_ID = 0x4D75_5461 // "Muse" + "Ta"
    }
}
