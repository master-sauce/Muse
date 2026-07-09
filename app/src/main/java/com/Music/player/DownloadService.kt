package com.Music.player

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.Music.downloader.DownloadNotificationManager
import com.Music.downloader.DownloadState

/**
 * Foreground service that keeps the app process alive while downloads are
 * running, so they continue even if the user closes the app.
 *
 * The actual download work lives in [DownloadState] (an app-scoped singleton
 * with its own coroutine scope). This service's only job is to hold the
 * foreground slot — Android won't kill a process that has a foreground service
 * running as readily as a background one.
 *
 * Lifecycle:
 *  - [DownloadState] calls `startForegroundService` when a download starts.
 *  - We immediately promote ourselves to the foreground with the **shared**
 *    download notification ([DownloadNotificationManager.FOREGROUND_NOTIFICATION_ID]).
 *    Because [DownloadState] updates that same notification id with real
 *    progress, the foreground notification always reflects live download
 *    progress and is non-dismissable.
 *  - When all downloads finish, [DownloadState] sends [ACTION_STOP] and we call
 *    [stopSelf].
 *
 * The foreground notification is the same ongoing download notification the
 * user already sees (built by [DownloadNotificationManager]); we just reuse a
 * lightweight placeholder to claim the slot, then DownloadState updates it
 * in place.
 */
class DownloadService : Service() {

    private val notifications by lazy { DownloadNotificationManager(this) }

    override fun onCreate() {
        super.onCreate()
        // Claim the foreground slot immediately with the shared notification id.
        // DownloadState will update this same id with real progress, keeping the
        // notification non-dismissable and the process alive.
        startForeground(
            DownloadNotificationManager.FOREGROUND_NOTIFICATION_ID,
            notifications.buildForegroundNotification()
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        // If we're somehow started but nothing is downloading, stop.
        if (!DownloadState.isDownloading.value) {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        // STICKY so the system tries to restart us if killed (we'll re-evaluate
        // whether anything is downloading in onStartCommand).
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // If the user swipes the app away but downloads are still going, keep
        // running. If nothing is downloading, let ourselves die.
        if (!DownloadState.isDownloading.value) {
            stopForegroundCompat()
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    /** stopForeground that works across API levels without the deprecation noise. */
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    companion object {
        const val ACTION_STOP = "com.Music.action.STOP_DOWNLOAD_SERVICE"
    }
}
