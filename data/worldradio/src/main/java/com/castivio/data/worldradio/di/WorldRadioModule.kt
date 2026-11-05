package com.castivio.data.worldradio.di

import android.content.Context
import com.castivio.data.worldradio.RoomDirectoryCache
import com.castivio.data.worldradio.RoomWorldRadioLibrary
import com.castivio.data.worldradio.WorldRadioDatabase
import com.castivio.data.worldradio.dao.DirectoryCacheDao
import com.castivio.domain.DirectoryCache
import com.castivio.domain.WorldRadioLibrary
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The world radio's store: lazily created, and opened by whatever screen first needs it -- not on the way to the first frame.
 * Nothing here is shared with `DatabaseModule`: the two databases do not know each other.
 */
@Module
@InstallIn(SingletonComponent::class)
object WorldRadioModule {

    @Provides
    @Singleton
    fun worldRadioDatabase(@ApplicationContext context: Context): WorldRadioDatabase = WorldRadioDatabase.open(context)

    @Provides
    fun directoryCacheDao(database: WorldRadioDatabase): DirectoryCacheDao = database.directoryCacheDao()

    @Provides
    @Singleton
    fun worldRadioLibrary(database: WorldRadioDatabase): WorldRadioLibrary = RoomWorldRadioLibrary(database)

    @Provides
    @Singleton
    fun directoryCache(dao: DirectoryCacheDao): DirectoryCache = RoomDirectoryCache(dao)
}
