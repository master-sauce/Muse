package com.Music.downloader

import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide registry that maps a "cancel id" to the callback that should be
 * invoked when the user taps **Cancel** on a download notification.
 *
 * The notification's Cancel action fires [DownloadCancelReceiver], which looks
 * up the cancel id here and runs the associated callback. The callback is
 * whatever the [com.Music.MainViewModel] registered when it started the
 * download — for a single download that's "cancel this taskId", for a batch
 * download that's "cancel the whole batch".
 *
 * Because the registry is a process-wide singleton, the receiver (which is
 * instantiated by the system, not by the ViewModel) can reach the ViewModel's
 * cancel logic without any direct reference to it.
 */
object DownloadCancelRegistry {

    /** Sentinel cancel id used for the batch (playlist / links-file) download. */
    const val BATCH_ID = "__muse_batch_download__"

    private val callbacks = ConcurrentHashMap<String, () -> Unit>()

    /**
     * Register [onCancel] for [cancelId]. Re-registering replaces the previous
     * callback for that id.
     */
    fun register(cancelId: String, onCancel: () -> Unit) {
        callbacks[cancelId] = onCancel
    }

    /** Remove the callback for [cancelId], if any. Safe to call when none exists. */
    fun unregister(cancelId: String) {
        callbacks.remove(cancelId)
    }

    /** Remove every registered callback (e.g. on app shutdown). */
    fun clear() {
        callbacks.clear()
    }

    /**
     * Look up and invoke the callback for [cancelId], then remove it.
     * Returns true if a callback was found and run, false otherwise.
     */
    fun trigger(cancelId: String): Boolean {
        val cb = callbacks.remove(cancelId) ?: return false
        return try {
            cb()
            true
        } catch (_: Throwable) {
            false
        }
    }
}
