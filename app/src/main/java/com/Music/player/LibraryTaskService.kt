package com.Music.player

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.Music.data.storage.LibraryTasks

/**
 * Foreground service for library maintenance jobs (bulk playlist edits, bulk
 * deletes, restores).
 *
 * Holds the process alive so the job keeps running with the app closed, and
 * hosts the Cancel action of the ongoing progress notification. The work itself
 * lives in [LibraryTasks].
 */
class LibraryTaskService : Service() {

    override fun onCreate() {
        super.onCreate()
        val started = runCatching {
            startForeground(
                com.Music.data.storage.TaskNotifications.NOTIFICATION_ID,
                com.Music.data.storage.TaskNotifications(this)
                    .buildForegroundNotification(LibraryTasks.titleOrDefault())
            )
        }.isSuccess
        if (!started) stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                LibraryTasks.cancel()
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
        }
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
        const val ACTION_CANCEL = "com.Music.action.CANCEL_LIBRARY_TASK"
        const val ACTION_STOP = "com.Music.action.STOP_LIBRARY_TASK_SERVICE"
    }
}
