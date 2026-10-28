package com.Music.data.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * User-owned library folder (default `Download/Muse`).
 *
 * The whole library lives here as plain files so the user can open the folder
 * with any file manager and copy / edit / tag / delete songs — the app re-syncs
 * from it (see [LibrarySync]). Access is granted once via
 * `ACTION_OPEN_DOCUMENT_TREE` and persisted, which is what gives us read/write
 * on files the user creates or edits by hand (a MediaStore-only approach would
 * not see their sidecars).
 *
 * Layout:
 * ```
 * Download/Muse/
 *   Artist - Title [songId].m4a      ← the audio
 *   Artist - Title [songId].json     ← metadata sidecar (see LibrarySync)
 *   Playlists/
 *     Chill.m3u8                     ← playlist, one file per playlist
 * ```
 */
object MuseStorage {
    const val DEFAULT_FOLDER_NAME = "Muse"
    const val PLAYLIST_DIR = "Playlists"

    /** Pre-sync playlist snapshots; kept out of the playlist scan. */
    const val HISTORY_DIR = ".history"

    private const val PREFS = "muse_prefs"
    private const val KEY_TREE_URI = "storage_tree_uri"
    private const val TAG = "MuseStorage"

    private const val MIME_JSON = "application/json"
    private const val MIME_M3U = "audio/x-mpegurl"

    private lateinit var appContext: Context

    @Volatile
    private var treeUri: Uri? = null

