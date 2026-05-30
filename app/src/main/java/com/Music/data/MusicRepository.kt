package com.Music.data

import com.Music.data.local.SongDao
import com.Music.data.local.SongEntity
import com.Music.data.remote.OdesliService
import com.Music.downloader.DownloadManager
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

class MusicRepository(
    private val songDao: SongDao,
    private val odesliService: OdesliService,
    private val downloadManager: DownloadManager
) {
    val allSongs: Flow<List<SongEntity>> = songDao.getAllSongs()

    suspend fun downloadAndSave(url: String, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        var finalUrl = url
        var metadataThumbnail: String? = null
        var metadataArtist: String? = null

        // 1. Resolve Link if it's Spotify/Apple Music etc.
        if (url.contains("spotify.com") || url.contains("apple.com")) {
            try {
                val response = odesliService.getLinks(url)
                val entity = response.entitiesByUniqueId[response.entityUniqueId]
                val youtubeLink = response.linksByPlatform["youtube"]?.url
                if (youtubeLink != null) {
                    finalUrl = youtubeLink
                    metadataThumbnail = entity?.thumbnailUrl
                    metadataArtist = entity?.artistName
                }
            } catch (e: Exception) {
                // If resolution fails, we try downloading the original link anyway
            }
        }

        // 2. Fetch Metadata from YoutubeDL for final download
        val request = YoutubeDLRequest(finalUrl)
        val info = try {
            YoutubeDL.getInstance().getInfo(request)
        } catch (e: Exception) {
            null
        }

        // 3. Download the audio
        val file = downloadManager.downloadSong(finalUrl) { progress, _ ->
            onProgress(progress)
        }

        // 4. Save to DB with proper metadata
        val song = SongEntity(
            id = info?.id ?: System.currentTimeMillis().toString(),
            title = info?.title ?: file.nameWithoutExtension,
            // Use Odesli artist if available, fallback to YouTube uploader
            artist = metadataArtist ?: info?.uploader ?: "Unknown Artist",
            filePath = file.absolutePath,
            duration = (info?.duration?.toLong() ?: 0L) * 1000L,
            thumbnailUrl = metadataThumbnail ?: info?.thumbnail,
            sourceUrl = url
        )
        songDao.insertSong(song)
    }

    suspend fun deleteSong(song: SongEntity) = withContext(Dispatchers.IO) {
        val file = File(song.filePath)
        if (file.exists()) file.delete()
        songDao.deleteSong(song)
    }
}
