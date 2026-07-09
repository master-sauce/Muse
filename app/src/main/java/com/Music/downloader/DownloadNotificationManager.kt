package com.Music.downloader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.Music.MainActivity
import com.Music.R

/**
 * Manages the download progress notification.
 *
 * The notification is **ongoing** ([NotificationCompat.Builder.setOngoing] = true), which
 * prevents the user from dismissing it by swiping it away — it can only be removed by
 * tapping the built-in **Cancel** action (which fires [DownloadCancelReceiver]) or by the
 * download finishing / being cancelled programmatically (see [cancel] / [finish]).
 *
 * Two flavours are supported:
 *  - A **single** download notification keyed by the download's taskId, showing that
 *    song's 0–100 % progress.
 *  - A **batch** download notification (playlist / links-file import) showing
 *    `completed / total` plus the current song's progress, with an overall progress bar.
 */
class DownloadNotificationManager(private val context: Context) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init { ensureChannel() }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val existing = notificationManager.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Downloads",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Shows progress for music downloads"
                    setShowBadge(false)
                }
                notificationManager.createNotificationChannel(channel)
            }
        }
    }

    /** Build the tap-to-open PendingIntent (immutable on API 23+). */
    private fun contentIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getActivity(context, 0, intent, flags)
    }

    /**
     * Build the **Cancel** action's PendingIntent for the given [cancelId].
     *
     * [cancelId] is whatever the caller registered with [DownloadCancelRegistry.register]
     * — i.e. the single-download taskId or the batch sentinel
     * [DownloadCancelRegistry.BATCH_ID].
     */
    private fun cancelIntent(cancelId: String): PendingIntent {
        val intent = Intent(context, DownloadCancelReceiver::class.java).apply {
            action = DownloadCancelReceiver.ACTION_CANCEL
            putExtra(DownloadCancelReceiver.EXTRA_CANCEL_ID, cancelId)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT
        // Unique request code per cancelId so each notification gets its own action.
        return PendingIntent.getBroadcast(
            context, cancelId.hashCode(), intent, flags
        )
    }

    // ── Single download ────────────────────────────────────────────────────

    /**
     * Show / update the progress notification for a single download.
     *
     * @param taskId  stable id used as the notification id (and as the cancel id)
     * @param title   song title, or null while metadata is still being fetched
     * @param progress 0f..100f
     */
    fun showProgress(taskId: String, title: String?, progress: Float) {
        val id = notificationId(taskId)
        val percent = progress.coerceIn(0f, 100f).toInt()
        val builder = baseBuilder()
            .setContentTitle(title ?: "Downloading...")
            .setContentText("$percent%")
            .setProgress(100, percent, /* indeterminate = */ false)
            .addAction(
                R.drawable.ic_launcher_foreground,
                "Cancel",
                cancelIntent(taskId)
            )
        notificationManager.notify(id, builder.build())
    }

    /**
     * Mark a single download as finished and remove its notification.
     *
     * Removing the notification is what finally lets it be dismissed — while a
     * download is in progress the notification is ongoing and cannot be swiped
     * away, only cancelled via the Cancel action.
     */
    fun finish(taskId: String) {
        notificationManager.cancel(notificationId(taskId))
    }

    // ── Batch download ─────────────────────────────────────────────────────

    /**
     * Show / update the batch download notification.
     *
     * @param completed   how many songs in the batch have finished
     * @param total       total songs in the batch
     * @param currentTitle  title of the song currently downloading (nullable)
     * @param currentProgress 0f..100f progress of the current song
     */
    fun showBatchProgress(
        completed: Int,
        total: Int,
        currentTitle: String?,
        currentProgress: Float
    ) {
        val overallFloat = if (total > 0) {
            (completed + currentProgress.coerceIn(0f, 100f) / 100f) / total
        } else 0f
        val overallPercent = (overallFloat * 100f).coerceIn(0f, 100f).toInt()

        val title = "Downloading playlist • $completed/$total"
        val text = currentTitle ?: "Preparing..."

        val builder = baseBuilder()
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(100, overallPercent, /* indeterminate = */ false)
            .addAction(
                R.drawable.ic_launcher_foreground,
                "Cancel",
                cancelIntent(DownloadCancelRegistry.BATCH_ID)
            )
        notificationManager.notify(BATCH_NOTIFICATION_ID, builder.build())
    }

    /** Remove the batch download notification (download done or cancelled). */
    fun finishBatch() {
        notificationManager.cancel(BATCH_NOTIFICATION_ID)
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /**
     * Shared notification builder: low-importance, ongoing (non-swipeable),
     * no sound, tap opens the app.
     */
    private fun baseBuilder(): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)            // ← cannot be swiped away; only Cancel removes it
            .setOnlyAlertOnce(true)      // don't buzz/beep on every progress update
            .setSilent(true)
            .setContentIntent(contentIntent())
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)

    private fun notificationId(taskId: String): Int = taskId.hashCode()

    companion object {
        private const val CHANNEL_ID = "muse_downloads"
        // Use a fixed, stable id for the batch notification so updates replace
        // the previous one instead of stacking.
        private const val BATCH_NOTIFICATION_ID = 0x4D75_7365.toInt() // "Muse"
    }
}
