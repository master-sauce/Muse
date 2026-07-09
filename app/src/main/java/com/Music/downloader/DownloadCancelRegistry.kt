package com.Music.downloader

import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide registry that maps a "cancel id" to the callback that should be
 * invoked when the user taps **Cancel** on a download notification.
 *
 * The notification's Cancel action fires [DownloadCancelReceiver], which looks
 * up the cancel id here and runs the associated callback. The callback is
 * whatever the download engine registered when it started the download — for a
 * single download that's "cancel this taskId", for a batch download that's
 * "cancel the whole batch", and for the foreground notification's Cancel
 * button it's [ALL_ID] ("cancel everything").
 *
 * Because the registry is a process-wide singleton, the receiver (which is
 * instantiated by the system, not by the app) can reach the engine's cancel
 * logic without any direct reference to it.
 *
 * Callbacks come in two flavours:
 *  - **One-shot** (default): removed after being triggered. Used for
 *    per-download / per-batch cancel ids that are only valid while that
 *    specific download is running.
 *  - **Persistent** ([registerPersistent]): NOT removed after being triggered,
 *    so the same callback keeps working for every future notification. Used
 *    for [ALL_ID], which is registered once at app init and must keep working
 *    for the lifetime of the process.
 */
object DownloadCancelRegistry {

    /** Sentinel cancel id used for the batch (playlist / links-file) download. */
    const val BATCH_ID = "__muse_batch_download__"

    /**
     * Sentinel cancel id used by the foreground notification's **Cancel**
     * button, which cancels *every* active download (all single-song downloads
     * plus the batch, if running).
     */
    const val ALL_ID = "__muse_cancel_all__"

    private data class Entry(val callback: () -> Unit, val persistent: Boolean)

    private val callbacks = ConcurrentHashMap<String, Entry>()

    /**
     * Register a **one-shot** [onCancel] for [cancelId]. Re-registering
     * replaces the previous callback for that id. The callback is removed
     * after it is triggered by [trigger].
     */
    fun register(cancelId: String, onCancel: () -> Unit) {
        callbacks[cancelId] = Entry(onCancel, persistent = false)
    }

    /**
     * Register a **persistent** [onCancel] for [cancelId]. Re-registering
     * replaces the previous callback for that id. The callback is NOT removed
     * after being triggered by [trigger], so it keeps working for every
     * subsequent Cancel tap. Used for [ALL_ID].
     */
    fun registerPersistent(cancelId: String, onCancel: () -> Unit) {
        callbacks[cancelId] = Entry(onCancel, persistent = true)
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
     * Look up and invoke the callback for [cancelId]. For one-shot entries the
     * callback is removed after being run; for persistent entries it stays.
     * Returns true if a callback was found and run, false otherwise.
     */
    fun trigger(cancelId: String): Boolean {
        val entry = callbacks[cancelId] ?: return false
        return try {
            entry.callback()
            true
        } catch (_: Throwable) {
            false
        } finally {
            if (!entry.persistent) callbacks.remove(cancelId)
        }
    }
}
