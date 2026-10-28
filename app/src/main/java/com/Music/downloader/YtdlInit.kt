package com.Music.downloader

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Deferred YouTubeDL / FFmpeg bootstrap.
 *
 * Their `init()` unpacks native binaries and touches the filesystem, which took
 * seconds when done synchronously in `Application.onCreate` — that was the
 * "app is stuck for a moment on open" symptom. It now runs on IO while the UI
 * comes up, and anything that needs the engine awaits [await] first.
 */
object YtdlInit {

    private val ready = CompletableDeferred<Unit>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        scope.launch {
            // Complete even on failure: the download paths report their own
            // errors, and a hung start would block every download forever.
            runCatching { YoutubeDL.getInstance().init(app) }
            runCatching { FFmpeg.getInstance().init(app) }
            ready.complete(Unit)
        }
    }

    suspend fun await() {
        ready.await()
    }

    val isReady: Boolean get() = ready.isCompleted

    /** Run [block] once the engine is ready, in the background. */
    fun afterReady(block: suspend () -> Unit) {
        scope.launch {
            await()
            runCatching { block() }
        }
    }
}
