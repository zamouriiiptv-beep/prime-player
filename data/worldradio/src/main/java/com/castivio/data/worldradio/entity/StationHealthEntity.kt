package com.castivio.data.worldradio.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * What listening has said about one station: when it last played, when it last failed, how many failures in a row, how many unstable
 * listens today. A row per station that has said anything; no row means nothing is known. See `StationHealth` for what is made of it.
 */
@Entity(tableName = "station_health")
data class StationHealthEntity(
    @PrimaryKey
    @ColumnInfo(name = "uuid")
    val uuid: String,

    @ColumnInfo(name = "last_ok_at")
    val lastOkAt: Long?,

    @ColumnInfo(name = "last_failed_at")
    val lastFailedAt: Long?,

    @ColumnInfo(name = "consecutive_failures")
    val consecutiveFailures: Int,

    @ColumnInfo(name = "unstable_count")
    val unstableCount: Int,

    @ColumnInfo(name = "unstable_since")
    val unstableSince: Long?,
)
