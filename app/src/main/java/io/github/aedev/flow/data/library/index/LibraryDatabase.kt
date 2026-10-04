package io.github.aedev.flow.data.library.index

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File

/**
 * The tag index of the music folders, kept apart from the app database: it holds nothing that
 * cannot be read again from the files, so a schema change may simply rebuild it.
 */
@Database(
    entities = [
        LibraryTrackEntity::class,
        LibraryTrackArtistEntity::class,
        LibraryPlaylistEntity::class,
        LibraryPlaylistEntryEntity::class,
        LibraryArtworkEntity::class,
        LibraryMetaEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
internal abstract class LibraryDatabase : RoomDatabase() {
    abstract fun dao(): LibraryDao

    companion object {
        fun create(context: Context): LibraryDatabase =
            Room
                .databaseBuilder(context.applicationContext, LibraryDatabase::class.java, "music_library")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .addCallback(
                    object : Callback() {
                        override fun onDestructiveMigration(db: SupportSQLiteDatabase) {
                            File(context.filesDir, LibraryArtworkStore.DIRECTORY).deleteRecursively()
                        }
                    },
                ).build()
    }
}
