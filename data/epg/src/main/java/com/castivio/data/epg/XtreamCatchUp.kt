package com.castivio.data.epg

import android.util.Log
import com.castivio.core.common.AppDispatchers
import com.castivio.core.common.AppError
import com.castivio.core.common.Outcome
import com.castivio.core.platform.DevDiagnostics
import com.castivio.data.networking.XtreamHttpApi
import com.castivio.data.parsing.XtreamArchiveEntry
import com.castivio.data.parsing.XtreamUrls
import com.castivio.domain.ArchiveCover
import com.castivio.domain.ArchiveCoverage
import com.castivio.domain.ArchiveSpan
import com.castivio.domain.CatchUpChannel
import com.castivio.domain.CatchUpDepth
import com.castivio.domain.CatchUpGateway
import com.castivio.domain.CatchUpListing
import com.castivio.domain.CatchUpMemory
import com.castivio.domain.CatchUpPolicy
import com.castivio.domain.CatchUpProgramme
import com.castivio.domain.CatchUpUnsupported
import com.castivio.domain.ProviderSource
import com.castivio.domain.SourceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient

/**
 * Catch-up on an Xtream panel: the one protocol whose archive this app has been taught.
 *
 * ## What is asked, and when
 *
 * Nothing at construction, nothing when a channel is shown, nothing while the guide is browsed. [listing] makes one
 * request -- `get_simple_data_table` for one channel -- and it is made when a viewer opens that channel's catch-up
 * screen; [playbackUrl] makes none: an archived programme's address is built from the account and the programme's own
 * start, and the panel is first asked for it when the player opens it.
 *
 * ## Leaving the screen ends the request
 *
 * The request runs in a coroutine of this object's own and is awaited by the caller's, so a caller that is cancelled
 * stops waiting at once and the request is cancelled with it: the thread is interrupted, which a blocking read in the
 * middle of a reply honours at its next chunk, and the connection closes with the reader. A panel that is merely slow is
 * given [LISTING_TIMEOUT_MS] and then reported as a timeout, so the screen is never waiting on it for ever.
 *
 * ## What it remembers, and why
 *
 * A [CatchUpMemory], in the process only: a listing for ten minutes, so that opening the same channel's catch-up
 * twice -- or coming back from a programme that was not the one wanted -- is one request; and the fact that a channel
 * had nothing archived, for longer, so that its button stops offering what is not there. Nothing is written to disk:
 * an archive moves on with every programme that ends, and a stored one would be a guess about the past that is wrong
 * by the next day.
 *
 * ## Time zones
 *
 * The instant a programme began is the panel's own `start_timestamp`, shown in the viewer's zone. The address it is
 * played from is made from the panel's own `start` string -- the wall-clock time in the *panel's* zone, which is what an
 * archive is asked by -- carried over unchanged. Nothing here guesses the panel's zone.
 *
 * An instant that is not a programme's own -- a quarter of an hour before now, for a rewind -- has no such string, and is written
 * on the panel's clock from the offset the listing itself shows: the digits of a programme's `start` against the instant the panel
 * gave for it ([XtreamUrls.clockOffsetMs]). A panel whose listing gives no such pair offers no rewind, and is not guessed at.
 */