    @Volatile
    private var root: DocumentFile? = null

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        treeUri = prefs().getString(KEY_TREE_URI, null)
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }
    }

    fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── Access ──────────────────────────────────────────────────────────────

    /** True when the user has picked a library folder and the grant is still valid. */
    fun hasAccess(): Boolean {
        val uri = treeUri ?: return false
        return try {
            appContext.contentResolver.persistedUriPermissions.any {
                it.isReadPermission && it.uri.toString() == uri.toString()
            }
        } catch (_: Exception) {
            false
        }
    }

    /** Name of the currently granted folder, or the default name if none. */
    fun folderName(): String = runCatching { rootOrNull()?.name }.getOrNull() ?: DEFAULT_FOLDER_NAME

    fun rootUri(): Uri? = rootOrNull()?.uri

    /**
     * True when [uri] is a document inside the folder currently in use. Used to
     * tell "the user deleted this file" apart from "this row still points at a
     * previous folder we may no longer be able to read".
     */
    fun isInsideCurrentFolder(uri: Uri): Boolean {
        val root = rootOrNull()?.uri?.toString() ?: return false
        val treePrefix = root.substringBefore("/document/")
        return uri.toString().startsWith(treePrefix)
    }

    /** Persist a tree grant picked by the user. Returns false if the grant failed. */
    fun setTreeUri(uri: Uri): Boolean = try {
        appContext.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        prefs().edit().putString(KEY_TREE_URI, uri.toString()).apply()
        treeUri = uri
        root = null
        true
    } catch (e: Exception) {
        Log.e(TAG, "takePersistableUriPermission failed", e)
        false
    }

    fun clearAccess() {
        prefs().edit().remove(KEY_TREE_URI).apply()
        treeUri = null
        root = null
    }

    /**
     * Starting point for the folder picker: public `Download/`. The user lands
     * one tap away from `Download/Muse` (and can create or pick any folder).
     */
    fun defaultPickerUri(): Uri? = runCatching {
        DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents",
            "primary:Download"
        )
    }.getOrNull()

    private fun rootOrNull(): DocumentFile? {
        if (!hasAccess()) return null
        root?.let { return it }
        val dir = treeUri?.let { DocumentFile.fromTreeUri(appContext, it) } ?: return null
        root = dir
        return dir
    }

    // ── Listing ─────────────────────────────────────────────────────────────

    /**
     * Folder children indexed by display name.
     *
     * `DocumentFile.findFile()` re-queries the whole directory for every lookup;
     * with a few thousand files that turns any per-song loop into O(n²) and
     * makes the migration look hung. One listing + in-place updates instead.
     */
    private var childIndex: MutableMap<String, DocumentFile>? = null

    fun invalidateIndex() {
        childIndex = null
    }

    private fun index(): MutableMap<String, DocumentFile> {
        childIndex?.let { return it }
        val map = HashMap<String, DocumentFile>()
        runCatching { rootOrNull()?.listFiles() }.getOrNull()?.forEach { doc ->
            val name = doc.name ?: return@forEach
            map[name] = doc
        }
        childIndex = map
        return map
    }

    fun list(): List<DocumentFile> = index().values.toList()

    /** Audio/video documents directly inside the library folder. */
    fun listAudio(): List<DocumentFile> = list().filter { doc ->
        if (!doc.isFile) return@filter false
        val mime = doc.type
        if (mime != null && (mime.startsWith("audio/") || mime.startsWith("video/"))) true
        else isMediaName(doc.name)
    }

    fun find(name: String): DocumentFile? = index()[name]

    /** Drop a document from the index after it was deleted/renamed elsewhere. */
    fun forget(doc: DocumentFile) {
        val map = childIndex ?: return
        val name = doc.name
        if (name != null) map.remove(name) else forgetByUri(map, doc.uri)
    }

    fun forget(name: String) {
        childIndex?.remove(name)
    }

    private fun forgetByUri(map: MutableMap<String, DocumentFile>, uri: Uri) {
        val key = map.entries.firstOrNull { it.value.uri == uri }?.key ?: return
        map.remove(key)
    }

    /** Delete by display name, keeping the index in sync. */
    fun deleteByName(name: String): Boolean {
        val doc = find(name) ?: return false
        val ok = runCatching { doc.delete() }.getOrDefault(false)
        forget(name)
        return ok
    }

    fun playlistDir(create: Boolean): DocumentFile? {
        val dir = rootOrNull() ?: return null
        val existing = runCatching { dir.findFile(PLAYLIST_DIR) }.getOrNull()
        if (existing != null && existing.isDirectory) return existing
        if (!create) return null
        return runCatching { dir.createDirectory(PLAYLIST_DIR) }.getOrNull()
    }

    fun listPlaylistFiles(): List<DocumentFile> {
        val dir = playlistDir(create = false) ?: return emptyList()
        return runCatching { dir.listFiles().toList() }.getOrNull().orEmpty()
            .filter { it.isFile && it.name?.lowercase()?.let { n -> n.endsWith(".m3u8") || n.endsWith(".m3u") } == true }
    }

    // ── Text (sidecars / m3u) ───────────────────────────────────────────────

    fun readText(name: String): String? = find(name)?.let { readText(it) }

    fun readText(doc: DocumentFile): String? = runCatching {
        appContext.contentResolver.openInputStream(doc.uri)?.use { it.bufferedReader().readText() }
    }.getOrNull()

    /**
     * Create or overwrite `name` in the library folder.
     * Overwriting truncates through mode "wt" so no stale tail is left behind.
     */
    fun writeText(name: String, text: String, mime: String = MIME_JSON): Boolean {
        val dir = rootOrNull() ?: return false
        return runCatching {
            val existing = find(name)
            val target = existing ?: dir.createFile(mime, name) ?: return false
            appContext.contentResolver.openOutputStream(target.uri, "wt")?.use { out ->
                out.write(text.toByteArray())
            } ?: return false
            // Some providers append an extension from the MIME type; keep the
            // exact name so sidecars stay discoverable by name.
            if (target.name != name) renameDocument(target.uri, name)
            childIndex?.put(name, target)
            true
        }.getOrElse {
            Log.e(TAG, "writeText($name) failed", it)
            invalidateIndex()
            false
        }
    }

    fun writePlaylistText(fileName: String, text: String): Boolean {
        val dir = playlistDir(create = true) ?: return false
        return runCatching {
            val existing = dir.findFile(fileName)
            val target = existing ?: dir.createFile("text/plain", fileName) ?: return false
            appContext.contentResolver.openOutputStream(target.uri, "wt")?.use { out ->
                out.write(text.toByteArray())
            } ?: return false
            if (target.name != fileName) renameDocument(target.uri, fileName)
            true
        }.getOrElse {
            Log.e(TAG, "writePlaylistText($fileName) failed", it)
            false
        }
    }

    fun deletePlaylistFile(fileName: String) {
        runCatching { playlistDir(create = false)?.findFile(fileName)?.delete() }
    }

    /**
     * Snapshot a playlist into `Playlists/.history/` before a sync applies
     * file-driven removals. `.history` is a directory, so the playlist scan
     * (which is non-recursive) never picks these up as playlists.
     */
    fun writePlaylistHistory(fileName: String, text: String): Boolean {
        val dir = playlistDir(create = true) ?: return false
        return runCatching {
            val history = dir.findFile(HISTORY_DIR)?.takeIf { it.isDirectory }
                ?: dir.createDirectory(HISTORY_DIR)
                ?: return false
            val target = history.findFile(fileName)
                ?: history.createFile("text/plain", fileName)
                ?: return false
            appContext.contentResolver.openOutputStream(target.uri, "wt")?.use { out ->
                out.write(text.toByteArray())
            } ?: return false
            true
        }.getOrElse {
            Log.e(TAG, "writePlaylistHistory($fileName) failed", it)
            false
        }
    }

    // ── Media ───────────────────────────────────────────────────────────────

    /** Copy a locally downloaded/imported file into the library folder. */
    fun importFile(src: File, displayName: String): Uri? =
        importFromUri(Uri.fromFile(src), displayName)

    /** Copy any readable document (e.g. from the previous folder) into the library. */
    fun importFromUri(source: Uri, displayName: String): Uri? {
        val dir = rootOrNull() ?: return null
        return runCatching {
            // Open the source first: it may be the very document we are about
            // to replace (re-importing a file that already lives in the folder).
            val input = appContext.contentResolver.openInputStream(source) ?: return null
            // Providers append the extension themselves when the name has none;
            // we always pass a complete name, so the file lands exactly as named.
            find(displayName)?.let {
                it.delete()
                forget(displayName)
            }
            val doc = dir.createFile(mimeForName(displayName), displayName)
                ?: run { input.close(); return null }
            input.use { src ->
                appContext.contentResolver.openOutputStream(doc.uri, "wt")?.use { out ->
                    src.copyTo(out)
                } ?: throw IllegalStateException("Cannot write $displayName")
            }
            // Providers may append an extension derived from the MIME type.
            // Keep the exact name requested so sidecar/m3u references match.
            val finalUri =
                if (doc.name != displayName) renameDocument(doc.uri, displayName) ?: doc.uri
                else doc.uri
            childIndex?.put(displayName, doc)
            finalUri
        }.getOrElse {
            Log.e(TAG, "importFile($displayName) failed", it)
            invalidateIndex()
            null
        }
    }

    fun openStream(uri: Uri) = runCatching {
        appContext.contentResolver.openInputStream(uri)
    }.getOrNull()

    fun exists(uri: Uri): Boolean = runCatching {
        appContext.contentResolver.openInputStream(uri)?.use { true } ?: false
    }.getOrElse { false }

    fun delete(uri: Uri): Boolean {
        val ok = runCatching {
            DocumentsContract.deleteDocument(appContext.contentResolver, uri)
        }.getOrElse { false }
        if (ok) childIndex?.let { forgetByUri(it, uri) }
        return ok
    }

    /** Display name of a document URI (`Artist - Title [id].m4a`). */
    fun displayName(uri: Uri): String? {
        runCatching {
            appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) {
                    val name = cursor.getString(idx)
                    if (!name.isNullOrBlank()) return name
                }
            }
        }
        // Fallback: ExternalStorageProvider document ids are the real path.
        return uri.lastPathSegment
            ?.let { Uri.decode(it) }
            ?.substringAfterLast('/')
            ?.substringAfterLast(':')
            ?.takeIf { it.isNotBlank() }
    }

    fun mimeType(uri: Uri): String? = runCatching {
        appContext.contentResolver.getType(uri)
    }.getOrNull()

    // ── Naming helpers ──────────────────────────────────────────────────────

    private val MEDIA_EXTENSIONS = setOf(
        "mp3", "m4a", "aac", "opus", "ogg", "oga", "flac", "wav", "wma", "mka",
        "mp4", "mkv", "webm", "avi", "mov", "3gp", "m4v"
    )

    fun extensionOf(name: String): String =
        name.substringAfterLast('.', "").lowercase()

    fun isMediaName(name: String?): Boolean =
        name != null && extensionOf(name) in MEDIA_EXTENSIONS

    fun isMediaExtension(ext: String): Boolean = ext in MEDIA_EXTENSIONS

    /**
     * Force the provider to keep [displayName] verbatim. Some providers append
     * an extension of their own choosing from the MIME type (that is how
     * "Song.mp4" used to become "Song.mp4.m4a"); renaming straight after the
     * create pins the name we actually asked for.
     */
    fun renameDocument(uri: Uri, newName: String): Uri? {
        val map = childIndex
        val previousName = map?.entries?.firstOrNull { it.value.uri == uri }?.key
        val renamed = runCatching {
            DocumentsContract.renameDocument(appContext.contentResolver, uri, newName)
        }.getOrNull()
        // Keep the index in sync instead of dropping it: normalizing a few
        // thousand names would otherwise re-list the folder every time.
        if (renamed != null && map != null) {
            previousName?.let { map.remove(it) }
            runCatching { DocumentFile.fromSingleUri(appContext, renamed) }
                .getOrNull()
                ?.let { map[newName] = it }
        }
        return renamed
    }

    fun mimeForName(name: String): String = when (extensionOf(name)) {
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "mp4", "m4v" -> "video/mp4"
        "aac" -> "audio/aac"
        "opus", "ogg", "oga" -> "audio/ogg"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        "mka" -> "audio/x-matroska"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "3gp" -> "video/3gpp"
        else -> "application/octet-stream"
    }
}
