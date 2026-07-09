package com.Music.downloader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fired when the user taps **Cancel** on the download notification.
 *
 * Looks up the cancel id (passed via [EXTRA_CANCEL_ID]) in
 * [DownloadCancelRegistry] and runs the registered callback. The foreground
 * notification's Cancel button uses [DownloadCancelRegistry.ALL_ID], which
 * cancels every active download (all single-song downloads plus the batch, if
 * running).
 *
 * This is the *only* way the user can dismiss an in-progress download
 * notification: it is a foreground notification built with `setOngoing(true)`,
 * so swiping it away does nothing. Tapping Cancel stops the downloads; once
 * they have all actually stopped, [DownloadState] removes the notification via
 * [DownloadNotificationManager.finish].
 */
class DownloadCancelReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CANCEL) return
        val cancelId = intent.getStringExtra(EXTRA_CANCEL_ID) ?: return
        DownloadCancelRegistry.trigger(cancelId)
    }

    companion object {
        const val ACTION_CANCEL = "com.Music.action.CANCEL_DOWNLOAD"
        const val EXTRA_CANCEL_ID = "cancel_id"
    }
}
