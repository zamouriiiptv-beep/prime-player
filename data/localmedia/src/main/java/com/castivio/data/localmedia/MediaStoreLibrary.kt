package com.castivio.data.localmedia

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.castivio.domain.LocalFolder
import com.castivio.domain.LocalMediaKind
import com.castivio.domain.LocalMediaLibrary
import com.castivio.domain.LocalTrack
import com.castivio.domain.LocalVideo
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The device's own media, read from `MediaStore`.
 *
 * ## One cursor, one pass, everything the row needs
 *
 * The projection below carries the name, the duration, the size and the folder together,
 * so a list can be drawn complete the moment the page is read. That is the direct answer
 * to the reference players that show `loading…` beside every row: they are issuing a
 * lookup per item, usually to read a tag out of the file. Nothing here reads a file.
 *
 * ## Why the paging is done two ways
 *
 * `MediaStore` accepts `LIMIT`/`OFFSET` through a query bundle on API 26 and above, and
 * only through a raw `sortOrder` string below it. The bundle is the supported path and the
 * string is the one that works everywhere; both are here because the alternative is
 * reading 8,000 rows into memory on an older phone to show sixty of them, which is the
 * thing this whole product is shaped to avoid.
 *
 * ## What it does when the permission is missing
 *
 * Returns nothing, and says so through [hasPermission]. It does not throw: a user who has
 * declined is not an error condition, they are a user who needs a sentence and a button,
 * and a `SecurityException` thrown from a list read would be a crash instead.
 */
@Singleton
class MediaStoreLibrary @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : LocalMediaLibrary {

    private val resolver: ContentResolver get() = context.contentResolver

    override fun requiredPermissions(): List<String> = required()

    override fun hasPermission(): Boolean =
        required().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    override suspend fun videos(folder: String?, offset: Int, limit: Int): List<LocalVideo> =
        read(
            collection = videoCollection(),
            projection = VIDEO_PROJECTION,
            folder = folder,
            offset = offset,
            limit = limit,
        ) { cursor, columns ->
            val id = cursor.getLong(columns.id)
            LocalVideo(
                id = id,
                uri = ContentUris.withAppendedId(videoCollection(), id).toString(),
                name = cursor.name(columns),
                durationMs = cursor.getLongOrZero(columns.duration),
                sizeBytes = cursor.getLongOrZero(columns.size),
                folder = cursor.getStringOrNull(columns.bucket),
                width = cursor.getIntOrZero(columns.width),
                height = cursor.getIntOrZero(columns.height),
            )
        }

    override suspend fun audio(folder: String?, offset: Int, limit: Int): List<LocalTrack> =
        read(
            collection = audioCollection(),
            projection = AUDIO_PROJECTION,
            folder = folder,
            offset = offset,
            limit = limit,
            // Ringtones, notification blips and alarm tones are audio files and are not
            // music. A library that lists them buries the user's actual songs under
            // forty two-second beeps, which is the state of most stock players.
            extraSelection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 OR " +
                "${MediaStore.Audio.Media.IS_PODCAST} != 0 OR " +
                "${MediaStore.Audio.Media.IS_AUDIOBOOK} != 0",
        ) { cursor, columns ->
            val id = cursor.getLong(columns.id)
            LocalTrack(
                id = id,
                uri = ContentUris.withAppendedId(audioCollection(), id).toString(),
                name = cursor.name(columns),
                durationMs = cursor.getLongOrZero(columns.duration),
                sizeBytes = cursor.getLongOrZero(columns.size),
                folder = cursor.getStringOrNull(columns.bucket),
                artist = cursor.getStringOrNull(columns.artist)?.takeIf { it != UNKNOWN },
                album = cursor.getStringOrNull(columns.album)?.takeIf { it != UNKNOWN },
                albumId = cursor.getLongOrNull(columns.albumId),
            )
        }

