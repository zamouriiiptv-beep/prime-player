package com.castivio.data.playlist

import android.util.Log
import com.castivio.core.common.AppError
import com.castivio.core.common.AppDispatchers
import com.castivio.core.platform.PerformanceLog
import com.castivio.data.networking.CallMetrics
import com.castivio.data.networking.HttpStreamSource
import com.castivio.data.networking.Received
import com.castivio.data.networking.RemoteRequest
import com.castivio.data.networking.RemoteResult
import com.castivio.data.parsing.CatalogImportEngine
import com.castivio.data.parsing.SourceIds
import com.castivio.data.parsing.StalkerImportEngine
import com.castivio.data.parsing.XtreamImportEngine
import com.castivio.domain.CatalogImporter
import com.castivio.domain.CatalogItem
import com.castivio.domain.CatalogWriter
import com.castivio.domain.ImportMode
import com.castivio.domain.MediaGroup
import com.castivio.domain.MediaKind
import com.castivio.domain.ImportProgress
import com.castivio.domain.ImportSummary
import com.castivio.domain.PlaylistSource
import com.castivio.domain.ProviderSource
import com.castivio.domain.SourceRepository
import com.castivio.domain.SyncState
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import java.io.Reader

/**
 * Turns a configured provider into rows on disk.
 *
 * This is where the three source kinds converge. Each has a different cheapest
 * path and the differences are the point:
 *
 *  - **M3U URL** — a conditional GET. If the provider answers `304`, the import is
 *    over in one request. If it has no validators, the stream is hashed as it is
 *    read, so an unchanged playlist still skips the *import* even though the
 *    download could not be skipped.
 *  - **Local file** — no validators exist at all, so the hash is the only signal.
 *  - **Xtream** — no full download in the first place: categories, then only what
 *    is asked for.
 *
 * Progress is emitted as it happens, because the UI shows content while the import
 * is still running. Cancellation propagates into the engines, which stop reading
 * the source rather than draining it.
 */
