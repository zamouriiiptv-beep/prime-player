package com.castivio.data.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.castivio.data.database.entity.ProgrammeEntity
import kotlinx.coroutines.flow.Flow

/**
 * Guide reads, all of them windowed.
 *
 * There is no "everything for this channel" query and no unbounded time range.
 * A guide is millions of rows; the only affordable question is "these channels,
 * this slice of time", which is what every method here asks.
 */
@Dao
interface EpgDao {

    /**
     * Now and next for the given channels.
     *
     * `stop_ms > :atMs` finds what is on; the upper bound keeps a channel with a
     * sparse guide from dragging in next week's schedule. Two rows per channel is
     * all the caller needs, and SQLite has no per-group limit, so the bound is
     * expressed in time instead — the rows come back ordered and the repository
     * takes the first two.
     */
    @Query(
        """
        SELECT * FROM programme
        WHERE channel_id IN (:channelIds) AND stop_ms > :atMs AND start_ms < :untilMs
        ORDER BY channel_id, start_ms
        """,
    )
    suspend fun aroundNow(channelIds: List<String>, atMs: Long, untilMs: Long): List<ProgrammeEntity>

    /** The grid: visible channels crossed with the visible time range. */
    @Query(
        """
        SELECT * FROM programme
        WHERE channel_id IN (:channelIds) AND stop_ms > :fromMs AND start_ms < :toMs
        ORDER BY channel_id, start_ms
        """,
    )
    suspend fun window(channelIds: List<String>, fromMs: Long, toMs: Long): List<ProgrammeEntity>

    @Query(
        """
        SELECT * FROM programme
        WHERE channel_id = :channelId AND stop_ms > :fromMs AND start_ms < :toMs
        ORDER BY start_ms
        """,
    )
    suspend fun forChannel(channelId: String, fromMs: Long, toMs: Long): List<ProgrammeEntity>

    // ---------------------------------------------------------------- coverage

    @Query("SELECT COUNT(*) FROM programme")
    fun programmeCount(): Flow<Int>

    @Query("SELECT COUNT(DISTINCT channel_id) FROM programme")
    suspend fun channelCount(): Int

    @Query("SELECT MIN(start_ms) FROM programme")
    suspend fun earliestStart(): Long?

    @Query("SELECT MAX(stop_ms) FROM programme")
    suspend fun latestStop(): Long?

    /** How far the guide still reaches for the channels the user can actually see. */
    @Query("SELECT COUNT(*) FROM programme WHERE stop_ms > :atMs")
    suspend fun upcomingCount(atMs: Long): Int

    // -------------------------------------------------------------- retention

    /**
     * Drops what nobody will look at again.
     *
     * Retention is applied at import too, so this only catches what aged out
     * since the last refresh — a range delete over the `stop_ms` index rather
     * than a scan.
     */
    @Query("DELETE FROM programme WHERE stop_ms < :cutoffMs")
    suspend fun deleteBefore(cutoffMs: Long): Int

    @Query("DELETE FROM programme WHERE start_ms > :horizonMs")
    suspend fun deleteAfter(horizonMs: Long): Int

    @Query("DELETE FROM programme WHERE source_id = :sourceId")
    suspend fun deleteSource(sourceId: String): Int
}
