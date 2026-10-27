package com.castivio.data.playlist.di

import android.content.Context
import android.os.SystemClock
import androidx.tracing.Trace
import com.castivio.core.common.AppDispatchers
import com.castivio.core.common.Outcome
import com.castivio.core.platform.CastivioTrace
import com.castivio.core.platform.PerfMode
import com.castivio.core.platform.PerfSection
import com.castivio.core.platform.PerformanceLog
import com.castivio.data.networking.HttpStreamSource
import com.castivio.data.networking.StalkerHttpApi
import com.castivio.data.networking.StalkerSession
import com.castivio.data.playlist.DefaultMovieDetails
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
import com.castivio.data.playlist.PlaylistCopyStore
import com.castivio.domain.CatalogImporter
import com.castivio.domain.CatalogWriter
import com.castivio.domain.LoadSection
import com.castivio.domain.PlaylistCopies
import com.castivio.domain.SectionLoad
import com.castivio.domain.SectionLoads
import com.castivio.domain.time.TrustedTime
import com.castivio.domain.SectionCatalogue
import com.castivio.domain.PlaylistSource
import com.castivio.domain.ProviderStatusCatalogue
import com.castivio.domain.RefreshProvider
import com.castivio.domain.RemoveSource
import com.castivio.domain.ProviderValidator
import com.castivio.domain.MovieDetailsCache
import com.castivio.domain.MovieDetailsRepository
import com.castivio.domain.SourceRepository
import com.castivio.domain.activation.ActivateProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart

@Module
@InstallIn(SingletonComponent::class)
object PlaylistModule {

    @Provides
    @Singleton
    fun localPlaylistReader(@ApplicationContext context: Context): LocalPlaylistReader =
        AndroidLocalPlaylistReader(context)

    /**
     * The playlists kept for the sections after the first, in the app's cache: derived
     * data, downloaded again when the system reclaims the space. See [PlaylistCopyStore].
     */
    @Provides
    @Singleton
    fun playlistCopyStore(@ApplicationContext context: Context): PlaylistCopyStore =
        PlaylistCopyStore(File(context.cacheDir, "playlists"))

    @Provides
    fun playlistCopies(store: PlaylistCopyStore): PlaylistCopies = store

    /**
     * A film's details: asked of its provider when its page opens, and kept in the cache after that. The
     * cache is the database's; the provider is the same one the film came from.
     */
    @Provides
    @Singleton
    fun movieDetails(
        cache: MovieDetailsCache,
        sources: SourceRepository,
        client: OkHttpClient,
    ): MovieDetailsRepository = DefaultMovieDetails(
        cache = cache,
        sources = sources,
        client = client,
        dispatcher = Dispatchers.IO,
        clock = System::currentTimeMillis,
    )

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
        copies: PlaylistCopyStore,
    ): CatalogImporter = DefaultCatalogImporter(
        http = http,
        writerFactory = { writers.get() },
        sources = sources,
        localFiles = localFiles,
        xtreamApiFactory = { source -> source.toApi(client) },
        copies = copies,
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
        copies: PlaylistCopies,
    ): RefreshProvider = RefreshProvider(sources, validator, statuses, sections, copies)

    /**
     * Removing a subscription with everything kept about it: its catalogue, its section
     * marks, its provider's recorded answer and a playlist's kept copy. See [RemoveSource].
     */
    @Provides
    @Singleton
    fun removeSource(
        sources: SourceRepository,
        marks: SectionCatalogue,
        statuses: ProviderStatusCatalogue,
        copies: PlaylistCopies,
    ): RemoveSource = RemoveSource(sources, marks, statuses, copies)

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

    /**
     * The one queue every section's load goes through, for as long as the app runs.
     *
     * Collected on the main thread, as the section screens collected their loads before it:
     * the importer moves its own work off that thread, and the trace section opened here has
     * to end on the thread it began on. Each load is a run of the stopwatch of its own, and
     * ends in the log line it always ended in -- see [settle].
     */
    @Provides
    @Singleton
    fun sectionLoads(
        loadSection: LoadSection,
        sources: SourceRepository,
        clock: TrustedTime,
        dispatchers: AppDispatchers,
    ): SectionLoads = SectionLoads(
        scope = CoroutineScope(SupervisorJob() + dispatchers.main),
        activeSource = sources.active().map { it?.id },
        load = { kind, force ->
            loadSection.load(kind, clock.nowMs(), force = force)
                .onStart { Trace.beginSection(CastivioTrace.FETCH) }
                .onCompletion { Trace.endSection() }
        },
        monotonicMs = SystemClock::elapsedRealtime,
        onRunStarted = { kind -> PerformanceLog.begin(kind.perfSection) },
        onAnswer = { _, load -> settle(load) },
    )

    /**
     * Tells the stopwatch how a load ended, from what the loader said and nothing inferred.
     *
     * `Ready` means the section was marked and nothing was imported: warm, with no import to
     * have an end. `Done` means the importer reached the end and committed: a full load, which
     * the log turns into a cache hit itself when every call came from OkHttp's cache.
     */
    private fun settle(load: SectionLoad) {
        when (load) {
            is SectionLoad.Ready -> PerformanceLog.settled(PerfMode.WARM, fullLoad = false)
            is SectionLoad.Done -> PerformanceLog.settled(PerfMode.COLD, fullLoad = true)
            is SectionLoad.Failed, is SectionLoad.NoSource -> PerformanceLog.settled(PerfMode.UNKNOWN, fullLoad = false)
            is SectionLoad.Loading, is SectionLoad.Queued -> Unit
        }
    }

    private val MediaKind.perfSection: PerfSection
        get() = when (this) {
            MediaKind.LIVE -> PerfSection.CHANNELS
            MediaKind.MOVIE -> PerfSection.MOVIES
            MediaKind.SERIES -> PerfSection.SERIES
            MediaKind.RADIO -> PerfSection.RADIO
        }

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

        // A page that could not be read is thrown, never returned as zero: zero is the end of
        // the list, and a failed page read as the end is how an import stopped at the page
        // that timed out and finished as a success. The engine tries the page again and
        // decides what a page that stays unreadable means. Only the error's name goes in the
        // message -- the address the call went to carries the device identity.
        override fun channels(page: Int, onChannel: (StalkerChannel) -> Unit): Int {
            val open = session() ?: throw IOException("the portal's session could not be opened")
            return when (val answer = api.channels(open, page, onChannel)) {
                is Outcome.Success -> answer.value
                is Outcome.Failure -> throw IOException("portal page $page failed: ${answer.error}")
            }
        }

        override fun items(kind: MediaKind, page: Int, onItem: (StalkerItem) -> Unit): Int {
            val open = session() ?: throw IOException("the portal's session could not be opened")
            return when (val answer = api.items(open, kind, page, onItem)) {
                is Outcome.Success -> answer.value
                is Outcome.Failure -> throw IOException("portal page $page failed: ${answer.error}")
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
