package com.Music.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {
    @Query("SELECT * FROM songs ORDER BY sortOrder ASC")
    fun getAllSongs(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs ORDER BY sortOrder ASC")
    suspend fun getAllSongsOnce(): List<SongEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSong(song: SongEntity)

    @Delete
    suspend fun deleteSong(song: SongEntity)

    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun getSongById(id: String): SongEntity?

    @Query("SELECT * FROM songs WHERE sourceUrl = :url")
    suspend fun getSongByUrl(url: String): SongEntity?

    @Query("SELECT * FROM songs WHERE title = :title AND artist = :artist")
    suspend fun getSongByMetadata(title: String, artist: String): SongEntity?

    @Query("UPDATE songs SET sortOrder = :order WHERE id = :id")
    suspend fun updateSortOrder(id: String, order: Int)

    /**
     * Bump every song's sortOrder up by 1. Used to make room at the top (position 0)
     * when a new song is inserted ahead of the existing members so it lands at the
     * head of the user's custom order.
     */
    @Query("UPDATE songs SET sortOrder = sortOrder + 1")
    suspend fun shiftAllSortOrders()

    @Query("DELETE FROM songs WHERE id IN (:ids)")
    suspend fun deleteSongsByIds(ids: List<String>)
}