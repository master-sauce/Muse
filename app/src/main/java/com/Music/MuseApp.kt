package com.Music

import android.app.Application
import android.os.SystemClock
import android.util.Log
import com.Music.data.storage.LibraryTasks
import com.Music.data.storage.MuseStorage
import com.Music.downloader.DownloadState
import com.Music.downloader.YtdlInit
import com.yausername.youtubedl_android.YoutubeDL

class MuseApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val t0 = SystemClock.elapsedRealtime()
        try {
            // Library folder access must be resolved before the repository is
            // built (downloads land there and the DB mirrors its contents).
            MuseStorage.init(this)
            // App-scoped runner for long library jobs (bulk playlist edits…).
            LibraryTasks.init(this)

            // YouTubeDL/FFmpeg unpack native binaries; doing that synchronously
            // here cost seconds of main-thread time on every cold start. They
            // now bootstrap in the background; anything that needs them awaits
            // YtdlInit.await().
            YtdlInit.start(this)

            // Initialize the app-wide download engine (DB + repository + state).
            // Must happen before any download is requested. Done here so the
            // engine and its coroutine scope survive Activity death and keep
            // downloads running even when the app is closed (backed by
            // DownloadService as a foreground service).
            DownloadState.init(this)

            // Deliberately NO automatic library sync here: scanning/migrating a
            // few thousand files goes through the SAF provider and would make
            // every cold start feel slow. The folder is read on demand
            // ("Sync folder" / "Restore playlists") and songs downloaded from
            // now on are written straight into it.

            // Update yt-dlp once the engine is up (fixes "download does nothing"
            // when the site changes). Never blocks startup.
            YtdlInit.afterReady {
                runCatching { YoutubeDL.getInstance().updateYoutubeDL(this@MuseApp) }
                    .onFailure { Log.e("MuseApp", "Failed to update YoutubeDL", it) }
                    .onSuccess { Log.d("MuseApp", "YoutubeDL updated successfully") }
            }

            // Warm the library-folder index in the background so the first
            // playback/queue filter never pays the provider query on the UI thread.
            LibraryTasks.warmFolderIndex()

            Log.d("Startup", "Application.onCreate done in ${SystemClock.elapsedRealtime() - t0}ms")
        } catch (e: Exception) {
            Log.e("MuseApp", "Failed to initialize YoutubeDL components", e)
        }
    }
}
