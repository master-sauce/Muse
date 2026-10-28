package com.Music.data.storage

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.Music.data.local.PlaylistDao
import com.Music.data.local.PlaylistEntity
import com.Music.data.local.PlaylistSongCrossRef
import com.Music.data.local.SongDao
import com.Music.data.local.SongEntity
import com.Music.player.MigrationService
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** What a folder → library sync changed, for the "Rescan" toast. */
data class SyncResult(
    val added: Int = 0,
    val removed: Int = 0,
    val updated: Int = 0,
    val migrated: Int = 0,
    val playlistsCreated: Int = 0,
    val songsRestored: Int = 0
) {
    val changed: Boolean
        get() = added + removed + updated + migrated + playlistsCreated + songsRestored > 0
}

/** Outcome of a manual playlist restore, surfaced in the toast. */
data class RepairResult(
    val sidecarsRead: Int = 0,
    val recordedLinks: Int = 0,
    val restored: Int = 0,
    val playlistsTouched: Int = 0,
    val playlistsCreated: Int = 0,
    val unknownPlaylists: Set<String> = emptySet()
)

/**
 * Two-way mirror between the Room library and the user-visible folder.
 *
 * Push: every song/mutation writes a `<song>.json` sidecar and every playlist
 * writes `Playlists/<name>.m3u8`, so the folder is self-describing.
 *
 * Pull: [syncFromDisk] re-reads the folder — files the user deleted disappear
 * from the library, files they added show up, and sidecar/m3u edits (title,
 * artist, membership, order) override the DB. That is the whole point of
 * keeping the library outside the app sandbox.
 */
