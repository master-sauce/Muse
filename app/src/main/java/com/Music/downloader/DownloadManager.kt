package com.Music.downloader

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class DownloadManager(private val context: Context) {

    suspend fun downloadSong(url: String, taskId: String, onProgress: (Float, Long) -> Unit): File = withContext(Dispatchers.IO) {
        val downloadDir = File(context.getExternalFilesDir(null), "downloads")
        if (!downloadDir.exists()) downloadDir.mkdirs()

        // Use the taskId in the filename to prevent race conditions during concurrent downloads
        val outputTemplate = "${downloadDir.absolutePath}/$taskId.%(ext)s"

        val request = YoutubeDLRequest(url)
        request.addOption("-f", "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best")
        request.addOption("-o", outputTemplate)

        // Snapshot before download to find the specific extension
        val before = downloadDir.listFiles()?.map { it.absolutePath }?.toSet() ?: emptySet()

        YoutubeDL.getInstance().execute(request) { progress, etaInSeconds, _ ->
            onProgress(progress, etaInSeconds)
        }

        val after = downloadDir.listFiles()?.map { it.absolutePath }?.toSet() ?: emptySet()
        
        // Find the file that starts with our taskId
        val newFilePath = after.subtract(before).find { 
            File(it).name.startsWith(taskId) 
        } ?: throw Exception("Downloaded file not found for task $taskId")

        File(newFilePath)
    }
}