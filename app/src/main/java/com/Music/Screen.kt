package com.Music

sealed class Screen(val route: String) {
    object Library       : Screen("library")
    object Player        : Screen("player")
    object Lyrics        : Screen("lyrics")
    object YouTubeSearch : Screen("youtube_search")
    object PlaylistDetail : Screen("playlist_detail/{playlistId}") {
        const val ARG = "playlistId"
        fun route(id: Long) = "playlist_detail/$id"
    }
}