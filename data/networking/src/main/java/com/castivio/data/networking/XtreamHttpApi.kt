package com.castivio.data.networking

import com.castivio.core.common.AppError
import com.castivio.core.common.Outcome
import com.castivio.data.parsing.XtreamAccount
import com.castivio.data.parsing.XtreamEpgEntry
import com.castivio.data.parsing.XtreamImportEngine
import com.castivio.data.parsing.XtreamParser
import com.castivio.data.parsing.XtreamUrls
import androidx.tracing.trace
import com.castivio.core.platform.CastivioTrace
import com.castivio.domain.MediaKind
import okhttp3.OkHttpClient
import okhttp3.Request
import com.castivio.core.platform.PerformanceLog
import java.io.IOException
import java.io.InterruptedIOException
import java.io.InputStreamReader
import java.io.Reader
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * The HTTP half of the Xtream integration.
 *
 * Everything about *what* the responses mean lives in `:data:parsing`; this
 * builds URLs, opens streams and closes them. Keeping the split there is what
 * lets the import engine be tested against string fixtures and benchmarked on
 * every commit without a server.
 *
 * Readers returned from here are owned by the caller, which closes them — the
 * import engine does so with `use`. Closing the reader closes the socket.
 */
class XtreamHttpApi(
    private val client: OkHttpClient,
    private val base: String,
    private val username: String,
    private val password: String,
    private val userAgent: String? = null,
) : XtreamImportEngine.Api {

    override fun categories(kind: MediaKind): Reader = open(
        XtreamUrls.api(base, username, password, action = kind.categoriesAction()),
    )

    override fun streams(kind: MediaKind, categoryId: String): Reader = open(
        XtreamUrls.api(
            base, username, password,
            action = kind.streamsAction(),
            parameters = mapOf("category_id" to categoryId),
        ),
    )

    override fun series(categoryId: String): Reader = open(
        XtreamUrls.api(
            base, username, password,
            action = "get_series",
            parameters = mapOf("category_id" to categoryId),
        ),
    )

    override fun seriesInfo(seriesId: String): Reader = open(
        XtreamUrls.api(
            base, username, password,
            action = "get_series_info",
            parameters = mapOf("series_id" to seriesId),
        ),
    )

    override fun streamUrl(kind: MediaKind, streamId: String, extension: String?): String =
        XtreamUrls.stream(base, username, password, kind, streamId, extension)

    /**
     * Validates the credentials.
     *
     * The distinction this draws is what a login screen needs: wrong password,
     * expired subscription and unreachable host are three different messages, and
     * an empty catalogue is none of them.
     */
    fun account(): Outcome<XtreamAccount> = try {
        open(XtreamUrls.api(base, username, password)).use { reader ->
            val account = XtreamParser.parseAccount(reader)
            when {
                account == null -> Outcome.Failure(AppError.MALFORMED_PLAYLIST)
                !account.authenticated -> Outcome.Failure(AppError.UNAUTHORIZED)
                else -> Outcome.Success(account)
            }
        }
    } catch (e: HttpStatusException) {
        Outcome.Failure(e.error, e)
    } catch (e: SocketTimeoutException) {
        // The order matters and is the whole defect this replaces: every one of these
        // is an IOException, so a single `catch (IOException)` swallowed all of them
        // and reported a slow provider as an absent one. `HttpStreamSource` has drawn
        // this distinction since it was written; this path had not.
        Outcome.Failure(AppError.TIMEOUT, e)
    } catch (e: InterruptedIOException) {
        Outcome.Failure(AppError.TIMEOUT, e)
    } catch (e: UnknownHostException) {
        Outcome.Failure(AppError.NETWORK_UNAVAILABLE, e)
    } catch (e: IOException) {
        Outcome.Failure(AppError.NETWORK_UNAVAILABLE, e)
    }

    /**
     * Now/next for one channel, in kilobytes.
     *
     * Preferred over an XMLTV download when all the app needs is what is on: a
     * short EPG response is ~2 KB against a guide's ~100 MB.
     */
    fun shortEpg(streamId: String, limit: Int = SHORT_EPG_LIMIT): Outcome<List<XtreamEpgEntry>> = try {
        open(
            XtreamUrls.api(
                base, username, password,
                action = "get_short_epg",
                parameters = mapOf("stream_id" to streamId, "limit" to limit.toString()),
            ),
        ).use { reader ->
            val entries = ArrayList<XtreamEpgEntry>(limit)
            XtreamParser.parseShortEpg(reader) { entries.add(it) }
            Outcome.Success(entries)
        }
    } catch (e: HttpStatusException) {
        Outcome.Failure(e.error, e)
    } catch (e: SocketTimeoutException) {
        // The order matters and is the whole defect this replaces: every one of these
        // is an IOException, so a single `catch (IOException)` swallowed all of them
        // and reported a slow provider as an absent one. `HttpStreamSource` has drawn
        // this distinction since it was written; this path had not.
        Outcome.Failure(AppError.TIMEOUT, e)
    } catch (e: InterruptedIOException) {
        Outcome.Failure(AppError.TIMEOUT, e)
    } catch (e: UnknownHostException) {
        Outcome.Failure(AppError.NETWORK_UNAVAILABLE, e)
    } catch (e: IOException) {
        Outcome.Failure(AppError.NETWORK_UNAVAILABLE, e)
    }

    /** The provider's own XMLTV endpoint, for a full guide import. */
    fun xmltvUrl(): String = XtreamUrls.xmltv(base, username, password)

    /**
     * Opens one Xtream call, inside a trace section named for its action.
     *
     * The section is the request *and* the handshake: `execute()` is where a sequential
     * import actually spends its time, and Phase A could only reason about that from the
     * shape of the loop. Named by the provider's own `action` so a trace separates the
     * single categories call from the N stream calls that follow without counting
     * anything -- and without a credential reaching the trace, which is why the name is
     * built from one query parameter rather than from the URL.
     */
    private fun open(url: String): Reader = trace(traceName(url)) { openWithRetry(url) }

    /**
     * One call, tried up to [MAX_RETRIES] more times if the failure was transient.
     *
     * ## Why this is here and not in the engine
     *
     * Because the engine's own contract says so: it takes plain [Reader]s precisely so
     * that "HTTP, credentials and retries live in `:data:networking`". A retry loop in
     * the engine would also be a retry loop in the pure module that must compile for
     * every future platform.
     *
     * ## Why a retry changes the arithmetic rather than merely papering over it
     *
     * A section import is hundreds of sequential calls and the engine used to abandon
     * the whole catalogue on the first one that failed. At a 1% per-call failure rate
     * that is a 0.7% chance of ever finishing. Two cheap retries turn a per-call
     * failure rate of 1% into roughly 0.0001%, which is what makes finishing the normal
     * outcome instead of the lucky one.
     *
     * ## What is not retried
     *
     * A rejected password, a missing endpoint and a malformed reply are answers, not
     * accidents: asking again produces the same answer more slowly. Only the failures
     * that are about the moment rather than the request are repeated — see
     * [isTransient].
     *
     * The backoff is blocking, like everything else on this path, and short by design:
     * 500ms then 1s. The engine checks cancellation between categories, so the longest
     * a cancelled import can linger inside a backoff is one second.
     */
    private fun openWithRetry(url: String): Reader {
        var attempt = 0
        while (true) {
            PerformanceLog.requestStarted()
            try {
                return openNow(url)
            } catch (e: IOException) {
                if (attempt >= MAX_RETRIES || !isTransient(e)) {
                    PerformanceLog.requestFailed()
                    throw e
                }
                attempt++
                PerformanceLog.requestRetried()
                try {
                    Thread.sleep(BACKOFF_MS * attempt)
                } catch (interrupted: InterruptedException) {
                    // The thread was cancelled while waiting. Restore the flag and let
                    // the original failure stand rather than starting another attempt
                    // nobody is waiting for.
                    Thread.currentThread().interrupt()
                    PerformanceLog.requestFailed()
                    throw e
                }
            }
        }
    }

    /**
     * Whether asking again could plausibly get a different answer.
     *
     * The distinction is "about the moment" versus "about the request". A timeout, a
     * connection reset and a 503 are the first; a 401 and a 404 are the second, and
     * repeating those costs the user two extra round trips to be told the same thing.
     */
    private fun isTransient(e: IOException): Boolean = when (e) {
        // Checked before the timeout cases: this is an IOException of our own, and its
        // status already says whether repeating is worth anything.
        is HttpStatusException -> e.statusCode == 408 || e.statusCode == 429 || e.statusCode in 500..599
        // A host that does not resolve will not resolve 500ms later, and a retry here
        // only makes a wrong address take three times as long to report.
        is UnknownHostException -> false
        // SocketTimeoutException is an InterruptedIOException, so this covers both.
        is InterruptedIOException -> true
        // Connection reset, broken pipe, an early EOF mid-body: all about the moment.
        else -> true
    }

    private fun openNow(url: String): Reader {
        val builder = Request.Builder().url(url).get()
        if (userAgent != null) builder.header("User-Agent", userAgent)
        val response = client.newCall(builder.build()).execute()
        if (!response.isSuccessful) {
            response.close()
            throw HttpStatusException(response.code)
        }
        val body = response.body ?: run {
            response.close()
            throw HttpStatusException(response.code)
        }
        // Reading the body as a stream, never as a string: a category response can
        // be tens of megabytes and `string()` would hold all of it.
        return InputStreamReader(decompressIfNeeded(body.byteStream()), Charsets.UTF_8)
            .buffered(READ_BUFFER)
    }

    private fun MediaKind.categoriesAction(): String = when (this) {
        MediaKind.MOVIE -> "get_vod_categories"
        MediaKind.SERIES -> "get_series_categories"
        MediaKind.LIVE, MediaKind.RADIO -> "get_live_categories"
    }

    private fun MediaKind.streamsAction(): String = when (this) {
        MediaKind.MOVIE -> "get_vod_streams"
        MediaKind.SERIES -> "get_series"
        MediaKind.LIVE, MediaKind.RADIO -> "get_live_streams"
    }

    /** `Castivio.Api.get_live_streams`, and never the host, the user or the password. */
    private fun traceName(url: String): String {
        val action = url.substringAfter("action=", "").substringBefore('&')
        return if (action.isEmpty()) "${CastivioTrace.API}.account" else "${CastivioTrace.API}.$action"
    }

    private companion object {
        const val READ_BUFFER = 1 shl 16

        /** Attempts after the first. Two, and never unbounded -- see [openWithRetry]. */
        const val MAX_RETRIES = 2

        /** Multiplied by the attempt number: 500ms, then 1s. */
        const val BACKOFF_MS = 500L
        /** Two entries is now and next; more is a guide, not a row label. */
        const val SHORT_EPG_LIMIT = 4
    }
}

/** An HTTP status the caller has to distinguish: wrong password versus server down. */
class HttpStatusException(val statusCode: Int) : IOException("HTTP $statusCode") {
    val error: AppError = when (statusCode) {
        401, 403 -> AppError.UNAUTHORIZED
        404, 410 -> AppError.NOT_FOUND
        408, 429 -> AppError.TIMEOUT
        in 500..599 -> AppError.SERVER_ERROR
        else -> AppError.UNKNOWN
    }
}
