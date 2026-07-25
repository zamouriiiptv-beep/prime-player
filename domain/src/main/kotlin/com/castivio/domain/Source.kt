package com.castivio.domain

import kotlinx.coroutines.flow.Flow

/**
 * A provider the user has configured, and what the last refresh learned about it.
 *
 * [sync] is why this type exists. Re-downloading a 100 MB playlist that has not
 * changed is the most expensive thing this app could do on a metered connection
 * and a slow box, so every refresh starts by asking whether it can be skipped.
 */
data class ProviderSource(
    val id: String,
    val kind: SourceKind,
    /** What the user sees in Settings: "My IPTV", a hostname, a file name. */
    val label: String,
    /** Playlist URL, Xtream host, or a content URI for a local file. */
    val url: String?,
    val username: String? = null,
    val password: String? = null,
    /** Explicit XMLTV URL when the user supplied one; Xtream derives its own. */
    val epgUrl: String? = null,
    /** Some providers gate on it, so it is per-source rather than global. */
    val userAgent: String? = null,
    val sync: SyncState = SyncState(),
    val createdAtMs: Long = 0,
    val isActive: Boolean = true,
)

enum class SourceKind {
    /** A playlist URL, streamed and parsed in full. */
    M3U_URL,

    /** A file the user picked. No validators, so change detection is by hash. */
    LOCAL_FILE,

    /** Category-addressable, so the catalogue is never downloaded whole. */
    XTREAM,

    /** Resolved from the activation portal by device MAC. */
    PORTAL,
}

/**
 * What the last successful fetch told us, used to skip the next one.
 *
 * Three levels of certainty, in order of preference:
 *  1. [etag] — the provider tells us whether anything changed. One cheap request.
 *  2. [lastModified] — same idea, weaker guarantee.
 *  3. [contentHash] — computed while streaming, so it costs nothing extra, and
 *     works for the providers (and local files) that offer no validators at all.
 *     It only saves the *import*, not the download.
 */
data class SyncState(
    val etag: String? = null,
    val lastModified: String? = null,
    val contentHash: String? = null,
    val lastImportAtMs: Long? = null,
    val lastEpgImportAtMs: Long? = null,
    val itemCount: Int = 0,
) {
    val hasValidators: Boolean get() = etag != null || lastModified != null
}

interface SourceRepository {
    fun sources(): Flow<List<ProviderSource>>

    /** The source the app is currently showing. Null before activation. */
    fun active(): Flow<ProviderSource?>

    suspend fun activeNow(): ProviderSource?

    suspend fun get(id: String): ProviderSource?

    suspend fun save(source: ProviderSource)

    suspend fun setActive(id: String)

    /** Records what a completed import learned, so the next one can be skipped. */
    suspend fun recordCatalogueImport(id: String, sync: SyncState)

    suspend fun recordEpgImport(id: String, atMs: Long)

    suspend fun delete(id: String)
}

/**
 * When a refresh is worth doing.
 *
 * Deliberately pure and deliberately conservative. Providers change their
 * playlists daily at most, while a user can open the app twenty times a day —
 * refreshing on every launch would cost a large download for nothing, and on a
 * Fire Stick it competes with playback for the same weak CPU.
 */
object RefreshPolicy {

    /**
     * Catalogues get half a day. Long enough that ordinary use never triggers a
     * refresh, short enough that a channel added this morning appears tonight.
     */
    const val CATALOGUE_MAX_AGE_MS = 12 * 60 * 60 * 1000L

    /**
     * Guides get six hours, because a guide that has run out is visibly broken in
     * a way a missing channel is not.
     */
    const val EPG_MAX_AGE_MS = 6 * 60 * 60 * 1000L

    fun catalogueIsStale(
        source: ProviderSource,
        nowMs: Long,
        maxAgeMs: Long = CATALOGUE_MAX_AGE_MS,
    ): Boolean {
        val last = source.sync.lastImportAtMs ?: return true
        // A clock that moved backwards (a box with no RTC, or a timezone change
        // during setup) must not pin the catalogue as fresh forever.
        if (nowMs < last) return true
        return nowMs - last >= maxAgeMs
    }

    fun epgIsStale(
        source: ProviderSource,
        nowMs: Long,
        maxAgeMs: Long = EPG_MAX_AGE_MS,
    ): Boolean {
        val last = source.sync.lastEpgImportAtMs ?: return true
        if (nowMs < last) return true
        return nowMs - last >= maxAgeMs
    }

    /** True when the catalogue has never been imported and the app has nothing to show. */
    fun needsFirstImport(source: ProviderSource): Boolean =
        source.sync.lastImportAtMs == null || source.sync.itemCount == 0
}
