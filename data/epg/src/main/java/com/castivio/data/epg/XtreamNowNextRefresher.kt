package com.castivio.data.epg

import android.util.Log
import com.castivio.core.common.AppDispatchers
import com.castivio.core.common.Outcome
import com.castivio.data.networking.XtreamHttpApi
import com.castivio.data.parsing.XtreamEpgEntry
import com.castivio.domain.ChannelGuideFetcher
import com.castivio.domain.ChannelRef
import com.castivio.domain.EpgProgramme
import com.castivio.domain.EpgSummary
import com.castivio.domain.EpgWriter
import com.castivio.domain.NowNextRefresher
import com.castivio.domain.ProviderSource
import com.castivio.domain.SourceKind
import com.castivio.domain.SourceRepository
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Fills now/next for the channels on screen, one small request each.
 *
 * This is the cheap half of the EPG story. A full XMLTV guide is ~100 MB and
 * covers a week for every channel the provider has; `get_short_epg` is ~2 KB and
 * covers the next few hours for one channel. For a user who only ever looks at
 * what is on — most users, most of the time — this replaces the guide download
 * entirely, and for everyone else it fills the gap while a guide import runs.
 *
 * It is only possible because every Xtream row keeps its provider stream id: the
 * request is addressed by that, while the response is stored under the channel's
 * guide id, and the two are different strings.
 *
 * Bounded twice over. The caller passes the visible window, and
 * [MAX_CHANNELS_PER_REFRESH] caps it again — a guide grid can show more rows than
 * it is reasonable to issue requests for, and a hundred parallel requests on a
 * Fire Stick's radio would hurt playback more than a missing programme title does.
 */
