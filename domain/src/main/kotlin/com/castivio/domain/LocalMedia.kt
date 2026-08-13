package com.castivio.domain

/**
 * What the device itself holds.
 *
 * ## Why this is a separate contract from [CatalogRepository]
 *
 * They look similar and they are not. A provider's catalogue is imported, normalised and
 * indexed by Castivio, so it can be paged, searched and counted on our own terms. The
 * device's media is somebody else's index — `MediaStore` — which has its own sort orders,
 * its own idea of a folder, and rows that appear and vanish while you are reading them.
 * Forcing both through one interface would mean either pretending we own an index we do
 * not, or giving the catalogue the awkward shape of one we do not control.
 *
 * ## The rules it keeps from the rest of the data layer
 *
 * **Paged, always.** There is no `allVideos()`. A phone with 8,000 clips is ordinary, and
 * the whole product is sized so that memory is O(page) and never O(library).
 *
 * **Names arrive with the row.** Every field below except the thumbnail comes out of the
 * same cursor in one pass, so a list can be drawn complete the instant it is read. The
 * reference implementations that show `loading…` beside every row are doing a second
 * lookup per item; this interface makes that impossible by having nothing left to look up.
 *
 * **The thumbnail is not here.** It is a bitmap, it is slow, and it is the one thing that
 * genuinely arrives late — so it is a separate concern, requested per visible item and
 * never on the path that draws the list.
 */
interface LocalMediaLibrary {

    /**
     * Video files, newest first, one page at a time.
     *
     * [folder] is a bucket name as the device reports it — `Movies`, `DCIM`, `Download`.
     * Null means every folder, which is what the library screen shows.
     */
    suspend fun videos(folder: String? = null, offset: Int = 0, limit: Int = PAGE): List<LocalVideo>

    suspend fun audio(folder: String? = null, offset: Int = 0, limit: Int = PAGE): List<LocalTrack>

    /**
     * The folders that actually contain something of [kind].
     *
     * Derived from the media rows rather than from the filesystem, and that is the whole
     * point: a picker that lists directories shows the user forty empty ones and hides the
     * two with films in them. `MediaStore` already knows which buckets have content, so the
     * list is the answer to "where is my media" rather than "what directories exist".
     */
    suspend fun folders(kind: LocalMediaKind): List<LocalFolder>

    /** Whether the app currently holds the permission these reads need. */
    fun hasPermission(): Boolean

    /**
     * The permissions to ask for, as plain strings.
     *
     * Strings rather than a platform type, so this module stays free of Android and a
     * feature can drive the request without depending on the implementation that knows
     * which ones apply. Which ones *do* apply changed shape in Android 13 — one broad
     * storage permission became two media-typed ones — and that is the implementation's
     * business, not a caller's.
     */
    fun requiredPermissions(): List<String>

    companion object {
        /** The same page the rest of the product uses. One figure, one place. */
        const val PAGE = 60
    }
}

enum class LocalMediaKind { VIDEO, AUDIO }

/**
 * One video on the device.
 *
 * [uri] is a content URI string rather than a path. On modern Android the path is either
 * unavailable or unreadable, and a player handed a path fails on exactly the devices where
 * it matters most.
 */
data class LocalVideo(
    val id: Long,
    val uri: String,
    /** The file's own name, from the same cursor row. Never empty, never late. */
    val name: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val folder: String?,
    val width: Int = 0,
    val height: Int = 0,
)

/**
 * One audio file.
 *
 * [artist] and [album] are what `MediaStore` was told by the tags, so both are frequently
 * null or the literal string the platform uses for "unknown". A caller that shows them
 * must treat null as "say nothing" rather than as an error.
 */
data class LocalTrack(
    val id: Long,
    val uri: String,
    val name: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val folder: String?,
    val artist: String? = null,
    val album: String? = null,
    /** The album this track's art belongs to, for the cover lookup. */
    val albumId: Long? = null,
)

/** A folder that has media in it, and how much. */
data class LocalFolder(val name: String, val count: Int)
