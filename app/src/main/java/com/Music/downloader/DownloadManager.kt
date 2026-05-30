package com.Music.downloader

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class DownloadManager(private val context: Context) {

    suspend fun downloadSong(url: String, onProgress: (Float, Long) -> Unit): File = withContext(Dispatchers.IO) {
        val downloadDir = File(context.getExternalFilesDir(null), "downloads")
        if (!downloadDir.exists()) downloadDir.mkdirs()

        // response.out is stdout TEXT not a path — use UUID so we know the filename
        val uniqueId = UUID.randomUUID().toString()

        val request = YoutubeDLRequest(url)
        request.addOption("--extract-audio")
        request.addOption("--audio-format", "mp3")
        request.addOption("--audio-quality", "0")
        request.addOption("--no-playlist")
        request.addOption("-o", "${downloadDir.absolutePath}/$uniqueId.%(ext)s")

        YoutubeDL.getInstance().execute(request) { progress, eta, _ ->
            onProgress(progress, eta)
        }

        // --extract-audio --audio-format mp3 always outputs .mp3
        val mp3File = File(downloadDir, "$uniqueId.mp3")
        if (mp3File.exists()) {
            mp3File
        } else {
            // Fallback: yt-dlp left a different extension
            downloadDir.listFiles()?.find { it.name.startsWith(uniqueId) }
                ?: throw Exception("Download completed but output file not found in $downloadDir")
        }
    }
}