    /**
     * The folders that have something in them.
     *
     * Counted here rather than in SQL because `MediaStore` will not group, so the bucket
     * column is read across the collection and tallied. Bounded by [FOLDER_SCAN]: a device
     * with more than that many media files still gets a correct list of the folders its
     * newest few thousand items live in, which is every folder anybody browses to, and the
     * alternative is walking a hundred thousand rows to build a list of eight.
     */
    override suspend fun folders(kind: LocalMediaKind): List<LocalFolder> = withContext(io) {
        if (!hasPermission()) return@withContext emptyList()
        val collection = if (kind == LocalMediaKind.VIDEO) videoCollection() else audioCollection()
        val bucket = if (kind == LocalMediaKind.VIDEO) {
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME
        } else {
            MediaStore.Audio.Media.BUCKET_DISPLAY_NAME
        }

        val counts = linkedMapOf<String, Int>()
        runCatching {
            resolver.query(collection, arrayOf(bucket), null, null, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")
                ?.use { cursor ->
                    var seen = 0
                    while (cursor.moveToNext() && seen < FOLDER_SCAN) {
                        seen++
                        val name = cursor.getStringOrNull(0) ?: continue
                        counts[name] = (counts[name] ?: 0) + 1
                    }
                }
        }
        counts.entries
            .sortedByDescending { it.value }
            .map { LocalFolder(name = it.key, count = it.value) }
    }

    /* -------------------------------------------------------------------- the read */

    private suspend fun <T> read(
        collection: android.net.Uri,
        projection: Array<String>,
        folder: String?,
        offset: Int,
        limit: Int,
        extraSelection: String? = null,
        map: (Cursor, Columns) -> T,
    ): List<T> = withContext(io) {
        if (!hasPermission()) return@withContext emptyList()

        val clauses = buildList {
            extraSelection?.let { add("($it)") }
            if (folder != null) add("${MediaStore.MediaColumns.BUCKET_DISPLAY_NAME} = ?")
        }
        val selection = clauses.takeIf { it.isNotEmpty() }?.joinToString(" AND ")
        val args = folder?.let { arrayOf(it) }

        // Every read is wrapped. A provider that has been revoked mid-session, a row that
        // vanished while the cursor was open, an OEM store that reports a column it does
        // not have -- all of them are exceptions on a list read, and none of them is worth
        // closing the application for. An empty page draws an empty state.
        runCatching {
            queryPage(collection, projection, selection, args, offset, limit)?.use { cursor ->
                val columns = Columns.of(cursor)
                buildList(cursor.count) {
                    while (cursor.moveToNext()) add(map(cursor, columns))
                }
            }
        }.getOrNull().orEmpty()
    }

    private fun queryPage(
        collection: android.net.Uri,
        projection: Array<String>,
        selection: String?,
        args: Array<String>?,
        offset: Int,
        limit: Int,
    ): Cursor? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        resolver.query(
            collection,
            projection,
            Bundle().apply {
                selection?.let { putString(ContentResolver.QUERY_ARG_SQL_SELECTION, it) }
                args?.let { putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, it) }
                putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, NEWEST_FIRST)
                putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
                putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
            },
            null,
        )
    } else {
        resolver.query(collection, projection, selection, args, "$NEWEST_FIRST LIMIT $limit OFFSET $offset")
    }

    /**
     * Column indices, resolved once per cursor.
     *
     * `getColumnIndex` is a string comparison across the projection and calling it inside
     * the row loop is the classic way to make a list read four times slower than it needs
     * to be. Nullable where the column is not guaranteed: an OEM `MediaStore` that omits
     * one returns -1, and reading -1 throws.
     */
    private class Columns(
        val id: Int,
        val name: Int,
        val title: Int,
        val duration: Int,
        val size: Int,
        val bucket: Int?,
        val width: Int?,
        val height: Int?,
        val artist: Int?,
        val album: Int?,
        val albumId: Int?,
    ) {
        companion object {
            fun of(cursor: Cursor) = Columns(
                id = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID),
                name = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME),
                title = cursor.getColumnIndex(MediaStore.MediaColumns.TITLE),
                duration = cursor.getColumnIndex(MediaStore.MediaColumns.DURATION),
                size = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE),
                bucket = cursor.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME).nullIfMissing(),
                width = cursor.getColumnIndex(MediaStore.MediaColumns.WIDTH).nullIfMissing(),
                height = cursor.getColumnIndex(MediaStore.MediaColumns.HEIGHT).nullIfMissing(),
                artist = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST).nullIfMissing(),
                album = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM).nullIfMissing(),
                albumId = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID).nullIfMissing(),
            )

            private fun Int.nullIfMissing(): Int? = takeIf { it >= 0 }
        }
    }

    /**
     * The name to show.
     *
     * `DISPLAY_NAME` is the filename and `TITLE` is the tag. The filename is preferred
     * because it is what the user recognises and what they see in every other application
     * on the device; the tag is the fallback for the rows where the platform has hidden
     * the filename. Neither is ever allowed to be empty — a blank row is the defect the
     * reference audio list has.
     */
    private fun Cursor.name(columns: Columns): String =
        getStringOrNull(columns.name)?.takeIf { it.isNotBlank() }
            ?: getStringOrNull(columns.title)?.takeIf { it.isNotBlank() }
            ?: UNTITLED

    /*
     * The four readers, each taking a nullable index.
     *
     * Nullable because that is what a call site has: `Columns` resolves a column that an
     * OEM `MediaStore` does not report to null, and every reader already had to answer
     * "is this column here at all" before answering "is this row's value null". Folding
     * both questions into the reader removes five call sites that were answering the
     * first one in three different ways.
     */
    private fun Cursor.getStringOrNull(index: Int?): String? =
        if (index == null || index < 0 || isNull(index)) null else getString(index)

    private fun Cursor.getLongOrZero(index: Int?): Long =
        if (index == null || index < 0 || isNull(index)) 0 else getLong(index)

    private fun Cursor.getLongOrNull(index: Int?): Long? =
        if (index == null || index < 0 || isNull(index)) null else getLong(index)

    private fun Cursor.getIntOrZero(index: Int?): Int =
        if (index == null || index < 0 || isNull(index)) 0 else getInt(index)

    private fun videoCollection() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

    private fun audioCollection() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

    companion object {
        /**
         * What has to be granted, which changed shape in Android 13.
         *
         * Before 33 one broad storage permission covered everything; from 33 the media
         * types are separate, which is better for the user and means two requests here.
         * Asking for the old one on a new device is silently refused, and asking for the
         * new one on an old device throws, so the split is by version and not by hope.
         */
        fun required(): List<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                listOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
            } else {
                listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }

        /** Newest first: what somebody looking for the clip they just shot expects. */
        private const val NEWEST_FIRST = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"

        private const val FOLDER_SCAN = 5_000
        private const val UNKNOWN = "<unknown>"
        private const val UNTITLED = "—"

        private val VIDEO_PROJECTION = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.DATE_MODIFIED,
        )

        private val AUDIO_PROJECTION = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DATE_MODIFIED,
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class LocalMediaModule {

    @Binds
    @Singleton
    abstract fun library(real: MediaStoreLibrary): LocalMediaLibrary
}
