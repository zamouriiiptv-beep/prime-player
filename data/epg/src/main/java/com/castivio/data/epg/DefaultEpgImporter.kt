package com.castivio.data.epg

import com.castivio.core.common.AppDispatchers
import com.castivio.core.common.AppError
import com.castivio.data.networking.HttpStreamSource
import com.castivio.data.networking.RemoteRequest
import com.castivio.data.networking.RemoteResult
import com.castivio.data.parsing.EpgImportEngine
import com.castivio.data.parsing.XtreamUrls
import com.castivio.domain.EpgImporter
import com.castivio.domain.EpgProgress
import com.castivio.domain.EpgRetention
import com.castivio.domain.EpgSource
import com.castivio.domain.EpgSummary
import com.castivio.domain.EpgWriter
import com.castivio.domain.SourceRepository
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn

/**
 * Downloads a guide and streams it into storage.
 *
 * A guide is the biggest thing this app fetches — 100 MB of XML, usually gzipped —
 * so the same two savings as the catalogue apply, in order of value:
 *
 *  1. **A conditional request.** Guides are regenerated on a schedule; between
 *     regenerations a refresh is a `304`.
 *  2. **Retention at import.** Out-of-window programmes are never written, so the
 *     download is the only cost paid for a provider that ships three weeks of
 *     schedule.
 *
 * Progress is emitted per batch, so a slow guide shows movement instead of looking
 * hung, and cancellation stops the download rather than draining it.
 */
class DefaultEpgImporter(
    private val http: HttpStreamSource,
    private val writerFactory: () -> EpgWriter,
    private val sources: SourceRepository,
    private val dispatchers: AppDispatchers,
    private val retention: EpgRetention = EpgRetention.DEFAULT,
    private val clock: () -> Long = System::currentTimeMillis,
) : EpgImporter {

    override fun import(source: EpgSource): Flow<EpgProgress> = channelFlow {
        val stored = sources.activeNow()
        val sourceId = stored?.id ?: DEFAULT_SOURCE_ID
        val url = when (source) {
            is EpgSource.Xmltv -> source.url
            // The provider's own XMLTV endpoint. Used when the user has not
            // supplied a guide URL of their own.
            is EpgSource.XtreamShort -> XtreamUrls.xmltv(source.host, source.username, source.password)
        }

        val writer = writerFactory()
        val request = RemoteRequest(
            url = url,
            etag = null,
            lastModified = null,
            userAgent = stored?.userAgent,
        )

        val result = http.stream(request) { reader ->
            EpgImportEngine(writer, retention = retention, clock = clock).importXmltv(
                sourceId = sourceId,
                reader = reader,
                onProgress = { trySend(it) },
                isCancelled = { !isActive },
            )
        }

        when (result) {
            is RemoteResult.NotModified -> {
                writer.abort(null)
                trySend(EpgProgress.Done(0, 0))
            }

            is RemoteResult.Failure -> {
                writer.abort(result.cause)
                trySend(EpgProgress.Failed(result.error))
            }

            is RemoteResult.Success -> {
                val summary: EpgSummary = result.value
                if (!summary.cancelled) {
                    sources.recordEpgImport(sourceId, clock())
                }
                if (summary.programmes == 0 && !summary.cancelled) {
                    // A guide that parsed to nothing is a provider problem worth
                    // surfacing: an empty EPG looks identical to a broken one.
                    trySend(EpgProgress.Failed(AppError.MALFORMED_PLAYLIST))
                }
            }
        }
        // No awaitClose: the work here is synchronous and finishes, so the flow
        // must complete when it returns. Keeping the channel open would leave every
        // collector waiting forever for a "done" that already happened.
    }.flowOn(dispatchers.io)

    private companion object {
        /** Used when a guide is imported before any source is active. */
        const val DEFAULT_SOURCE_ID = "epg"
    }
}
