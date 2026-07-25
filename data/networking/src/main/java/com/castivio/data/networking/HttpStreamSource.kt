package com.castivio.data.networking

import com.castivio.core.common.AppError
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.InterruptedIOException
import java.io.Reader
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.zip.CRC32
import java.util.zip.GZIPInputStream

/**
 * Fetches a playlist or guide as a stream.
 *
 * Three things here are the difference between a player that feels considerate
 * and one that burns a user's data allowance every launch:
 *
 *  - **Conditional requests.** An `ETag` or `Last-Modified` from the last fetch
 *    turns a 100 MB download into a 200-byte `304 Not Modified`. This is the
 *    single most valuable thing the sync layer does.
 *  - **Content hashing while streaming.** Plenty of providers send no validators
 *    at all. The hash is computed from the bytes as they pass through, so it costs
 *    nothing extra, and an unchanged playlist skips the *import* even when the
 *    download could not be skipped.
 *  - **Gzip by content, not by header.** XMLTV guides are usually served as `.gz`
 *    files, and providers disagree about `Content-Type` and `Content-Encoding`.
 *    The magic bytes are checked instead, which is right regardless of what the
 *    headers claim.
 *
 * Blocking, like the engines it feeds. Call it on an IO dispatcher.
 */
class HttpStreamSource(private val client: OkHttpClient) {

    /**
     * Opens [request] and hands the body to [consume].
     *
     * The stream is closed before this returns — a leaked response body holds a
     * socket, and a TV box has few to spare.
     */
    fun <T> stream(request: RemoteRequest, consume: (Reader) -> T): RemoteResult<T> {
        val call = try {
            client.newCall(request.toOkHttp())
        } catch (e: IllegalArgumentException) {
            // A URL the user typed that is not a URL at all.
            return RemoteResult.Failure(AppError.UNKNOWN, null, e)
        }

        return try {
            call.execute().use { response ->
                when {
                    response.code == HTTP_NOT_MODIFIED -> RemoteResult.NotModified
                    !response.isSuccessful -> RemoteResult.Failure(response.code.toAppError(), response.code)
                    else -> {
                        val body = response.body ?: return RemoteResult.Failure(AppError.SERVER_ERROR, response.code)
                        val counting = HashingInputStream(decompressIfNeeded(body.byteStream()))
                        val value = InputStreamReader(counting, Charsets.UTF_8)
                            .buffered(READ_BUFFER)
                            .use(consume)
                        RemoteResult.Success(
                            value = value,
                            etag = response.header("ETag"),
                            lastModified = response.header("Last-Modified"),
                            contentHash = counting.fingerprint(),
                        )
                    }
                }
            }
        } catch (e: SocketTimeoutException) {
            RemoteResult.Failure(AppError.TIMEOUT, null, e)
        } catch (e: InterruptedIOException) {
            RemoteResult.Failure(AppError.TIMEOUT, null, e)
        } catch (e: UnknownHostException) {
            RemoteResult.Failure(AppError.NETWORK_UNAVAILABLE, null, e)
        } catch (e: IOException) {
            RemoteResult.Failure(AppError.NETWORK_UNAVAILABLE, null, e)
        }
    }

    /**
     * Whether the remote content has changed, using one cheap request.
     *
     * Uses GET with validators rather than HEAD: some Xtream panels answer HEAD
     * with 405 or with a 200 and no validators, which would make every check look
     * like a change.
     */
    fun hasChanged(request: RemoteRequest): RemoteResult<Boolean> =
        when (val result = stream(request.copy(rangeFirstBytes = PROBE_BYTES)) { reader ->
            // Read a little so the connection is used as intended, and discard it.
            val buffer = CharArray(1024)
            while (reader.read(buffer) > 0) Unit
            true
        }) {
            is RemoteResult.NotModified -> RemoteResult.NotModified
            is RemoteResult.Success -> RemoteResult.Success(
                value = true,
                etag = result.etag,
                lastModified = result.lastModified,
                contentHash = result.contentHash,
            )
            is RemoteResult.Failure -> result
        }

