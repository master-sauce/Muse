package com.Music.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {
    @Query("SELECT * FROM songs ORDER BY sortOrder ASC")
    fun getAllSongs(): Flow<List<SongEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSong(song: SongEntity)

    @Delete
    suspend fun deleteSong(song: SongEntity)

    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun getSongById(id: String): SongEntity?

    @Query("UPDATE songs SET sortOrder = :order WHERE id = :id")
    suspend fun updateSortOrder(id: String, order: Int)

    @Query("DELETE FROM songs WHERE id IN (:ids)")
    suspend fun deleteSongsByIds(ids: List<String>)
}