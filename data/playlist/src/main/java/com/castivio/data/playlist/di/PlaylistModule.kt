package com.castivio.data.playlist.di

import android.content.Context
import com.castivio.core.common.AppDispatchers
import com.castivio.core.common.Outcome
import com.castivio.data.networking.HttpStreamSource
import com.castivio.data.networking.StalkerHttpApi
import com.castivio.data.networking.StalkerSession
import com.castivio.data.parsing.StalkerChannel
import com.castivio.data.parsing.StalkerItem
import com.castivio.data.parsing.StalkerImportEngine
import com.castivio.domain.MediaKind
import com.castivio.domain.identity.DeviceIdentity
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
        identity: DeviceIdentity,
    ): CatalogImporter = DefaultCatalogImporter(
        http = http,
        writerFactory = { writers.get() },
        sources = sources,
        localFiles = localFiles,
        xtreamApiFactory = { source -> source.toApi(client) },
        // The device address a portal handshake needs, from the one place that derives
        // it. Never asked of the user: it is the address the activation screen already
        // shows, which is what they gave their provider. See `StalkerHttpApi`.
        portalApiFactory = { source -> source.toApi(client, identity.current().macAddress.value) },
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
        sections: SectionCatalogue,
    ): RefreshProvider = RefreshProvider(sources, validator, statuses, sections)

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

    /**
     * A portal's engine API, with the session it needs opened lazily.
     *
     * The handshake happens on the first page rather than here: a factory that
     * performed a network call would be a factory that blocks whoever assembles the
     * importer, and an import that is cancelled before it starts would have paid for a
     * session nobody used.
     */
    private fun PlaylistSource.Portal.toApi(
        client: OkHttpClient,
        mac: String,
    ): StalkerImportEngine.Api = object : StalkerImportEngine.Api {
        private val api = StalkerHttpApi(client, url, mac)
        private var session: StalkerSession? = null

        private fun session(): StalkerSession? {
            session?.let { return it }
            return when (val opened = api.handshake()) {
                is Outcome.Success -> opened.value.also { session = it }
                is Outcome.Failure -> null
            }
        }

        override fun channels(page: Int, onChannel: (StalkerChannel) -> Unit): Int {
            val open = session() ?: return 0
            return when (val answer = api.channels(open, page, onChannel)) {
                is Outcome.Success -> answer.value
                is Outcome.Failure -> 0
            }
        }

        override fun items(kind: MediaKind, page: Int, onItem: (StalkerItem) -> Unit): Int {
            val open = session() ?: return 0
            return when (val answer = api.items(open, kind, page, onItem)) {
                is Outcome.Success -> answer.value
                is Outcome.Failure -> 0
            }
        }

        // The protocol's own call, asked only for the commands that need it. A portal
        // that refuses to resolve one is answered with null, and the engine writes no
        // row — never a link assembled from parts.
        override fun resolve(kind: MediaKind, command: String): String? {
            val open = session() ?: return null
            return when (val answer = api.createLink(open, kind, command)) {
                is Outcome.Success -> answer.value
                is Outcome.Failure -> null
            }
        }
    }
}
