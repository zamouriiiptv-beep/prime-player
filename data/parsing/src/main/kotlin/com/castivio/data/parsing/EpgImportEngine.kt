package com.castivio.data.parsing

import com.castivio.domain.EpgProgramme
import com.castivio.domain.EpgProgress
import com.castivio.domain.EpgRetention
import com.castivio.domain.EpgSummary
import com.castivio.domain.EpgWriter
import java.io.Reader

/**
 * Streams an XMLTV guide into storage.
 *
 * The guide is the biggest thing this app ingests: 100 MB of XML and millions of
 * programmes is an ordinary week for a large provider. So the same discipline as
 * the catalogue applies — one pass, bounded batches, nothing accumulated — plus
 * two things specific to the EPG:
 *
 *  - **Retention is applied here, not later.** Guides carry weeks in both
 *    directions; out-of-window programmes are never written at all rather than
 *    written and pruned afterwards. That is the difference between a 40 MB
 *    database and a 400 MB one, and it removes the writes as well as the rows.
 *  - **Missing stop times are resolved from the next programme.** `stop` is
 *    optional in XMLTV and plenty of providers omit it. A fixed guess would put
 *    a two-hour film's "now playing" bar in the wrong place, so a programme with
 *    no stop waits for its channel's next entry and takes that start instead.
 *    Only one programme per channel is ever held.
 *
 * Peak memory is therefore O(channels) — one pending programme and one id per
 * channel — and never O(programmes). A million-entry guide across 5,000 channels
 * costs the same as a hundred-entry one across 5,000 channels.
 *
 * Blocking, like the writer it feeds. Call it on an IO dispatcher.
 */
class EpgImportEngine(
    private val writer: EpgWriter,
    private val batchSize: Int = DEFAULT_BATCH,
    private val retention: EpgRetention = EpgRetention.DEFAULT,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        require(batchSize in 1..MAX_BATCH) { "batchSize $batchSize outside 1..$MAX_BATCH" }
    }

    fun importXmltv(
        sourceId: String,
        reader: Reader,
        onProgress: (EpgProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): EpgSummary {
        val started = clock()
        val now = started

        val batch = ArrayList<EpgProgramme>(batchSize)
        // At most one programme per channel, awaiting the next entry to learn its
        // stop time. Bounded by channel count (thousands), not guide size.
        val awaitingStop = HashMap<String, EpgProgramme>()
        val channels = HashSet<String>()

        var written = 0
        var skipped = 0
        var outsideWindow = 0
        var cancelled = false

        // Cancellation ends the *stream*, so a cancelled refresh stops
        // downloading rather than reading a 100 MB guide to its end.
        val guarded = CancellableReader(reader) { cancelled || isCancelled().also { cancelled = it } }

        writer.begin(sourceId)
        try {
            fun emit(programme: EpgProgramme) {
                if (!retention.contains(programme.startMs, programme.stopMs, now)) {
                    outsideWindow++
                    return
                }
                batch.add(programme)
                channels.add(programme.channelId)
                if (batch.size >= batchSize) {
                    written += flush(batch)
                    onProgress(EpgProgress.Importing(written))
                }
            }

            XmltvParser.parse(guarded) { parsed ->
                if (parsed.channelId.isEmpty() || parsed.startMs <= 0L) {
                    skipped++
                    return@parse
                }

                // The pending entry for this channel now knows where it ends.
                awaitingStop.remove(parsed.channelId)?.let { pending ->
                    val stop = if (parsed.startMs > pending.startMs) {
                        parsed.startMs
                    } else {
                        pending.startMs + ASSUMED_DURATION_MS
                    }
                    emit(pending.copy(stopMs = stop))
                }

                val programme = EpgProgramme(
                    channelId = parsed.channelId,
                    title = parsed.title,
                    description = parsed.description,
                    startMs = parsed.startMs,
                    stopMs = parsed.stopMs,
                )
                if (programme.stopMs > programme.startMs) {
                    emit(programme)
                } else {
                    awaitingStop[programme.channelId] = programme
                }
            }

            // Whatever is still waiting has no following entry to learn from.
            for (pending in awaitingStop.values) {
                emit(pending.copy(stopMs = pending.startMs + ASSUMED_DURATION_MS))
            }
            awaitingStop.clear()
            written += flush(batch)

            val summary = EpgSummary(
                sourceId = sourceId,
                programmes = written,
                channels = channels.size,
                skipped = skipped,
                outsideWindow = outsideWindow,
                durationMs = clock() - started,
                cancelled = cancelled,
            )

            if (cancelled) {
                // Committed programmes stay: a partial guide still shows now/next
                // for the channels it reached.
                writer.abort(null)
            } else {
                writer.finish(summary)
                onProgress(EpgProgress.Done(written, summary.durationMs))
            }
            return summary
        } catch (t: Throwable) {
            writer.abort(t)
            throw t
        }
    }

    private fun flush(batch: MutableList<EpgProgramme>): Int {
        if (batch.isEmpty()) return 0
        val size = batch.size
        writer.writeProgrammes(batch)
        batch.clear()
        writer.commit()
        return size
    }

    companion object {
        /**
         * Larger than the catalogue's batch: programme rows are smaller, there is
         * no UI waiting on the first one, and fewer transactions matter more when
         * the row count runs into the millions.
         */
        const val DEFAULT_BATCH = 2_000
        const val MAX_BATCH = 10_000

        /**
         * Fallback length for a programme whose channel never gets another entry.
         * Thirty minutes is the most common slot; the alternative is dropping the
         * row, which would blank the guide's last cell on every channel.
         */
        const val ASSUMED_DURATION_MS = 30 * 60 * 1000L
    }
}

/**
 * A [Reader] that reports end-of-stream once [shouldStop] says so.
 *
 * The parser has no cancellation hook by design — it is a hot loop — so
 * cancellation is expressed as the stream ending. Checked per chunk rather than
 * per character: a chunk is 64 KB, so the check costs nothing and still stops
 * within milliseconds.
 */
internal class CancellableReader(
    private val delegate: Reader,
    private val shouldStop: () -> Boolean,
) : Reader() {

    override fun read(cbuf: CharArray, off: Int, len: Int): Int {
        if (shouldStop()) return -1
        return delegate.read(cbuf, off, len)
    }

    override fun close() = delegate.close()
}
