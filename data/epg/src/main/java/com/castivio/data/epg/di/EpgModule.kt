package com.castivio.data.epg.di

import com.castivio.core.common.AppDispatchers
import com.castivio.data.epg.DefaultEpgImporter
import com.castivio.data.networking.HttpStreamSource
import com.castivio.domain.EpgImporter
import com.castivio.domain.EpgWriter
import com.castivio.domain.NowNextRefresher
import com.castivio.data.epg.XtreamNowNextRefresher
import okhttp3.OkHttpClient
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

    /**
     * The cheap guide path. Separate from [EpgImporter] because it answers a
     * different question — "what is on these twenty channels" rather than "fetch the
     * whole guide" — and a screen should be able to ask the first without the second.
     */
    @Provides
    @Singleton
    fun nowNextRefresher(
        client: OkHttpClient,
        writers: Provider<EpgWriter>,
        sources: com.castivio.domain.SourceRepository,
        dispatchers: AppDispatchers,
    ): NowNextRefresher = XtreamNowNextRefresher(
        client = client,
        writerFactory = { writers.get() },
        sources = sources,
        dispatchers = dispatchers,
    )
}
