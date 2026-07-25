package com.castivio.data.database.di

import android.content.Context
import com.castivio.data.database.CastivioDatabase
import com.castivio.data.database.RoomCatalogRepository
import com.castivio.data.database.RoomCatalogWriter
import com.castivio.data.database.RoomFavoritesRepository
import com.castivio.data.database.RoomProgressRepository
import com.castivio.data.database.dao.FavoriteDao
import com.castivio.data.database.dao.GroupDao
import com.castivio.data.database.dao.MediaDao
import com.castivio.data.database.dao.ProgressDao
import com.castivio.domain.CatalogPager
import com.castivio.domain.CatalogRepository
import com.castivio.domain.CatalogWriter
import com.castivio.domain.FavoritesRepository
import com.castivio.domain.ProgressRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Everything here is `@Singleton` and lazily created.
 *
 * Nothing in this module runs at startup: `Room.databaseBuilder` does not touch
 * the file until the first query, so the database is opened by whatever screen
 * actually needs it rather than on the way to the first frame.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): CastivioDatabase =
        CastivioDatabase.open(context)

    @Provides fun mediaDao(database: CastivioDatabase): MediaDao = database.mediaDao()
    @Provides fun groupDao(database: CastivioDatabase): GroupDao = database.groupDao()
    @Provides fun favoriteDao(database: CastivioDatabase): FavoriteDao = database.favoriteDao()
    @Provides fun progressDao(database: CastivioDatabase): ProgressDao = database.progressDao()

    @Provides
    @Singleton
    fun catalog(
        mediaDao: MediaDao,
        groupDao: GroupDao,
        favoriteDao: FavoriteDao,
        progressDao: ProgressDao,
    ): RoomCatalogRepository = RoomCatalogRepository(mediaDao, groupDao, favoriteDao, progressDao)

    @Provides
    fun catalogRepository(repository: RoomCatalogRepository): CatalogRepository = repository

    @Provides
    fun catalogPager(repository: RoomCatalogRepository): CatalogPager = repository

    /**
     * Not a singleton: a writer holds prepared statements and the state of one
     * import. Sharing one between two imports would interleave their
     * transactions.
     */
    @Provides
    fun catalogWriter(database: CastivioDatabase): CatalogWriter = RoomCatalogWriter(database)

    @Provides
    @Singleton
    fun favorites(dao: FavoriteDao): FavoritesRepository = RoomFavoritesRepository(dao)

    @Provides
    @Singleton
    fun progress(dao: ProgressDao): ProgressRepository = RoomProgressRepository(dao)
}
