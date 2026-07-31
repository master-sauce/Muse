package com.Music.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    /**
     * Manual drag order for the Playlists tab. Lower = higher up in the list.
     * New playlists are inserted at position 0 (top of the list) — see
     * [com.Music.data.MusicRepository.createPlaylist], which shifts every
     * existing playlist's sortOrder up by one first. The Playlists tab
     * reorders this column directly via drag handles (no Newest/Oldest mode
     * for playlists — the user's manual order is the only order).
     *
     * Older rows (pre-migration) fall back to `rowid` so their relative
     * creation order is preserved when the column is added.
     */
    val sortOrder: Int = 0
)