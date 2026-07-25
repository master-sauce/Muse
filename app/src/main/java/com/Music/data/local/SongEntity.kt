package com.Music.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "songs")
data class SongEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val filePath: String,
    val duration: Long,
    val thumbnailUrl: String?,
    val sourceUrl: String,
    val sortOrder: Int = 0,
    /** Wall-clock millis when the song was added to the library. Drives the
     *  Newest / Oldest sort modes in the Library. Older rows (pre-migration)
     *  fall back to their rowid so their relative insertion order is preserved. */
    val createdAt: Long = 0L
)

fun SongEntity.isVideo(): Boolean =
    filePath.substringAfterLast(".").lowercase() in
            setOf("mp4", "mkv", "webm", "avi", "mov", "3gp", "m4v")