package com.castivio.data.epg

import android.util.Log
import com.castivio.core.common.AppDispatchers
import com.castivio.core.platform.DevDiagnostics
import com.castivio.data.networking.XtreamHttpApi
import com.castivio.data.parsing.JsonFormatException
import com.castivio.data.parsing.WholeSectionUnsupported
import com.castivio.data.parsing.XtreamArchiveBackfill
import com.castivio.domain.BackfillOutcome
import com.castivio.domain.CatchUpBackfill
import com.castivio.domain.CatchUpBackfillSchedule
import com.castivio.domain.CatchUpBackfillMarks
import com.castivio.domain.CatchUpHours
import com.castivio.domain.CatchUpHoursWriter
import com.castivio.domain.MediaKind
import com.castivio.domain.ProviderSource
import com.castivio.domain.SourceRepository
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.IOException
import java.io.Reader
import java.util.concurrent.ConcurrentHashMap

/**
 * Gives an Xtream catalogue that was imported before catch-up existed the archive its channels keep, without importing it
 * again. See [CatchUpBackfill] for why that is not a re-import.
 *
 * ## What it does, in order
 *
 *  1. Nothing, unless the active subscription is an Xtream panel and has not been done: the mark is read first, so every
 *     later start returns here at once.
 *  2. A subscription with no catalogue yet is marked done and left alone: its first import writes the column with the rows.
 *  3. **One request**: the panel's list of live channels in one answer -- the whole-section call the import itself tries
 *     first -- read for three fields. Never one request per channel, and never the per-category walk.
 *  4. **One transaction**: the rows of the channels that keep an archive get their hours, by their stable ids.
 *  5. The mark is recorded.
 *
 * ## When it does not work
 *
 * A panel that refuses the whole list, answers with something that is not a list, or has no such call is marked
 * [BackfillOutcome.REFUSED] and left to its next ordinary import: no catalogue is read again and no request is made per
 * channel. A failure of the connection alone writes no mark, so the next start asks once more -- one small request, and
 * the only repetition there is.
 *
 * ## It never gets in the way
 *
 * **One in flight.** A second caller while one is running returns at once ([running]), and a subscription that has been
 * settled in this process is skipped without a request or a write ([settled]), so however often the caller asks it is one
 * request per subscription. **Low priority.** The blocking read and the write run at the lowest thread priority and give
 * it back, on an IO thread, and the read is interruptible, so a caller that is cancelled -- because something started that
 * the viewer is waiting on -- takes the request with it. **When** to ask is the caller's: see [CatchUpBackfillSchedule].
 */
class XtreamCatchUpBackfill(
    private val client: OkHttpClient,
    private val sources: SourceRepository,
    private val writer: () -> CatchUpHoursWriter,
    private val marks: CatchUpBackfillMarks,
    private val dispatchers: AppDispatchers,
    private val apiFactory: (OkHttpClient, ProviderSource) -> LiveList = ::httpLiveList,
) : CatchUpBackfill {

    /** Held while a request is out: a second caller has nothing to add and does not wait. */
    private val running = Mutex()

    /** The subscriptions settled in this process: done or refused, and not asked about again. */
    private val settled: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override suspend fun ensure() {
        if (!running.tryLock()) return
        try {
            work()
        } finally {
            running.unlock()
        }
    }

    private suspend fun work() {
        withContext(dispatchers.io) {
            val source = (sources.activeProvider() as? ActiveProvider.Xtream)?.source ?: return@withContext
            if (source.id in settled) return@withContext
            if (marks.outcomeOf(source.id) != null) {
                settled += source.id
                return@withContext
            }

            if (source.sync.lastImportAtMs == null || source.sync.itemCount == 0) {
                marks.record(source.id, BackfillOutcome.DONE)
                settled += source.id
                return@withContext
            }

            val startedAt = System.nanoTime()
            val updates = when (val result = runInterruptible { background { read(source) } }) {
                is Read.Channels -> result.updates
                Read.Refused -> {
                    marks.record(source.id, BackfillOutcome.REFUSED)
                    settled += source.id
                    trace("the panel would not give its list in one answer: left to the next import")
                    return@withContext
                }

                Read.Unreachable -> {
                    trace("the connection failed: asked again at the next start")
                    return@withContext
                }
            }

            val found = background { writer().apply(source.id, updates) }
            marks.record(source.id, BackfillOutcome.DONE)
            settled += source.id
            trace("archive channels=${updates.size} rows=$found took=${(System.nanoTime() - startedAt) / 1_000_000}ms")
        }
    }

    /**
     * [block] at the lowest thread priority, the thread's own given back after. Only around work that does not suspend,
     * so it is the same thread at the end as at the start: a pooled thread is not left low for whoever runs on it next.
     */
    private fun <T> background(block: () -> T): T {
        val thread = Thread.currentThread()
        val before = thread.priority
        thread.priority = Thread.MIN_PRIORITY
        try {
            return block()
        } finally {
            thread.priority = before
        }
    }

    private fun read(source: ProviderSource): Read {
        return try {
            val reader = apiFactory(client, source).liveStreams() ?: return Read.Refused
            reader.use { Read.Channels(XtreamArchiveBackfill.updates(source.id, it)) }
        } catch (refused: WholeSectionUnsupported) {
            Read.Refused
        } catch (malformed: JsonFormatException) {
            Read.Refused
        } catch (io: IOException) {
            Read.Unreachable
        }
    }

    private sealed interface Read {
        class Channels(val updates: List<CatchUpHours>) : Read
        data object Refused : Read
        data object Unreachable : Read
    }

    /** Counts and words only -- never the address, the account or a channel -- and only where the development switch is on. */
    private fun trace(message: String) {
        if (DevDiagnostics.enabled) Log.i(TAG, "backfill: $message")
    }

    companion object {
        private const val TAG = "CastivioCatchUp"

        private fun httpLiveList(client: OkHttpClient, source: ProviderSource): LiveList {
            val api = XtreamHttpApi(
                client = client,
                base = source.url.orEmpty(),
                username = source.username.orEmpty(),
                password = source.password.orEmpty(),
                userAgent = source.userAgent,
            )
            return LiveList { api.allStreams(MediaKind.LIVE) }
        }
    }
}

/** The panel's list of live channels in one answer, or null when it has none to give. A seam so the backfill is tested without a server. */
fun interface LiveList {
    fun liveStreams(): Reader?
}
