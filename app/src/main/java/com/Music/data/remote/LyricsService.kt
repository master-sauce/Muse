package com.Music.data.remote

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

interface LyricsService {
    @GET("get")
    suspend fun getLyrics(
        @Query("artist_name") artistName: String,
        @Query("track_name") trackName: String,
        @Query("duration") duration: Int? = null
    ): Response<LyricsResponse>
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

sealed class LyricsState {
    object Idle         : LyricsState()
    object Loading      : LyricsState()
    object NotFound     : LyricsState()
    object Instrumental : LyricsState()
    data class Plain(val text: String)       : LyricsState()
    data class Synced(val lines: List<LyricLine>) : LyricsState()
}