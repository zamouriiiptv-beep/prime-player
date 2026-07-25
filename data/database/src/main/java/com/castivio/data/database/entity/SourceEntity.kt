package com.castivio.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A configured provider and its sync state.
 *
 * This is the table that decides whether the app has to download anything at
 * all. `etag`, `last_modified` and `content_hash` are what a refresh checks
 * before spending a 100 MB download, and `last_import_at` is what keeps twenty
 * app launches in a day from becoming twenty imports.
 *
 * Credentials live here in app-private storage — the same place every IPTV player
 * keeps them, and the only place the importer can reach them unattended for a
 * background refresh. They are never written to logs or shown in Settings beyond
 * the username.
 */
@Entity(
    tableName = "source",
    indices = [Index(name = "idx_source_active", value = ["is_active"])],
)
data class SourceEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    /** [com.castivio.domain.SourceKind] by name, not ordinal. */
    @ColumnInfo(name = "kind")
    val kind: String,

    @ColumnInfo(name = "label")
    val label: String,

    @ColumnInfo(name = "url")
    val url: String?,

    @ColumnInfo(name = "username")
    val username: String?,

    @ColumnInfo(name = "password")
    val password: String?,

    @ColumnInfo(name = "epg_url")
    val epgUrl: String?,

    @ColumnInfo(name = "user_agent")
    val userAgent: String?,

    @ColumnInfo(name = "etag")
    val etag: String?,

    @ColumnInfo(name = "last_modified")
    val lastModified: String?,

    /** Computed while streaming, for providers that offer no validators. */
    @ColumnInfo(name = "content_hash")
    val contentHash: String?,

    @ColumnInfo(name = "last_import_at")
    val lastImportAt: Long?,

    @ColumnInfo(name = "last_epg_import_at")
    val lastEpgImportAt: Long?,

    @ColumnInfo(name = "item_count")
    val itemCount: Int = 0,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = 0,

    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true,
)
