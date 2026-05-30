package com.Music.data.remote

import retrofit2.http.GET
import retrofit2.http.Query

interface OdesliService {
    @GET("links")
    suspend fun getLinks(
        @Query("url") url: String,
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