class LibrarySync(
    private val songDao: SongDao,
    private val playlistDao: PlaylistDao,
    private val context: Context
) {
    companion object {
        private const val TAG = "LibrarySync"
        private const val SIDECAR_VERSION = 1
        private val ILLEGAL = Regex("[\\\\/:*?\"<>|\\r\\n\\t]")
        private val ID_SUFFIX = Regex("\\[([A-Za-z0-9_-]+)]$")
        private const val PREF_EXPORTED_PLAYLISTS = "exported_playlists"

        /** Playlist names the user deleted, so a restore never resurrects them. */
        private const val PREF_DELETED_PLAYLISTS = "deleted_playlists"

        /**
         * Set by the notification's Cancel action (via [MigrationService]).
         * Checked between songs, so cancelling never leaves a half-written
         * row — the pass just stops and resumes on the next sync.
         */
        @Volatile
        private var cancelRequested = false

        fun requestCancel() {
            cancelRequested = true
        }
    }

    // ── Sync: folder → DB ───────────────────────────────────────────────────

    /** Serializes syncs: startup sync and a user "Rescan" could otherwise race
     *  each other and interleave playlist membership rewrites. */
    private val syncMutex = Mutex()

    private val notifications by lazy { MigrationNotifications(context) }

    /** Set while a sync is showing progress; playlist passes call it per step. */
    private var progressTick: ((String?) -> Unit)? = null

    @Volatile
    private var migrationServiceRunning = false

    suspend fun syncFromDisk(
        showProgress: Boolean = false,
        forcePlaylistRestore: Boolean = false
    ): SyncResult = syncMutex.withLock {
        syncFromDiskLocked(showProgress, forcePlaylistRestore)
    }

    // ── App-scoped sync requests ────────────────────────────────────────────
    // The sync must outlive the Activity (the user closes the app while a
    // 1300-song pass runs), so it is launched from a scope owned by this
    // long-lived instance instead of viewModelScope.

    private val syncScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    private val _lastSyncResult = kotlinx.coroutines.flow.MutableStateFlow<SyncResult?>(null)
    val lastSyncResult: kotlinx.coroutines.flow.StateFlow<SyncResult?> = _lastSyncResult

    val isSyncRunning = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** Fire-and-forget sync that survives the caller's lifecycle. */
    fun requestSync(showProgress: Boolean = true, forcePlaylistRestore: Boolean = false) {
        if (isSyncRunning.value) return
        isSyncRunning.value = true
        syncScope.launch {
            try {
                _lastSyncResult.value = syncFromDisk(showProgress, forcePlaylistRestore)
            } catch (_: Throwable) {
                _lastSyncResult.value = SyncResult()
            } finally {
                isSyncRunning.value = false
            }
        }
    }

    fun clearLastSyncResult() {
        _lastSyncResult.value = null
    }

    private suspend fun syncFromDiskLocked(showProgress: Boolean = false, forcePlaylistRestore: Boolean = false): SyncResult {
        cancelRequested = false
        SongMedia.clearPresenceCache()
        // Pick up files the user added/renamed/removed outside the app.
        MuseStorage.invalidateIndex()
        if (!MuseStorage.hasAccess()) return SyncResult()
        val docs = MuseStorage.listAudio()
        val dbSongs = songDao.getAllSongsOnce()
        if (docs.isEmpty() && dbSongs.any { SongMedia.isContent(it) }) {
            // Provider hiccup / permission revoked mid-flight — never treat an
            // empty listing as "user deleted everything".
            return SyncResult()
        }

        var added = 0
        var updated = 0
        var playlistsCreated = 0
        var songsRestored = 0
        // Songs the user still has on disk, by library id.
        val alive = mutableSetOf<String>()

        // Progress = work units of this pass: songs to move out of private
        // storage + files to inspect in the folder + playlist files/records to
        // re-check. The playlist work is why a reinstall still shows progress
        // even when every song is already in the folder.
        val legacySongs = dbSongs.filter { !SongMedia.isContent(it) && File(it.filePath).exists() }
        val playlistFileCount = MuseStorage.listPlaylistFiles().size
        val dbPlaylistCount = playlistDao.getAllPlaylistsOnce().size
        // Per playlist: repair pass + m3u reconcile/export.
        val playlistWork = playlistFileCount + dbPlaylistCount * 2
        val total = legacySongs.size + docs.size + playlistWork
        val progressShown = total > 0 && (showProgress || legacySongs.isNotEmpty())

        var completed = 0
        if (progressShown) startMigration(total)

        fun advance(title: String?, phase: String? = "Moving songs") {
            completed++
            if (progressShown) {
                notifications.update(completed, total, title, cancelRequested, phase)
            }
        }

        // Lets playlist-side passes report their own steps without threading a
        // lambda through every helper.
        progressTick = { title -> advance(title, "Checking playlists") }

        try {
            // Pass 1: legacy rows stored in app-private storage move into the folder.
            var migrated = 0
            for (song in legacySongs) {
                if (cancelRequested) break
                val src = File(song.filePath)
                if (!src.exists()) continue
                val displayName = buildFileName(song.artist, song.title, song.id, src.extension.ifBlank { "mp3" })
                val uri = MuseStorage.importFile(src, displayName)
                if (uri == null) {
                    advance(song.title)
                    continue
                }
                val moved = song.copy(filePath = uri.toString())
                songDao.updateSong(moved)
                src.delete()
                alive.add(moved.id)
                writeSongSidecar(moved)
                migrated++
                advance(song.title)
            }

            // Pass 2: adopt everything that lives in the folder.
            if (!cancelRequested) {
                val byId = songDao.getAllSongsOnce().associateBy { it.id }
                var nextOrder = songDao.maxSortOrder() + 1
                for (rawDoc in docs) {
                    if (cancelRequested) break
                    // Repairs names written by older builds (provider had appended
                    // a second extension: "Song.mp4.m4a" + sidecar "Song.mp4.json").
                    val rawName = rawDoc.name ?: continue
                    normalizeSidecar(rawName)
                    val doc = normalizeAudioName(rawDoc)
                    val name = doc.name ?: rawName
                    val uri = doc.uri
                    val sidecar = readSidecar(name)
                    val id = sidecar?.optString("id")?.takeIf { it.isNotBlank() }
                        ?: idFromFileName(name)
                        ?: "local_${name.hashCode().toUInt()}"

                    val existing = byId[id]
                    if (existing != null) {
                        val merged = merge(existing, sidecar, uri)
                        if (merged != existing) {
                            // @Update, not insertSong: REPLACE would cascade-delete
                            // the song's playlist memberships.
                            songDao.updateSong(merged)
                            updated++
                        }
                        alive.add(id)
                    } else {
                        val fresh = songFromDisk(doc, name, id, sidecar, nextOrder++)
                        songDao.insertSong(fresh)
                        alive.add(id)
                        added++
                    }
                    advance(existing?.title ?: baseNameWithoutTags(name), "Reading folder")
                }
            }

            // Pass 3: DB rows whose file vanished from the folder are gone. A row
            // whose file still exists somewhere else (the user switched folders)
            // is moved into the current one instead of being dropped.
            var removed = 0
            val stale = mutableListOf<SongEntity>()
            if (!cancelRequested) {
                for (song in songDao.getAllSongsOnce()) {
                    if (!SongMedia.isContent(song) || song.id in alive) continue
                    val name = SongMedia.displayName(song)
                    val copied = name?.let { MuseStorage.importFromUri(SongMedia.uri(song), it) }
                    if (copied != null) {
                        songDao.updateSong(song.copy(filePath = copied.toString()))
                        alive.add(song.id)
                        migrated++
                    } else if (MuseStorage.isInsideCurrentFolder(SongMedia.uri(song))) {
                        // File really is gone from the current folder → delete.
                        stale.add(song)
                    }
                    // Otherwise the row points at a previous folder we can't read
                    // right now (revoked grant). Keep the row: deleting a song we
                    // simply couldn't copy would be data loss.
                }
            }
            if (stale.isNotEmpty()) {
                songDao.deleteSongsByIds(stale.map { it.id })
                removed = stale.size
            }

            // Pass 3b: sidecars whose audio the user deleted are stale by definition.
            // Re-list: pass 2 may have renamed files, so `docs` holds stale names.
            if (!cancelRequested) {
                val audioBases = MuseStorage.listAudio()
                    .mapNotNull { it.name?.substringBeforeLast('.') }
                    .toSet()
                for (doc in MuseStorage.list()) {
                    val name = doc.name ?: continue
                    if (!name.endsWith(".json", ignoreCase = true)) continue
                    val sidecarBase = name.dropLast(".json".length)
                    if (sidecarBase !in audioBases && sidecarBase.substringBeforeLast('.') !in audioBases) {
                        doc.delete()
                        MuseStorage.forget(name)
                    }
                }

                // Pass 4: guarantee every song has a sidecar (new files, old rows).
                for (song in songDao.getAllSongsOnce()) {
                    if (SongMedia.isContent(song) && MuseStorage.find(sidecarNameFor(song)) == null) {
                        writeSongSidecar(song)
                    }
                }

                // Pass 5: playlists from the m3u files (creates/orders them).
                if (progressShown) {
                    // Announce the phase immediately — this pass can take a while
                    // on a large library and would otherwise look frozen.
                    notifications.update(completed, total, null, cancelRequested, "Checking playlists")
                }
                syncPlaylists()

                // Pass 6: restore playlists from the per-song JSON records. On a
                // fresh install this recreates every playlist the sidecars know
                // about, even if the .m3u8 files are gone. Ignored after the
                // first successful run unless the user asks for it explicitly.
                val restored = restoreMembershipsLocked(force = forcePlaylistRestore)
                playlistsCreated = restored.playlistsCreated
                songsRestored = restored.restored
            }

            progressTick = null
            if (progressShown && !cancelRequested) {
                notifications.update(total, total, null, false, "Library checked")
            }

            return SyncResult(
                added = added,
                removed = removed,
                updated = updated,
                migrated = migrated,
                playlistsCreated = playlistsCreated,
                songsRestored = songsRestored
            )
        } finally {
            if (progressShown) stopMigration()
        }
    }

    // ── Migration notification / foreground service ─────────────────────────

    private fun startMigration(total: Int) {
        runCatching {
            val intent = Intent(context, MigrationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            migrationServiceRunning = true
        }
        notifications.update(0, total, null, false, "Starting")
    }

    private fun stopMigration() {
        if (migrationServiceRunning) {
            migrationServiceRunning = false
            runCatching {
                context.startService(
                    Intent(context, MigrationService::class.java)
                        .setAction(MigrationService.ACTION_STOP)
                )
            }
        }
        notifications.finish()
    }

    /**
     * Rebuild playlists from the per-song `.json` records.
     *
     * Every sidecar carries `"playlists": ["favorite songs", …]`, so the folder
     * alone is enough to reconstruct the whole playlist set — this is what makes
     * "fresh install + point at the backup folder" restore everything, and what
     * the **Restore from folder** button runs on demand.
     *
     * Playlists that don't exist yet are created; existing ones get their
     * missing members appended, then the `.m3u8` is rewritten.
     *
     * Reads every sidecar exactly once and inverts the mapping (playlist →
     * song ids); a playlist×song loop meant ~10⁷ provider calls.
     */
    private suspend fun restoreMembershipsLocked(force: Boolean): RepairResult {
        if (!MuseStorage.hasAccess()) return RepairResult()
        val songs = songDao.getAllSongsOnce().filter { SongMedia.isContent(it) }
        if (songs.isEmpty()) return RepairResult()

        val recordedByPlaylist = HashMap<String, MutableList<String>>()
        var sidecarsRead = 0
        var recordedLinks = 0
        for (song in songs) {
            val text = MuseStorage.readText(sidecarNameFor(song)) ?: continue
            sidecarsRead++
            val array = runCatching { JSONObject(text).optJSONArray("playlists") }
                .getOrNull() ?: continue
            for (i in 0 until array.length()) {
                val name = array.optString(i)
                if (name.isBlank()) continue
                recordedLinks++
                recordedByPlaylist.getOrPut(name) { mutableListOf() }.add(song.id)
            }
        }
        if (recordedByPlaylist.isEmpty()) return RepairResult(sidecarsRead = sidecarsRead)

        // Names the user deleted on purpose must never be resurrected.
        val deleted = MuseStorage.prefs()
            .getStringSet(PREF_DELETED_PLAYLISTS, emptySet()).orEmpty()
            .toMutableSet()

        var restored = 0
        var touched = 0
        var created = 0
        val existing = playlistDao.getAllPlaylistsOnce().associateBy { it.name }
        for ((name, recorded) in recordedByPlaylist) {
            progressTick?.invoke(name)
            val playlist = existing[name]
            if (playlist == null) {
                if (name in deleted) continue
                // The playlist vanished entirely (fresh install, folder copied
                // back, .m3u8 lost) → rebuild it from the JSON records.
                val id = playlistDao.createPlaylist(
                    PlaylistEntity(
                        name = name,
                        sortOrder = playlistDao.maxPlaylistSortOrder() + 1
                    )
                )
                val fresh = playlistDao.getPlaylistById(id) ?: continue
                created++
                notePlaylistCreated(name)
                recorded.forEachIndexed { index, songId ->
                    playlistDao.addSongToPlaylist(
                        PlaylistSongCrossRef(fresh.id, songId, index)
                    )
                    restored++
                }
                exportPlaylist(fresh.id)
                touched++
                continue
            }
            // Existing playlist: only the forced restore adds members back, so a
            // deliberate .m3u8 edit (removing songs) still sticks.
            if (!force) continue
            val members = playlistDao.getSongsInPlaylistOnce(playlist.id)
                .map { it.id }
                .toMutableSet()
            var position = members.size
            var changed = false
            for (songId in recorded) {
                if (!members.add(songId)) continue
                playlistDao.addSongToPlaylist(
                    PlaylistSongCrossRef(playlist.id, songId, position++)
                )
                restored++
                changed = true
            }
            if (changed) {
                exportPlaylist(playlist.id)
                touched++
            }
        }

        return RepairResult(
            sidecarsRead = sidecarsRead,
            recordedLinks = recordedLinks,
            restored = restored,
            playlistsTouched = touched,
            playlistsCreated = created
        )
    }

    /**
     * User-triggered restore ("Restore playlists from files"): ignores the
     * one-time guard. On a fresh install (no songs in the DB yet) it adopts the
     * folder first, then rebuilds the playlists, so the button works either way.
     */
    suspend fun restoreMemberships(): RepairResult = syncMutex.withLock {
        SongMedia.clearPresenceCache()
        MuseStorage.invalidateIndex()

        val hasSongs = songDao.getAllSongsOnce().any { SongMedia.isContent(it) }
        if (!hasSongs && MuseStorage.hasAccess()) {
            val sync = syncFromDiskLocked(showProgress = true, forcePlaylistRestore = true)
            return@withLock RepairResult(
                sidecarsRead = sync.added,
                restored = sync.songsRestored,
                playlistsTouched = sync.playlistsCreated,
                playlistsCreated = sync.playlistsCreated
            )
        }
        restoreMembershipsLocked(force = true)
    }

    private fun merge(
        existing: SongEntity,
        sidecar: JSONObject?,
        uri: android.net.Uri
    ): SongEntity {
        // Uri may change on rename (document ids are path based), so always
        // refresh it; metadata comes from the sidecar when the user edited it.
        var song = existing.copy(filePath = uri.toString())
        if (sidecar == null) return song
        song = song.copy(
            title = sidecar.optString("title", song.title).ifBlank { song.title },
            artist = sidecar.optString("artist", song.artist).ifBlank { song.artist },
            duration = sidecar.optLong("duration", song.duration),
            thumbnailUrl = sidecar.optString("thumbnail").takeIf { it.isNotBlank() }
                ?: song.thumbnailUrl,
            sourceUrl = sidecar.optString("sourceUrl", song.sourceUrl)
        )
        // Sidecar is authoritative for timestamps only when sensible. sortOrder
        // is deliberately NOT read back: drag-ordering is app-side state and a
        // stale sidecar would undo the user's reorder on every launch.
        val created = sidecar.optLong("createdAt", 0L)
        if (created > 0L) song = song.copy(createdAt = created)
        return song
    }

    private suspend fun songFromDisk(
        doc: DocumentFile,
        name: String,
        id: String,
        sidecar: JSONObject?,
        sortOrder: Int
    ): SongEntity {
        val created = doc.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
        if (sidecar != null) {
            return SongEntity(
                id = id,
                title = sidecar.optString("title").ifBlank { baseNameWithoutTags(name) },
                artist = sidecar.optString("artist").ifBlank { "Unknown Artist" },
                filePath = doc.uri.toString(),
                duration = sidecar.optLong("duration", 0L),
                thumbnailUrl = sidecar.optString("thumbnail").takeIf { it.isNotBlank() },
                sourceUrl = sidecar.optString("sourceUrl"),
                sortOrder = sortOrder,
                createdAt = sidecar.optLong("createdAt", created)
            )
        }
        // No sidecar (file dropped in by hand) — read the tags, then the name.
        val tags = readTags(doc.uri)
        val raw = baseNameWithoutTags(name)
        val guessedArtist = raw.substringBefore(" - ", "").trim()
        val guessedTitle = raw.substringAfter(" - ", raw).trim()
        return SongEntity(
            id = id,
            title = tags?.title?.ifBlank { null } ?: guessedTitle.ifBlank { name },
            artist = tags?.artist?.ifBlank { null }
                ?: guessedArtist.ifBlank { "Unknown Artist" },
            filePath = doc.uri.toString(),
            duration = tags?.durationMs ?: 0L,
            thumbnailUrl = null,
            sourceUrl = "",
            sortOrder = sortOrder,
            createdAt = created
        )
    }

    private data class Tags(val title: String?, val artist: String?, val durationMs: Long)

    private fun readTags(uri: android.net.Uri): Tags? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            Tags(
                title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
            )
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    // ── Playlist sync ───────────────────────────────────────────────────────

    private suspend fun syncPlaylists() {
        // The Playlists dir always exists once a folder is granted; its listing
        // is authoritative, so a deleted .m3u8 means "playlist deleted".
        // Exception: if the whole dir is missing (first run after the feature,
        // or the user removed the dir), we recreate it and keep the DB playlists.
        val dirExisted = MuseStorage.playlistDir(create = false) != null
        if (MuseStorage.playlistDir(create = true) == null) return
        val files = MuseStorage.listPlaylistFiles()
        val folderPlaylists = mutableSetOf<String>()
        val songsByName = songDao.getAllSongsOnce()
            .associateBy { SongMedia.fileName(it) }
        val songsById = songDao.getAllSongsOnce().associateBy { it.id }

        for (file in files) {
            val fileName = file.name ?: continue
            val name = fileName.substringBeforeLast('.').trim().ifBlank { "Playlist" }
            folderPlaylists.add(name)
            progressTick?.invoke(name)
            val text = MuseStorage.readText(file) ?: continue
            val entries = parseM3u(text)

            val existing = playlistDao.getPlaylistByName(name)
            val playlistId = existing?.id ?: playlistDao.createPlaylist(
                PlaylistEntity(
                    name = name,
                    sortOrder = playlistDao.maxPlaylistSortOrder() + 1
                )
            )

            val resolved = entries.mapNotNull { entry ->
                entry.songId?.takeIf { songsById.containsKey(it) }
                    ?: songsByName[entry.fileName]?.id
            }.distinct()

            // Never rebuild destructively: a truncated read (file mid-write), a
            // still-renaming audio file or a stale #EXTMUSE id would otherwise
            // erase members permanently, and the next export would write the
            // shortened list back to disk.
            if (entries.isNotEmpty() && resolved.isEmpty()) continue

            // Snapshot current membership before a file-driven removal so it
            // can be restored from Playlists/.history/<name>.m3u8.
            val current = playlistDao.getSongsInPlaylistOnce(playlistId)
            if (current.any { it.id !in resolved } && current.isNotEmpty()) {
                MuseStorage.writePlaylistHistory(fileName, renderPlaylist(name, current))
            }

            if (resolved.isEmpty()) {
                playlistDao.clearPlaylistSongs(playlistId)
            } else {
                val dropped = current.map { it.id }.filterNot { it in resolved }
                playlistDao.removeSongsNotIn(playlistId, resolved)
                // Keep the JSON records honest: a membership removed because the
                // m3u no longer lists it must disappear from the sidecar too,
                // otherwise a future restore would resurrect it.
                dropped.forEach { id ->
                    songDao.getSongById(id)?.let { writeSongSidecar(it) }
                }
                resolved.forEachIndexed { index, songId ->
                    playlistDao.addSongToPlaylist(PlaylistSongCrossRef(playlistId, songId, index))
                    playlistDao.updateSongPosition(playlistId, songId, index)
                }
            }
        }

        // Playlists that only exist in the DB yet get their m3u written out.
        for (playlist in playlistDao.getAllPlaylistsOnce()) {
            if (playlist.name !in folderPlaylists) {
                exportPlaylist(playlist.id)
                progressTick?.invoke(playlist.name)
            }
        }

        // Playlists whose file the user removed from the folder are gone too.
        if (dirExisted) {
            val known = MuseStorage.prefs().getStringSet(PREF_EXPORTED_PLAYLISTS, emptySet())
                .orEmpty()
            for (name in known - folderPlaylists) {
                val playlist = playlistDao.getPlaylistByName(name) ?: continue
                val members = playlistDao.getSongsInPlaylistOnce(playlist.id)
                playlistDao.deletePlaylist(playlist)
                notePlaylistDeleted(name)
                // Strip the name from the members' JSONs so neither the restore
                // pass nor a future install brings the playlist back.
                members.forEach { writeSongSidecar(it) }
            }
        }
        MuseStorage.prefs().edit()
            .putStringSet(
                PREF_EXPORTED_PLAYLISTS,
                playlistDao.getAllPlaylistsOnce().map { it.name }.toSet()
            )
            .apply()
    }

    private data class M3uEntry(val songId: String?, val fileName: String)

    private fun parseM3u(text: String): List<M3uEntry> {
        val out = mutableListOf<M3uEntry>()
        var pendingId: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTMUSE:") -> pendingId = line.removePrefix("#EXTMUSE:").trim()
                line.startsWith("#") -> Unit
                else -> {
                    out.add(M3uEntry(pendingId, line.substringAfterLast('/').substringAfterLast('\\')))
                    pendingId = null
                }
            }
        }
        return out
    }

    /** Remember that a playlist was deleted on purpose (see restore logic). */
    fun notePlaylistDeleted(name: String) {
        val prefs = MuseStorage.prefs()
        val deleted = prefs.getStringSet(PREF_DELETED_PLAYLISTS, emptySet()).orEmpty().toMutableSet()
        if (deleted.add(name)) {
            prefs.edit().putStringSet(PREF_DELETED_PLAYLISTS, deleted).apply()
        }
    }

    /** A (re)created playlist may be restored again later. */
    fun notePlaylistCreated(name: String) {
        val prefs = MuseStorage.prefs()
        val deleted = prefs.getStringSet(PREF_DELETED_PLAYLISTS, emptySet()).orEmpty().toMutableSet()
        if (deleted.remove(name)) {
            prefs.edit().putStringSet(PREF_DELETED_PLAYLISTS, deleted).apply()
        }
    }

    // ── Push: DB → folder ─────────────────────────────────��─────────────────

    /** Write/refresh the metadata sidecar for one song. */
    suspend fun writeSongSidecar(song: SongEntity) {
        if (!MuseStorage.hasAccess() || !SongMedia.isContent(song)) return
        val name = SongMedia.fileName(song)
        val json = JSONObject().apply {
            put("app", "Muse")
            put("version", SIDECAR_VERSION)
            put("id", song.id)
            put("title", song.title)
            put("artist", song.artist)
            put("file", name)
            put("duration", song.duration)
            put("thumbnail", song.thumbnailUrl ?: "")
            put("sourceUrl", song.sourceUrl)
            put("createdAt", song.createdAt)
            put("sortOrder", song.sortOrder)
            put("playlists", JSONArray(playlistDao.getPlaylistNamesForSong(song.id)))
        }
        val canonical = SongMedia.sidecarName(name)
        MuseStorage.writeText(canonical, json.toString(2))
        // Drop a stale `<audio>.json` variant left by an older build.
        val stray = "$name.json"
        if (stray != canonical) MuseStorage.deleteByName(stray)
    }

    /** Write `Playlists/<name>.m3u8` for one playlist. */
    suspend fun exportPlaylist(playlistId: Long, onSidecar: ((done: Int, total: Int) -> Unit)? = null) {
        if (!MuseStorage.hasAccess()) return
        val playlist = playlistDao.getPlaylistById(playlistId) ?: return
        val songs = playlistDao.getSongsInPlaylistOnce(playlistId)
        MuseStorage.writePlaylistText(m3uFileName(playlist.name), renderPlaylist(playlist.name, songs))
        rememberExported(playlist.name)
        // Membership is part of a song's metadata too, but rewriting every
        // sidecar on each playlist edit would be a lot of provider writes —
        // only touch the ones whose playlist list actually changed.
        songs.forEachIndexed { index, song ->
            val names = playlistDao.getPlaylistNamesForSong(song.id)
            if (sidecarPlaylistsDiffer(song, names)) writeSongSidecar(song)
            onSidecar?.invoke(index + 1, songs.size)
        }
    }

    /** Standard M3U8 body: `#EXTMUSE:<id>` keeps membership rename-proof. */
    private fun renderPlaylist(name: String, songs: List<SongEntity>): String {
        val sb = StringBuilder("#EXTM3U\n#MUSE-PLAYLIST:").append(name).append('\n')
        for (song in songs) {
            sb.append("#EXTINF:")
                .append(song.duration / 1000)
                .append(',')
                .append(song.artist)
                .append(" - ")
                .append(song.title)
                .append('\n')
            sb.append("#EXTMUSE:").append(song.id).append('\n')
            sb.append(SongMedia.fileName(song)).append('\n')
        }
        return sb.toString()
    }

    private fun sidecarPlaylistsDiffer(song: SongEntity, names: List<String>): Boolean {
        val text = MuseStorage.readText(SongMedia.sidecarName(SongMedia.fileName(song)))
            ?: return true
        val array = runCatching { JSONObject(text).optJSONArray("playlists") }.getOrNull()
            ?: return true
        if (array.length() != names.size) return true
        for (i in names.indices) {
            if (array.optString(i) != names[i]) return true
        }
        return false
    }

    /** Remove `Playlists/<name>.m3u8` after the playlist was deleted in-app. */
    fun deletePlaylistFile(playlistName: String) {
        if (!MuseStorage.hasAccess()) return
        MuseStorage.deletePlaylistFile(m3uFileName(playlistName))
        forgetExported(playlistName)
    }

    /** Rewrite the m3u under its new name after a rename in-app. */
    suspend fun renamePlaylistFile(oldName: String, playlistId: Long) {
        if (!MuseStorage.hasAccess()) return
        MuseStorage.deletePlaylistFile(m3uFileName(oldName))
        exportPlaylist(playlistId)
    }

    fun m3uFileName(playlistName: String): String =
        sanitize(playlistName).ifBlank { "Playlist" } + ".m3u8"

    /** Playlist names double as .m3u8 file names — keep them filesystem-safe. */
    fun sanitizePlaylistName(name: String): String =
        sanitize(name).trim().ifBlank { "Playlist" }

    // Names with an m3u the app wrote. Used to tell "user deleted the file"
    // apart from "playlist never existed in the folder yet".
    private fun rememberExported(playlistName: String) {
        val names = MuseStorage.prefs()
            .getStringSet(PREF_EXPORTED_PLAYLISTS, emptySet()).orEmpty().toMutableSet()
        if (names.add(playlistName)) {
            MuseStorage.prefs().edit().putStringSet(PREF_EXPORTED_PLAYLISTS, names).apply()
        }
    }

    private fun forgetExported(playlistName: String) {
        val names = MuseStorage.prefs()
            .getStringSet(PREF_EXPORTED_PLAYLISTS, emptySet()).orEmpty().toMutableSet()
        if (names.remove(playlistName)) {
            MuseStorage.prefs().edit().putStringSet(PREF_EXPORTED_PLAYLISTS, names).apply()
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    fun buildFileName(artist: String, title: String, id: String, ext: String): String {
        val base = sanitize("$artist - $title").trim().ifBlank { sanitize(title) }
            .ifBlank { "Song" }
            .take(120)
        return "$base [$id].${ext.ifBlank { "mp3" }}"
    }

    private fun sanitize(value: String): String = value.replace(ILLEGAL, "_")

    private fun sidecarNameFor(song: SongEntity): String =
        SongMedia.sidecarName(SongMedia.fileName(song))

    /**
     * `Song.mp4.m4a` → `Song.mp4`. Some providers append an extension from the
     * MIME type on top of the one we passed, which also leaked into sidecar
     * names ("Song.mp4.json"). Restore the name the app actually asked for.
     */
    private fun normalizeAudioName(doc: DocumentFile): DocumentFile {
        val name = doc.name ?: return doc
        val lastExt = MuseStorage.extensionOf(name)
        if (!MuseStorage.isMediaExtension(lastExt)) return doc
        val base = name.substringBeforeLast('.')
        val previousExt = MuseStorage.extensionOf(base)
        if (!MuseStorage.isMediaExtension(previousExt)) return doc

        val renamedUri = MuseStorage.renameDocument(doc.uri, base) ?: return doc
        return DocumentFile.fromSingleUri(context, renamedUri) ?: doc
    }

    /** Move a legacy/mangled `<audio>[-ext].json` sidecar onto the canonical name. */
    private fun normalizeSidecar(mediaName: String) {
        val canonical = SongMedia.sidecarName(mediaName)
        for (candidate in sidecarCandidates(mediaName)) {
            if (candidate == canonical) continue
            val doc = MuseStorage.find(candidate) ?: continue
            if (MuseStorage.find(canonical) == null) {
                MuseStorage.readText(doc)?.let { MuseStorage.writeText(canonical, it) }
            }
            doc.delete()
            MuseStorage.forget(candidate)
        }
    }

    /**
     * Every name a sidecar for [mediaName] may have had before names were
     * normalized ("Song.json", "Song.mp4.json", "Song.mp4.m4a.json").
     */
    private fun sidecarCandidates(mediaName: String): List<String> {
        val out = mutableListOf(SongMedia.sidecarName(mediaName))
        var base = mediaName.substringAfterLast('/').substringAfterLast('\\')
        while (true) {
            val ext = MuseStorage.extensionOf(base)
            if (!MuseStorage.isMediaExtension(ext)) break
            base = base.substringBeforeLast('.')
            out.add("$base.json")
        }
        out.add("$mediaName.json")
        return out.distinct()
    }

    private fun readSidecar(mediaName: String): JSONObject? {
        val text = sidecarCandidates(mediaName)
            .firstNotNullOfOrNull { MuseStorage.readText(it) }
            ?: return null
        return runCatching { JSONObject(text) }.getOrElse { _ ->
            Log.w(TAG, "Ignoring malformed sidecar for $mediaName")
            null
        }
    }

    /** `Artist - Title [abc123].m4a` → `abc123` */
    private fun idFromFileName(name: String): String? =
        ID_SUFFIX.find(name.substringBeforeLast('.', name))?.groupValues?.get(1)

    private fun baseNameWithoutTags(name: String): String =
        name.substringBeforeLast('.', name)
            .replace(Regex("\\s*\\[[A-Za-z0-9_-]+]$"), "")
            .trim()
}
