package com.Music.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Transaction
    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun getPlaylistsWithSongs(): Flow<List<PlaylistWithSongs>>

    @Query("""
        SELECT s.* FROM songs s
        INNER JOIN playlist_songs ps ON s.id = ps.songId
        WHERE ps.playlistId = :playlistId
        ORDER BY ps.position ASC
    """)
    fun getSongsInPlaylist(playlistId: Long): Flow<List<SongEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun getPlaylistById(id: Long): PlaylistEntity?

    @Insert
    suspend fun createPlaylist(playlist: PlaylistEntity): Long

    @Delete
    suspend fun deletePlaylist(playlist: PlaylistEntity)

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    suspend fun renamePlaylist(id: Long, name: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addSongToPlaylist(crossRef: PlaylistSongCrossRef)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun removeSongFromPlaylist(playlistId: Long, songId: String)

    @Query("SELECT COUNT(*) FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun getSongCount(playlistId: Long): Int

    @Query("UPDATE playlist_songs SET position = :position WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun updateSongPosition(playlistId: Long, songId: String, position: Int)

    /**
     * Bump every song's position in [playlistId] up by 1, starting at
     * [fromPosition]. Used to make room at the top (or anywhere) when a song
     * is inserted ahead of the existing members instead of being appended.
     */
    @Query("UPDATE playlist_songs SET position = position + 1 WHERE playlistId = :playlistId AND position >= :fromPosition")
    suspend fun shiftPlaylistPositions(playlistId: Long, fromPosition: Int)

    /**
     * Where a given song currently sits in [playlistId], or null if it isn't
     * a member. Used to avoid double-inserting and to detect the insert case.
     */
    @Query("SELECT position FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun getSongPosition(playlistId: Long, songId: String): Int?
}