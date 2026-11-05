package com.castivio.data.worldradio.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A list the directory sent, kept as it was sent: the countries, the tags. A few kilobytes each, replaced whenever it is fetched again.
 */
@Entity(tableName = "directory_cache")
data class DirectoryCacheEntity(
    @PrimaryKey
    @ColumnInfo(name = "cache_key")
    val key: String,

    @ColumnInfo(name = "body")
    val body: String,

    @ColumnInfo(name = "fetched_at")
    val fetchedAt: Long,
)
