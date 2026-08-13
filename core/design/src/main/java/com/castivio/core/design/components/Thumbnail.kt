package com.castivio.core.design.components

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.util.LruCache
import android.util.Size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A real picture of a real file.
 *
 * ## Why Castivio draws its own instead of adding an image library
 *
 * Coil or Glide would do this and do it well. Both are also a dependency, a second
 * lifecycle to reason about and a second memory policy, added to solve one problem that
 * the platform already answers directly: `ContentResolver.loadThumbnail` returns a frame
 * for a video and cover art for a track, decoded by the system, usually from a cache the
 * system already built when the file was indexed. On a 1 GB stick that is a materially
 * different amount of machinery.
 *
 * ## The rules it keeps
 *
 * **Never on the drawing path.** A row draws its name, its duration and a placeholder
 * immediately; the picture arrives afterwards or not at all. That is the direct fix for
 * the reference players that show `loading…` where the name should be — the name is never
 * waiting on the picture here, because the two are not the same read.
 *
 * **Never on the main thread.** Decoding a bitmap is tens of milliseconds and a grid
 * scrolls past twelve of them.
 *
 * **Bounded.** A cache measured in bytes rather than entries, sized against the device's
 * own heap, because a wall of 4K thumbnails is the classic way to turn a media library
 * into an out-of-memory crash.
 *
 * **Silent on failure.** A file with no decodable frame, a codec the extractor cannot
 * open, a row that vanished between the query and the read: all ordinary, all answered
 * with null and a placeholder, none of them an error the user should be told about.
 */
object CastivioThumbnails {

    /**
     * An eighth of the heap.
     *
     * The conventional figure for an image cache is a quarter. Half of that here because
     * this cache serves a browse screen the user passes through, not the screen they live
     * on, and because a player has a decoder and a buffer competing for the same heap.
     */
    private val cache: LruCache<String, Bitmap> by lazy {
        val budget = (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(MAX_CACHE_BYTES)
        object : LruCache<String, Bitmap>(budget.toInt()) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        }
    }

    /**
     * Files that have already failed once.
     *
     * Without this a grid retries the same undecodable file every time it scrolls back
     * into view, which is the most expensive possible way to produce nothing. The set is
     * bounded and cleared wholesale rather than pruned — it is a hint, and rediscovering a
     * few entries costs one failed decode each.
     */
    private val hopeless = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * The picture for [uri], or null.
     *
     * [albumId] is the audio case: on older platforms a track's cover is not reachable
     * from the track's own URI and has to be read from the album art table instead, so the
     * caller passes the album it belongs to and this decides which route applies.
     */
    suspend fun load(
        context: Context,
        uri: String,
        widthPx: Int,
        heightPx: Int,
        albumId: Long? = null,
    ): Bitmap? {
        cache.get(uri)?.let { return it }
        if (uri in hopeless) return null

        val bitmap = withContext(Dispatchers.IO) {
            decode(context, uri, widthPx, heightPx, albumId)
        }
        if (bitmap == null) {
            if (hopeless.size > HOPELESS_LIMIT) hopeless.clear()
            hopeless += uri
            return null
        }
        cache.put(uri, bitmap)
        return bitmap
    }

    private fun decode(
        context: Context,
        uri: String,
        widthPx: Int,
        heightPx: Int,
        albumId: Long?,
    ): Bitmap? {
        val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return null

        // The platform's own path, and by far the best one: on API 29+ this returns the
        // thumbnail the media scanner already generated, so most rows cost a file read
        // rather than a decode of the video.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                context.contentResolver.loadThumbnail(
                    parsed,
                    Size(widthPx, heightPx),
                    CancellationSignal(),
                )
            }.getOrNull()?.let { return it }
        }

        // Older platforms, and the rows the scanner has no thumbnail for. Audio first,
        // because a track's art is embedded and cheap where a video frame is not.
        albumId?.let { id ->
            albumArt(context, id, widthPx, heightPx)?.let { return it }
        }
        return embeddedOrFrame(context, parsed, widthPx, heightPx)
    }

    /** The album art table, which is where a cover lives before API 29. */
    private fun albumArt(context: Context, albumId: Long, widthPx: Int, heightPx: Int): Bitmap? =
        runCatching {
            val art = ContentUris.withAppendedId(ALBUM_ART, albumId)
            context.contentResolver.openFileDescriptor(art, "r")?.use { descriptor ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor, null, bounds)
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, widthPx, heightPx)
                }
                BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor, null, options)
            }
        }.getOrNull()

    /**
     * The last resort: open the file and take a frame.
     *
     * Genuinely expensive — this is a decoder start for one picture — which is why it is
     * behind two cheaper routes and behind the cache. `getScaledFrameAtTime` where the
     * platform has it, because scaling inside the retriever avoids allocating the full
     * frame first.
     */
    private fun embeddedOrFrame(context: Context, uri: Uri, widthPx: Int, heightPx: Int): Bitmap? =
        runCatching {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(context, uri)
                retriever.embeddedPicture?.let { bytes ->
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, widthPx, heightPx)
                    }
                    return@use BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    retriever.getScaledFrameAtTime(
                        FRAME_AT_US,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        widthPx,
                        heightPx,
                    )
                } else {
                    retriever.getFrameAtTime(FRAME_AT_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
            }
        }.getOrNull()

    /** `use` for a retriever, which is only `AutoCloseable` from API 29. */
    private inline fun <T> MediaMetadataRetriever.use(block: (MediaMetadataRetriever) -> T): T = try {
        block(this)
    } finally {
        runCatching { release() }
    }

    private fun sampleSize(width: Int, height: Int, targetW: Int, targetH: Int): Int {
        if (width <= 0 || height <= 0 || targetW <= 0 || targetH <= 0) return 1
        var sample = 1
        while (width / (sample * 2) >= targetW && height / (sample * 2) >= targetH) sample *= 2
        return sample
    }

    private val ALBUM_ART: Uri = Uri.parse("content://media/external/audio/albumart")

    /**
     * One second in, not zero.
     *
     * The first frame of a great many files is black — a fade in, a slate, a blank leader —
     * and a wall of black rectangles looks exactly like a wall of failures.
     */
    private const val FRAME_AT_US = 1_000_000L

    private const val MAX_CACHE_BYTES = 48L * 1024 * 1024
    private const val HOPELESS_LIMIT = 512
}

/**
 * The picture for a row, as composition state.
 *
 * Null until it arrives, and null forever for a file that has none — so every caller draws
 * a placeholder first and swaps when there is something to swap to. Keyed on the URI, so a
 * recycled row in a grid cancels the load it no longer needs and starts the one it does.
 */
@Composable
fun rememberThumbnail(
    uri: String?,
    width: Dp,
    height: Dp,
    albumId: Long? = null,
): State<ImageBitmap?> {
    val context = LocalContext.current
    val density = LocalDensity.current
    val widthPx = with(density) { width.roundToPx() }
    val heightPx = with(density) { height.roundToPx() }

    val state = remember(uri) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(uri, widthPx, heightPx) {
        if (uri == null || widthPx <= 0 || heightPx <= 0) return@LaunchedEffect
        val bitmap = CastivioThumbnails.load(context, uri, widthPx, heightPx, albumId)
        state.value = bitmap?.asImageBitmap()
    }
    return state
}
