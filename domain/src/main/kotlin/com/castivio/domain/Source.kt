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

/**
 * Whether this kind of provider can be read a section at a time.
 *
 * Stated on the kind rather than on [PlaylistSource] so that both the credentials the
 * user typed and the row this app stored answer it the same way — one rule, two
 * accessors, no chance of the sign-in and the start-up gate disagreeing about whether
 * a catalogue was expected.
 */
val SourceKind.isOnDemand: Boolean
    get() = this == SourceKind.XTREAM

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

    /**
     * Stores a provider the user just entered and returns it with its id.
     *
     * Takes a [PlaylistSource] rather than a [ProviderSource] because the id is
     * derived from what the provider *is*, and that derivation belongs to the data
     * layer — a login screen must not be able to invent one, or the ids that every
     * catalogue row and favourite hang off would depend on which screen created
     * them.
     */
    suspend fun register(source: PlaylistSource, label: String? = null): ProviderSource

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

    /**
     * True when the app has nothing to show and has to send the user back to set-up.
     *
     * The question is "is there anything here", and for a provider read a section at a
     * time the answer is yes as soon as it is signed in to: the panel *is* the
     * catalogue, and the first request happens when the user presses Channels. Counting
     * its stored rows would send somebody who has just signed in successfully straight
     * back to the sign-in screen, which is the one place this rule is consulted.
     *
     * A playlist is the opposite. Its rows arrive in one file at set-up, so none of
     * them means the import did not happen, and there is no later moment that would
     * fill them in.
     */
    fun needsFirstImport(source: ProviderSource): Boolean {
        if (source.kind.isOnDemand) return false
        return source.sync.lastImportAtMs == null || source.sync.itemCount == 0
    }
}

/**
 * Whether a provider will actually work, asked before importing anything.
 *
 * A login screen has to distinguish four outcomes that all look like "no
 * channels" if you only try to import: wrong credentials, an expired
 * subscription, every connection already in use, and a host that cannot be
 * reached. Importing first and inferring the reason afterwards produces the
 * generic failure every mediocre IPTV player shows.
 */
interface ProviderValidator {
    suspend fun validate(source: PlaylistSource): com.castivio.core.common.Outcome<ProviderStatus>
}

data class ProviderStatus(
    val usable: Boolean,
    /** Non-null when the provider states one. */
    val expiresAtMs: Long? = null,
    val isTrial: Boolean = false,
    val activeConnections: Int = 0,
    val maxConnections: Int = 0,
    /** What the panel called it: "Active", "Expired", "Banned". */
    val statusLabel: String? = null,
) {
    val atConnectionLimit: Boolean get() = maxConnections in 1..activeConnections

    fun expiresWithin(nowMs: Long, windowMs: Long): Boolean =
        expiresAtMs?.let { it in nowMs..(nowMs + windowMs) } == true
}
