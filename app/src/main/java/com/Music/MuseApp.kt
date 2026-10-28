package com.Music

import android.app.Application
import android.util.Log
import com.Music.data.storage.LibraryTasks
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
            // App-scoped runner for long library jobs (bulk playlist edits…).
            LibraryTasks.init(this)

            // Initialize YoutubeDL and FFmpeg
            YoutubeDL.getInstance().init(this)
            FFmpeg.getInstance().init(this)

            // Initialize the app-wide download engine (DB + repository + state).
            // Must happen before any download is requested. Done here so the
            // engine and its coroutine scope survive Activity death and keep
            // downloads running even when the app is closed (backed by
            // DownloadService as a foreground service).
            DownloadState.init(this)

            // Deliberately NO automatic library sync here: scanning/migrating a
            // few thousand files goes through the SAF provider and would make
            // every cold start feel slow. The folder is read on demand
            // ("Sync folder" / "Repair playlists") and songs downloaded from now
            // on are written straight into it.

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
