package com.Music.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [SongEntity::class, PlaylistEntity::class, PlaylistSongCrossRef::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun playlistDao(): PlaylistDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE songs SET sortOrder = rowid")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS playlists (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        createdAt INTEGER NOT NULL DEFAULT 0
                    )
                """)
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS playlist_songs (
                        playlistId INTEGER NOT NULL,
                        songId TEXT NOT NULL,
                        position INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(playlistId, songId),
                        FOREIGN KEY(playlistId) REFERENCES playlists(id) ON DELETE CASCADE,
                        FOREIGN KEY(songId) REFERENCES songs(id) ON DELETE CASCADE
                    )
                """)
                db.execSQL("CREATE INDEX IF NOT EXISTS index_playlist_songs_songId ON playlist_songs(songId)")
            }
        }
    }
}