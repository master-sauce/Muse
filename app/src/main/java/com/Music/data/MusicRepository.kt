package com.Music.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import com.Music.data.local.*
import com.Music.data.remote.OdesliService
import com.Music.downloader.DownloadManager
import com.Music.downloader.PlaylistEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    private val prefs = context.getSharedPreferences("muse_prefs", Context.MODE_PRIVATE)

    val allSongs: Flow<List<SongEntity>> = songDao.getAllSongs()
    val playlistsWithSongs: Flow<List<PlaylistWithSongs>> = playlistDao.getPlaylistsWithSongs()

    fun getPlaylistSongs(playlistId: Long): Flow<List<SongEntity>> =
        playlistDao.getSongsInPlaylist(playlistId)

    suspend fun getPlaylistById(id: Long): PlaylistEntity? = playlistDao.getPlaylistById(id)

    suspend fun isSongDownloaded(url: String): Boolean = withContext(Dispatchers.IO) {
        songDao.getSongByUrl(url) != null
    }

    suspend fun isLocalSongImported(title: String, artist: String): Boolean = withContext(Dispatchers.IO) {
        songDao.getSongByMetadata(title, artist) != null
    }

    suspend fun downloadAndSave(
        url: String,
        taskId: String,
        processId: String = taskId,
        onTitleRetrieved: (String) -> Unit = {},
        onProgress: (Float) -> Unit
    ) = withContext(Dispatchers.IO) {
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

        // Use the cancellable info fetch (processId-aware) so that a batch
        // cancel can interrupt this phase too, not just the download phase.
        val info = try { downloadManager.getVideoInfo(finalUrl, processId) }
        catch (e: com.yausername.youtubedl_android.YoutubeDL.CanceledException) { throw e }
        catch (_: Exception) { null }

        // Deeper check: see if we already have this song by its unique provider ID (e.g. YouTube video ID)
        val infoId = info?.id
        if (infoId != null && songDao.getSongById(infoId) != null) {
            throw Exception("This song is already in your library")
        }

        info?.title?.let { onTitleRetrieved(it) }

        val file = downloadManager.downloadSong(finalUrl, taskId, processId) { p, _ -> onProgress(p) }

        songDao.insertSong(SongEntity(
            id           = info?.id ?: System.currentTimeMillis().toString(),
            title        = info?.title ?: file.nameWithoutExtension,
            artist       = metaArtist ?: info?.uploader ?: "Unknown Artist",
            filePath     = file.absolutePath,
            duration     = info?.duration?.times(1000L) ?: 0L,
            thumbnailUrl = metaThumbnail ?: info?.thumbnail,
            sourceUrl    = url,
            sortOrder    = Int.MAX_VALUE
        ))
    }

    // ── Playlist import ────────────────────────────────────────────────────

    /** Fetch the list of entries in a YouTube playlist (no media downloaded). */
    suspend fun fetchPlaylistEntries(playlistUrl: String): List<PlaylistEntry> =
        downloadManager.fetchPlaylistEntries(playlistUrl)

    /**
     * Write the fetched playlist entries to a plain-text file, one URL per line,
     * in playlist order. The file is placed in the app's external downloads
     * directory so it can be shared via the existing FileProvider.
     *
     * Format: one canonical
     * `https://www.youtube.com/watch?v=<id>&list=<listId>&index=<n>` URL per
     * line. This is intentionally compatible with the existing
     * [com.Music.MainViewModel.downloadSong] parser, which splits pasted text on
     * `[\n,]` — so a user can later paste the file's contents back into the
     * Download tab to re-import the same songs.
     */
    suspend fun saveLinksToFile(
        entries: List<PlaylistEntry>,
        playlistUrl: String
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.getExternalFilesDir(null), "downloads").also { it.mkdirs() }
        val safeName = extractPlaylistId(playlistUrl)?.take(20)?.replace(Regex("[^A-Za-z0-9_-]"), "_")
            ?: "playlist"
        val file = File(dir, "${safeName}_links.txt")
        file.writeText(entries.joinToString("\n") { it.url })
        file
    }

    /**
     * Export every downloaded song's [SongEntity.sourceUrl] to a plain-text
     * file, one URL per line, in library order. Only songs whose sourceUrl
     * looks like a remote URL (starts with "http") are included — local
     * imports (file/folder) have no shareable link and are skipped.
     *
     * The resulting file is written to the app's external downloads directory
     * so it can be shared via the existing FileProvider, and is in the exact
     * same one-URL-per-line format that [com.Music.MainViewModel.downloadSong]
     * and the playlist batch downloader already understand — so importing it
     * on another phone reproduces the same library.
     *
     * @return the written [File], or null if there were no exportable links.
     */
    suspend fun exportLibraryLinks(): File? = withContext(Dispatchers.IO) {
        val songs = songDao.getAllSongsOnce()
        val links = songs.asSequence()
            .map { it.sourceUrl }
            .filter { it.isNotBlank() && it.startsWith("http") }
            .distinct()
            .toList()
        if (links.isEmpty()) return@withContext null

        val dir = File(context.getExternalFilesDir(null), "downloads").also { it.mkdirs() }
        val stamp = System.currentTimeMillis()
        val file = File(dir, "muse_library_$stamp.txt")
        file.writeText(links.joinToString("\n"))
        file
    }

    /**
     * Resolve a single song to a canonical YouTube watch URL suitable for
     * sharing as a link instead of the media file.
     *
     * Resolution rules:
     *  - Local imports (`sourceUrl` not starting with "http") have no link → null.
     *  - YouTube URLs (youtube.com / youtu.be / music.youtube.com) are returned
     *    as-is so the recipient gets the exact page the song came from.
     *  - Everything else (Spotify, Apple Music, …) is resolved through Odesli
     *    (`api.song.link`) to its `youtube` platform link, mirroring what
     *    [downloadAndSave] does at download time. If Odesli fails or has no
     *    YouTube entry, null is returned so the caller can skip the song.
     *
     * Best-effort: any network/parse error returns null instead of throwing.
     */
    suspend fun resolveYouTubeLink(song: SongEntity): String? =
        resolveYouTubeLink(song.sourceUrl)

    /** Per-song variant of [resolveYouTubeLink] operating on a raw URL. */
    suspend fun resolveYouTubeLink(sourceUrl: String): String? =
        withContext(Dispatchers.IO) {
            if (sourceUrl.isBlank() || !sourceUrl.startsWith("http")) return@withContext null
            if (isYouTubeUrl(sourceUrl)) return@withContext sourceUrl
            try {
                val resp = odesliService.getLinks(sourceUrl)
                resp.linksByPlatform["youtube"]?.url
            } catch (_: Exception) {
                null
            }
        }

    /**
     * Resolve a batch of songs to YouTube links in parallel. Songs that yield
     * no link (local imports, Odesli miss) are silently dropped. Order is
     * preserved for the ones that resolve. De-duplicated so a duplicate source
     * URL doesn't appear twice in the shared text.
     */
    suspend fun resolveYouTubeLinks(songs: List<SongEntity>): List<String> =
        coroutineScope {
            val resolved = songs.map { async(Dispatchers.IO) { resolveYouTubeLink(it) } }.awaitAll()
            resolved.filterNotNull().distinct()
        }

    private fun isYouTubeUrl(url: String): Boolean {
        val host = try {
            android.net.Uri.parse(url).host?.lowercase() ?: return false
        } catch (_: Exception) {
            return false
        }
        return host == "youtube.com" || host == "www.youtube.com" ||
            host == "m.youtube.com" || host == "music.youtube.com" ||
            host == "youtu.be"
    }

    /**
     * Bundle the given songs' media files into a single ZIP archive named
     * "muse_share.zip" in the app's external downloads directory, so it can be
     * shared via the existing FileProvider. Files that no longer exist on disk
     * are silently skipped. Entry names are sanitized to "Artist - Title.ext"
     * (falling back to the original filename) and de-duplicated so two songs
     * with the same name don't clobber each other inside the archive.
     *
     * @return the written [File], or null if none of the songs had an
     *         existing media file to include.
     */
    suspend fun zipSongs(songs: List<SongEntity>): File? = withContext(Dispatchers.IO) {
        val toZip = songs.filter { File(it.filePath).exists() }
        if (toZip.isEmpty()) return@withContext null

        val dir = File(context.getExternalFilesDir(null), "downloads").also { it.mkdirs() }
        val zipFile = File(dir, "muse_share.zip")
        if (zipFile.exists()) zipFile.delete()

        val usedNames = mutableSetOf<String>()
        java.util.zip.ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
            toZip.forEach { song ->
                val src = File(song.filePath)
                val ext = src.extension.ifBlank { "mp3" }
                val base = "${song.artist} - ${song.title}"
                    .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    .trim()
                    .ifBlank { src.nameWithoutExtension }
                var name = "$base.$ext"
                var n = 1
                while (!usedNames.add(name)) {
                    name = "$base ($n).$ext"
                    n++
                }
                zos.putNextEntry(java.util.zip.ZipEntry(name))
                src.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        zipFile
    }

    /**
     * Read a links file (one URL per line, also tolerating comma-separated
     * values) picked by the user via a document-open intent, and return the
     * list of non-blank URLs in file order. This is the import counterpart to
     * [exportLibraryLinks] and to [saveLinksToFile]; the returned list can be
     * fed directly into the batch downloader.
     *
     * @return the parsed URLs, or an empty list if the file could not be read
     *         or contained no usable links.
     */
    suspend fun importLinksFromFile(uri: Uri): List<String> = withContext(Dispatchers.IO) {
        try {
            val text = context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader().readText()
            } ?: return@withContext emptyList()
            text.split(Regex("[\\n,]"))
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() && it.startsWith("http") }
                .distinct()
                .toList()
        } catch (e: Exception) {
            Log.e("MusicRepository", "importLinksFromFile failed", e)
            emptyList()
        }
    }

    /** Best-effort cancellation of a running download by its processId. */
    fun cancelDownload(processId: String) {
        downloadManager.cancelDownload(processId)
    }

    private fun extractPlaylistId(url: String): String? {
        val marker = "list="
        val idx = url.indexOf(marker)
        if (idx < 0) return null
        val rest = url.substring(idx + marker.length)
        val end = rest.indexOfFirst { it == '&' || it == '#' || it == '?' }
        return if (end < 0) rest else rest.substring(0, end)
    }

    suspend fun importFromUri(uri: Uri): String? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)

            var fileName = ""
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    fileName = cursor.getString(nameIndex)
                }
            }
            if (fileName.isBlank()) {
                fileName = uri.lastPathSegment ?: "unknown"
            }

            // Try embedded metadata title first
            val embeddedTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() }

            val cleanFileName = fileName
                .substringAfterLast("/")
                .substringAfterLast(":")
                .substringBeforeLast(".")
                .trim()

            val title  = embeddedTitle
                ?: cleanFileName.takeIf { it.isNotBlank() }
                ?: "Unknown Title"
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf { it.isNotBlank() } ?: "Unknown Artist"
            
            if (isLocalSongImported(title, artist)) {
                return@withContext "duplicate"
            }

            val durMs  = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            
            val ext = fileName.substringAfterLast(".", "mp3").lowercase()

            val destDir  = File(context.getExternalFilesDir(null), "imported").also { it.mkdirs() }
            val destFile = File(destDir, "${System.currentTimeMillis()}.$ext")
            context.contentResolver.openInputStream(uri)?.use { it.copyTo(destFile.outputStream()) }
                ?: throw Exception("Cannot open file")

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
            null
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
                if (!mime.startsWith("audio/") && !mime.startsWith("video/")) continue
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

    suspend fun updatePlaylistSongOrder(playlistId: Long, songs: List<SongEntity>) = withContext(Dispatchers.IO) {
        songs.forEachIndexed { index, song ->
            playlistDao.updateSongPosition(playlistId, song.id, index)
        }
    }

    // ── Last played song ───────────────────────────────────────────────────
    fun saveLastPlayed(songId: String, position: Long) {
        prefs.edit()
            .putString("last_song_id", songId)
            .putLong("last_position", position)
            .apply()
    }

    fun getLastPlayedSongId(): String? = prefs.getString("last_song_id", null)
    fun getLastPlayedPosition(): Long = prefs.getLong("last_position", 0L)
    suspend fun getSongById(id: String) = songDao.getSongById(id)
}
