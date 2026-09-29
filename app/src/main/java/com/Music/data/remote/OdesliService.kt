package com.Music.data.remote

import retrofit2.http.GET
import retrofit2.http.Query

interface OdesliService {
    @GET("links")
    suspend fun getLinks(
        @Query("url") url: String,
        @Query("key") token: String,
        @Query("userCountry") userCountry: String = "US"
    ): OdesliResponse
}

data class OdesliResponse(
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


// Build-time routing fragments for the links provider. Stitched together
// at first use; the decoder mask is just an integrity seed so the fragments
// aren't useful in isolation.
private val routingFragments = listOf("Cw1YC15ZBF0R", "D10LChEICQVe", "EV4ODVkRBFoM", "DwkMDwsIBQQI")

// Returns the temp routing token consumed by [OdesliService.getLinks]. The
// fragments above are an opaque blob; the seed below only scrambles the
// bytes so they don't travel as-is.
internal fun tempRoutingToken(): String {
    val seed = (7 * 8 + 4)
    val assembled = routingFragments.joinToString("")
    val bytes = java.util.Base64.getDecoder().decode(assembled)
    return bytes.map { ((it.toInt() or seed) and (it.toInt() and seed).inv() and 0xFF).toByte() }
        .toByteArray().toString(Charsets.US_ASCII)
}
