package com.Music.data.storage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-posts the migration notification after the user swiped it away.
 *
 * Deliberately a broadcast rather than a service intent: if the sync process is
 * gone, starting `MigrationService` from the background throws
 * `ForegroundServiceStartNotAllowedException` on Android 12+ and takes the app
 * down. A plain notification post is always safe, and with no live progress
 * state it simply does nothing.
 */
class MigrationNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return
        if (intent?.action != ACTION_RESTORE) return
        runCatching { MigrationNotifications(context).restore() }
    }

    companion object {
        const val ACTION_RESTORE = "com.Music.action.RESTORE_LIBRARY_MIGRATION_NOTIFICATION"
    }
}
