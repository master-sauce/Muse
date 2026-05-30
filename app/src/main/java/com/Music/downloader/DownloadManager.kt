package com.Music.downloader

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class DownloadManager(private val context: Context) {

    suspend fun downloadSong(url: String, onProgress: (Float, Long) -> Unit): File = withContext(Dispatchers.IO) {
        val downloadDir = File(context.getExternalFilesDir(null), "downloads")
        if (!downloadDir.exists()) downloadDir.mkdirs()

        val request = YoutubeDLRequest(url)
        request.addOption("--extract-audio")
        request.addOption("--audio-format", "mp3")
        // Use a temp name first to avoid collisions if title fetching fails
        request.addOption("-o", "${downloadDir.absolutePath}/%(title)s.%(ext)s")

        val response = YoutubeDL.getInstance().execute(request) { progress, etaInSeconds, line ->
            onProgress(progress, etaInSeconds)
        }

        File(response.out)
    }
}
