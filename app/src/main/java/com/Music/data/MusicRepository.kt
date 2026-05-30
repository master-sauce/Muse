package com.Music.data

import com.Music.data.local.SongDao
import com.Music.data.local.SongEntity
import com.Music.data.remote.OdesliService
import com.Music.downloader.DownloadManager
import kotlinx.coroutines.flow.Flow
import java.io.File

class MusicRepository(
    private val songDao: SongDao,
    private val odesliService: OdesliService,
    private val downloadManager: DownloadManager
) {
    val allSongs: Flow<List<SongEntity>> = songDao.getAllSongs()

    suspend fun downloadAndSave(url: String, onProgress: (Float) -> Unit) {
        // 1. Resolve with Odesli if needed (simplified for now, assuming direct or yt link)
        // 2. Download
        val file = downloadManager.downloadSong(url) { progress, _ ->
            onProgress(progress)
        }

        // 3. Save to DB
        val song = SongEntity(
            id = System.currentTimeMillis().toString(),
            title = file.nameWithoutExtension,
            artist = "Unknown",
            filePath = file.absolutePath,
            duration = 0, // Should be fetched from file
            thumbnailUrl = null,
            sourceUrl = url
        )
        songDao.insertSong(song)
    }

    suspend fun deleteSong(song: SongEntity) {
        val file = File(song.filePath)
        if (file.exists()) file.delete()
        songDao.deleteSong(song)
    }
}
