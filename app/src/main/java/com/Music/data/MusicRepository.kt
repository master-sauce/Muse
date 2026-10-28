package com.Music.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import com.Music.data.local.*
import com.Music.data.remote.PlatformsService
import com.Music.data.remote.tempRoutingToken
import com.Music.data.storage.LibrarySync
import com.Music.data.storage.MuseStorage
import com.Music.data.storage.RepairResult
import com.Music.data.storage.SongMedia
import com.Music.data.storage.SyncResult
import com.Music.downloader.DownloadManager
import com.Music.downloader.PlaylistEntry
import com.Music.downloader.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

class MusicRepository(
    private val songDao: SongDao,
    private val playlistDao: PlaylistDao,
    private val platformsService: PlatformsService,
    private val downloadManager: DownloadManager,
    private val context: Context,
    private val librarySync: LibrarySync
) {
    private val prefs = context.getSharedPreferences("muse_prefs", Context.MODE_PRIVATE)

    val allSongs: Flow<List<SongEntity>> = songDao.getAllSongs()
    val playlistsWithSongs: Flow<List<PlaylistWithSongs>> = playlistDao.getPlaylistsWithSongs()

    /** Exposed so callers can resolve cross-platform links via the resolver. */
    val platforms: PlatformsService get() = platformsService

    fun getPlaylistSongs(playlistId: Long): Flow<List<SongEntity>> =
        playlistDao.getSongsInPlaylist(playlistId)

    /** One-shot snapshot of a playlist's songs (for session restore). */
    suspend fun getPlaylistSongsOnce(playlistId: Long): List<SongEntity> =
        withContext(Dispatchers.IO) { playlistDao.getSongsInPlaylist(playlistId).first() }

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
    ): String = withContext(Dispatchers.IO) {
        var finalUrl = url
        var metaThumbnail: String? = null
        var metaArtist: String? = null

        if (url.contains("spotify.com") || url.contains("apple.com")) {
            try {
                val resp = platformsService.getLinks(url, tempRoutingToken())
                val entity = resp.entitiesByUniqueId[resp.entityUniqueId]
                resp.linksByPlatform["youtube"]?.url?.let {
                    finalUrl = it
                    metaThumbnail = entity?.thumbnailUrl
                    metaArtist = entity?.artistName
                }
            } catch (_: Exception) {
            }
        }

        // Use the cancellable info fetch (processId-aware) so that a batch
        // cancel can interrupt this phase too, not just the download phase.
        val info = try {
            downloadManager.getVideoInfo(finalUrl, processId)
        } catch (e: com.yausername.youtubedl_android.YoutubeDL.CanceledException) {
            throw e
        } catch (_: Exception) {
            null
        }

        // Deeper check: see if we already have this song by its unique provider ID (e.g. YouTube video ID)
        val infoId = info?.id
        if (infoId != null && songDao.getSongById(infoId) != null) {
            // Song already exists — return its id so the caller can still add it
            // to the chosen auto-add playlist (the addSongToPlaylist call IGNOREs
            // duplicates, so this is a no-op if it's already a member).
            return@withContext infoId
        }

        info?.title?.let { onTitleRetrieved(it) }

        val tempFile = downloadManager.downloadSong(finalUrl, taskId, processId) { p, _ -> onProgress(p) }

        val songId = info?.id ?: System.currentTimeMillis().toString()
        val title = info?.title ?: tempFile.nameWithoutExtension
        val artist = metaArtist ?: info?.uploader ?: "Unknown Artist"
        val extension = tempFile.extension.ifBlank { "mp3" }
        val displayName = librarySync.buildFileName(artist, title, songId, extension)

        // Library files go into the user-visible folder when one is granted;
        // otherwise fall back to app-private storage so downloads never fail.
        val storedUri = if (MuseStorage.hasAccess()) {
            MuseStorage.importFile(tempFile, displayName)
        } else null
        val filePath = when {
            storedUri != null -> {
                tempFile.delete()
                storedUri.toString()
            }
            else -> {
                val dir = File(context.getExternalFilesDir(null), "downloads").also { it.mkdirs() }
                val dest = File(dir, displayName)
                tempFile.copyTo(dest, overwrite = true)
                tempFile.delete()
                dest.absolutePath
            }
        }

        // Insert at the TOP of the user's custom order: bump every existing
        // song's sortOrder up by 1, then place this one at position 0 so it
        // shows first in CUSTOM mode. createdAt still drives Newest / Oldest.
        songDao.shiftAllSortOrders()
        val song = SongEntity(
            id = songId,
            title = title,
            artist = artist,
            filePath = filePath,
            duration = info?.duration?.times(1000L) ?: 0L,
            thumbnailUrl = metaThumbnail ?: info?.thumbnail,
            sourceUrl = url,
            sortOrder = 0,
            createdAt = System.currentTimeMillis()
        )
        songDao.insertSong(song)
        librarySync.writeSongSidecar(song)
        songId
    }

    // ── Library folder (user-visible Download/Muse) ─────────────────────────

    suspend fun syncLibraryFromDisk(
        showProgress: Boolean = false,
        forcePlaylistRestore: Boolean = false
    ): SyncResult = withContext(Dispatchers.IO) {
        librarySync.syncFromDisk(showProgress, forcePlaylistRestore)
    }

    /** Rebuild playlists from the `.json` records (manual "Restore from folder"). */
    suspend fun restorePlaylistsFromFolder(): RepairResult = withContext(Dispatchers.IO) {
        librarySync.restoreMemberships()
    }

    /** App-scoped sync: keeps running with the app closed (foreground service). */
    fun requestLibrarySync(forcePlaylistRestore: Boolean = false) {
        librarySync.requestSync(showProgress = true, forcePlaylistRestore = forcePlaylistRestore)
    }

    val lastSyncResult: kotlinx.coroutines.flow.StateFlow<SyncResult?> = librarySync.lastSyncResult
    val isLibrarySyncRunning: kotlinx.coroutines.flow.StateFlow<Boolean> = librarySync.isSyncRunning

    fun clearLastSyncResult() = librarySync.clearLastSyncResult()

    /**
     * Songs that are still living in app-private storage instead of the library
     * folder. Cheap (DB + File.exists, no SAF scan) so the UI can show a count
     * without paying for a full sync.
     */
    suspend fun countSongsAwaitingMigration(): Int = withContext(Dispatchers.IO) {
        songDao.getAllSongsOnce().count { song ->
            !song.filePath.startsWith("content://") && File(song.filePath).exists()
        }
    }

    /** Current library folder name for the UI ("Muse" until one is granted). */
    fun storageFolderName(): String = MuseStorage.folderName()

    fun hasStorageAccess(): Boolean = MuseStorage.hasAccess()

    // ── Playlist import ────────────────────────────────────────────────────

    /** Fetch the list of entries in a YouTube playlist (no media downloaded). */
    suspend fun fetchPlaylistEntries(playlistUrl: String): List<PlaylistEntry> =
        downloadManager.fetchPlaylistEntries(playlistUrl)

    /** Search YouTube for [query] and return flat results (no media downloaded). */
    suspend fun searchYouTube(query: String): List<SearchResult> =
        downloadManager.searchYouTube(query)

    /**
     * Download only the first 30s of [url]'s audio to a temp file for the
     * search-screen preview player. The clip goes to cacheDir/previews (tmp),
     * never the library. Cancellable via [processId].
     */
    suspend fun downloadPreviewClip(url: String, processId: String): java.io.File =
        downloadManager.downloadPreviewClip(url, processId)

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
        val toZip = songs.filter { SongMedia.exists(it) }
        if (toZip.isEmpty()) return@withContext null

        val dir = File(context.getExternalFilesDir(null), "downloads").also { it.mkdirs() }
        val zipFile = File(dir, "muse_share.zip")
        if (zipFile.exists()) zipFile.delete()

        val usedNames = mutableSetOf<String>()
        java.util.zip.ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
            toZip.forEach { song ->
                val ext = SongMedia.extension(song)
                val base = "${song.artist} - ${song.title}"
                    .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    .trim()
                    .ifBlank { SongMedia.fileName(song).substringBeforeLast('.') }
                var name = "$base.$ext"
                var n = 1
                while (!usedNames.add(name)) {
                    name = "$base ($n).$ext"
                    n++
                }
                val stream = SongMedia.readStream(song) ?: return@forEach
                zos.putNextEntry(java.util.zip.ZipEntry(name))
                stream.use { it.copyTo(zos) }
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

            val title = embeddedTitle
                ?: cleanFileName.takeIf { it.isNotBlank() }
                ?: "Unknown Title"
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf { it.isNotBlank() } ?: "Unknown Artist"

            if (isLocalSongImported(title, artist)) {
                return@withContext "duplicate"
            }

            val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L

            val ext = fileName.substringAfterLast(".", "mp3").lowercase()

            // Stage the picked file in cache, then hand it to the library
            // folder under a readable name (falls back to app-private storage
            // when the user hasn't granted a folder yet).
            val stagedDir = File(context.cacheDir, "import_tmp").also { it.mkdirs() }
            val staged = File(stagedDir, "${System.currentTimeMillis()}.$ext")
            context.contentResolver.openInputStream(uri)?.use { it.copyTo(staged.outputStream()) }
                ?: throw Exception("Cannot open file")

            val songId = "local_${System.currentTimeMillis()}"
            val displayName = librarySync.buildFileName(artist, title, songId, ext)
            val storedUri = if (MuseStorage.hasAccess()) {
                MuseStorage.importFile(staged, displayName)
            } else null
            val filePath = if (storedUri != null) {
                staged.delete()
                storedUri.toString()
            } else {
                val destDir = File(context.getExternalFilesDir(null), "imported").also { it.mkdirs() }
                val dest = File(destDir, displayName)
                staged.copyTo(dest, overwrite = true)
                staged.delete()
                dest.absolutePath
            }

            // Insert at the TOP of the user's custom order (see downloadAndSave).
            songDao.shiftAllSortOrders()
            val song = SongEntity(
                id = songId,
                title = title,
                artist = artist,
                filePath = filePath,
                duration = durMs,
                thumbnailUrl = null,
                sourceUrl = uri.toString(),
                sortOrder = 0,
                createdAt = System.currentTimeMillis()
            )
            songDao.insertSong(song)
            librarySync.writeSongSidecar(song)
            null
        } finally {
            retriever.release()
        }
    }

    suspend fun importFromFolder(treeUri: Uri) = withContext(Dispatchers.IO) {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
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
                    treeUri, cursor.getString(0)
                )
                try {
                    importFromUri(fileUri)
                } catch (e: Exception) {
                    Log.w("MusicRepo", "Skipped: ${e.message}")
                }
            }
        }
    }

    suspend fun updateSortOrder(id: String, order: Int) = withContext(Dispatchers.IO) {
        songDao.updateSortOrder(id, order)
    }

    /**
     * Mark [songId] as freshly added by stamping its `createdAt` with the
     * current wall-clock time, so it lands at the top in Newest sort mode on
     * both the Library and Playlist screens.
     *
     * Used after auto-adding a song to a playlist: when re-importing a
     * playlist (or re-downloading a link) that contains a song already in the
     * library, the duplicate-detection paths return the *existing* song id
     * without re-inserting it — so its `createdAt` stays at the original add
     * time and the song appears below newer songs in Newest sort, even though
     * the user just (re)added it. Touching `createdAt` here makes that re-add
     * behave like a fresh add for time-based ordering. The user's manual
     * custom drag order (`sortOrder`) is deliberately left untouched.
     */
    suspend fun touchSong(songId: String) = withContext(Dispatchers.IO) {
        songDao.touchCreatedAt(songId, System.currentTimeMillis())
        songDao.getSongById(songId)?.let { librarySync.writeSongSidecar(it) }
    }

    suspend fun deleteSong(song: SongEntity) = withContext(Dispatchers.IO) {
        // Remember which playlists contained it so their .m3u8 files can drop
        // the entry after the row (and its cross-refs) are gone.
        val affected = playlistDao.getPlaylistIdsForSong(song.id)
        SongMedia.delete(song)
        songDao.deleteSong(song)
        affected.forEach { librarySync.exportPlaylist(it) }
    }

    suspend fun deleteSongs(
        ids: Set<String>,
        onStep: (phase: String, done: Int, total: Int) -> Unit = { _, _, _ -> }
    ) = withContext(Dispatchers.IO) {
        val affected = ids.flatMap { playlistDao.getPlaylistIdsForSong(it) }.toSet()
        ids.forEachIndexed { index, id ->
            songDao.getSongById(id)?.let { SongMedia.delete(it) }
            onStep("Deleting songs", index + 1, ids.size)
        }
        songDao.deleteSongsByIds(ids.toList())
        affected.forEach { librarySync.exportPlaylist(it) }
    }

    // ── Playlist ops ────────────────────────────────────────────────────────
    // Every mutation is mirrored to `Playlists/<name>.m3u8` inside the library
    // folder, so playlists are editable outside the app too.
    suspend fun createPlaylist(name: String): Long = withContext(Dispatchers.IO) {
        // Insert at the TOP of the Playlists list: bump every existing
        // playlist's sortOrder up by one, then place this one at position 0 so
        // a freshly created playlist shows first in the Playlists tab (mirrors
        // how new songs land at the top of the library). createdAt still
        // records the real creation time for any future ordering needs.
        playlistDao.shiftAllPlaylistOrders()
        val id = playlistDao.createPlaylist(
            PlaylistEntity(name = librarySync.sanitizePlaylistName(name), sortOrder = 0)
        )
        librarySync.notePlaylistCreated(librarySync.sanitizePlaylistName(name))
        librarySync.exportPlaylist(id)
        id
    }

    suspend fun deletePlaylist(playlist: PlaylistEntity) = withContext(Dispatchers.IO) {
        val members = playlistDao.getSongsInPlaylistOnce(playlist.id)
        playlistDao.deletePlaylist(playlist)
        librarySync.deletePlaylistFile(playlist.name)
        librarySync.notePlaylistDeleted(playlist.name)
        // Strip the name from the members' JSON records too, otherwise a later
        // restore would resurrect the deleted playlist.
        members.forEach { librarySync.writeSongSidecar(it) }
    }

    suspend fun renamePlaylist(id: Long, name: String) = withContext(Dispatchers.IO) {
        val oldName = playlistDao.getPlaylistById(id)?.name
        val safeName = librarySync.sanitizePlaylistName(name)
        playlistDao.renamePlaylist(id, safeName)
        if (oldName != null && oldName != safeName) librarySync.renamePlaylistFile(oldName, id)
    }

    suspend fun addSongToPlaylist(playlistId: Long, songId: String) = withContext(Dispatchers.IO) {
        // If the song is already a member, leave it where it is (the CrossRef
        // insert IGNOREs on conflict, so a duplicate add is a no-op).
        if (playlistDao.getSongPosition(playlistId, songId) != null) return@withContext
        // Always insert at position 0 (top of the playlist) by shifting every
        // existing member down by one. This makes newly added songs land at the
        // top by default.
        playlistDao.shiftPlaylistPositions(playlistId, 0)
        playlistDao.addSongToPlaylist(PlaylistSongCrossRef(playlistId, songId, 0))
        librarySync.exportPlaylist(playlistId)
    }

    suspend fun removeSongFromPlaylist(playlistId: Long, songId: String) = withContext(Dispatchers.IO) {
        playlistDao.removeSongFromPlaylist(playlistId, songId)
        librarySync.exportPlaylist(playlistId)
        songDao.getSongById(songId)?.let { librarySync.writeSongSidecar(it) }
    }

    /**
     * Bulk add (selection → playlist). Inserts every new member in one go and
     * exports the playlist a single time — the per-song path rewrites the whole
     * `.m3u8` and re-checks every member's sidecar, which turns "add all 1300"
     * into an O(n²) SAF nightmare. [onStep] drives the progress notification.
     */
    suspend fun addSongsToPlaylist(
        playlistId: Long,
        songIds: Collection<String>,
        onStep: (phase: String, done: Int, total: Int) -> Unit = { _, _, _ -> }
    ) = withContext(Dispatchers.IO) {
        if (songIds.isEmpty()) return@withContext
        val existing = playlistDao.getSongsInPlaylistOnce(playlistId).map { it.id }.toSet()
        val toAdd = songIds.filterNot { it in existing }.distinct()
        if (toAdd.isEmpty()) return@withContext
        val membersAfter = existing.size + toAdd.size
        val total = toAdd.size + membersAfter

        onStep("Adding to playlist", 0, total)
        playlistDao.shiftPlaylistPositionsBy(playlistId, toAdd.size, 0)
        playlistDao.addSongsToPlaylist(
            toAdd.mapIndexed { index, songId -> PlaylistSongCrossRef(playlistId, songId, index) }
        )
        onStep("Writing playlist file", toAdd.size, total)
        librarySync.exportPlaylist(playlistId) { done, _ ->
            onStep("Updating song metadata", toAdd.size + done, total)
        }
    }

    /** Bulk remove (selection → delete from playlist), one export at the end. */
    suspend fun removeSongsFromPlaylist(
        playlistId: Long,
        songIds: Collection<String>,
        onStep: (phase: String, done: Int, total: Int) -> Unit = { _, _, _ -> }
    ) = withContext(Dispatchers.IO) {
        if (songIds.isEmpty()) return@withContext
        val total = songIds.size
        onStep("Removing from playlist", 0, total)
        playlistDao.removeSongsFromPlaylist(playlistId, songIds.toList())
        librarySync.exportPlaylist(playlistId)
        songIds.forEachIndexed { index, id ->
            songDao.getSongById(id)?.let { librarySync.writeSongSidecar(it) }
            onStep("Updating song metadata", index + 1, total)
        }
    }

    suspend fun updatePlaylistSongOrder(playlistId: Long, songs: List<SongEntity>) = withContext(Dispatchers.IO) {
        songs.forEachIndexed { index, song ->
            playlistDao.updateSongPosition(playlistId, song.id, index)
        }
        librarySync.exportPlaylist(playlistId)
    }

    /**
     * Persist the user's manual drag order for the Playlists tab. Writes each
     * playlist's `sortOrder` to match its on-screen index (0 = top). Called
     * once after a drag completes (see [com.Music.MainViewModel.endPlaylistListDrag]).
     */
    suspend fun updatePlaylistListOrder(playlists: List<PlaylistEntity>) = withContext(Dispatchers.IO) {
        playlists.forEachIndexed { index, pl -> playlistDao.updatePlaylistOrder(pl.id, index) }
    }

    // ── Last played song ───────────────────────────────────────────────────
    // [playlistId] records which playlist the song was playing from (null =
    // Library / ad-hoc). On restore we rebuild the timeline from that
    // playlist's songs — not the whole library — so shuffle / next / previous
    // stay inside the playlist the user was actually listening to.
    fun saveLastPlayed(songId: String, position: Long, playlistId: Long? = null) {
        prefs.edit()
            .putString("last_song_id", songId)
            .putLong("last_position", position)
            // -1 = no playlist (library). Stored as long so null round-trips.
            .putLong("last_playlist_id", playlistId ?: -1L)
            .apply()
    }

    fun getLastPlayedSongId(): String? = prefs.getString("last_song_id", null)
    fun getLastPlayedPosition(): Long = prefs.getLong("last_position", 0L)

    /** null = was playing from Library / ad-hoc list, not a playlist. */
    fun getLastPlayedPlaylistId(): Long? =
        prefs.getLong("last_playlist_id", -1L).takeIf { it != -1L }

    // ── Shuffle preference ─────────────────────────────────────────────────
    // Persisted so the player's shuffle mode survives app restarts. Saved from
    // the player's onShuffleModeEnabledChanged listener (not from ViewModel
    // helpers) so the manual-queue shuffle suppression (which never flips the
    // player flag) can't clobber the user's real preference.
    fun saveShuffleEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("shuffle_enabled", enabled).apply()
    }

    fun getShuffleEnabled(): Boolean = prefs.getBoolean("shuffle_enabled", false)
    suspend fun getSongById(id: String) = songDao.getSongById(id)

    /** Look up a song by its source URL (null if not in the library). */
    suspend fun getSongByUrl(url: String): SongEntity? = withContext(Dispatchers.IO) {
        songDao.getSongByUrl(url)
    }
}
