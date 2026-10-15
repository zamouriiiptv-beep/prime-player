package com.castivio.data.playlist

import android.util.Log
import com.castivio.core.common.AppError
import com.castivio.core.common.AppDispatchers
import com.castivio.core.platform.PerfPhase
import com.castivio.core.platform.PerformanceLog
import com.castivio.data.networking.CallMetrics
import com.castivio.data.networking.HttpStatusException
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
import com.castivio.domain.FetchDiagnosis
import com.castivio.domain.ImportMode
import com.castivio.domain.MediaGroup
import com.castivio.domain.MediaKind
import com.castivio.domain.ImportProgress
import com.castivio.domain.ImportSummary
import com.castivio.domain.PlaylistSource
import com.castivio.domain.ProviderSource
import com.castivio.domain.SourceRepository
import com.castivio.domain.SyncState
import com.castivio.domain.panelFailureAllowsPlaylist
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
            is PlaylistSource.M3u -> {
                val panel = source.xtream
                if (panel == null) {
                    importM3uUrl(source, sourceId, stored, onProgress, cancelled)
                } else {
                    // The whole catalogue through the panel, as for an Xtream subscription,
                    // and the playlist once when the panel's API says no.
                    importXtream(panel, sourceId, onProgress, cancelled)
                        ?.let { playlistAfter(it, source, sourceId, stored, onProgress, cancelled) }
                }
            }
            is PlaylistSource.LocalFile -> importLocalFile(source, sourceId, onProgress, cancelled)
            is PlaylistSource.Xtream -> importXtream(source, sourceId, onProgress, cancelled)?.let(onProgress)
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
     *
     * An M3U read through its panel — see [PlaylistSource.M3u.xtream] — takes the Xtream
     * path, under its own id, and falls back to its playlist once when the panel's API
     * says no.
     */
    override fun importKind(source: PlaylistSource, kind: MediaKind): Flow<ImportProgress> =
        when (source) {
            is PlaylistSource.Xtream -> channelFlow {
                importXtreamKind(
                    account = source,
                    sourceId = SourceIds.of(source),
                    kind = kind,
                    mode = ImportMode.APPEND,
                    onProgress = { trySend(it) },
                    cancelled = { !isActive },
                )?.let { trySend(it) }
            }.flowOn(dispatchers.io)

            is PlaylistSource.M3u -> source.xtream?.let { panel ->
                channelFlow {
                    val sourceId = SourceIds.of(source)
                    val stored = sources.get(sourceId)
                    val onProgress: (ImportProgress) -> Unit = { trySend(it) }
                    val cancelled = { !isActive }
                    importXtreamKind(
                        account = panel,
                        sourceId = sourceId,
                        kind = kind,
                        mode = firstReadingMode(stored),
                        onProgress = onProgress,
                        cancelled = cancelled,
                    )?.let { playlistAfter(it, source, sourceId, stored, onProgress, cancelled) }
                }.flowOn(dispatchers.io)
            } ?: import(source)

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
            // Read through its panel, a playlist is the Xtream case below.
            is PlaylistSource.M3u -> if (source.xtream != null) return false else source.url
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

    /**
     * @param after the panel's failure, when this is the playlist read in its place — see
     *   [playlistAfter]. Carried into this import's own failure, so the screen can say
     *   both.
     */
    private suspend fun importM3uUrl(
        source: PlaylistSource.M3u,
        sourceId: String,
        stored: ProviderSource?,
        onProgress: (ImportProgress) -> Unit,
        cancelled: () -> Boolean,
        after: FetchDiagnosis? = null,
    ) {
        onProgress(ImportProgress.CheckingForChanges)
        // The figures the Xtream path gives the on-device panel, for the same window: one
        // request, what it cost and how much arrived. A playlist panel that stayed on the
        // previous subscription's numbers was showing figures nobody had measured. Nothing
        // here changes what is requested.
        CallMetrics.reset()
        PerformanceLog.importConcurrency(1)
        PerformanceLog.phase(PerfPhase.IMPORT)
        PerformanceLog.requestStarted(PLAYLIST_ACTION)

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
                            // The playlist engine reports after each committed batch, so
                            // its first report is its first commit.
                            PerformanceLog.phase(PerfPhase.FIRST_COMMIT)
                            PerformanceLog.importProgress(progress.groupsReady, progress.itemsImported)
                            onProgress(progress.copy(bytes = received.bytes))
                        } else {
                            if (progress is ImportProgress.Done) PerformanceLog.phase(PerfPhase.FINISHED)
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
                    PerformanceLog.requestFailed(
                        PLAYLIST_ACTION,
                        result.statusCode?.let { "HTTP $it" } ?: result.cause?.javaClass?.simpleName ?: result.error.name,
                    )
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
                                after = after,
                            ),
                        ),
                    )
                }

                is RemoteResult.Success -> {
                    val summary = result.value
                    // The hash is only meaningful for a stream read to the end.
                    val hash = if (summary.cancelled) stored?.sync?.contentHash else result.contentHash
                    record(sourceId, summary, result.etag, result.lastModified, hash)
                    PerformanceLog.phase(PerfPhase.RECORDED)
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
                waitMs = CallMetrics.waitTimeMs(),
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
        // The same window the other two paths open, so a portal's load is measured in the
        // same terms instead of showing whatever the previous section's import left behind.
        // One page at a time, which is what the engine does.
        CallMetrics.reset()
        PerformanceLog.importConcurrency(1)
        PerformanceLog.phase(PerfPhase.IMPORT)
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
            PerformanceLog.phase(PerfPhase.FINISHED)
            onProgress(ImportProgress.Done(written, clock() - startedAt))
        } catch (e: Exception) {
            writer.abort(e)
            onProgress(ImportProgress.Failed(AppError.UNKNOWN))
        } finally {
            CallMetrics.report("import portal $kind").forEach(log)
            PerformanceLog.network(
                requests = CallMetrics.totalCalls().toInt(),
                serverMs = CallMetrics.callTimeMs(),
                cacheHits = CallMetrics.cacheHits().toInt(),
                waitMs = CallMetrics.waitTimeMs(),
            )
        }
    }

    /**
     * The whole catalogue from a panel's API, written under [sourceId].
     *
     * @return null when it arrived or was cancelled; otherwise the failure, with its
     *   diagnosis, for the caller to report — or, for a playlist read through its panel,
     *   to fall back from.
     */
    private suspend fun importXtream(
        account: PlaylistSource.Xtream,
        sourceId: String,
        onProgress: (ImportProgress) -> Unit,
        cancelled: () -> Boolean,
    ): ImportProgress.Failed? {
        val writer = WatchedWriter(writerFactory())
        CallMetrics.reset()
        val startedAt = clock()
        var saved = 0
        return try {
            val summary = XtreamImportEngine(writer, clock = clock).importCatalogue(
                sourceId = sourceId,
                api = xtreamApiFactory(account),
                kinds = XtreamImportEngine.DEFAULT_KINDS,
                onProgress = { progress ->
                    if (progress is ImportProgress.Importing) saved = progress.itemsImported
                    onProgress(progress)
                },
                isCancelled = cancelled,
            )
            // No validators to store: an Xtream catalogue is assembled from many
            // responses, so freshness is the import timestamp and nothing else.
            record(sourceId, summary, etag = null, lastModified = null, contentHash = null)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            xtreamFailure(e, writer, saved, clock() - startedAt)
        }
    }

    /**
     * One section from a panel's API, written under [sourceId].
     *
     * The Xtream subscription's own path, and the one a playlist read through its panel
     * takes too: under the playlist's id, so its rows, favourites and history stay with
     * the subscription the user added.
     *
     * @return null when the section arrived or was cancelled; otherwise the failure, with
     *   its diagnosis, for the caller to report or to fall back from.
     */
    private suspend fun importXtreamKind(
        account: PlaylistSource.Xtream,
        sourceId: String,
        kind: MediaKind,
        mode: ImportMode,
        onProgress: (ImportProgress) -> Unit,
        cancelled: () -> Boolean,
    ): ImportProgress.Failed? {
        val writer = WatchedWriter(writerFactory())
        // Phase B instrumentation, and the only reason these two lines exist: how many
        // requests one section costs is a property of the *user's* provider, so it cannot
        // be learned from this repository or from a fixture -- only from a real
        // subscription on a real device. The window is this import; the tally is printed
        // at the end of it.
        //
        // Nothing here changes what is requested, in what order, or with what headers.
        // See `CallMetrics`.
        CallMetrics.reset()
        // The limit actually in force, read from the engine rather than restated, so the
        // panel cannot claim a concurrency the import is not using.
        PerformanceLog.importConcurrency(XtreamImportEngine.DEFAULT_CONCURRENCY)
        PerformanceLog.phase(PerfPhase.IMPORT)
        val startedAt = clock()
        var saved = 0
        return try {
            val summary = XtreamImportEngine(writer, clock = clock).importCatalogue(
                sourceId = sourceId,
                api = xtreamApiFactory(account),
                kinds = setOf(kind),
                onStreams = { report ->
                    PerformanceLog.note(NOTE_STREAMS, report.toString())
                    log("import $kind: $report")
                },
                onPhase = { phase -> PerformanceLog.phase(phase.perf) },
                onProgress = { progress ->
                    // The importer's own running totals, forwarded to the panel as they
                    // arrive. Counting them a second time here is how the progress line
                    // and the panel end up disagreeing.
                    //
                    // The byte count is added *here* rather than in the engine, and that
                    // is the layering rather than a convenience: the engine is pure Kotlin
                    // and takes plain `Reader`s, so it has no idea how much came down the
                    // socket. This class is the one place that sees both the parser's
                    // progress and the HTTP counters, so it is where the two meet.
                    if (progress is ImportProgress.Importing) {
                        saved = progress.itemsImported
                        PerformanceLog.importProgress(progress.groupsReady, progress.itemsImported)
                        onProgress(progress.copy(bytes = CallMetrics.totalBytes()))
                    } else {
                        onProgress(progress)
                    }
                },
                isCancelled = cancelled,
                mode = mode,
            )
            // No validators for Xtream, and none to preserve: `record` reads the stored
            // row and writes back what this pass learned.
            record(sourceId, summary, etag = null, lastModified = null, contentHash = null)
            PerformanceLog.phase(PerfPhase.RECORDED)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            xtreamFailure(e, writer, saved, clock() - startedAt)
        } finally {
            // `finally`, so a cancelled or failed section still reports what it managed
            // to ask for -- which is the interesting case for a user whose first open
            // times out. Through [log], which is `Log.i` under the same tag on a device.
            CallMetrics.report("import $kind").forEach(log)
            // And the same figures to the on-device panel. Published from here rather
            // than read from a screen because this is what owns the window: the counters
            // were reset above, and this is where they stop being about this section.
            PerformanceLog.network(
                requests = CallMetrics.totalCalls().toInt(),
                serverMs = CallMetrics.callTimeMs(),
                cacheHits = CallMetrics.cacheHits().toInt(),
                waitMs = CallMetrics.waitTimeMs(),
            )
        }
    }

    /**
     * A panel's failure as the screen and the log are told it: the error, and the figures
     * — see [XtreamImportReport] for what they may and may not contain.
     */
    private fun xtreamFailure(e: Exception, writer: WatchedWriter, saved: Int, tookMs: Long): ImportProgress.Failed {
        val error = e.toAppError()
        val diagnosis = XtreamImportReport.diagnosisOf(
            error = e,
            savingFailed = writer.threw,
            receivedBytes = CallMetrics.totalBytes().takeIf { CallMetrics.totalCalls() > 0 },
            itemsRead = writer.written,
            itemsSaved = saved,
        )
        log(XtreamImportReport.line(error, diagnosis, tookMs))
        return ImportProgress.Failed(error, diagnosis)
    }

    /**
     * After a playlist's panel said no: the playlist itself, once — or the failure as it is.
     *
     * Once, because the panel already had its try and this is the other one: no loop, and
     * a press of Retry is a new attempt from the panel. Not at all when the network is the
     * reason or the database is — see [panelFailureAllowsPlaylist], which the refresh obeys
     * too. The playlist's own failure, if it has one, carries the panel's beside it, so the
     * screen can say what both said.
     *
     * And only while the panel has delivered nothing. The playlist arrives whole and keys
     * its channels by stream URL, so reading it after the panel has delivered a section
     * would replace that section's rows — and leave the favourites and history made on
     * them pointing at channels that are gone — to make up for another section the panel
     * refused. A panel that answered once has said what it serves; its refusal of the next
     * section is reported as it is.
     */
    private suspend fun playlistAfter(
        failure: ImportProgress.Failed,
        source: PlaylistSource.M3u,
        sourceId: String,
        stored: ProviderSource?,
        onProgress: (ImportProgress) -> Unit,
        cancelled: () -> Boolean,
    ) {
        val panelDelivered = stored?.sync?.lastImportAtMs != null
        if (panelDelivered || !panelFailureAllowsPlaylist(failure.error, failure.diagnosis?.stage)) {
            onProgress(failure)
            return
        }
        log("M3U fallback after Xtream failed error=${failure.error.name}: reading the playlist once")
        importM3uUrl(source, sourceId, stored, onProgress, cancelled, after = failure.diagnosis)
    }

    /**
     * How the first section a playlist reads through its panel is written: replacing.
     *
     * A subscription whose playlist was read to the end is never read through its panel
     * — see `asPlaylistSource` — but one whose playlist import was cut short can be, and
     * the rows that import left are keyed differently from the API's: the same channel
     * twice on screen. A replacing write prunes every row of an older generation when it
     * commits, which is exactly those. From the second section on, nothing is left over
     * and the write appends, for the reason [importKind] gives.
     *
     * The first is the one with no import recorded yet: the Xtream path records one when a
     * section arrives, and a playlist import that was cut short records nothing.
     */
    private fun firstReadingMode(stored: ProviderSource?): ImportMode =
        if (stored?.sync?.lastImportAtMs == null) ImportMode.REPLACE else ImportMode.APPEND

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
        // A status the panel answered with says what it meant. It is an IOException too,
        // so without this line a 403 read as "no network" -- the one reading that rules
        // out trying the playlist, and the sentence that sends a user to check their Wi-Fi.
        is HttpStatusException -> error
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

