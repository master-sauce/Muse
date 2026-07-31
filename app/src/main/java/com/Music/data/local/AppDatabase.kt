package com.Music.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [SongEntity::class, PlaylistEntity::class, PlaylistSongCrossRef::class],
    version = 4,
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

        /**
         * Adds the `createdAt` column to `songs` so the Library can be sorted
         * Newest / Oldest by add-time. Pre-existing rows get `createdAt = rowid`
         * (a monotonically-increasing proxy for insertion order) so their
         * relative add order survives the migration even though the wall-clock
         * time wasn't recorded.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE songs SET createdAt = rowid")
            }
        }

        /**
         * Adds the `sortOrder` column to `playlists` so the Playlists tab can be
         * manually drag-reordered by the user. Pre-existing rows get
         * `sortOrder = rowid` (a monotonically-increasing proxy for insertion
         * order) so the relative creation order of already-made playlists
         * survives the migration — they simply line up in the order they were
         * created, oldest first, until the user drags them around.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE playlists ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE playlists SET sortOrder = rowid")
            }
        }
    }
}