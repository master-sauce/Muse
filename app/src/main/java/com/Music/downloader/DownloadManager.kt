package com.Music.downloader

import android.content.Context
import org.json.JSONObject
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A single entry inside a YouTube playlist.
 *
 * [url] is always a canonical
 * `https://www.youtube.com/watch?v=<id>&list=<listId>&index=<n>` link so that
 * the `index=` query parameter is present — this is what the playlist-import
 * feature relies on to treat each line in the saved links file as its own song.
 */
data class PlaylistEntry(
    val url: String,
    val title: String,
    val index: Int
)

/**
 * A single YouTube search result.
 *
 * [url] is a canonical `https://www.youtube.com/watch?v=<id>` link, ready to be
 * pasted into the Add-Music URL field or shared. [duration] is in seconds
 * (0 when yt-dlp couldn't determine it for a flat result).
 */
data class SearchResult(
    val url: String,
    val title: String,
    val uploader: String,
    val thumbnailUrl: String?,
    val duration: Long
)

class DownloadManager(private val context: Context) {

    suspend fun downloadSong(
        url: String,
        taskId: String,
        processId: String,
        onProgress: (Float, Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val downloadDir = File(context.getExternalFilesDir(null), "downloads")
        if (!downloadDir.exists()) downloadDir.mkdirs()

        // Use the taskId in the filename to prevent race conditions during concurrent downloads
        val outputTemplate = "${downloadDir.absolutePath}/$taskId.%(ext)s"

        val request = YoutubeDLRequest(url)
        request.addOption("-f", "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best")
        request.addOption("-o", outputTemplate)
        // Crucial: when the URL contains a `list=` parameter (as our playlist
        // entries do), yt-dlp would otherwise try to download the *entire*
        // playlist for every single URL. --no-playlist forces single-video mode.
        request.addOption("--no-playlist")

        // Snapshot before download to find the specific extension
        val before = downloadDir.listFiles()?.map { it.absolutePath }?.toSet() ?: emptySet()

        YoutubeDL.getInstance().execute(request, processId) { progress, etaInSeconds, _ ->
            onProgress(progress, etaInSeconds)
        }

        val after = downloadDir.listFiles()?.map { it.absolutePath }?.toSet() ?: emptySet()

        // Find the file that starts with our taskId
        val newFilePath = after.subtract(before).find {
            File(it).name.startsWith(taskId)
        } ?: throw Exception("Downloaded file not found for task $taskId")

        File(newFilePath)
    }

    /**
     * Lightweight video info (only the fields we persist on a song).
     *
     * Returned by [getVideoInfo] for the batch playlist download path, where we
     * need a cancellable info fetch (the library's `YoutubeDL.getInfo` does not
     * accept a processId and therefore cannot be cancelled).
     */
    data class VideoInfo(
        val id: String?,
        val title: String?,
        val uploader: String?,
        val thumbnail: String?,
        val duration: Long
    )

    /**
     * Fetch metadata for a single video, cancellable via [processId].
     *
     * Uses `--no-playlist --dump-json` so that a URL containing `list=` only
     * resolves the single video (not the whole playlist) and so the call can be
     * cancelled mid-flight by [cancelDownload]. Returns null if the info cannot
     * be parsed (the caller treats null as "use fallback values").
     */
    suspend fun getVideoInfo(url: String, processId: String): VideoInfo? = withContext(Dispatchers.IO) {
        val request = YoutubeDLRequest(url)
        request.addOption("--no-playlist")
        request.addOption("--dump-json")
        request.addOption("--no-warnings")
        request.addOption("--ignore-errors")

        val response = try {
            YoutubeDL.getInstance().execute(request, processId, null)
        } catch (e: YoutubeDL.CanceledException) {
            // Propagate cancellation so the batch loop can stop cleanly.
            throw e
        } catch (e: Exception) {
            return@withContext null
        }

        try {
            val json = JSONObject(response.out)
            VideoInfo(
                id        = json.optString("id").ifBlank { null },
                title     = json.optString("title").ifBlank { null },
                uploader  = json.optString("uploader").ifBlank { null },
                thumbnail = json.optString("thumbnail").ifBlank { null },
                duration  = json.optLong("duration", 0L)
            )
        } catch (_: Exception) { null }
    }

    /**
     * Fetch the list of entries in a YouTube playlist without downloading any media.
     *
     * Uses yt-dlp's `--flat-playlist --dump-single-json` to get titles + video IDs
     * quickly and reliably (handles YouTube's anti-bot protection, unlike raw HTML
     * scraping). Returns entries in playlist order, each with a canonical
     * `watch?v=<id>&list=<listId>&index=<n>` URL.
     */
    suspend fun fetchPlaylistEntries(playlistUrl: String): List<PlaylistEntry> = withContext(Dispatchers.IO) {
        val playlistId = extractPlaylistId(playlistUrl)
            ?: throw Exception("Invalid playlist URL: missing list= parameter")

        val request = YoutubeDLRequest(playlistUrl)
        request.addOption("--flat-playlist")
        request.addOption("--dump-single-json")
        request.addOption("--no-warnings")

        val response = YoutubeDL.getInstance().execute(request, null, null)

        val root = JSONObject(response.out)
        val entriesArray = root.optJSONArray("entries") ?: throw Exception("Playlist has no entries")

        val result = mutableListOf<PlaylistEntry>()
        for (i in 0 until entriesArray.length()) {
            val entry = entriesArray.optJSONObject(i) ?: continue
            val videoId = entry.optString("id")
            if (videoId.isBlank()) continue
            val title = entry.optString("title").ifBlank { "Unknown Title" }
            val index = i + 1
            val canonicalUrl = "https://www.youtube.com/watch?v=$videoId&list=$playlistId&index=$index"
            result.add(PlaylistEntry(url = canonicalUrl, title = title, index = index))
        }
        if (result.isEmpty()) throw Exception("No downloadable videos found in playlist")
        result
    }

    /**
     * Search YouTube for [query] and return up to [limit] flat results (no
     * media, no full metadata fetch) using yt-dlp's `ytsearch{N}:<query>`
     * pseudo-URL together with `--flat-playlist --dump-single-json`.
     *
     * Each result carries a canonical `https://www.youtube.com/watch?v=<id>`
     * link plus the lightweight fields (title, uploader, thumbnail, duration)
     * that the flat extractor exposes — enough to render a result row and to
     * let the user copy the link into the Add-Music URL field.
     *
     * Throws on network/yt-dlp failure; the caller surfaces the message.
     */
    suspend fun searchYouTube(query: String, limit: Int = 20): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val trimmed = query.trim()
            if (trimmed.isEmpty()) return@withContext emptyList()

            val request = YoutubeDLRequest("ytsearch$limit:$trimmed")
            request.addOption("--flat-playlist")
            request.addOption("--dump-single-json")
            request.addOption("--no-warnings")
            request.addOption("--ignore-errors")

            val response = YoutubeDL.getInstance().execute(request, null, null)

            val root = JSONObject(response.out)
            val entriesArray = root.optJSONArray("entries")
                ?: return@withContext emptyList()

            val out = mutableListOf<SearchResult>()
            for (i in 0 until entriesArray.length()) {
                val entry = entriesArray.optJSONObject(i) ?: continue
                val id = entry.optString("id")
                if (id.isBlank()) continue
                val title = entry.optString("title").ifBlank { "Unknown Title" }
                val uploader = entry.optString("uploader")
                    .ifBlank { entry.optString("channel") }
                    .ifBlank { entry.optString("uploader_id") }
                    .ifBlank { "Unknown" }
                val thumb = entry.optString("thumbnail").ifBlank { null }
                val duration = entry.optLong("duration", 0L)
                out.add(
                    SearchResult(
                        url = "https://www.youtube.com/watch?v=$id",
                        title = title,
                        uploader = uploader,
                        thumbnailUrl = thumb,
                        duration = duration
                    )
                )
            }
            out
        }

    /**
     * Best-effort cancellation of a running download identified by [processId].
     * Safe to call even if the process already finished. When a running process
     * is destroyed, the corresponding [YoutubeDL.execute] call throws a
     * `CanceledException`.
     */
    fun cancelDownload(processId: String) {
        try { YoutubeDL.getInstance().destroyProcessById(processId) }
        catch (_: Exception) {}
    }

    /**
     * Extract the `list=` value from a YouTube URL.
     * Handles both `https://www.youtube.com/playlist?list=ID`
     * and `https://www.youtube.com/watch?v=...&list=ID&...`.
     */
    private fun extractPlaylistId(url: String): String? {
        val listMarker = "list="
        val idx = url.indexOf(listMarker)
        if (idx < 0) return null
        val start = idx + listMarker.length
        val rest = url.substring(start)
        val end = rest.indexOfFirst { it == '&' || it == '#' || it == '?' }
        return if (end < 0) rest else rest.substring(0, end)
    }
}
