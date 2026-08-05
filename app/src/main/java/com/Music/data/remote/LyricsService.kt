package com.Music.data.remote

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

interface LyricsService {
    /** Exact-match endpoint. LRCLIB requires `duration` here — without it the
     *  server returns HTTP 400 and the call always fails. */
    @GET("get")
    suspend fun getLyrics(
        @Query("artist_name") artistName: String,
        @Query("track_name") trackName: String,
        @Query("duration") duration: Int? = null
    ): Response<LyricsResponse>

    /** Fuzzy search endpoint. Every parameter is OPTIONAL, so it works without a
     *  duration and tolerates slightly-off artist/title strings (the usual reason
     *  [/get] 404s on YouTube-sourced metadata). Returns a list of candidates
     *  ranked by LRCLIB; the caller picks the best match. */
    @GET("search")
    suspend fun searchLyrics(
        @Query("track_name") trackName: String? = null,
        @Query("artist_name") artistName: String? = null,
        @Query("album_name") albumName: String? = null
    ): Response<List<LyricsResponse>>
}

data class LyricsResponse(
    val trackName: String?,
    val artistName: String?,
    val duration: Float?,
    val instrumental: Boolean,
    val plainLyrics: String?,
    val syncedLyrics: String?
)

data class LyricLine(val timeMs: Long, val text: String)

object LrcParser {
    private val LINE_RE = Regex("""^$$(\d{2}):(\d{2})\.(\d{2,3})$$\s?(.*)$""")

    fun parse(lrc: String): List<LyricLine> =
        lrc.lines().mapNotNull { raw ->
            LINE_RE.matchEntire(raw.trim())?.let { m ->
                val ms = m.groupValues[1].toLong() * 60_000 +
                        m.groupValues[2].toLong() * 1_000 +
                        m.groupValues[3].padEnd(3, '0').take(3).toLong()
                LyricLine(ms, m.groupValues[4])
            }
        }.sortedBy { it.timeMs }
}

/**
 * Cleans YouTube / locally-sourced metadata so it lines up with LRCLIB's
 * canonical "Artist" / "Track" values. yt-dlp's `uploader` and the
 * `MediaMetadataRetriever` often hand us things like "Queen - Topic",
 * "QueenOfficialVEVO", "Blur Official", or a *title* like
 * "Blur - Song 2 (Official Video) [HD]" — none of which match LRCLIB's clean
 * entries, so [/get] returns 404 and no lyrics ever load. [normalize] splits
 * the "Artist - Title" prefix out of the title and strips the noise so the
 * query lines up with what LRCLIB actually stores.
 */
object LyricsQueryCleaner {
    // "(Official Video)", "[Official MV]", "(Lyrics)", "(Audio)", "(4K)", "(Live)", ...
    private val PAREN_NOISE = Regex(
        """\s*[\(\[]\s*(?:official\s*(?:music\s*)?video|official\s*audio|official\s*visualizer|official\s*lyric|lyric(?:s)?(?:\s*video)?|audio|hd|4k|uhd|mv|m/v|visualizer|performance|live|remastered(?:\s+\d+)?|remix|explicit|clean)\s*[\)\]]""",
        RegexOption.IGNORE_CASE
    )
    // " - Official Video", " — Lyrics", " – Audio" trailing the title
    private val TRAILING_NOISE = Regex(
        """\s*[-–—]\s*(?:official\s*(?:music\s*)?video|official\s*audio|lyric(?:s)?(?:\s*video)?|audio|hd|4k|uhd|mv|visualizer|performance|live|remastered(?:\s+\d+)?|remix)\s*$""",
        RegexOption.IGNORE_CASE
    )
    // "(feat. Someone)", " ft. Someone"
    private val FEAT = Regex("""\s*\(?\s*(?:ft\.?|feat\.?)\s+[^)\]]*?\)?""", RegexOption.IGNORE_CASE)
    // " - Topic" suffix YouTube appends to auto-generated music channels
    private val TOPIC = Regex("""\s*[-–—]\s*topic\s*$""", RegexOption.IGNORE_CASE)
    private val VEVO = Regex("""\s*vevo\s*$""", RegexOption.IGNORE_CASE)
    // Trailing " Official" / " Official Music" channel suffix (e.g. "Queen Official")
    private val OFFICIAL_SUFFIX = Regex("""\s+official(?:\s+music)?\s*$""", RegexOption.IGNORE_CASE)
    private val MULTI_SPACE = Regex("""\s{2,}""")

