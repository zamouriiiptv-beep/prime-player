package com.castivio.domain

import kotlinx.coroutines.flow.Flow

/**
 * A programme, as the guide describes it.
 *
 * The EPG is the largest single dataset in the app — a real XMLTV guide reaches
 * 100 MB and millions of entries — so the read interface below only ever asks
 * for a window: visible channels × visible time. There is no "load the guide"
 * call for the same reason there is no `getAllChannels()`.
 */
data class Programme(
    val channelId: String,
    val title: String,
    val description: String?,
    val startMs: Long,
    val stopMs: Long,
) {
    val durationMs: Long get() = (stopMs - startMs).coerceAtLeast(0)

    fun isLiveAt(nowMs: Long): Boolean = nowMs in startMs until stopMs

    /** 0f–1f through the programme, for the progress bar under a now-playing row. */
    fun progressAt(nowMs: Long): Float {
        if (durationMs <= 0) return 0f
        return ((nowMs - startMs).toFloat() / durationMs).coerceIn(0f, 1f)
    }
}

/** What a channel row shows: what is on, and what is next. */
data class NowNext(val now: Programme?, val next: Programme?)

/** How much guide the app actually holds. Shown in Settings and Diagnostics. */
data class EpgCoverage(
    val programmes: Int,
    val channels: Int,
    val earliestMs: Long?,
    val latestMs: Long?,
    val updatedAtMs: Long?,
)

interface EpgRepository {

    /**
     * Now/next for the rows on screen.
     *
     * Takes the visible channel ids rather than resolving them internally, so a
     * list of 100,000 channels costs the same as a list of ten: the query is
     * bounded by what is being rendered.
     */
    suspend fun nowNext(channelIds: List<String>, atMs: Long): Map<String, NowNext>

    /** The guide grid: visible channels crossed with the visible time range. */
    suspend fun window(channelIds: List<String>, fromMs: Long, toMs: Long): Map<String, List<Programme>>

    /** One channel's schedule, for the channel detail page. */
    suspend fun programmes(channelId: String, fromMs: Long, toMs: Long): List<Programme>

    fun coverage(): Flow<EpgCoverage>

    /** True when the stored guide still covers [atMs] onward and a refresh can wait. */
    suspend fun hasFreshGuide(atMs: Long, minimumHorizonMs: Long = MINIMUM_HORIZON_MS): Boolean

    companion object {
        /** Below six hours of remaining guide, "now and next" starts running out. */
        const val MINIMUM_HORIZON_MS = 6 * 60 * 60 * 1000L
    }
}

/**
 * The write side of the guide. Same shape as [CatalogWriter], and for the same
 * reasons: bounded batches, commit as you go, blocking because SQLite is.
 */
interface EpgWriter {
    fun begin(sourceId: String)
    fun writeProgrammes(programmes: List<EpgProgramme>)
    fun commit()
    fun finish(summary: EpgSummary)
    fun abort(cause: Throwable?)
}

/** A programme on its way into storage. */
data class EpgProgramme(
    val channelId: String,
    val title: String,
    val description: String?,
    val startMs: Long,
    val stopMs: Long,
)

data class EpgSummary(
    val sourceId: String,
    val programmes: Int,
    val channels: Int,
    /** Entries with no usable channel or start time — normal in real guides. */
    val skipped: Int,
    /** Entries dropped by [EpgRetention]: last week's schedule, next month's. */
    val outsideWindow: Int,
    val durationMs: Long,
    val cancelled: Boolean = false,
)

sealed interface EpgProgress {
    data class Importing(val programmes: Int) : EpgProgress
    data class Done(val programmes: Int, val durationMs: Long) : EpgProgress
    data class Failed(val error: com.castivio.core.common.AppError) : EpgProgress
}