class XtreamNowNextRefresher(
    private val client: OkHttpClient,
    private val writerFactory: () -> EpgWriter,
    private val sources: SourceRepository,
    private val dispatchers: AppDispatchers,
    private val apiFactory: (OkHttpClient, ProviderSource) -> ShortEpgSource = ::httpShortEpg,
) : NowNextRefresher, ChannelGuideFetcher {

    override suspend fun refresh(channels: List<ChannelRef>): Int = withContext(dispatchers.io) {
        if (channels.isEmpty()) return@withContext 0
        val source = xtreamSource() ?: return@withContext 0

        val api = apiFactory(client, source)
        val requests = channels.asSequence()
            .filter { !it.providerRef.isNullOrEmpty() }
            .take(MAX_CHANNELS_PER_REFRESH)
            .toList()
        if (requests.isEmpty()) return@withContext 0

        val batch = ArrayList<EpgProgramme>(requests.size * XtreamHttpApi.SHORT_EPG_LIMIT)
        for (channel in requests) {
            val entries = when (
                val result = api.shortEpg(channel.providerRef!!, XtreamHttpApi.SHORT_EPG_LIMIT)
            ) {
                is Outcome.Failure -> continue // one channel's guide, not the refresh
                is Outcome.Success -> result.value
            }
            for (entry in entries) {
                batch.add(entry.toProgramme(channel))
            }
        }
        store(source, batch, channels = requests.size)
    }

    /**
     * One channel's guide, as deep as the provider will answer.
     *
     * The same endpoint, the same parser and the same store as [refresh] — only the
     * count differs, and it differs because a person asked for this one rather than a
     * list scrolling past. It is a single request for a single channel, issued when the
     * guide is opened and not before.
     *
     * Everything that can go wrong returns zero rather than throwing: no provider, an
     * M3U provider with no such endpoint, a channel with no stream id, a refused
     * request, a malformed answer. The caller's job is then to show what the store
     * already holds, which is never worse than what was on screen a moment earlier.
     */
    override suspend fun fetch(channel: ChannelRef): Int = withContext(dispatchers.io) {
        val providerRef = channel.providerRef
        if (providerRef.isNullOrEmpty()) return@withContext 0
        val source = xtreamSource() ?: return@withContext 0

        val api = apiFactory(client, source)
        // TEMPORARY DIAGNOSTIC. Ids and counts only -- never the host, the user or the
        // password. See the matching lines in `XtreamHttpApi.shortEpg`.
        Log.i(
            FULL_EPG_TAG,
            "FULL_EPG REQUEST streamId=$providerRef limit=${XtreamHttpApi.FULL_EPG_LIMIT} " +
                "atMs=${System.currentTimeMillis()}",
        )
        val entries = when (val result = api.shortEpg(providerRef, XtreamHttpApi.FULL_EPG_LIMIT)) {
            is Outcome.Failure -> return@withContext 0
            is Outcome.Success -> result.value
        }
        val programmes = entries.map { it.toProgramme(channel) }
        Log.i(FULL_EPG_TAG, "FULL_EPG MAPPED streamId=$providerRef programmes=${programmes.size}")
        // Immediately before `store`, whose only step ahead of `writer.writeProgrammes`
        // is an `isEmpty` guard -- so this is the count that reaches the writer.
        Log.i(FULL_EPG_TAG, "FULL_EPG WRITE streamId=$providerRef programmes=${programmes.size}")
        store(source, programmes, channels = 1)
    }

    /**
     * The active provider, when it is one this can ask.
     *
     * Only Xtream has this endpoint. An M3U provider gets its guide from XMLTV and that
     * is not a failure, so this returns null and both callers return zero quietly.
     */
    private suspend fun xtreamSource(): ProviderSource? {
        val source = sources.activeNow() ?: return null
        if (source.kind != SourceKind.XTREAM) return null
        if (source.url == null || source.username == null || source.password == null) return null
        return source
    }

    /**
     * Commits a batch, whatever asked for it.
     *
     * Shared by both entry points because the writing is the part that must not differ:
     * one transaction, one retention pass, one set of import pragmas. `programme`'s key
     * is `(channel_id, start_ms)` and the insert is `INSERT OR REPLACE`, so a week
     * written here survives every later now/next refresh — the four rows they share are
     * overwritten with identical values and the rest of the week is untouched.
     */
    private fun store(source: ProviderSource, batch: List<EpgProgramme>, channels: Int): Int {
        if (batch.isEmpty()) return 0

        val writer = writerFactory()
        writer.begin(source.id)
        try {
            writer.writeProgrammes(batch)
            writer.commit()
            writer.finish(
                EpgSummary(
                    sourceId = source.id,
                    programmes = batch.size,
                    channels = channels,
                    skipped = 0,
                    outsideWindow = 0,
                    durationMs = 0,
                ),
            )
        } catch (t: Throwable) {
            writer.abort(t)
            throw t
        }
        return batch.size
    }

    /**
     * Stored under the channel's *guide* id.
     *
     * The response carries a `channel_id`, but panels frequently leave it empty or
     * put the stream id there instead — and a programme filed under the wrong id is
     * invisible, because every read joins on the guide id.
     */
    private fun XtreamEpgEntry.toProgramme(channel: ChannelRef): EpgProgramme = EpgProgramme(
        channelId = channel.epgChannelId?.takeIf { it.isNotBlank() } ?: channel.mediaId,
        title = title,
        description = description,
        startMs = startMs,
        stopMs = stopMs,
    )

    companion object {
        /**
         * TEMPORARY DIAGNOSTIC. The tag `ChannelsViewModel` already logs the guide under,
         * so one `adb logcat -s CastivioEpg` shows the whole chain in order.
         */
        private const val FULL_EPG_TAG = "CastivioEpg"

        /**
         * One request per channel, so this is a request budget rather than a row
         * limit. Forty covers a full guide page with room to spare.
         */
        const val MAX_CHANNELS_PER_REFRESH = 40

        private fun httpShortEpg(client: OkHttpClient, source: ProviderSource): ShortEpgSource {
            val api = XtreamHttpApi(
                client = client,
                base = source.url.orEmpty(),
                username = source.username.orEmpty(),
                password = source.password.orEmpty(),
                userAgent = source.userAgent,
            )
            return ShortEpgSource { providerRef, limit -> api.shortEpg(providerRef, limit) }
        }
    }
}

/**
 * One channel's short guide.
 *
 * A one-method interface so the refresher can be tested without a server — the
 * HTTP implementation is a lambda over [XtreamHttpApi].
 */
fun interface ShortEpgSource {

    /**
     * @param limit how many entries to ask for. A ceiling and not a promise: panels
     *   differ in what they honour, and a provider holding one day of guide answers
     *   with fewer entries however large the number. The two callers pass
     *   `XtreamHttpApi.SHORT_EPG_LIMIT` and `XtreamHttpApi.FULL_EPG_LIMIT`, and the
     *   count is the only thing that separates a row label from a guide page.
     */
    fun shortEpg(providerRef: String, limit: Int): Outcome<List<XtreamEpgEntry>>
}
