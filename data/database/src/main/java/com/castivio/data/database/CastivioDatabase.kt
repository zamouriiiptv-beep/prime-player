package com.castivio.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.castivio.data.database.dao.EpgDao
import com.castivio.data.database.dao.FavoriteDao
import com.castivio.data.database.dao.GroupDao
import com.castivio.data.database.dao.HiddenDao
import com.castivio.data.database.dao.MediaDao
import com.castivio.data.database.dao.OrderDao
import com.castivio.data.database.dao.ProgressDao
import com.castivio.data.database.dao.SourceDao
import com.castivio.data.database.dao.SeriesDetailDao
import com.castivio.data.database.dao.VodDetailDao
import com.castivio.data.database.entity.CustomOrderEntity
import com.castivio.data.database.entity.FavoriteEntity
import com.castivio.data.database.entity.GroupEntity
import com.castivio.data.database.entity.HiddenEntity
import com.castivio.data.database.entity.MediaEntity
import com.castivio.data.database.entity.MediaFtsEntity
import com.castivio.data.database.entity.ProgrammeEntity
import com.castivio.data.database.entity.ProgressEntity
import com.castivio.data.database.entity.SourceEntity
import com.castivio.data.database.entity.EpisodePlotEntity
import com.castivio.data.database.entity.SeriesDetailEntity
import com.castivio.data.database.entity.VodDetailEntity

@Database(
    entities = [
        MediaEntity::class,
        MediaFtsEntity::class,
        GroupEntity::class,
        FavoriteEntity::class,
        ProgressEntity::class,
        ProgrammeEntity::class,
        SourceEntity::class,
        CustomOrderEntity::class,
        HiddenEntity::class,
        VodDetailEntity::class,
        SeriesDetailEntity::class,
        EpisodePlotEntity::class,
    ],
    // 2: no table changed. The *derivation* of a source id did, so the stored rows
    //    have to be re-keyed onto it — see `CastivioMigrations.REKEY_SOURCES`, which
    //    explains why leaving them alone loses a subscription's catalogue silently.
    // 3: the catalogue reads are scoped to the active subscription, so the browse
    //    indices lead with `source_id`, and the rows of subscriptions that no longer
    //    exist are removed — see `CastivioMigrations.SCOPE_TO_SOURCE`.
    // 4: no table changed. Every search entry is keyed by its row's rowid, which an
    //    import relies on to find the entries it made stale — see
    //    `CastivioMigrations.KEY_SEARCH_BY_ROW`.
    // 5: one table added, `custom_order`, for the order a viewer gives their own lists -- see
    //    `CastivioMigrations.ADD_CUSTOM_ORDER`. Nothing that was stored changed.
    // 6: one table added, `hidden_item`, for the bouquets and channels a viewer hides -- see
    //    `CastivioMigrations.ADD_HIDDEN_ITEM`. Nothing that was stored changed.
    // 7: one table added, `vod_detail`, the film details a provider gives on request -- see
    //    `CastivioMigrations.ADD_VOD_DETAIL`. Nothing that was stored changed.
    // 8: two tables added, `series_detail` and `episode_plot`, what a provider says of a show and of its
    //    episodes with the episodes -- see `CastivioMigrations.ADD_SERIES_DETAIL`. Nothing that was stored changed.
    version = 8,
    exportSchema = true,
)
abstract class CastivioDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao
    abstract fun groupDao(): GroupDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun progressDao(): ProgressDao
    abstract fun epgDao(): EpgDao
    abstract fun sourceDao(): SourceDao
    abstract fun orderDao(): OrderDao
    abstract fun hiddenDao(): HiddenDao
    abstract fun vodDetailDao(): VodDetailDao
    abstract fun seriesDetailDao(): SeriesDetailDao

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
