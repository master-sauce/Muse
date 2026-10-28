package com.Music.player

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.Music.data.storage.LibrarySync
import com.Music.data.storage.MigrationNotifications

/**
 * Foreground service that keeps the process alive while the library is
 * migrated into the user's folder / re-synced from it.
 *
 * The work itself lives in [LibrarySync] (driven by the caller's coroutine);
 * this service only:
 *  - holds the foreground slot so a long migration isn't killed when the app is
 *    backgrounded, and
 *  - turns the notification's **Cancel** button into
 *    [LibrarySync.requestCancel], which stops the loop after the song in
 *    progress (the pass stays resumable).
 */
class MigrationService : Service() {

    private val notifications by lazy { MigrationNotifications(this) }

    override fun onCreate() {
        super.onCreate()
        startForeground(
            MigrationNotifications.NOTIFICATION_ID,
            notifications.buildForegroundNotification()
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                LibrarySync.requestCancel()
                return START_NOT_STICKY
            }
            ACTION_RESTORE -> {
                // Dismissed (Android 13+ allows swiping FGS notifications) → re-post.
                notifications.restore()
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        // Already claimed in onCreate; progress arrives via the same id.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    companion object {
        const val ACTION_CANCEL = "com.Music.action.CANCEL_LIBRARY_MIGRATION"
        const val ACTION_RESTORE = "com.Music.action.RESTORE_LIBRARY_MIGRATION_NOTIFICATION"
        const val ACTION_STOP = "com.Music.action.STOP_LIBRARY_MIGRATION_SERVICE"
    }
}
