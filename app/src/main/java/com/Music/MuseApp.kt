package com.Music

import android.app.Application
import android.util.Log
import com.Music.data.storage.MuseStorage
import com.Music.downloader.DownloadState
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.ffmpeg.FFmpeg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MuseApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            // Library folder access must be resolved before the repository is
            // built (downloads land there and the DB mirrors its contents).
            MuseStorage.init(this)

            // Initialize YoutubeDL and FFmpeg
            YoutubeDL.getInstance().init(this)
            FFmpeg.getInstance().init(this)

            // Initialize the app-wide download engine (DB + repository + state).
            // Must happen before any download is requested. Done here so the
            // engine and its coroutine scope survive Activity death and keep
            // downloads running even when the app is closed (backed by
            // DownloadService as a foreground service).
            DownloadState.init(this)

            // Re-sync the library with the user's folder on every cold start:
            // files they deleted are dropped from the library, files they added
            // (or edited) are picked up.
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { DownloadState.repository().syncLibraryFromDisk() }
                    .onFailure { Log.w("MuseApp", "Library sync failed", it) }
            }

            // Background update yt-dlp to ensure it works with latest site changes
            // This often fixes the "doesn't do anything" issue when downloading
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    YoutubeDL.getInstance().updateYoutubeDL(this@MuseApp)
                    Log.d("MuseApp", "YoutubeDL updated successfully")
                } catch (e: Exception) {
                    Log.e("MuseApp", "Failed to update YoutubeDL", e)
                }
            }
        } catch (e: Exception) {
            Log.e("MuseApp", "Failed to initialize YoutubeDL components", e)
        }
    }
}
