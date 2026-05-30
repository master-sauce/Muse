package com.Music.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
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
    private val context: Context
) {
    val allSongs: Flow<List<SongEntity>> = songDao.getAllSongs()

    suspend fun downloadAndSave(url: String, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        var finalUrl          = url
        var metaThumbnail: String? = null
        var metaArtist: String?    = null

        if (url.contains("spotify.com") || url.contains("apple.com")) {
            try {
                val resp   = odesliService.getLinks(url)
                val entity = resp.entitiesByUniqueId[resp.entityUniqueId]
                val ytUrl  = resp.linksByPlatform["youtube"]?.url
                if (ytUrl != null) {
                    finalUrl     = ytUrl
                    metaThumbnail = entity?.thumbnailUrl
                    metaArtist   = entity?.artistName
                }
            } catch (_: Exception) {}
        }

        val info = try { YoutubeDL.getInstance().getInfo(YoutubeDLRequest(finalUrl)) }
        catch (_: Exception) { null }

        val file = downloadManager.downloadSong(finalUrl) { p, _ -> onProgress(p) }

        songDao.insertSong(SongEntity(
            id           = info?.id ?: System.currentTimeMillis().toString(),
            title        = info?.title ?: file.nameWithoutExtension,
            artist       = metaArtist ?: info?.uploader ?: "Unknown Artist",
            filePath     = file.absolutePath,
            duration     = (info?.duration?.toLong() ?: 0L) * 1000L,
            thumbnailUrl = metaThumbnail ?: info?.thumbnail,
            sourceUrl    = url
        ))
    }

    suspend fun importFromUri(uri: Uri) = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)

            val title    = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?: uri.lastPathSegment?.substringBeforeLast(".") ?: "Unknown Title"
            val artist   = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "Unknown Artist"
            val durMs    = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L

            val destDir  = File(context.getExternalFilesDir(null), "imported").also { it.mkdirs() }
            val ext      = uri.lastPathSegment?.substringAfterLast(".")?.takeIf { it.length <= 5 } ?: "mp3"
            val destFile = File(destDir, "${System.currentTimeMillis()}.$ext")

            context.contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { input.copyTo(it) }
            } ?: throw Exception("Cannot read file")

            songDao.insertSong(SongEntity(
                id           = "local_${destFile.name}",
                title        = title,
                artist       = artist,
                filePath     = destFile.absolutePath,
                duration     = durMs,
                thumbnailUrl = null,
                sourceUrl    = uri.toString()
            ))
        } finally {
            retriever.release()
        }
    }

    /** Scans a SAF folder tree; imports every audio/* file found. */
     */
    suspend fun importFromFolder(treeUri: Uri) = withContext(Dispatchers.IO) {
val docId       = DocumentsContract.getTreeDocumentId(treeUri)
val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)

context.contentResolver.query(
childrenUri,
arrayOf(
DocumentsContract.Document.COLUMN_DOCUMENT_ID,
DocumentsContract.Document.COLUMN_DISPLAY_NAME,
DocumentsContract.Document.COLUMN_MIME_TYPE
),
null, null, null
)?.use { cursor ->
while (cursor.moveToNext()) {
val mime      = cursor.getString(2) ?: continue
if (!mime.startsWith("audio/")) continue
val fileDocId = cursor.getString(0)
val fileUri   = DocumentsContract.buildDocumentUriUsingTree(treeUri, fileDocId)
try {
importFromUri(fileUri)
} catch (e: Exception) {
Log.w("MusicRepo", "Skipped ${cursor.getString(1)}: ${e.message}")
}
}
}
}

suspend fun deleteSong(song: SongEntity) = withContext(Dispatchers.IO) {
File(song.filePath).takeIf { it.exists() }?.delete()
songDao.deleteSong(song)
}
}