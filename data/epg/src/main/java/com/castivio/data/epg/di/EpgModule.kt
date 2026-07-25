package com.castivio.data.epg.di

import com.castivio.core.common.AppDispatchers
import com.castivio.data.epg.DefaultEpgImporter
import com.castivio.data.networking.HttpStreamSource
import com.castivio.domain.EpgImporter
import com.castivio.domain.EpgWriter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Provider
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object EpgModule {

    @Provides
    @Singleton
    fun epgImporter(
        http: HttpStreamSource,
        writers: Provider<EpgWriter>,
        sources: com.castivio.domain.SourceRepository,
        dispatchers: AppDispatchers,
    ): EpgImporter = DefaultEpgImporter(
        http = http,
        writerFactory = { writers.get() },
        sources = sources,
        dispatchers = dispatchers,
    )
}
