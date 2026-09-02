package com.castivio.data.playlist.di

import android.content.Context
import com.castivio.core.common.AppDispatchers
import com.castivio.data.networking.HttpStreamSource
import com.castivio.data.networking.XtreamHttpApi
import com.castivio.data.parsing.XtreamImportEngine
import com.castivio.data.playlist.AndroidLocalPlaylistReader
import com.castivio.data.playlist.DefaultCatalogImporter
import com.castivio.data.playlist.XtreamCatalogSections
import com.castivio.data.playlist.LocalPlaylistReader
import com.castivio.domain.CatalogImporter
import com.castivio.domain.CatalogSectionStore
import com.castivio.domain.CatalogSections
import com.castivio.domain.CatalogWriter
import com.castivio.domain.PlaylistSource
import com.castivio.domain.ProviderValidator
import com.castivio.domain.SourceRepository
import com.castivio.domain.activation.ActivateProvider
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

    /**
     * On-demand loading: one section, when a section is asked for.
     *
     * The counterpart to `catalogImporter` rather than a replacement. That one reads a
     * playlist, which has to be read whole; this one addresses a panel by category, and
     * is what makes signing in cost one request instead of the catalogue.
     */
    @Provides
    @Singleton
    fun catalogSections(
        sources: SourceRepository,
        // A Provider for the same reason as above: each fetch gets its own writer, so
        // two sections loading at once cannot interleave transactions.
        writers: Provider<CatalogWriter>,
        store: CatalogSectionStore,
        client: OkHttpClient,
        dispatchers: AppDispatchers,
    ): CatalogSections = XtreamCatalogSections(
        sources = sources,
        writerFactory = { writers.get() },
        groups = store,
        apiFactory = { source -> source.toApi(client) },
        dispatchers = dispatchers,
    )

    /**
     * The whole activation sequence, assembled from three contracts it does not know
     * the implementations of. Lives here rather than in the feature because this is
     * where the importer it needs is already bound.
     */
    @Provides
    @Singleton
    fun activateProvider(
        validator: ProviderValidator,
        importer: CatalogImporter,
        sources: SourceRepository,
    ): ActivateProvider = ActivateProvider(validator, importer, sources)

    private fun PlaylistSource.Xtream.toApi(client: OkHttpClient): XtreamImportEngine.Api =
        XtreamHttpApi(client, host, username, password)
}
