package com.castivio.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.castivio.data.database.dao.EpgDao
import com.castivio.data.database.dao.FavoriteDao
import com.castivio.data.database.dao.GroupDao
import com.castivio.data.database.dao.MediaDao
import com.castivio.data.database.dao.ProgressDao
import com.castivio.data.database.dao.SourceDao
import com.castivio.data.database.entity.FavoriteEntity
import com.castivio.data.database.entity.GroupEntity
import com.castivio.data.database.entity.MediaEntity
import com.castivio.data.database.entity.MediaFtsEntity
import com.castivio.data.database.entity.ProgrammeEntity
import com.castivio.data.database.entity.ProgressEntity
import com.castivio.data.database.entity.SourceEntity

@Database(
    entities = [
        MediaEntity::class,
        MediaFtsEntity::class,
        GroupEntity::class,
        FavoriteEntity::class,
        ProgressEntity::class,
        ProgrammeEntity::class,
        SourceEntity::class,
    ],
    // 2 adds `media_group.provider_ref` and `media_group.items_loaded_at`, which are
    // what let a category be listed now and fetched when it is opened.
    version = 2,
    exportSchema = true,
)
abstract class CastivioDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao
    abstract fun groupDao(): GroupDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun progressDao(): ProgressDao
    abstract fun epgDao(): EpgDao
    abstract fun sourceDao(): SourceDao

    companion object {
        const val NAME = "castivio.db"

        /**
         * Opens the database.
         *
         * No `allowMainThreadQueries`, ever. And no callback that touches data on
         * open: startup must be an `open()` and nothing else, because the first
         * frame is waiting on it.
         *
         * Migrations are explicit — see [CastivioMigrations] for why
         * `fallbackToDestructiveMigration()` is not used on upgrades. Downgrades
         * are destructive, because a sideloaded older APK cannot know a newer
         * schema, and that happens on TV boxes.
         */
        fun open(context: Context): CastivioDatabase =
            Room.databaseBuilder(context, CastivioDatabase::class.java, NAME)
                .addMigrations(*CastivioMigrations.ALL)
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()

        /** In-memory instance for tests. */
        fun inMemory(context: Context): CastivioDatabase =
            Room.inMemoryDatabaseBuilder(context, CastivioDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