class XtreamCatchUp(
    private val client: OkHttpClient,
    private val sources: SourceRepository,
    dispatchers: AppDispatchers,
    private val memory: CatchUpMemory = CatchUpMemory(),
    private val apiFactory: (OkHttpClient, ProviderSource) -> ArchiveSource = ::httpArchive,
) : CatchUpGateway {

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)

    override suspend fun listing(channel: CatchUpChannel, nowMs: Long): CatchUpListing = when (val read = load(channel, nowMs)) {
        is Read.Unsupported -> CatchUpListing.NotSupported(read.reason)
        is Read.Failed -> CatchUpListing.Failed(read.error)
        is Read.Done -> CatchUpListing.Programmes(read.usable)
    }

    /**
     * What the channel's archive holds, for rewinding its live picture. **The same read as [listing], from the same memory:** a
     * channel whose catch-up was opened a few minutes ago, or whose rewind was asked about, is not asked of the provider again.
     * Nothing recorded in what the panel listed is [ArchiveCoverage.None], and a panel that did not answer is [ArchiveCoverage.Failed]
     * and not remembered as nothing.
     */
    override suspend fun coverage(channel: CatchUpChannel, nowMs: Long): ArchiveCoverage = when (val read = load(channel, nowMs)) {
        is Read.Unsupported -> ArchiveCoverage.None
        is Read.Failed -> ArchiveCoverage.Failed(read.error)
        is Read.Done -> read.cover?.takeIf { cover -> cover.spans.any { it.archived } }?.let(ArchiveCoverage::Known)
            ?: ArchiveCoverage.None
    }

    /** What one look at a channel's archive came to: the memory's answer, or the panel's. */
    private sealed interface Read {
        class Done(val usable: List<CatchUpProgramme>, val cover: ArchiveCover?) : Read
        class Unsupported(val reason: CatchUpUnsupported) : Read
        class Failed(val error: AppError) : Read
    }

    private suspend fun load(channel: CatchUpChannel, nowMs: Long): Read {
        val reference = channel.providerRef?.takeIf { it.isNotBlank() }
            ?: return Read.Unsupported(CatchUpUnsupported.NO_PROVIDER_REFERENCE)

        val (kept, keptCover) = synchronized(lock) { memory.listing(channel.mediaId, nowMs) to memory.cover(channel.mediaId, nowMs) }
        if (kept != null) return Read.Done(kept, keptCover)

        val source = when (val provider = sources.activeProvider()) {
            ActiveProvider.None -> return Read.Unsupported(CatchUpUnsupported.NO_ACTIVE_PROVIDER)
            ActiveProvider.Other -> return Read.Unsupported(CatchUpUnsupported.PROTOCOL)
            is ActiveProvider.Xtream -> provider.source
        }

        val api = apiFactory(client, source)
        val request = scope.async { runInterruptible { api.archiveListings(reference) } }
        val outcome = try {
            withTimeout(LISTING_TIMEOUT_MS) { request.await() }
        } catch (timeout: TimeoutCancellationException) {
            request.cancel()
            return Read.Failed(AppError.TIMEOUT)
        } catch (cancelled: CancellationException) {
            request.cancel()
            throw cancelled
        }

        val entries = when (outcome) {
            is Outcome.Failure -> return Read.Failed(outcome.error)
            is Outcome.Success -> outcome.value
        }
        val archived = entries.filter { it.archived }
        val programmes = archived.mapNotNull { it.toProgramme() }
        val usable = CatchUpPolicy.replayable(programmes, nowMs, channel.archiveHours)
        // What the panel really listed and how far back, for a development build: the archive's depth is measured here and
        // not taken from the days the panel claims. Numbers only.
        if (DevDiagnostics.enabled) {
            Log.i(
                TAG,
                CatchUpDepth.of(
                    receivedStarts = entries.map { it.startMs },
                    archivedStarts = archived.map { it.startMs },
                    replayableStarts = usable.map { it.startMs },
                    windowHours = channel.archiveHours,
                    nowMs = nowMs,
                ).format(),
            )
        }
        val cover = coverOf(entries, channel.archiveHours, nowMs)
        synchronized(lock) { memory.remember(channel.mediaId, usable, nowMs, cover) }
        return Read.Done(usable, cover)
    }

    /**
     * What the listing says about the archive as a whole: every programme that is inside the window and has begun -- or will
     * within the time this is kept -- and the panel's clock, read from the programme nearest to now that gives one.
     */
    private fun coverOf(entries: List<XtreamArchiveEntry>, windowHours: Int, nowMs: Long): ArchiveCover {
        val earliest = nowMs - windowHours.toLong() * CatchUpPolicy.HOUR_MS
        val spans = entries
            .filter { it.stopMs >= earliest && it.startMs <= nowMs + CatchUpMemory.LISTING_TTL_MS }
            .map { ArchiveSpan(startMs = it.startMs, stopMs = it.stopMs, archived = it.archived) }
        val offset = entries
            .sortedBy { kotlin.math.abs(it.startMs - nowMs) }
            .firstNotNullOfOrNull { XtreamUrls.clockOffsetMs(it.serverStart, it.startMs) }
        return ArchiveCover(spans = spans, clockOffsetMs = offset)
    }

    override suspend fun playbackUrl(channel: CatchUpChannel, programme: CatchUpProgramme): Outcome<String> {
        val reference = channel.providerRef?.takeIf { it.isNotBlank() } ?: return Outcome.Failure(AppError.NOT_FOUND)
        val source = (sources.activeProvider() as? ActiveProvider.Xtream)?.source
            ?: return Outcome.Failure(AppError.NOT_FOUND)
        val url = apiFactory(client, source).archiveUrl(reference, CatchUpPolicy.durationMinutes(programme), programme.locator)
        return Outcome.Success(url)
    }

    /**
     * An archive address for an instant that is not a programme's own start: the panel is asked for a minute on *its* clock, which is
     * the instant and the offset [cover] read from the programmes it listed. Made, not asked: no request.
     */
    override suspend fun playbackUrlAt(
        channel: CatchUpChannel,
        cover: ArchiveCover,
        atMs: Long,
        durationMinutes: Int,
    ): Outcome<String> {
        val reference = channel.providerRef?.takeIf { it.isNotBlank() } ?: return Outcome.Failure(AppError.NOT_FOUND)
        val source = (sources.activeProvider() as? ActiveProvider.Xtream)?.source
            ?: return Outcome.Failure(AppError.NOT_FOUND)
        val offset = cover.clockOffsetMs ?: return Outcome.Failure(AppError.NOT_FOUND)
        val start = XtreamUrls.timeshiftStartAt(atMs + offset)
        return Outcome.Success(apiFactory(client, source).archiveUrl(reference, durationMinutes.coerceAtLeast(1), start))
    }

    override fun knownEmpty(mediaId: String, nowMs: Long): Boolean = synchronized(lock) { memory.knownEmpty(mediaId, nowMs) }

    /** A panel, or an M3U link that names a panel account: the two readings that carry an archive. */
    override suspend fun supported(): Boolean = sources.activeProvider() is ActiveProvider.Xtream

    /** Null for an entry whose start cannot be written the way the archive path wants it: it cannot be addressed. */
    private fun XtreamArchiveEntry.toProgramme(): CatchUpProgramme? {
        val locator = XtreamUrls.timeshiftStart(serverStart) ?: return null
        return CatchUpProgramme(startMs = startMs, stopMs = stopMs, title = title, description = description, locator = locator)
    }

    companion object {
        private const val TAG = "CastivioCatchUp"

        /** How long a panel is given to answer a listing before it is reported as not answering. */
        const val LISTING_TIMEOUT_MS = 20_000L

        private fun httpArchive(client: OkHttpClient, source: ProviderSource): ArchiveSource {
            val api = XtreamHttpApi(
                client = client,
                base = source.url.orEmpty(),
                username = source.username.orEmpty(),
                password = source.password.orEmpty(),
                userAgent = source.userAgent,
            )
            return object : ArchiveSource {
                override fun archiveListings(streamId: String) = api.archiveListings(streamId)
                override fun archiveUrl(streamId: String, durationMinutes: Int, start: String) =
                    api.archiveUrl(streamId, durationMinutes, start)
            }
        }
    }
}

/**
 * One panel's archive, as [XtreamCatchUp] asks it: an interface so that the gateway is tested without a server, the
 * HTTP implementation being a wrapper over [XtreamHttpApi].
 */
interface ArchiveSource {

    /** Every programme the panel lists for the channel, the archive mark on each: blocking, and cancelled by interrupting the thread. */
    fun archiveListings(streamId: String): Outcome<List<XtreamArchiveEntry>>

    /** The address one of them plays from. Made, not asked: no request. */
    fun archiveUrl(streamId: String, durationMinutes: Int, start: String): String
}