    /**
     * Clean a (title, artist) pair and, when the title embeds the artist as an
     * "Artist - Track" prefix, split it back out. YouTube video titles almost
     * always look like "Blur - Song 2 (Official Video)" — LRCLIB's /get wants
     * track_name="Song 2" and artist_name="Blur"; sending
     * track_name="Blur - Song 2" 404s even though the metadata is right there
     * in the DB. This is the single most common reason lyrics never load.
     *
     * Returns the (artist, title) pair to send to LRCLIB.
     */
    fun normalize(rawTitle: String, rawArtist: String): Pair<String, String> {
        // Unify dash variants (en/em dash) so we only have to look for " - ".
        var title = rawTitle.trim().replace('–', '-').replace('—', '-')
        var artist = cleanArtist(rawArtist)

        val dashIdx = title.indexOf(" - ")
        if (dashIdx > 0) {
            val prefix = title.substring(0, dashIdx).trim()
            val rest   = title.substring(dashIdx + 3).trim()
            if (prefix.isNotBlank() && rest.isNotBlank()) {
                val cmpPrefix = norm(prefix)
                val cmpArtist = norm(artist)
                when {
                    // Title prefix matches the DB artist (after stripping channel
                    // noise like "Topic"/"VEVO"/"Official") → drop the prefix and
                    // keep the DB artist as the query artist.
                    cmpPrefix == cmpArtist -> title = rest
                    // No usable artist in the DB ("Unknown Artist" / blank) →
                    // adopt the title's prefix as the artist.
                    artist.isBlank() || artist == "Unknown Artist" -> {
                        artist = prefix
                        title = rest
                    }
                    // Prefix doesn't match → leave the title intact. A real song
                    // title may legitimately contain " - " and we'd rather miss
                    // the lyric than mangle the query into the wrong track.
                }
            }
        }

        title = cleanTitle(title)
        return artist to title
    }

    /**
     * Light, case-insensitive normalization for comparing an artist against a
     * title prefix. Strips YouTube channel-name suffixes ("Topic", "VEVO",
     * "Official") so "Blur - Topic" / "BlurVEVO" / "Blur Official" all compare
     * equal to "Blur". Used only for the match decision — the actual values
     * sent to LRCLIB come from [cleanArtist]/[cleanTitle], not from here.
     */
    private fun norm(s: String): String {
        var x = s.lowercase().trim()
        for (suf in listOf("topic", "vevo", "official")) {
            while (x.endsWith(suf)) x = x.removeSuffix(suf).trim()
        }
        return x.trim()
    }

    fun cleanTitle(raw: String): String {
        var t = raw.trim()
        t = PAREN_NOISE.replace(t, " ")
        t = TRAILING_NOISE.replace(t, "")
        t = FEAT.replace(t, "")
        return t.trim().replace(MULTI_SPACE, " ")
    }

    fun cleanArtist(raw: String): String {
        var a = raw.trim()
        a = TOPIC.replace(a, "")
        a = VEVO.replace(a, "")
        a = OFFICIAL_SUFFIX.replace(a, "")
        return a.trim().replace(MULTI_SPACE, " ")
    }
}

sealed class LyricsState {
    object Idle         : LyricsState()
    object Loading      : LyricsState()
    object NotFound     : LyricsState()
    object Instrumental : LyricsState()
    data class Plain(val text: String)       : LyricsState()
    data class Synced(val lines: List<LyricLine>) : LyricsState()
}