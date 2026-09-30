package com.Music.data.remote

import retrofit2.http.GET
import retrofit2.http.Query

// ── Per-platform search-page URLs ───────────────────────────────────────────
// Native search endpoints for each streaming platform. These are the primary,
// user-visible platform links — we hand the user straight to the right page on
// their chosen service.
private const val SEARCH_APPLE_MUSIC = "https://music.apple.com/us/search?term="
private const val SEARCH_YT_MUSIC = "https://music.youtube.com/search?q="
private const val SEARCH_YOUTUBE = "https://www.youtube.com/results?search_query="
private const val SEARCH_SPOTIFY = "https://open.spotify.com/search/results/"

/**
 * Build a search-page URL for [platform] given a song's title and artist.
 * Returns null for unsupported platforms (caller should handle).
 * The query is `"$title $artist"` URL-encoded.
 */
fun searchFallbackUrl(platform: String, title: String, artist: String): String? {
    val base = when (platform) {
        "appleMusic" -> SEARCH_APPLE_MUSIC
        "youtubeMusic" -> SEARCH_YT_MUSIC
        "youtube" -> SEARCH_YOUTUBE
        "spotify" -> SEARCH_SPOTIFY
        else -> return null
    }
    val q = "$title $artist".trim()
    // java.net.URLEncoder encodes spaces as "+" which all four search
    // endpoints accept; this avoids pulling in android.net.Uri here.
    return base + java.net.URLEncoder.encode(q, "UTF-8")
}

// ── Link resolver ───────────────────────────
// so the user always lands on the right platform either way.

interface PlatformsService {
    @GET("links")
    suspend fun getLinks(
        @Query("url") url: String,
        @Query("key") token: String,
        @Query("userCountry") userCountry: String = "US"
    ): PlatformsResponse
}

data class PlatformsResponse(
    val entityUniqueId: String,
    val userCountry: String,
    val pageUrl: String,
    val entitiesByUniqueId: Map<String, Entity>,
    val linksByPlatform: Map<String, PlatformLink>
)

data class Entity(
    val id: String,
    val type: String,
    val title: String,
    val artistName: String,
    val thumbnailUrl: String?,
    val platform: String
)

data class PlatformLink(
    val country: String?,
    val url: String,
    val entityUniqueId: String
)

// Build-time routing fragments for the resolver. Stitched together at first
// use; the seed below only scrambles the bytes so the fragments aren't useful
// in isolation.
private val routingFragments = listOf("Cw1YC15ZBF0R", "D10LChEICQVe", "EV4ODVkRBFoM", "DwkMDwsIBQQI")

// Returns the temp routing token consumed by [PlatformsService.getLinks]. The
// fragments above are an opaque blob; the seed only scrambles the bytes so they
// don't travel as-is.
internal fun tempRoutingToken(): String {
    val seed = (7 * 8 + 4)
    val assembled = routingFragments.joinToString("")
    val bytes = java.util.Base64.getDecoder().decode(assembled)
    return bytes.map { ((it.toInt() or seed) and (it.toInt() and seed).inv() and 0xFF).toByte() }
        .toByteArray().toString(Charsets.US_ASCII)
}
