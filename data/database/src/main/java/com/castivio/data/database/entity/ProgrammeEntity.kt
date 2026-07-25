package com.castivio.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * One guide entry.
 *
 * The primary key is `(channel_id, start_ms)` rather than a generated id, which
 * buys two things at once:
 *
 *  - **Idempotent refreshes.** Re-importing a guide that overlaps yesterday's
 *    download replaces rows instead of duplicating them, so the same programme
 *    can never appear twice in the grid.
 *  - **A covering index for the query that matters.** Every EPG read is
 *    "these channels, this time range", which is exactly this key's order.
 *
 * The extra index on `stop_ms` is for retention: pruning past programmes is a
 * range delete, and without it that is a full scan of a table with millions of
 * rows.
 */
@Entity(
    tableName = "programme",
    primaryKeys = ["channel_id", "start_ms"],
    indices = [
        Index(name = "idx_programme_stop", value = ["stop_ms"]),
        Index(name = "idx_programme_source", value = ["source_id"]),
    ],
)
data class ProgrammeEntity(
    /** The provider's EPG id — `tvg-id` on the channel, `channel` in XMLTV. */
    @ColumnInfo(name = "channel_id")
    val channelId: String,

    @ColumnInfo(name = "start_ms")
    val startMs: Long,

    @ColumnInfo(name = "stop_ms")
    val stopMs: Long,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "description")
    val description: String?,

    @ColumnInfo(name = "source_id")
    val sourceId: String,
)
