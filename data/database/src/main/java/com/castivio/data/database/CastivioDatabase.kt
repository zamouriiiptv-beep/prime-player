package com.castivio.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.castivio.data.database.dao.FavoriteDao
import com.castivio.data.database.dao.GroupDao
import com.castivio.data.database.dao.MediaDao
import com.castivio.data.database.dao.ProgressDao
import com.castivio.data.database.entity.FavoriteEntity
import com.castivio.data.database.entity.GroupEntity
import com.castivio.data.database.entity.MediaEntity
import com.castivio.data.database.entity.MediaFtsEntity
import com.castivio.data.database.entity.ProgressEntity

@Database(
    entities = [
        MediaEntity::class,
        MediaFtsEntity::class,
        GroupEntity::class,
        FavoriteEntity::class,
        ProgressEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class CastivioDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao
    abstract fun groupDao(): GroupDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun progressDao(): ProgressDao

    companion object {
        const val NAME = "castivio.db"

        /**
         * Opens the database.
         *
         * No `allowMainThreadQueries`, ever. And no callback that touches data on
         * open: startup must be an `open()` and nothing else, because the first
         * frame is waiting on it.
         *
         * The catalogue is rebuildable from the provider, so a schema change takes
         * [fallbackToDestructiveMigration] rather than a hand-written migration —
         * with one exception that is *not* destructible: favourites and watch
         * progress. Those tables get real migrations when they change, which is
         * why they are separate tables rather than columns on `media`.
         */
        fun open(context: Context): CastivioDatabase =
            Room.databaseBuilder(context, CastivioDatabase::class.java, NAME)
                .fallbackToDestructiveMigration()
                .build()

        /** In-memory instance for tests. */
        fun inMemory(context: Context): CastivioDatabase =
            Room.inMemoryDatabaseBuilder(context, CastivioDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
