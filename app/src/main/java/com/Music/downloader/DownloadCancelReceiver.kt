package com.Music.downloader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fired when the user taps **Cancel** on a download notification.
 *
 * Looks up the cancel id (passed via [EXTRA_CANCEL_ID]) in
 * [DownloadCancelRegistry] and runs the registered callback — which, for a
 * single download, kills that download's yt-dlp process, and for a batch
 * download, cancels the whole batch.
 *
 * This is the *only* way the user can dismiss an in-progress download
 * notification: the notification is built with `setOngoing(true)`, so swiping
 * it away does nothing. Tapping Cancel both stops the download and lets the
 * notification be removed (the ViewModel calls
 * [DownloadNotificationManager.finish] / [finishBatch] once the download has
 * actually stopped).
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
