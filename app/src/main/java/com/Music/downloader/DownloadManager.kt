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

        val outputTemplate = "${downloadDir.absolutePath}/%(title)s.%(ext)s"

        val request = YoutubeDLRequest(url)
        // Official format from library docs: best MP4 video + best M4A audio, fallback to best MP4, fallback to best
        request.addOption("-f", "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best")
        request.addOption("-o", outputTemplate)

        // Snapshot before download
        val before = downloadDir.listFiles()?.map { it.absolutePath }?.toSet() ?: emptySet()

        YoutubeDL.getInstance().execute(request) { progress, etaInSeconds, _ ->
            onProgress(progress, etaInSeconds)
        }

        // Find new file — do not trust response.out
        val after = downloadDir.listFiles()?.map { it.absolutePath }?.toSet() ?: emptySet()
        val newFile = after.subtract(before).firstOrNull()?.let { File(it) }
            ?: throw Exception("Downloaded file not found in ${downloadDir.absolutePath}")

        newFile
    }
}