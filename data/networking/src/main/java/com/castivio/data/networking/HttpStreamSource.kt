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
    fun <T> stream(request: RemoteRequest, consume: (Reader) -> T): RemoteResult<T> =
        streamCounting(request) { reader, _ -> consume(reader) }

    /**
     * [stream], with [consume] also told what it is reading: the status and type the
     * provider answered with, and how much of the body has arrived so far.
     *
     * A playlist import reports progress per committed batch, and a batch is a thousand
     * rows; on a slow line that is a long time to show nothing. The count lets it say
     * how far the download has got, and the status and type let a failure that happens
     * while reading say what the provider had sent.
     *
     * A failure while the body is being read is reported as [RemoteResult.Stage.BODY]:
     * the provider answered, and the answer did not arrive whole. That is a different
     * problem from not reaching the provider, and a log that cannot tell them apart
     * sends whoever reads it to look at the wrong thing.
     */
    fun <T> streamCounting(request: RemoteRequest, consume: (Reader, Received) -> T): RemoteResult<T> {
        val call = try {
            client.newCall(request.toOkHttp())
        } catch (e: IllegalArgumentException) {
            // A URL the user typed that is not a URL at all.
            return RemoteResult.Failure(AppError.UNKNOWN, null, e)
        }

        return try {
            call.execute().use { response ->
                val type = response.header("Content-Type")
                when {
                    response.code == HTTP_NOT_MODIFIED -> RemoteResult.NotModified
                    !response.isSuccessful ->
                        RemoteResult.Failure(response.code.toAppError(), response.code, contentType = type)
                    else -> {
                        val body = response.body
                            ?: return RemoteResult.Failure(AppError.SERVER_ERROR, response.code, contentType = type)
                        try {
                            val counting = HashingInputStream(decompressIfNeeded(body.byteStream()))
                            val received = Received(response.code, type, counting)
                            val value = InputStreamReader(counting, Charsets.UTF_8)
                                .buffered(READ_BUFFER)
                                .use { reader -> consume(reader, received) }
                            RemoteResult.Success(
                                value = value,
                                etag = response.header("ETag"),
                                lastModified = response.header("Last-Modified"),
                                contentHash = counting.fingerprint(),
                                statusCode = response.code,
                                contentType = type,
                            )
                        } catch (e: IOException) {
                            RemoteResult.Failure(
                                error = e.toAppError(),
                                statusCode = response.code,
                                cause = e,
                                contentType = type,
                                stage = RemoteResult.Stage.BODY,
                            )
                        }
                    }
                }
            }
        } catch (e: IOException) {
            RemoteResult.Failure(e.toAppError(), null, e)
        }
    }

    /**
     * Whether the remote content has changed, using one cheap request.
     *
     * Uses GET with validators rather than HEAD: some Xtream panels answer HEAD
     * with 405 or with a 200 and no validators, which would make every check look
     * like a change.
     *
     * ## The read is bounded, and that is the whole point of the method
     *
     * `Range: bytes=0-4095` asks for four kilobytes, and a provider that honours it
     * sends four kilobytes. Plenty do not: an IPTV playlist endpoint that ignores the
     * header answers `200` with the entire file, and the loop that used to drain the
     * body "so the connection is used as intended" then read every byte of a 100 MB
     * playlist in order to throw it away.
     *
     * That is not a slow probe, it is the download this request exists to avoid — and
     * it is what made adding an M3U link start a full transfer at the moment of
     * *validating* it, and time out on a slow line before activation could finish.
     * Nothing downstream had asked for a single row.
     *
     * So it reads at most the probe and stops. Closing a response early discards the
     * connection instead of returning it to the pool, which is the correct trade when
     * the alternative is the rest of the file.
     */
    fun hasChanged(request: RemoteRequest): RemoteResult<Boolean> =
        when (val result = stream(request.copy(rangeFirstBytes = PROBE_BYTES), ::readProbe)) {
            is RemoteResult.NotModified -> RemoteResult.NotModified
            is RemoteResult.Success -> RemoteResult.Success(
                value = true,
                etag = result.etag,
                lastModified = result.lastModified,
                // A fingerprint of the first page, not of the file — the hash is
                // computed from the bytes that passed through and only a page of them
                // did. Nothing reads it: the only consumer of a `contentHash` is a
                // completed import, which streams the whole body. The validators above
                // are what a probe is actually asked for.
                contentHash = result.contentHash,
                statusCode = result.statusCode,
                contentType = result.contentType,
            )
            is RemoteResult.Failure -> result
        }

    /**
     * Reads at most a page and stops.
     *
     * A named function rather than a lambda in the `when` subject above, because a
     * `when` subject may not contain a `var` declaration — the loop counter has to
     * live somewhere the compiler allows one.
     */
    private fun readProbe(reader: Reader): Boolean {
        val buffer = CharArray(PROBE_BYTES)
        var read = 0
        while (read < PROBE_BYTES) {
            val n = reader.read(buffer, read, PROBE_BYTES - read)
            if (n <= 0) break
            read += n
        }
        return true
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

    /** `SocketTimeoutException` is an `InterruptedIOException`, so this covers both. */
    private fun IOException.toAppError(): AppError = when (this) {
        is InterruptedIOException -> AppError.TIMEOUT
        is UnknownHostException -> AppError.NETWORK_UNAVAILABLE
        else -> AppError.NETWORK_UNAVAILABLE
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

    /** How many bytes have passed through so far. Rises while the stream is read. */
    val bytesRead: Long get() = bytes

    /** Only meaningful once the stream has been read to the end. */
    fun fingerprint(): String = "crc32:${crc.value.toString(16)}:$bytes"
}

/**
 * What [HttpStreamSource.streamCounting] tells a reader about the answer it is reading.
 *
 * The status and type are the provider's own; [bytes] is the body as it reaches the
 * reader, after any gzip was undone, and rises while the reader reads.
 */
class Received internal constructor(
    val statusCode: Int,
    val contentType: String?,
    private val counting: HashingInputStream,
) {
    val bytes: Long get() = counting.bytesRead
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
        val statusCode: Int = HTTP_OK,
        val contentType: String? = null,
    ) : RemoteResult<T>

    data class Failure(
        val error: AppError,
        val statusCode: Int? = null,
        val cause: Throwable? = null,
        /** The response's own `Content-Type`, when there was a response to have one. */
        val contentType: String? = null,
        val stage: Stage = Stage.REQUEST,
    ) : RemoteResult<Nothing>

    /** Whether the provider had answered when a failure happened. */
    enum class Stage {
        /** Before an answer: the address, the network, the handshake, or an error status. */
        REQUEST,

        /** After a successful answer began: the body did not arrive, or could not be read, whole. */
        BODY,
    }
}

private const val HTTP_OK = 200

/**
 * Which of four different problems a failed request was.
 *
 * The four a person reading a log has to tell apart, because each sends them somewhere
 * else: the provider said no, the provider could not be reached, the provider was too
 * slow, or the provider's answer could not be read. [RemoteResult.Failure.error] is the
 * coarser vocabulary the screens speak; this is the one a diagnosis needs.
 */
enum class FailureKind {
    /** The provider answered, and the answer was an error status. */
    REFUSED,

    /** No answer at all: the address, the network, the TLS handshake, the socket. */
    CONNECTION,

    /** An answer was awaited, or was arriving, and did not come in time. */
    TIMEOUT,

    /** The provider answered with success, and the body could not be read to the end. */
    READ,
}

val RemoteResult.Failure.kind: FailureKind
    get() = when {
        // `SocketTimeoutException` is an `InterruptedIOException`.
        cause is InterruptedIOException -> FailureKind.TIMEOUT
        stage == RemoteResult.Stage.BODY -> FailureKind.READ
        statusCode != null -> FailureKind.REFUSED
        else -> FailureKind.CONNECTION
    }
