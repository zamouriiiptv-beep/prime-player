package com.castivio.data.networking

import com.castivio.core.common.AppError
import com.castivio.core.common.Outcome
import com.castivio.data.parsing.XtreamAccount
import com.castivio.data.parsing.XtreamEpgEntry
import com.castivio.data.parsing.XtreamImportEngine
import com.castivio.data.parsing.XtreamParser
import com.castivio.data.parsing.XtreamUrls
import com.castivio.domain.MediaKind
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStreamReader
import java.io.Reader

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
    } catch (e: IOException) {
        Outcome.Failure(AppError.NETWORK_UNAVAILABLE, e)
    }

    /** The provider's own XMLTV endpoint, for a full guide import. */
    fun xmltvUrl(): String = XtreamUrls.xmltv(base, username, password)

    private fun open(url: String): Reader {
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

    private companion object {
        const val READ_BUFFER = 1 shl 16
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