    private fun RemoteRequest.toOkHttp(): Request {
        val builder = Request.Builder().url(url).get()
        if (userAgent != null) builder.header("User-Agent", userAgent)
        // Conditional headers only when we have something to compare against.
        if (etag != null) builder.header("If-None-Match", etag)
        if (lastModified != null) builder.header("If-Modified-Since", lastModified)
        if (rangeFirstBytes != null) builder.header("Range", "bytes=0-${rangeFirstBytes - 1}")
        // Never a cached response for a sync check: the point is to ask the server.
        if (noCache) builder.header("Cache-Control", "no-cache")
        return builder.build()
    }

    private fun Int.toAppError(): AppError = when (this) {
        401, 403 -> AppError.UNAUTHORIZED
        404, 410 -> AppError.NOT_FOUND
        408, 429 -> AppError.TIMEOUT
        in 500..599 -> AppError.SERVER_ERROR
        else -> AppError.UNKNOWN
    }

    private companion object {
        const val HTTP_NOT_MODIFIED = 304
        const val READ_BUFFER = 1 shl 16
        /** Enough to reach validators and a first chunk, not enough to matter. */
        const val PROBE_BYTES = 4096
    }
}

/**
 * Sniffs gzip from the first two bytes.
 *
 * Public because the local-file path needs it too: users pick `playlist.m3u.gz`
 * as readily as they paste a gzipped URL.
 *
 * A guide at `epg.xml.gz` is a gzip *file*, which is not the same as a gzipped
 * *transfer* — OkHttp transparently handles the latter and must not be asked to
 * handle the former. Providers also mislabel both, so the bytes are the only
 * reliable signal.
 */
fun decompressIfNeeded(input: InputStream): InputStream {
    val buffered = BufferedInputStream(input, 1 shl 15)
    buffered.mark(2)
    val first = buffered.read()
    val second = buffered.read()
    buffered.reset()
    val isGzip = first == 0x1f && second == 0x8b
    return if (isGzip) GZIPInputStream(buffered, 1 shl 15) else buffered
}

/**
 * Fingerprints a stream as it is read.
 *
 * Public for the same reason as [decompressIfNeeded]: a local file has no ETag,
 * so this is the only way to know whether re-importing it is worth the work.
 *
 * CRC32 plus the byte count, which is enough to answer "did this change?" — the
 * only question asked of it. A cryptographic digest would cost several times more
 * CPU across 100 MB on a device that needs that CPU for decoding video.
 */
class HashingInputStream(private val delegate: InputStream) : InputStream() {
    private val crc = CRC32()
    private var bytes = 0L

    override fun read(): Int {
        val value = delegate.read()
        if (value >= 0) {
            crc.update(value)
            bytes++
        }
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val read = delegate.read(b, off, len)
        if (read > 0) {
            crc.update(b, off, read)
            bytes += read
        }
        return read
    }

    override fun close() = delegate.close()

    /** Only meaningful once the stream has been read to the end. */
    fun fingerprint(): String = "crc32:${crc.value.toString(16)}:$bytes"
}

data class RemoteRequest(
    val url: String,
    val etag: String? = null,
    val lastModified: String? = null,
    val userAgent: String? = null,
    /** Set for a sync probe, so the server is asked rather than the cache. */
    val noCache: Boolean = false,
    /** Requests only the first N bytes, for a change probe. */
    val rangeFirstBytes: Int? = null,
)

sealed interface RemoteResult<out T> {
    /** The provider confirmed nothing changed. The cheapest possible outcome. */
    data object NotModified : RemoteResult<Nothing>

    data class Success<T>(
        val value: T,
        val etag: String?,
        val lastModified: String?,
        val contentHash: String,
    ) : RemoteResult<T>

    data class Failure(
        val error: AppError,
        val statusCode: Int? = null,
        val cause: Throwable? = null,
    ) : RemoteResult<Nothing>
}
