package com.Music.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.Music.data.local.*
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
    private val playlistDao: PlaylistDao,
    private val odesliService: OdesliService,
    private val downloadManager: DownloadManager,
    private val context: Context
) {
    val allSongs: Flow<List<SongEntity>> = songDao.getAllSongs()
    val playlistsWithSongs: Flow<List<PlaylistWithSongs>> = playlistDao.getPlaylistsWithSongs()

    fun getPlaylistSongs(playlistId: Long): Flow<List<SongEntity>> =
        playlistDao.getSongsInPlaylist(playlistId)

    suspend fun getPlaylistById(id: Long): PlaylistEntity? = playlistDao.getPlaylistById(id)

    suspend fun downloadAndSave(url: String, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        var finalUrl = url
        var metaThumbnail: String? = null
        var metaArtist: String? = null

        if (url.contains("spotify.com") || url.contains("apple.com")) {
            try {
                val resp   = odesliService.getLinks(url)
                val entity = resp.entitiesByUniqueId[resp.entityUniqueId]
                resp.linksByPlatform["youtube"]?.url?.let {
                    finalUrl     = it
                    metaThumbnail = entity?.thumbnailUrl
                    metaArtist   = entity?.artistName
                }
            } catch (_: Exception) {}
        }

        val info = try { YoutubeDL.getInstance().getInfo(YoutubeDLRequest(finalUrl)) }
        catch (_: Exception) { null }

        val file = downloadManager.downloadSong(finalUrl) { p, _ -> onProgress(p) }

        val maxOrder = allSongs.let { 0 } // will be set via rowid in migration
        songDao.insertSong(SongEntity(
            id           = info?.id ?: System.currentTimeMillis().toString(),
            title        = info?.title ?: file.nameWithoutExtension,
            artist       = metaArtist ?: info?.uploader ?: "Unknown Artist",
            filePath     = file.absolutePath,
            duration     = (info?.duration?.toLong() ?: 0L) * 1000L,
            thumbnailUrl = metaThumbnail ?: info?.thumbnail,
            sourceUrl    = url,
            sortOrder    = Int.MAX_VALUE
        ))
    }

    suspend fun importFromUri(uri: Uri) = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val title  = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?: uri.lastPathSegment?.substringBeforeLast(".") ?: "Unknown Title"
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "Unknown Artist"
            val durMs  = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val ext    = uri.lastPathSegment?.substringAfterLast(".")?.takeIf { it.length <= 5 } ?: "mp3"

            val destDir  = File(context.getExternalFilesDir(null), "imported").also { it.mkdirs() }
            val destFile = File(destDir, "${System.currentTimeMillis()}.$ext")
            context.contentResolver.openInputStream(uri)?.use { it.copyTo(destFile.outputStream()) }
                ?: throw Exception("Cannot read file")

            songDao.insertSong(SongEntity(
                id           = "local_${destFile.name}",
                title        = title,
                artist       = artist,
                filePath     = destFile.absolutePath,
                duration     = durMs,
                thumbnailUrl = null,
                sourceUrl    = uri.toString(),
                sortOrder    = Int.MAX_VALUE
            ))
        } finally { retriever.release() }
    }

    suspend fun importFromFolder(treeUri: Uri) = withContext(Dispatchers.IO) {
        val docId       = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ), null, null, null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val mime = cursor.getString(2) ?: continue
                if (!mime.startsWith("audio/")) continue
                val fileUri = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri, cursor.getString(0))
                try { importFromUri(fileUri) }
                catch (e: Exception) { Log.w("MusicRepo", "Skipped: ${e.message}") }
            }
        }
    }

    suspend fun updateSortOrder(id: String, order: Int) = withContext(Dispatchers.IO) {
        songDao.updateSortOrder(id, order)
    }

    suspend fun deleteSong(song: SongEntity) = withContext(Dispatchers.IO) {
        File(song.filePath).takeIf { it.exists() }?.delete()
        songDao.deleteSong(song)
    }

    suspend fun deleteSongs(ids: Set<String>) = withContext(Dispatchers.IO) {
        ids.forEach { id -> songDao.getSongById(id)?.let { File(it.filePath).takeIf { f -> f.exists() }?.delete() } }
        songDao.deleteSongsByIds(ids.toList())
    }

    // ── Playlist ops ────────────────────────────────────────────────────────
    suspend fun createPlaylist(name: String): Long =
        playlistDao.createPlaylist(PlaylistEntity(name = name))

    suspend fun deletePlaylist(playlist: PlaylistEntity) =
        playlistDao.deletePlaylist(playlist)

    suspend fun renamePlaylist(id: Long, name: String) =
        playlistDao.renamePlaylist(id, name)

    suspend fun addSongToPlaylist(playlistId: Long, songId: String) = withContext(Dispatchers.IO) {
        val pos = playlistDao.getSongCount(playlistId)
        playlistDao.addSongToPlaylist(PlaylistSongCrossRef(playlistId, songId, pos))
    }

    suspend fun removeSongFromPlaylist(playlistId: Long, songId: String) =
        playlistDao.removeSongFromPlaylist(playlistId, songId)
}