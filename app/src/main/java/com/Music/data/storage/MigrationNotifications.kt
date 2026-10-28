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
import com.Music.player.MigrationService

/**
 * The single, ongoing notification shown while the library is being migrated /
 * re-synced (and the foreground-service notification for [MigrationService]).
 *
 * It is `setOngoing(true)` + backed by a foreground service, so it cannot be
 * swiped away — the only way out is the **Cancel** action, which flips
 * [LibrarySync.requestCancel]. Progress is a real percentage of songs
 * processed, with the current song as the second line.
 */
class MigrationNotifications(private val context: Context) {

    private val manager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init { ensureChannel() }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Library migration",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = "Progress while Muse moves songs into your library folder"
                        setShowBadge(false)
                    }
                )
            }
        }
    }

    /** Placeholder used to claim the foreground slot before counts are known. */
    fun buildForegroundNotification(): Notification =
        baseBuilder()
            .setContentTitle("Preparing library")
            .setContentText("Scanning \u201c${MuseStorage.folderName()}\u201d")
            .setProgress(0, 0, true)
            .addAction(CANCEL_ICON, "Cancel", cancelIntent())
            .build()

    /** Update the live progress. [cancelling] shows that Cancel was pressed. */
    fun update(
        completed: Int,
        total: Int,
        currentTitle: String?,
        cancelling: Boolean,
        phase: String? = null
    ) {
        val safeTotal = total.coerceAtLeast(1)
        val done = completed.coerceIn(0, safeTotal)
        val percent = (done * 100 / safeTotal)
        lastProgress = Progress(done, safeTotal, currentTitle, cancelling, phase)

        val title = when {
            cancelling -> "Cancelling migration…"
            phase != null -> "$phase • $done/$safeTotal"
            else -> "Migrating library • $done/$safeTotal"
        }
        val text = when {
            cancelling -> "Stopping after the current song"
            currentTitle != null -> currentTitle
            else -> "Working in \u201c${MuseStorage.folderName()}\u201d"
        }

        manager.notify(
            NOTIFICATION_ID,
            baseBuilder()
                .setContentTitle(title)
                .setContentText(text)
                .setSubText("$percent%")
                .setProgress(100, percent, false)
                .addAction(CANCEL_ICON, "Cancel", cancelIntent())
                .build()
        )
    }

    /**
     * Re-post the notification after the user swiped it away. Android 13+ lets
     * a foreground-service notification be dismissed while the service keeps
     * running, so the delete intent brings it straight back.
     */
    fun restore() {
        val state = lastProgress ?: return
        update(state.completed, state.total, state.title, state.cancelling, state.phase)
    }

    fun finish() {
        lastProgress = null
        manager.cancel(NOTIFICATION_ID)
    }

    // ── Intents ─────────────────────────────────────────────────────────────

    private fun cancelIntent(): PendingIntent {
        val intent = Intent(context, MigrationService::class.java)
            .setAction(MigrationService.ACTION_CANCEL)
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

    /** Fired when the notification is dismissed; asks the service to re-post it. */
    private fun restoreIntent(): PendingIntent {
        val intent = Intent(context, MigrationService::class.java)
            .setAction(MigrationService.ACTION_RESTORE)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(context, RESTORE_REQUEST_CODE, intent, flags)
        } else {
            PendingIntent.getService(context, RESTORE_REQUEST_CODE, intent, flags)
        }
    }

    private fun baseBuilder(): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)          // not swipeable — only Cancel ends it
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(contentIntent())
            .setDeleteIntent(restoreIntent())   // swipe on 13+ → comes right back
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)

    companion object {
        private const val CHANNEL_ID = "muse_library"
        private const val CANCEL_REQUEST_CODE = 2
        private const val RESTORE_REQUEST_CODE = 3
        private val CANCEL_ICON = R.drawable.ic_launcher_foreground

        /** Shared by [MigrationService.startForeground] and progress updates. */
        val NOTIFICATION_ID = 0x4D75_4C69

        /** Last posted state, so a dismissed notification can be restored. */
        @Volatile
        private var lastProgress: Progress? = null
    }

    private data class Progress(
        val completed: Int,
        val total: Int,
        val title: String?,
        val cancelling: Boolean,
        val phase: String?
    )
}
