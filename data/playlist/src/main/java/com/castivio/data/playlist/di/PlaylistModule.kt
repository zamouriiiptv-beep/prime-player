package com.castivio.data.playlist.di

import android.content.Context
import com.castivio.core.common.AppDispatchers
import com.castivio.data.networking.HttpStreamSource
import com.castivio.data.networking.XtreamHttpApi
import com.castivio.data.parsing.XtreamImportEngine
import com.castivio.data.playlist.AndroidLocalPlaylistReader
import com.castivio.data.playlist.DefaultCatalogImporter
import com.castivio.data.playlist.LocalPlaylistReader
import com.castivio.domain.CatalogImporter
import com.castivio.domain.CatalogWriter
import com.castivio.domain.PlaylistSource
import com.castivio.domain.SourceRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Provider
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object PlaylistModule {

    @Provides
    @Singleton
    fun localPlaylistReader(@ApplicationContext context: Context): LocalPlaylistReader =
        AndroidLocalPlaylistReader(context)

    @Provides
    @Singleton
    fun catalogImporter(
        http: HttpStreamSource,
        // A Provider, not an instance: a writer holds the state of one import, so
        // each import gets its own rather than interleaving transactions.
        writers: Provider<CatalogWriter>,
        sources: SourceRepository,
        localFiles: LocalPlaylistReader,
        client: OkHttpClient,
        dispatchers: AppDispatchers,
    ): CatalogImporter = DefaultCatalogImporter(
        http = http,
        writerFactory = { writers.get() },
        sources = sources,
        localFiles = localFiles,
        xtreamApiFactory = { source -> source.toApi(client) },
    dispatchers = dispatchers,
    )

    private fun PlaylistSource.Xtream.toApi(client: OkHttpClient): XtreamImportEngine.Api =
        XtreamHttpApi(client, host, username, password)
}
