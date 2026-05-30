package com.Music.downloader

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File

class DownloadManager(private val context: Context) {

    suspend fun downloadSong(url: String, onProgress: (Float, Long) -> Unit): File {
        val downloadDir = File(context.getExternalFilesDir(null), "downloads")
        if (!downloadDir.exists()) downloadDir.mkdirs()

        val request = YoutubeDLRequest(url)
        request.addOption("--extract-audio")
        request.addOption("--audio-format", "mp3")
        request.addOption("-o", "${downloadDir.absolutePath}/%(title)s.%(ext)s")

        val response = YoutubeDL.getInstance().execute(request) { progress, etaInSeconds, line ->
            onProgress(progress, etaInSeconds)
        }

        return File(response.out)
    }
}
