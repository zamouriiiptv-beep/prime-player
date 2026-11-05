package com.castivio.data.worldradio.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A station of the world that the viewer kept: liked, or listened to lately.
 *
 * Keyed by the directory's own `stationuuid`, and a copy of what the directory said of it when it was kept, so a favourite is there, with
 * its name and its address, when the directory is not. It is a row either because it is a favourite ([favoriteAt]) or because it was
 * listened to ([playedAt]), or both; a row that is neither is deleted.
 *
 * **No foreign key, and not a column shared with the subscription's tables**: this database does not know the catalogue exists.
 */
@Entity(
    tableName = "saved_station",
    indices = [Index(name = "idx_saved_favorite", value = ["favorite_at"]), Index(name = "idx_saved_played", value = ["played_at"])],
)
data class SavedStationEntity(
    @PrimaryKey
    @ColumnInfo(name = "uuid")
    val uuid: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "url")
    val url: String,

    @ColumnInfo(name = "url_resolved")
    val urlResolved: String?,

    @ColumnInfo(name = "favicon")
    val favicon: String?,

    @ColumnInfo(name = "country_code")
    val countryCode: String?,

    @ColumnInfo(name = "country")
    val country: String?,

    /** The tags, joined by a comma: the directory's own tags never contain one. */
    @ColumnInfo(name = "tags")
    val tags: String,

    @ColumnInfo(name = "codec")
    val codec: String?,

    @ColumnInfo(name = "bitrate")
    val bitrate: Int,

    @ColumnInfo(name = "hls")
    val hls: Boolean,

    @ColumnInfo(name = "votes")
    val votes: Int,

    @ColumnInfo(name = "click_count")
    val clickCount: Int,

    @ColumnInfo(name = "checked_ok")
    val checkedOk: Boolean?,

    @ColumnInfo(name = "checked_at")
    val checkedAt: Long?,

    @ColumnInfo(name = "ssl_error")
    val sslError: Boolean,

    /** When it was made a favourite, or null while it is not one. */
    @ColumnInfo(name = "favorite_at")
    val favoriteAt: Long?,

    /** When it was last listened to, or null if it never was. */
    @ColumnInfo(name = "played_at")
    val playedAt: Long?,
)
