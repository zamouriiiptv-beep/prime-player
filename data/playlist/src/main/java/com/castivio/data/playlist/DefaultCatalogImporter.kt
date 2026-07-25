package com.castivio.data.playlist

import com.castivio.core.common.AppError
import com.castivio.core.common.AppDispatchers
import com.castivio.data.networking.HttpStreamSource
import com.castivio.data.networking.RemoteRequest
import com.castivio.data.networking.RemoteResult
import com.castivio.data.parsing.CatalogImportEngine
import com.castivio.data.parsing.SourceIds
import com.castivio.data.parsing.XtreamImportEngine
import com.castivio.domain.CatalogImporter
import com.castivio.domain.CatalogWriter
import com.castivio.domain.ImportProgress
import com.castivio.domain.ImportSummary
import com.castivio.domain.PlaylistSource
import com.castivio.domain.ProviderSource
import com.castivio.domain.SourceRepository
import com.castivio.domain.SyncState
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
    private val dispatchers: AppDispatchers,
    private val clock: () -> Long = System::currentTimeMillis,
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
            is PlaylistSource.Portal -> trySend(ImportProgress.Failed(AppError.NOT_FOUND))
        }
        // No awaitClose: the work here is synchronous and finishes, so the flow
        // must complete when it returns. Keeping the channel open would leave every
        // collector waiting forever for a "done" that already happened.
    }.flowOn(dispatchers.io)

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
        val writer = writerFactory()
        val request = RemoteRequest(
            url = source.url,
            etag = stored?.sync?.etag,
            lastModified = stored?.sync?.lastModified,
            userAgent = source.userAgent ?: stored?.userAgent,
        )

        when (val result = http.stream(request) { reader -> runImport(writer, sourceId, reader, onProgress, cancelled) }) {
            is RemoteResult.NotModified -> {
                // Nothing downloaded, nothing parsed, nothing written.
                writer.abort(null)
                onProgress(ImportProgress.UpToDate)
            }

            is RemoteResult.Failure -> {
                writer.abort(result.cause)
                onProgress(ImportProgress.Failed(result.error))
            }

            is RemoteResult.Success -> {
                val summary = result.value
                // The hash is only meaningful for a stream read to the end.
                val hash = if (summary.cancelled) stored?.sync?.contentHash else result.contentHash
                record(sourceId, summary, result.etag, result.lastModified, hash)
            }
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

    private fun runImport(
        writer: CatalogWriter,
        sourceId: String,
        reader: Reader,
        onProgress: (ImportProgress) -> Unit,
        cancelled: () -> Boolean,
    ): ImportSummary = CatalogImportEngine(writer, clock = clock).importM3u(
        sourceId = sourceId,
        // Line by line: the whole point is that the playlist is never a String.
        lines = reader.buffered().lineSequence(),
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
