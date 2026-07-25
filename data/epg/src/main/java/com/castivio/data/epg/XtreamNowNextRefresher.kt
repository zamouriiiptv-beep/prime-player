package com.castivio.data.epg

import com.castivio.core.common.AppDispatchers
import com.castivio.core.common.Outcome
import com.castivio.data.networking.XtreamHttpApi
import com.castivio.data.parsing.XtreamEpgEntry
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
) : NowNextRefresher {

    override suspend fun refresh(channels: List<ChannelRef>): Int = withContext(dispatchers.io) {
        if (channels.isEmpty()) return@withContext 0

        val source = sources.activeNow() ?: return@withContext 0
        // Only Xtream has this endpoint. An M3U provider gets its guide from XMLTV
        // and this is not a failure, so it returns quietly rather than erroring.
        if (source.kind != SourceKind.XTREAM) return@withContext 0
        if (source.url == null || source.username == null || source.password == null) return@withContext 0

        val api = apiFactory(client, source)
        val requests = channels.asSequence()
            .filter { !it.providerRef.isNullOrEmpty() }
            .take(MAX_CHANNELS_PER_REFRESH)
            .toList()
        if (requests.isEmpty()) return@withContext 0

        val batch = ArrayList<EpgProgramme>(requests.size * 4)
        for (channel in requests) {
            val entries = when (val result = api.shortEpg(channel.providerRef!!)) {
                is Outcome.Failure -> continue // one channel's guide, not the refresh
                is Outcome.Success -> result.value
            }
            for (entry in entries) {
                batch.add(entry.toProgramme(channel))
            }
        }
        if (batch.isEmpty()) return@withContext 0

        val writer = writerFactory()
        writer.begin(source.id)
        try {
            writer.writeProgrammes(batch)
            writer.commit()
            writer.finish(
                EpgSummary(
                    sourceId = source.id,
                    programmes = batch.size,
                    channels = requests.size,
                    skipped = 0,
                    outsideWindow = 0,
                    durationMs = 0,
                ),
            )
        } catch (t: Throwable) {
            writer.abort(t)
            throw t
        }
        batch.size
    }

    /**
     * Stored under the channel's *guide* id.
     *
     * The response carries a `channel_id`, but panels frequently leave it empty or
     * put the stream id there instead — and a programme filed under the wrong id is
     * invisible, because every read joins on the guide id.
     */
    private fun XtreamEpgEntry.toProgramme(channel: ChannelRef): EpgProgramme = EpgProgramme(
        channelId = channel.epgChannelId?.takeIf { it.isNotEmpty() }
            ?: channelId.takeIf { it.isNotEmpty() }
            ?: channel.mediaId,
        title = title,
        description = description,
        startMs = startMs,
        stopMs = stopMs,
    )

    companion object {
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
            return ShortEpgSource { providerRef -> api.shortEpg(providerRef) }
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
    fun shortEpg(providerRef: String): Outcome<List<XtreamEpgEntry>>
}