/**
 * How much of the guide is worth keeping.
 *
 * Guides routinely carry two weeks in both directions. Storing all of it costs
 * hundreds of megabytes and slows every query, to show a schedule nobody opens.
 * Filtering happens *at import*, so out-of-window programmes are never written
 * at all rather than written and pruned later.
 */
data class EpgRetention(
    /** Yesterday stays, because catch-up playback needs it. */
    val pastMs: Long = DEFAULT_PAST_MS,
    val futureMs: Long = DEFAULT_FUTURE_MS,
) {
    fun contains(startMs: Long, stopMs: Long, nowMs: Long): Boolean =
        stopMs >= nowMs - pastMs && startMs <= nowMs + futureMs

    companion object {
        const val DEFAULT_PAST_MS = 24 * 60 * 60 * 1000L
        const val DEFAULT_FUTURE_MS = 7 * 24 * 60 * 60 * 1000L
        val DEFAULT = EpgRetention()
    }
}

/** Where a guide comes from. */
sealed interface EpgSource {
    /** An XMLTV URL, usually gzipped. */
    data class Xmltv(val url: String) : EpgSource

    /**
     * Xtream's per-channel short EPG.
     *
     * Kilobytes per channel instead of a full guide download, which is why it is
     * preferred for now/next when the provider supports it.
     */
    data class XtreamShort(val host: String, val username: String, val password: String) : EpgSource
}

interface EpgImporter {
    fun import(source: EpgSource): Flow<EpgProgress>
}

/**
 * What a channel needs for a per-channel guide request.
 *
 * [providerRef] is the provider's own stream id; [epgChannelId] is the guide id.
 * Both are needed and they are not the same thing: the request is addressed by the
 * former, and the response is stored under the latter.
 */
data class ChannelRef(
    val mediaId: String,
    val providerRef: String?,
    val epgChannelId: String?,
)

/**
 * Fills now/next for the channels on screen, cheaply.
 *
 * Xtream's `get_short_epg` is a couple of kilobytes per channel against a full
 * guide's hundred megabytes, so for a user who only ever looks at what is on, this
 * replaces the guide download entirely.
 */
interface NowNextRefresher {
    /** @return how many programmes were stored. */
    suspend fun refresh(channels: List<ChannelRef>): Int
}

/**
 * Fetches as much of **one** channel's guide as its provider will give.
 *
 * ## Why this is not a parameter on [NowNextRefresher]
 *
 * Because the two ask different questions and the difference is the whole point.
 * [NowNextRefresher] answers "what is on these forty rows" and is called every time a
 * viewer moves through the list; its request has to stay small or browsing a catalogue
 * becomes a download. This answers "show me this channel's week", for one channel, once,
 * because a person opened the guide and asked.
 *
 * A limit parameter on the first would have made the cheap path capable of the expensive
 * one, and a caller that passed the wrong number would turn every keypress into a guide
 * fetch. Two ports cannot be confused; one port with a number can.
 *
 * ## What "as much as it will give" means
 *
 * A ceiling, never a promise. The request names a large count, and what comes back is
 * whatever the provider holds: seven days, four, one, or nothing at all. A short answer
 * is data, not an error — the screen shows the days that exist and invents none. A
 * provider with no guide endpoint at all answers zero, quietly, exactly as the M3U case
 * already does.
 *
 * ## And it writes where everything else reads
 *
 * Into the same store, through the same writer. `programme`'s primary key is
 * `(channel_id, start_ms)` and the insert is `INSERT OR REPLACE`, so this merges with
 * what now/next already wrote rather than replacing it — and a later now/next refresh
 * overwrites its own four rows with identical values and leaves the rest of the week
 * standing. That is what makes the cache in [EpgRepository.programmes] enough, and a
 * second store unnecessary.
 */
interface ChannelGuideFetcher {

    /**
     * @param channel the one channel to ask about.
     * @return how many programmes were stored, zero when the provider offered none.
     */
    suspend fun fetch(channel: ChannelRef): Int
}