class DefaultCatalogImporter(
    private val http: HttpStreamSource,
    private val writerFactory: () -> CatalogWriter,
    private val sources: SourceRepository,
    private val localFiles: LocalPlaylistReader,
    private val xtreamApiFactory: (PlaylistSource.Xtream) -> XtreamImportEngine.Api,
    /**
     * A portal's channel list, behind the engine's contract.
     *
     * Nullable, and null in a build that cannot supply the device address a portal
     * handshake needs. A portal then reports [AppError.NOT_CONFIGURED] instead of
     * failing somewhere further in, and no other kind of source is affected.
     */
    private val portalApiFactory: ((PlaylistSource.Portal) -> StalkerImportEngine.Api?)? = null,
    private val dispatchers: AppDispatchers,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Where the playlist import's one-line outcome goes. Logcat, under the tag the
     * network tally already uses; a parameter so a test can read what was written and
     * prove that no address or credential is in it.
     */
    private val log: (String) -> Unit = { Log.i(CallMetrics.TAG, it) },
) : CatalogImporter {

    override fun import(source: PlaylistSource): Flow<ImportProgress> = channelFlow {
        val sourceId = SourceIds.of(source)
        val stored = sources.get(sourceId)
        val cancelled = { !isActive }
        val onProgress: (ImportProgress) -> Unit = { trySend(it) }

        when (source) {
            is PlaylistSource.M3u -> importM3uUrl(source, sourceId, stored, onProgress, cancelled)
            is PlaylistSource.LocalFile -> importLocalFile(source, sourceId, onProgress, cancelled)
            is PlaylistSource.Xtream -> importXtream(source, sourceId, onProgress, cancelled)
            // The whole catalogue: every section the portal serves, one call each.
            is PlaylistSource.Portal -> for (kind in MediaKind.entries) {
                if (cancelled()) break
                importPortal(
                    source = source,
                    sourceId = sourceId,
                    kind = kind,
                    // The first section replaces what was there; the three after it add
                    // to the same generation. A replacing write per kind would prune
                    // the previous one at `finish()`.
                    mode = if (kind == MediaKind.entries.first()) ImportMode.REPLACE else ImportMode.APPEND,
                    onProgress = onProgress,
                    cancelled = cancelled,
                )
            }
        }
        // No awaitClose: the work here is synchronous and finishes, so the flow
        // must complete when it returns. Keeping the channel open would leave every
        // collector waiting forever for a "done" that already happened.
    }.flowOn(dispatchers.io)

    /**
     * One kind, for a section that was just opened for the first time.
     *
     * Only Xtream can honour it. Live, VOD and series are three separate endpoints
     * there, so asking for one is a smaller *download* and not merely a smaller
     * write — which is the whole point of fetching a section when it is opened.
     *
     * Everything else falls through to [import], and that is not a shortcut: an M3U
     * playlist is one file holding all three kinds, and there is no request that asks
     * it for only the films. The caller is told as much by
     * [com.castivio.domain.carriesEveryKind], so the first section opened marks the
     * others as fetched too instead of downloading the same file three times.
     *
     * [ImportMode.APPEND], necessarily. A replacing write prunes every row of another
     * generation at `finish()`, which after a per-kind import is every row of every
     * other kind — the films would delete the channels.
     */
    override fun importKind(source: PlaylistSource, kind: MediaKind): Flow<ImportProgress> =
        when (source) {
            is PlaylistSource.Xtream -> channelFlow {
                val sourceId = SourceIds.of(source)
                val writer = writerFactory()
                // Phase B instrumentation, and the only reason these two lines exist:
                // how many requests one section costs is a property of the *user's*
                // provider, so it cannot be learned from this repository or from a
                // fixture -- only from a real subscription on a real device. The window
                // is this import; the tally is printed at the end of it.
                //
                // Nothing here changes what is requested, in what order, or with what
                // headers. See `CallMetrics`.
                CallMetrics.reset()
                // The limit actually in force, read from the engine rather than restated,
                // so the panel cannot claim a concurrency the import is not using.
                PerformanceLog.importConcurrency(XtreamImportEngine.DEFAULT_CONCURRENCY)
                try {
                    val summary = XtreamImportEngine(writer, clock = clock).importCatalogue(
                        sourceId = sourceId,
                        api = xtreamApiFactory(source),
                        kinds = setOf(kind),
                        onProgress = { progress ->
                            // The importer's own running totals, forwarded to the panel
                            // as they arrive. Counting them a second time here is how
                            // the progress line and the panel end up disagreeing.
                            //
                            // The byte count is added *here* rather than in the engine,
                            // and that is the layering rather than a convenience: the
                            // engine is pure Kotlin and takes plain `Reader`s, so it has
                            // no idea how much came down the socket. This class is the
                            // one place that sees both the parser's progress and the
                            // HTTP counters, so it is where the two meet.
                            if (progress is ImportProgress.Importing) {
                                PerformanceLog.importProgress(progress.groupsReady, progress.itemsImported)
                                trySend(progress.copy(bytes = CallMetrics.totalBytes()))
                            } else {
                                trySend(progress)
                            }
                        },
                        isCancelled = { !isActive },
                        mode = ImportMode.APPEND,
                    )
                    // No validators for Xtream, and none to preserve: `record` reads
                    // the stored row and writes back what this pass learned.
                    record(sourceId, summary, etag = null, lastModified = null, contentHash = null)
                } catch (e: Exception) {
                    trySend(ImportProgress.Failed(e.toAppError()))
                } finally {
                    // `finally`, so a cancelled or failed section still reports what it
                    // managed to ask for -- which is the interesting case for a user
                    // whose first open times out.
                    CallMetrics.logSummary("import $kind")
                    // And the same figures to the on-device panel. Published from
                    // here rather than read from a screen because this is what owns
                    // the window: the counters were reset above, and this is where
                    // they stop being about this section.
                    PerformanceLog.network(
                        requests = CallMetrics.totalCalls().toInt(),
                        serverMs = CallMetrics.callTimeMs(),
                        cacheHits = CallMetrics.cacheHits().toInt(),
                    )
                }
            }.flowOn(dispatchers.io)

            // A portal answers `itv`, `vod`, `series` and `radio` on separate calls, so
            // a section here is a real request rather than a filter over one download —
            // which is what lets Films be fetched when Films is opened and the rest be
            // left alone. `APPEND` for the reason stated above: a replacing write would
            // prune every row of every other kind at `finish()`.
            is PlaylistSource.Portal -> channelFlow {
                importPortal(
                    source = source,
                    sourceId = SourceIds.of(source),
                    kind = kind,
                    mode = ImportMode.APPEND,
                    onProgress = { trySend(it) },
                    cancelled = { !isActive },
                )
            }.flowOn(dispatchers.io)

            else -> import(source)
        }

    /**
     * Whether a refresh can be skipped entirely.
     *
     * Asks the provider with one small conditional request rather than trusting a
     * timestamp: a playlist that changed an hour ago should be picked up, and one
     * that has not changed in a month should never be downloaded again.
     */
    override suspend fun isUpToDate(source: PlaylistSource): Boolean {
        val stored = sources.get(SourceIds.of(source)) ?: return false
        if (stored.sync.lastImportAtMs == null || stored.sync.itemCount == 0) return false
        val url = when (source) {
            is PlaylistSource.M3u -> source.url
            // Xtream is category-addressable, so "up to date" is not a useful
            // question: nothing was downloaded wholesale to begin with.
            else -> return false
        }
        if (!stored.sync.hasValidators) return false

        return when (
            http.hasChanged(
                RemoteRequest(
                    url = url,
                    etag = stored.sync.etag,
                    lastModified = stored.sync.lastModified,
                    userAgent = stored.userAgent ?: (source as? PlaylistSource.M3u)?.userAgent,
                    noCache = true,
                ),
            )
        ) {
            is RemoteResult.NotModified -> true
            else -> false
        }
    }

    private suspend fun importM3uUrl(
        source: PlaylistSource.M3u,
        sourceId: String,
        stored: ProviderSource?,
        onProgress: (ImportProgress) -> Unit,
        cancelled: () -> Boolean,
    ) {
        onProgress(ImportProgress.CheckingForChanges)
        // The figures the Xtream path gives the on-device panel, for the same window: one
        // request, what it cost and how much arrived. A playlist panel that stayed on the
        // previous subscription's numbers was showing figures nobody had measured. Nothing
        // here changes what is requested.
        CallMetrics.reset()
        PerformanceLog.importConcurrency(1)
        PerformanceLog.requestStarted()

        val writer = WatchedWriter(writerFactory())
        val request = RemoteRequest(
            url = source.url,
            etag = stored?.sync?.etag,
            lastModified = stored?.sync?.lastModified,
            userAgent = source.userAgent ?: stored?.userAgent,
        )
        val startedAt = clock()
        var answer: Received? = null
        // How far a failure had got, for the screen that reports it: entries read off the
        // stream, and rows the engine had committed when it last reported.
        var read = 0
        var saved = 0

        val result: RemoteResult<ImportSummary> = try {
            http.streamCounting(request) { reader, received ->
                answer = received
                runImport(
                    writer = writer,
                    sourceId = sourceId,
                    reader = reader,
                    onProgress = { progress ->
                        if (progress is ImportProgress.Importing) {
                            saved = progress.itemsImported
                            PerformanceLog.importProgress(progress.groupsReady, progress.itemsImported)
                            onProgress(progress.copy(bytes = received.bytes))
                        } else {
                            onProgress(progress)
                        }
                    },
                    cancelled = cancelled,
                    onEntryRead = { read++ },
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Not the network: what arrived could not be turned into rows -- the parser or
            // the writer threw. It used to escape this flow and take the screen's scope
            // with it; it is now a failure the user can read and retry, the way the Xtream
            // path has always treated the same thing.
            RemoteResult.Failure(
                error = AppError.UNKNOWN,
                statusCode = answer?.statusCode,
                cause = e,
                contentType = answer?.contentType,
                stage = RemoteResult.Stage.BODY,
            )
        }

        try {
            when (result) {
                is RemoteResult.NotModified -> {
                    // Nothing downloaded, nothing parsed, nothing written.
                    writer.abort(null)
                    onProgress(ImportProgress.UpToDate)
                }

                is RemoteResult.Failure -> {
                    PerformanceLog.requestFailed()
                    writer.abort(result.cause)
                    onProgress(
                        ImportProgress.Failed(
                            error = result.error,
                            diagnosis = M3uImportReport.diagnosisOf(
                                failure = result,
                                savingFailed = writer.threw,
                                receivedBytes = answer?.bytes,
                                itemsRead = read,
                                itemsSaved = saved,
                            ),
                        ),
                    )
                }

                is RemoteResult.Success -> {
                    val summary = result.value
                    // The hash is only meaningful for a stream read to the end.
                    val hash = if (summary.cancelled) stored?.sync?.contentHash else result.contentHash
                    record(sourceId, summary, result.etag, result.lastModified, hash)
                }
            }
        } finally {
            // One line saying how it ended -- see `M3uImportReport` for what it may and
            // may not contain -- then the window's tally, both under the tag the Xtream
            // summary already uses, so one `adb logcat -s CastivioNet` shows either.
            log(M3uImportReport.line(result, M3uImportReport.schemeOf(source.url), clock() - startedAt, answer?.bytes))
            CallMetrics.report("import m3u").forEach(log)
            PerformanceLog.network(
                requests = CallMetrics.totalCalls().toInt(),
                serverMs = CallMetrics.callTimeMs(),
                cacheHits = CallMetrics.cacheHits().toInt(),
            )
        }
    }

    private suspend fun importLocalFile(
        source: PlaylistSource.LocalFile,
        sourceId: String,
        onProgress: (ImportProgress) -> Unit,
        cancelled: () -> Boolean,
    ) {
        val writer = writerFactory()
        val opened = localFiles.open(source.uri)
        if (opened == null) {
            writer.abort(null)
            // A file the user picked and later moved or deleted: not a network
            // problem, and worth saying so.
            onProgress(ImportProgress.Failed(AppError.NOT_FOUND))
            return
        }
        try {
            val summary = opened.reader.use { reader ->
                runImport(writer, sourceId, reader, onProgress, cancelled)
            }
            record(sourceId, summary, etag = null, lastModified = null, contentHash = opened.fingerprint())
        } catch (e: Exception) {
            writer.abort(e)
            onProgress(ImportProgress.Failed(AppError.MALFORMED_PLAYLIST))
        }
    }

    /**
     * A portal's channels, written as they arrive.
     *
     * One kind, and that is the honest shape of it: a Stalker `itv` list is television,
     * and the film and series calls are answered by so few installations, and so
     * differently, that importing a guess would fill a library with rows that fail when
     * pressed. `carriesEveryKind` reports that, so a section that a portal has nothing
     * for says it has nothing rather than spinning.
     */
    private fun importPortal(
        source: PlaylistSource.Portal,
        sourceId: String,
        kind: MediaKind,
        mode: ImportMode,
        onProgress: (ImportProgress) -> Unit,
        cancelled: () -> Boolean,
    ) {
        val api = portalApiFactory?.invoke(source)
        if (api == null) {
            onProgress(ImportProgress.Failed(AppError.NOT_CONFIGURED))
            return
        }
        val writer = writerFactory()
        val startedAt = clock()
        try {
            val written = StalkerImportEngine().importKind(
                sourceId = sourceId,
                kind = kind,
                api = api,
                writer = writer,
                mode = mode,
                onProgress = onProgress,
                isCancelled = cancelled,
            )
            if (cancelled()) {
                writer.abort(null)
                return
            }
            writer.finish(
                ImportSummary(
                    sourceId = sourceId,
                    items = written,
                    groups = 0,
                    skipped = 0,
                    byKind = mapOf(kind to written),
                    durationMs = clock() - startedAt,
                ),
            )
            onProgress(ImportProgress.Done(written, clock() - startedAt))
        } catch (e: Exception) {
            writer.abort(e)
            onProgress(ImportProgress.Failed(AppError.UNKNOWN))
        }
    }

    private suspend fun importXtream(
        source: PlaylistSource.Xtream,
        sourceId: String,
        onProgress: (ImportProgress) -> Unit,
        cancelled: () -> Boolean,
    ) {
        val writer = writerFactory()
        try {
            val summary = XtreamImportEngine(writer, clock = clock).importCatalogue(
                sourceId = sourceId,
                api = xtreamApiFactory(source),
                kinds = XtreamImportEngine.DEFAULT_KINDS,
                onProgress = onProgress,
                isCancelled = cancelled,
            )
            // No validators to store: an Xtream catalogue is assembled from many
            // responses, so freshness is the import timestamp and nothing else.
            record(sourceId, summary, etag = null, lastModified = null, contentHash = null)
        } catch (e: Exception) {
            onProgress(ImportProgress.Failed(e.toAppError()))
        }
    }

    /**
     * @param onEntryRead told of every entry line as it passes on its way to the parser, so
     *   a failure can say how much of the playlist had been read. Counted out here rather
     *   than in the parser, which is left exactly as it is; null costs nothing per line.
     */
    private fun runImport(
        writer: CatalogWriter,
        sourceId: String,
        reader: Reader,
        onProgress: (ImportProgress) -> Unit,
        cancelled: () -> Boolean,
        onEntryRead: (() -> Unit)? = null,
    ): ImportSummary = CatalogImportEngine(writer, clock = clock).importM3u(
        sourceId = sourceId,
        // Line by line: the whole point is that the playlist is never a String.
        lines = reader.buffered().lineSequence().let { lines ->
            if (onEntryRead == null) lines else lines.onEach { if (it.startsWith(M3U_ENTRY)) onEntryRead() }
        },
        onProgress = onProgress,
        isCancelled = cancelled,
    )

    private suspend fun record(
        sourceId: String,
        summary: ImportSummary,
        etag: String?,
        lastModified: String?,
        contentHash: String?,
    ) {
        // A cancelled import leaves a partial catalogue, so it must not be recorded
        // as a complete one — the next launch has to finish the job.
        if (summary.cancelled) return
        sources.recordCatalogueImport(
            sourceId,
            SyncState(
                etag = etag,
                lastModified = lastModified,
                contentHash = contentHash,
                lastImportAtMs = clock(),
                itemCount = summary.items,
            ),
        )
    }

    private fun Exception.toAppError(): AppError = when (this) {
        is java.net.SocketTimeoutException -> AppError.TIMEOUT
        is java.net.UnknownHostException -> AppError.NETWORK_UNAVAILABLE
        is com.castivio.data.parsing.JsonFormatException -> AppError.MALFORMED_PLAYLIST
        is java.io.IOException -> AppError.NETWORK_UNAVAILABLE
        else -> AppError.UNKNOWN
    }
}

/**
 * The line an M3U entry starts with, as the parser reads it — counted on the way past,
 * never parsed here.
 */
private const val M3U_ENTRY = "#EXTINF:"

/**
 * The writer, with a note of whether it was the one that threw.
 *
 * An exception out of a playlist import has two possible authors — the download and the
 * parser on one side, SQLite on the other — and they are different things to report: a
 * playlist that could not be read, and one that could not be kept. The class name does
 * not always say which (an `IllegalStateException` can come from either), so the writer
 * is asked directly. Every call goes through to the real writer unchanged.
 */
private class WatchedWriter(private val delegate: CatalogWriter) : CatalogWriter by delegate {

    var threw = false
        private set

    override fun begin(sourceId: String, mode: ImportMode) = watched { delegate.begin(sourceId, mode) }

    override fun writeGroups(groups: List<MediaGroup>) = watched { delegate.writeGroups(groups) }

    override fun writeItems(items: List<CatalogItem>) = watched { delegate.writeItems(items) }

    override fun commit() = watched { delegate.commit() }

    override fun finish(summary: ImportSummary) = watched { delegate.finish(summary) }

    private inline fun watched(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            threw = true
            throw e
        }
    }
}
