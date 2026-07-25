package com.castivio.data.database

import com.castivio.data.database.dao.EpgDao
import com.castivio.data.database.entity.ProgrammeEntity
import com.castivio.domain.EpgCoverage
import com.castivio.domain.EpgRepository
import com.castivio.domain.NowNext
import com.castivio.domain.Programme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The guide, read in windows.
 *
 * Two details do real work here:
 *
 *  - **Channel ids are chunked.** SQLite's bind-variable limit is 999 on the
 *    versions shipped with older Android, and a guide grid can easily ask about
 *    more channels than that. Chunking keeps a wide grid from failing with a
 *    "too many SQL variables" error on exactly the cheap boxes this app targets.
 *  - **Now/next is time-bounded.** A channel whose guide has a gap must not drag
 *    next week's programme into the "next" slot, so the query stops at a horizon
 *    rather than taking whatever comes after now.
 */
class RoomEpgRepository(
    private val dao: EpgDao,
    private val clock: () -> Long = System::currentTimeMillis,
) : EpgRepository {

    override suspend fun nowNext(channelIds: List<String>, atMs: Long): Map<String, NowNext> {
        if (channelIds.isEmpty()) return emptyMap()
        val horizon = atMs + NOW_NEXT_HORIZON_MS
        val result = HashMap<String, NowNext>(channelIds.size)

        for (chunk in channelIds.chunked(MAX_IDS_PER_QUERY)) {
            for ((channelId, rows) in dao.aroundNow(chunk, atMs, horizon).groupBy { it.channelId }) {
                val ordered = rows.sortedBy { it.startMs }
                val now = ordered.firstOrNull { atMs in it.startMs until it.stopMs }
                // "Next" follows what is on; with nothing on, it is whatever starts
                // after the requested instant.
                val nextFrom = now?.stopMs ?: atMs
                val next = ordered.firstOrNull { it.startMs >= nextFrom }
                result[channelId] = NowNext(now = now?.toDomain(), next = next?.toDomain())
            }
        }
        return result
    }

    override suspend fun window(
        channelIds: List<String>,
        fromMs: Long,
        toMs: Long,
    ): Map<String, List<Programme>> {
        if (channelIds.isEmpty() || toMs <= fromMs) return emptyMap()
        val result = HashMap<String, MutableList<Programme>>(channelIds.size)
        for (chunk in channelIds.chunked(MAX_IDS_PER_QUERY)) {
            for (row in dao.window(chunk, fromMs, toMs)) {
                result.getOrPut(row.channelId) { ArrayList() }.add(row.toDomain())
            }
        }
        return result
    }

    override suspend fun programmes(channelId: String, fromMs: Long, toMs: Long): List<Programme> =
        dao.forChannel(channelId, fromMs, toMs).map { it.toDomain() }

    override fun coverage(): Flow<EpgCoverage> = dao.programmeCount().map { count ->
        EpgCoverage(
            programmes = count,
            channels = if (count == 0) 0 else dao.channelCount(),
            earliestMs = dao.earliestStart(),
            latestMs = dao.latestStop(),
            updatedAtMs = null,
        )
    }

    /**
     * Whether a refresh can wait.
     *
     * Counting upcoming rows is not enough on its own — a guide can hold
     * thousands of programmes and still end in an hour — so this checks how far
     * the guide actually reaches.
     */
    override suspend fun hasFreshGuide(atMs: Long, minimumHorizonMs: Long): Boolean {
        val latest = dao.latestStop() ?: return false
        return latest - atMs >= minimumHorizonMs && dao.upcomingCount(atMs) > 0
    }

    /** Prunes what aged out, for the periodic maintenance job. */
    suspend fun prune(retentionPastMs: Long, retentionFutureMs: Long): Int {
        val now = clock()
        return dao.deleteBefore(now - retentionPastMs) + dao.deleteAfter(now + retentionFutureMs)
    }

    private companion object {
        /** SQLite's variable limit is 999 on older Android; stay well clear of it. */
        const val MAX_IDS_PER_QUERY = 400

        /**
         * How far past "now" a channel's next programme may start. Beyond this the
         * guide has a hole, and showing next Tuesday as "next" is worse than
         * showing nothing.
         */
        const val NOW_NEXT_HORIZON_MS = 12 * 60 * 60 * 1000L
    }
}

internal fun ProgrammeEntity.toDomain(): Programme = Programme(
    channelId = channelId,
    title = title,
    description = description,
    startMs = startMs,
    stopMs = stopMs,
)
