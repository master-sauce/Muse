package com.Music.data.storage

import android.net.Uri
import com.Music.data.local.SongEntity
import java.io.File
import java.io.InputStream

/**
 * Resolves a song to its actual media.
 *
 * Songs are either:
 *  · stored in the user-visible library folder → [SongEntity.filePath] holds a
 *    `content://` document URI (see [MuseStorage]);
 *  · legacy rows / imports made before the folder existed → a plain filesystem
 *    path in the app-private dir.
 *
 * Everything that touches media goes through here instead of `File(song.filePath)`
 * so both kinds keep working (and old libraries migrate on the next sync).
 */
object SongMedia {

    /**
     * Presence cache. Checking a `content://` song is a provider IPC, and the
     * UI filters the whole library on the main thread (play, queue, shuffle),
     * so cache the "yes" answers until the next folder sync / delete.
     */
    private val knownPresent = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun uri(song: SongEntity): Uri = Uri.parse(song.filePath)

    fun displayName(song: SongEntity): String? =
        if (isContent(song)) MuseStorage.displayName(uri(song))
        else File(song.filePath).name

    fun fileName(song: SongEntity): String =
        displayName(song) ?: song.id

    fun exists(song: SongEntity): Boolean {
        if (knownPresent[song.filePath] == true) return true
        val present =
            if (isContent(song)) MuseStorage.exists(uri(song))
            else File(song.filePath).exists()
        if (present) knownPresent[song.filePath] = true
        return present
    }

    fun forget(song: SongEntity) {
        knownPresent.remove(song.filePath)
    }

    /** Called after a folder sync — files may have appeared or vanished. */
    fun clearPresenceCache() {
        knownPresent.clear()
    }

    fun delete(song: SongEntity): Boolean {
        forget(song)
        val ok = if (isContent(song)) MuseStorage.delete(uri(song))
        else File(song.filePath).takeIf { it.exists() }?.delete() ?: false
        // Drop the sidecar too, so the folder doesn't collect orphan metadata.
        if (isContent(song)) {
            displayName(song)?.let { MuseStorage.deleteByName(sidecarName(it)) }
        }
        return ok
    }

    fun readStream(song: SongEntity): InputStream? =
        if (isContent(song)) MuseStorage.openStream(uri(song))
        else runCatching { File(song.filePath).inputStream() }.getOrNull()

    /** Extension of the media file (falls back to "mp3"). */
    fun extension(song: SongEntity): String {
        val name = displayName(song) ?: song.filePath
        return MuseStorage.extensionOf(name).ifBlank { "mp3" }
    }

    fun isVideo(song: SongEntity): Boolean = extension(song) in VIDEO_EXTENSIONS

    fun isContent(song: SongEntity): Boolean = song.filePath.startsWith("content://")

    /** `Artist - Title [id].ext` → `Artist - Title [id].json` */
    fun sidecarName(mediaName: String): String {
        var base = mediaName.substringAfterLast('/').substringAfterLast('\\')
        // Tolerate a name that was already derived from a sidecar.
        if (base.endsWith(".json", ignoreCase = true)) base = base.dropLast(5)
        // A provider may have appended a second media extension (Song.mp4.m4a);
        // strip every trailing media extension so the sidecar stays "Song.json".
        while (true) {
            val ext = MuseStorage.extensionOf(base)
            if (!MuseStorage.isMediaExtension(ext)) break
            val dot = base.lastIndexOf('.')
            if (dot <= 0) break
            base = base.substring(0, dot)
        }
        return "$base.json"
    }

    private val VIDEO_EXTENSIONS =
        setOf("mp4", "mkv", "webm", "avi", "mov", "3gp", "m4v")
}