/** Which way a section's streams came, as the engine reported it. */
private const val NOTE_STREAMS = "streams"

/** A playlist download's name in the request counters, where a panel's is its action. */
private const val PLAYLIST_ACTION = "playlist"

/**
 * The engine's moments, as the panel names them. The engine is in a module that may not
 * see the platform's stopwatch, so it reports its own and they are translated here.
 */
private val XtreamImportEngine.Phase.perf: PerfPhase
    get() = when (this) {
        XtreamImportEngine.Phase.CATEGORIES -> PerfPhase.CATEGORIES
        XtreamImportEngine.Phase.GROUPS -> PerfPhase.GROUPS
        XtreamImportEngine.Phase.FIRST_COMMIT -> PerfPhase.FIRST_COMMIT
        XtreamImportEngine.Phase.STREAMS -> PerfPhase.STREAMS
        XtreamImportEngine.Phase.FINISHED -> PerfPhase.FINISHED
    }

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

    /**
     * Items handed to the database, committed or not: what a panel import had read when
     * it stopped. A playlist import counts its entries as they pass instead.
     */
    var written = 0
        private set

    override fun begin(sourceId: String, mode: ImportMode) = watched { delegate.begin(sourceId, mode) }

    override fun writeGroups(groups: List<MediaGroup>) = watched { delegate.writeGroups(groups) }

    override fun writeItems(items: List<CatalogItem>) = watched {
        written += items.size
        delegate.writeItems(items)
    }

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
