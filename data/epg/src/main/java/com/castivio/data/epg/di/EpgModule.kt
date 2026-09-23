package com.castivio.data.epg.di

import com.castivio.core.common.AppDispatchers
import com.castivio.data.epg.DefaultEpgImporter
import com.castivio.data.networking.HttpStreamSource
import com.castivio.domain.ChannelGuideFetcher
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
    fun xtreamGuide(
        client: OkHttpClient,
        writers: Provider<EpgWriter>,
        sources: com.castivio.domain.SourceRepository,
        dispatchers: AppDispatchers,
    ): XtreamNowNextRefresher = XtreamNowNextRefresher(
        client = client,
        writerFactory = { writers.get() },
        sources = sources,
        dispatchers = dispatchers,
    )

    /**
     * Two ports, one object, and the concrete type bound above rather than cast down to.
     *
     * `XtreamNowNextRefresher` serves both because the *writing* is identical and must
     * stay identical — one transaction, one retention pass, one set of import pragmas —
     * while the two requests differ only in how many entries they ask for. Binding one
     * instance to both is what stops that becoming two stores that drift.
     *
     * The types stay separate all the same, and that is the point: a screen drawing rows
     * holds a [NowNextRefresher], and that interface has no method that could fetch a
     * week. It cannot make the expensive request by accident because it cannot name it.
     */
    @Provides
    @Singleton
    fun nowNextRefresher(guide: XtreamNowNextRefresher): NowNextRefresher = guide

    @Provides
    @Singleton
    fun channelGuideFetcher(guide: XtreamNowNextRefresher): ChannelGuideFetcher = guide
}
