package com.Music.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
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
    private val downloadManager: DownloadManager,
    private val context: Context          // added
) {
    val allSongs: Flow<List<SongEntity>> = songDao.getAllSongs()

    suspend fun downloadAndSave(url: String, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        var finalUrl = url
        var metadataThumbnail: String? = null
        var metadataArtist: String? = null

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
            } catch (e: Exception) { /* fall through, try direct download */ }
        }

        val infoRequest = YoutubeDLRequest(finalUrl)
        val info = try { YoutubeDL.getInstance().getInfo(infoRequest) } catch (e: Exception) { null }

        val file = downloadManager.downloadSong(finalUrl) { progress, _ -> onProgress(progress) }

        val song = SongEntity(
            id = info?.id ?: System.currentTimeMillis().toString(),
            title = info?.title ?: file.nameWithoutExtension,
            artist = metadataArtist ?: info?.uploader ?: "Unknown Artist",
            filePath = file.absolutePath,
            duration = (info?.duration?.toLong() ?: 0L) * 1000L,
            thumbnailUrl = metadataThumbnail ?: info?.thumbnail,
            sourceUrl = url
        )
        songDao.insertSong(song)
    }

    suspend fun importFromUri(uri: Uri) = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)

            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?: uri.lastPathSegment?.substringBeforeLast(".") ?: "Unknown Title"
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: "Unknown Artist"
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L

            // Copy to app-owned storage — SAF URIs can become invalid later
            val destDir = File(context.getExternalFilesDir(null), "imported")
            if (!destDir.exists()) destDir.mkdirs()
            val destFile = File(destDir, "${System.currentTimeMillis()}.mp3")

            context.contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            } ?: throw Exception("Cannot open selected file")

            val song = SongEntity(
                id = "local_${destFile.name}",
                title = title,
                artist = if (album != null) "$artist — $album" else artist,
                filePath = destFile.absolutePath,
                duration = durationMs,
                thumbnailUrl = null,
                sourceUrl = uri.toString()
            )
            songDao.insertSong(song)
        } finally {
            retriever.release()
        }
    }

    suspend fun deleteSong(song: SongEntity) = withContext(Dispatchers.IO) {
        File(song.filePath).takeIf { it.exists() }?.delete()
        songDao.deleteSong(song)
    }
}