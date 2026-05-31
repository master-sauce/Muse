package com.Music.data.local

import androidx.room.*

@Entity(
    tableName = "playlist_songs",
    primaryKeys = ["playlistId", "songId"],
    foreignKeys = [
        ForeignKey(entity = PlaylistEntity::class, parentColumns = ["id"],
            childColumns = ["playlistId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = SongEntity::class, parentColumns = ["id"],
            childColumns = ["songId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("songId")]
)
data class PlaylistSongCrossRef(
    val playlistId: Long,
    val songId: String,
    val position: Int = 0
)

data class PlaylistWithSongs(
    @Embedded val playlist: PlaylistEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = PlaylistSongCrossRef::class,
            parentColumn = "playlistId",
            entityColumn = "songId"
        )
    )
    val songs: List<SongEntity>
)