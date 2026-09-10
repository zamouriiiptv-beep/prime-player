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
import com.castivio.domain.LoadSection
import com.castivio.domain.SectionCatalogue
import com.castivio.domain.PlaylistSource
import com.castivio.domain.ProviderStatusCatalogue
import com.castivio.domain.RefreshProvider
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
     * The whole activation sequence, assembled from four contracts it does not know
     * the implementations of. Lives here rather than in the feature because this is
     * where the importer it needs is already bound.
     */
    @Provides
    @Singleton
    fun activateProvider(
        validator: ProviderValidator,
        importer: CatalogImporter,
        sources: SourceRepository,
        statuses: ProviderStatusCatalogue,
    ): ActivateProvider = ActivateProvider(validator, importer, sources, statuses)

    /**
     * Asking the active provider again what it says, which is the only thing that can
     * move the two facts Home states about the subscription. No importer, because it
     * downloads nothing — see [com.castivio.domain.RefreshProvider].
     */
    @Provides
    @Singleton
    fun refreshProvider(
        sources: SourceRepository,
        validator: ProviderValidator,
        statuses: ProviderStatusCatalogue,
    ): RefreshProvider = RefreshProvider(sources, validator, statuses)

    /**
     * Fetching one section, assembled where the importer it needs is already bound —
     * the same reason [activateProvider] lives here rather than in a feature.
     */
    @Provides
    @Singleton
    fun loadSection(
        sources: SourceRepository,
        importer: CatalogImporter,
        marks: SectionCatalogue,
    ): LoadSection = LoadSection(sources, importer, marks)

    private fun PlaylistSource.Xtream.toApi(client: OkHttpClient): XtreamImportEngine.Api =
        XtreamHttpApi(client, host, username, password)
}
