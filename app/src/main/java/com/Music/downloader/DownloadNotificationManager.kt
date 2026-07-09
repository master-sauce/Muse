package com.Music.downloader

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

/**
 * Manages the SINGLE foreground download notification.
 *
 * There is exactly one notification (id [FOREGROUND_NOTIFICATION_ID]) that is
 * simultaneously:
 *  - the **foreground-service** notification for
 *    [com.Music.player.DownloadService] (which keeps the app process alive so
 *    downloads continue even when the app is closed), and
 *  - the **user-visible progress** notification.
 *
 * Because it is a foreground notification it CANNOT be swiped away by the
 * user — it is only removed by [finish] (called once every download has
 * completed) or by tapping the built-in **Cancel** action (which fires
 * [DownloadCancelReceiver] with [DownloadCancelRegistry.ALL_ID] and cancels
 * every active download).
 *
 * The notification aggregates all active downloads (single-song downloads plus
 * an optional batch/playlist download) into one progress bar.
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
     * Build the **Cancel** action's PendingIntent. The Cancel button cancels
     * *every* active download (single + batch) via
     * [DownloadCancelRegistry.ALL_ID].
     */
    private fun cancelAllIntent(): PendingIntent {
        val intent = Intent(context, DownloadCancelReceiver::class.java).apply {
            action = DownloadCancelReceiver.ACTION_CANCEL
            putExtra(DownloadCancelReceiver.EXTRA_CANCEL_ID, DownloadCancelRegistry.ALL_ID)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getBroadcast(
            context, DownloadCancelRegistry.ALL_ID.hashCode(), intent, flags
        )
    }

    /**
     * The lightweight notification used to claim the foreground slot when the
     * service starts (before any progress is known). Ongoing + non-dismissable.
     * Uses the SAME id as [showAggregateProgress] so subsequent progress
     * updates replace this placeholder in place while keeping the foreground
     * status.
     */
    fun buildForegroundNotification(): Notification =
        baseBuilder()
            .setContentTitle("Muse")
            .setContentText("Preparing download...")
            .setProgress(0, 0, /* indeterminate = */ true)
            .addAction(R.drawable.ic_launcher_foreground, "Cancel", cancelAllIntent())
            .build()

    /**
     * Update the single foreground notification with aggregate progress across
     * all active downloads (single-song downloads plus an optional batch).
     *
     * @param singles  the currently active single-song downloads (taskId -> task)
     * @param batch    the current batch-download state (may be idle)
     */
    fun showAggregateProgress(
        singles: Map<String, DownloadTask>,
        batch: BatchDownloadState
    ) {
        val singleCount = singles.size
        val batchRunning = batch.isRunning
        val totalActive = singleCount + (if (batchRunning) 1 else 0)
        if (totalActive == 0) return

        val title: String
        val text: String
        val percent: Int

        if (batchRunning) {
            // Batch dominates the display.
            val overall = if (batch.total > 0) {
                ((batch.completed + batch.currentProgress.coerceIn(0f, 100f) / 100f) / batch.total) * 100f
            } else 0f
            percent = overall.coerceIn(0f, 100f).toInt()
            title = "Downloading playlist • ${batch.completed}/${batch.total}"
            text = when {
                singleCount > 0 -> "${batch.currentTitle ?: "Downloading..."} (+ $singleCount more)"
                else -> batch.currentTitle ?: "Downloading..."
            }
        } else if (singleCount == 1) {
            val task = singles.values.first()
            percent = task.progress.coerceIn(0f, 100f).toInt()
            title = task.title ?: "Downloading..."
            text = "$percent%"
        } else {
            val avg = singles.values
                .map { it.progress.coerceIn(0f, 100f) }
                .average().toFloat()
            percent = avg.toInt()
            title = "Downloading • $singleCount songs"
            text = "$percent% overall"
        }

        val builder = baseBuilder()
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(100, percent, /* indeterminate = */ false)
            .addAction(R.drawable.ic_launcher_foreground, "Cancel", cancelAllIntent())
        notificationManager.notify(FOREGROUND_NOTIFICATION_ID, builder.build())
    }

    /**
     * Remove the foreground notification. Only call this once EVERY download
     * has finished — while anything is downloading the notification must stay
     * (it is the foreground notification that keeps the process alive and is
     * non-dismissable).
     */
    fun finish() {
        notificationManager.cancel(FOREGROUND_NOTIFICATION_ID)
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

    companion object {
        private const val CHANNEL_ID = "muse_downloads"

        /**
         * The single, shared notification id used by both
         * [com.Music.player.DownloadService] (for `startForeground`) and the
         * progress updates here. Using one id means the progress notification
         * IS the foreground notification — so it keeps the process alive AND
         * cannot be swiped away.
         */
        const val FOREGROUND_NOTIFICATION_ID = 0x4D75_7365.toInt() // "Muse"
    }
}
