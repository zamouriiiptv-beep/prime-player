package com.castivio.data.worldradio

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.castivio.data.worldradio.dao.DirectoryCacheDao
import com.castivio.data.worldradio.dao.SavedStationDao
import com.castivio.data.worldradio.dao.StationHealthDao
import com.castivio.data.worldradio.entity.DirectoryCacheEntity
import com.castivio.data.worldradio.entity.SavedStationEntity
import com.castivio.data.worldradio.entity.StationHealthEntity

/**
 * The world radio's own database: what the viewer kept of the world's stations, what listening said about them, and the lists the
 * directory sent. A file of its own -- `world_radio.db` -- so it is opened, migrated, cleared and lost on its own terms.
 *
 * Version 1: three tables, `saved_station`, `station_health` and `directory_cache`.
 */
@Database(
    entities = [SavedStationEntity::class, StationHealthEntity::class, DirectoryCacheEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class WorldRadioDatabase : RoomDatabase() {
    abstract fun savedStationDao(): SavedStationDao
    abstract fun stationHealthDao(): StationHealthDao
    abstract fun directoryCacheDao(): DirectoryCacheDao

    companion object {
        const val NAME = "world_radio.db"

        /**
         * Opens the database: lazily, with no queries on the main thread and nothing on open. A downgrade -- a sideloaded older build -- starts
         * the file again, as the catalogue's does, and what it loses is a list of favourites the directory can be asked for again.
         */
        fun open(context: Context): WorldRadioDatabase =
            Room.databaseBuilder(context, WorldRadioDatabase::class.java, NAME)
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()

        /** In-memory instance for tests. */
        fun inMemory(context: Context): WorldRadioDatabase =
            Room.inMemoryDatabaseBuilder(context, WorldRadioDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
