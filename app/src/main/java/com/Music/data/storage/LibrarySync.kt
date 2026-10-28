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
    val migrated: Int = 0
) {
    val changed: Boolean get() = added + removed + updated + migrated > 0
}

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
        private const val PREF_MEMBERSHIP_RECOVERED = "membership_recovered_v1"

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

    suspend fun syncFromDisk(showProgress: Boolean = false): SyncResult = syncMutex.withLock {
        syncFromDiskLocked(showProgress)
    }

    private suspend fun syncFromDiskLocked(showProgress: Boolean = false): SyncResult {
        cancelRequested = false
        SongMedia.clearPresenceCache()
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
        // Songs the user still has on disk, by library id.
        val alive = mutableSetOf<String>()

        // Progress = work units of this pass: songs to move out of private
        // storage + files to inspect in the folder + playlist files/records to
        // re-check. The playlist work is why a reinstall still shows progress
        // even when every song is already in the folder.
        val legacySongs = dbSongs.filter { !SongMedia.isContent(it) && File(it.filePath).exists() }
        val playlistFileCount = MuseStorage.listPlaylistFiles().size
        val dbPlaylistCount = playlistDao.getAllPlaylistsOnce().size
        val playlistWork = playlistFileCount + dbPlaylistCount
        val membershipPending =
            !MuseStorage.prefs().getBoolean(PREF_MEMBERSHIP_RECOVERED, false)
        val total = legacySongs.size + docs.size + playlistWork
        val progressShown = total > 0 &&
            (showProgress || legacySongs.isNotEmpty() || membershipPending)

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
                    }
                }

                // Pass 4: guarantee every song has a sidecar (new files, old rows).
                for (song in songDao.getAllSongsOnce()) {
                    if (SongMedia.isContent(song) && MuseStorage.find(sidecarNameFor(song)) == null) {
                        writeSongSidecar(song)
                    }
                }

                // Pass 4b: repair playlist memberships that an older build dropped.
                recoverMembershipFromSidecars()

                // Pass 5: playlists — m3u files are the source of truth when present.
                syncPlaylists()
            }

            progressTick = null
            if (progressShown && !cancelRequested) {
                notifications.update(total, total, null, false, "Library checked")
            }

            return SyncResult(added = added, removed = removed, updated = updated, migrated = migrated)
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
     * One-shot repair for libraries damaged by the `INSERT OR REPLACE`
     * cascade bug: re-insert songs whose sidecar still lists a playlist they
     * are no longer a member of. Runs before the m3u pass and rewrites the
     * affected playlists so the file agrees with the recovery.
     */
    private suspend fun recoverMembershipFromSidecars() {
        val prefs = MuseStorage.prefs()
        if (prefs.getBoolean(PREF_MEMBERSHIP_RECOVERED, false)) return

        val songs = songDao.getAllSongsOnce().filter { SongMedia.isContent(it) }
        val playlists = playlistDao.getAllPlaylistsOnce()
        if (songs.isEmpty() || playlists.isEmpty()) return

        var sawSidecar = false
        for (playlist in playlists) {
            val members = playlistDao.getSongsInPlaylistOnce(playlist.id).map { it.id }.toMutableSet()
            var position = members.size
            var changed = false
            for (song in songs) {
                if (song.id in members) continue
                val text = MuseStorage.readText(sidecarNameFor(song)) ?: continue
                sawSidecar = true
                val listed = runCatching {
                    val array = JSONObject(text).optJSONArray("playlists") ?: return@runCatching false
                    (0 until array.length()).any { array.optString(it) == playlist.name }
                }.getOrDefault(false)
                if (!listed) continue
                playlistDao.addSongToPlaylist(
                    PlaylistSongCrossRef(playlist.id, song.id, position++)
                )
                members.add(song.id)
                changed = true
            }
            if (changed) exportPlaylist(playlist.id)
            progressTick?.invoke(playlist.name)
        }
        if (sawSidecar) prefs.edit().putBoolean(PREF_MEMBERSHIP_RECOVERED, true).apply()
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
                playlistDao.removeSongsNotIn(playlistId, resolved)
                resolved.forEachIndexed { index, songId ->
                    playlistDao.addSongToPlaylist(PlaylistSongCrossRef(playlistId, songId, index))
                    playlistDao.updateSongPosition(playlistId, songId, index)
                }
            }
            progressTick?.invoke(name)
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
                playlistDao.getPlaylistByName(name)?.let { playlistDao.deletePlaylist(it) }
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

    // ── Push: DB → folder ───────────────────────────────────────────────────

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
        if (stray != canonical) MuseStorage.find(stray)?.delete()
    }

    /** Write `Playlists/<name>.m3u8` for one playlist. */
    suspend fun exportPlaylist(playlistId: Long) {
        if (!MuseStorage.hasAccess()) return
        val playlist = playlistDao.getPlaylistById(playlistId) ?: return
        val songs = playlistDao.getSongsInPlaylistOnce(playlistId)
        MuseStorage.writePlaylistText(m3uFileName(playlist.name), renderPlaylist(playlist.name, songs))
        rememberExported(playlist.name)
        // Membership is part of a song's metadata too, but rewriting every
        // sidecar on each playlist edit would be a lot of provider writes —
        // only touch the ones whose playlist list actually changed.
        songs.forEach { song ->
            val names = playlistDao.getPlaylistNamesForSong(song.id)
            if (sidecarPlaylistsDiffer(song, names)) writeSongSidecar(song)
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
