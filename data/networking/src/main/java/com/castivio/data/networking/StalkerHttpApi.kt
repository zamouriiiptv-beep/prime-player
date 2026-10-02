package com.castivio.data.networking

import com.castivio.core.common.AppError
import com.castivio.core.common.Outcome
import com.castivio.data.parsing.JsonFormatException
import com.castivio.data.parsing.PortalUrls
import com.castivio.data.parsing.StalkerChannel
import com.castivio.data.parsing.StalkerParser
import com.castivio.data.parsing.StalkerItem
import com.castivio.data.parsing.StalkerProfile
import com.castivio.domain.MediaKind
import java.io.IOException
import java.io.InputStreamReader
import java.io.InterruptedIOException
import java.io.Reader
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The HTTP half of the Stalker and Ministra integration.
 *
 * The same split as [XtreamHttpApi]: what a response *means* is `:data:parsing`'s,
 * and this builds requests, opens streams and maps failures. What differs is that a
 * portal has a session where a panel has none — every call after the first carries a
 * token the first one returned.
 *
 * ## The device address, and why it is not asked for
 *
 * A portal binds a subscription to a set-top box, and the box identifies itself with a
 * MAC in a cookie on every request. That is the protocol, not a choice: a request
 * without one is answered as an unknown device.
 *
 * The address Castivio sends is [mac], which the caller takes from the device identity —
 * the same address the activation screen shows, because that is the one a user reads
 * out to their provider. It is never a field on the portal form. A user who has already
 * sent their provider an address and received a portal URL has done the identifying
 * part; asking them to type the number back in would be asking them to copy something
 * the application is holding.
 *
 * **It can still be the wrong address**, and that is worth being clear about rather
 * than hiding. On a box where the user read a MAC out of the system's own settings
 * instead of out of Castivio, the provider bound the subscription to that one, and this
 * handshake will be refused. [AppError.UNAUTHORIZED] is the honest answer to that, and
 * it is the one failure on this path where the user has something specific to do.
 *
 * ## Which endpoint
 *
 * A portal's API file is a fact about the installation. [PortalUrls.endpoints] offers
 * the three worth asking in order, and [handshake] stops at the first that answers like
 * a portal — carrying that endpoint forward in the session, so the rest of the
 * subscription's life costs no searching.
 */
class StalkerHttpApi(
    private val client: OkHttpClient,
    private val portalUrl: String,
    private val mac: String,
    private val userAgent: String? = null,
) {

    /**
     * Opens a session: finds the endpoint, and gets a token from it.
     *
     * A refusal is reported from the *last* endpoint tried rather than the first,
     * because the interesting failure is the specific one — a portal that answered
     * 401 is a different message from a host that answered nothing at all.
     */
    fun handshake(): Outcome<StalkerSession> {
        val endpoints = PortalUrls.endpoints(portalUrl)
        if (endpoints.isEmpty()) return Outcome.Failure(AppError.NOT_CONFIGURED)

        var lastFailure: Outcome.Failure? = null
        for (endpoint in endpoints) {
            val attempt = tokenFrom(endpoint)
            when (attempt) {
                is Outcome.Success -> return Outcome.Success(
                    StalkerSession(endpoint = endpoint, token = attempt.value),
                )
                is Outcome.Failure -> {
                    // A 401 is the portal speaking, and asking the next endpoint cannot
                    // improve on it: the installation was found and it refused this
                    // device. Reported immediately so the user is told what to fix
                    // rather than waiting out two more round trips for the same answer.
                    if (attempt.error == AppError.UNAUTHORIZED) return attempt
                    lastFailure = attempt
                }
            }
        }
        return lastFailure ?: Outcome.Failure(AppError.NOT_FOUND)
    }

    /** What the portal says about the account this token belongs to. */
    fun profile(session: StalkerSession): Outcome<StalkerProfile> =
        call(session, "stb", "get_profile") { reader ->
            val parsed = StalkerParser.parseProfile(reader)
            if (parsed == null) Outcome.Failure(AppError.MALFORMED_PLAYLIST) else Outcome.Success(parsed)
        }

    /**
     * One page of live channels, handed over as they are read.
     *
     * Returns the total the portal states, so the caller knows when to stop asking.
     * Zero means the portal did not say, which several do — then an empty page is the
     * only end-of-list signal there is.
     */
    fun channels(
        session: StalkerSession,
        page: Int,
        onChannel: (StalkerChannel) -> Unit,
    ): Outcome<Int> = call(
        session = session,
        type = "itv",
        action = "get_ordered_list",
        parameters = mapOf("genre" to "*", "p" to page.toString(), "sortby" to "number"),
    ) { reader ->
        var total = 0
        StalkerParser.channels(reader, onTotal = { total = it }, onChannel = onChannel)
        Outcome.Success(total)
    }

    /**
     * One page of films, shows or stations.
     *
     * The portal's own type name for each — `vod`, `series`, `radio` — and the same
     * ordered-list action the channels use. An installation that does not serve the
     * type answers with an empty envelope, which reads here as zero items rather than
     * as a failure: a portal with no film library is an ordinary portal.
     */
    fun items(
        session: StalkerSession,
        kind: MediaKind,
        page: Int,
        onItem: (StalkerItem) -> Unit,
    ): Outcome<Int> {
        val type = kind.portalType ?: return Outcome.Success(0)
        return call(
            session = session,
            type = type,
            action = "get_ordered_list",
            parameters = mapOf("category" to "*", "p" to page.toString(), "sortby" to "name"),
        ) { reader ->
            var total = 0
            StalkerParser.items(reader, kind, onTotal = { total = it }, onItem = onItem)
            Outcome.Success(total)
        }
    }

    /**
     * What the portal resolves a command into.
     *
     * The protocol's own answer to a `/media/…` command, and the only honest way to
     * turn one into an address. A refusal comes back as a failure rather than as a
     * guess, and the importer writes no row for it.
     */
    fun createLink(session: StalkerSession, kind: MediaKind, command: String): Outcome<String> {
        val type = kind.portalType ?: return Outcome.Failure(AppError.NOT_FOUND)
        return call(
            session = session,
            type = type,
            action = "create_link",
            parameters = mapOf("cmd" to command, "forced_storage" to "0", "disable_ad" to "0"),
        ) { reader ->
            StalkerParser.parseLink(reader)
                ?.let { Outcome.Success(it) }
                ?: Outcome.Failure(AppError.NOT_FOUND)
        }
    }

    private fun tokenFrom(endpoint: String): Outcome<String> = try {
        open(PortalUrls.call(endpoint, type = "stb", action = "handshake"), token = null).use { reader ->
            StalkerParser.parseHandshake(reader)
                ?.let { Outcome.Success(it) }
                // Answered, and not as a portal: a web server's index page, or an
                // installation at a different path. Not found rather than malformed,
                // because the caller's next move is to try somewhere else.
                ?: Outcome.Failure(AppError.NOT_FOUND)
        }
    } catch (e: JsonFormatException) {
        Outcome.Failure(AppError.NOT_FOUND, e)
    } catch (e: Exception) {
        failureFor(e)
    }

    private fun <T> call(
        session: StalkerSession,
        type: String,
        action: String,
        parameters: Map<String, String> = emptyMap(),
        read: (Reader) -> Outcome<T>,
    ): Outcome<T> = try {
        open(PortalUrls.call(session.endpoint, type, action, parameters), session.token).use(read)
    } catch (e: JsonFormatException) {
        Outcome.Failure(AppError.MALFORMED_PLAYLIST, e)
    } catch (e: Exception) {
        failureFor(e)
    }

    /**
     * The failures that are about the network rather than about the answer.
     *
     * The order is the one [XtreamHttpApi] documents and for the same reason: every
     * one of these is an `IOException`, so a single catch would report a slow portal
     * as an absent one.
     */
    private fun failureFor(e: Exception): Outcome.Failure = when (e) {
        is HttpStatusException -> Outcome.Failure(e.error, e)
        is SocketTimeoutException -> Outcome.Failure(AppError.TIMEOUT, e)
        is InterruptedIOException -> Outcome.Failure(AppError.TIMEOUT, e)
        is UnknownHostException -> Outcome.Failure(AppError.NETWORK_UNAVAILABLE, e)
        is IOException -> Outcome.Failure(AppError.NETWORK_UNAVAILABLE, e)
        else -> throw e
    }

    /**
     * One request, with the headers a portal expects.
     *
     * The cookie is the protocol's, not a convenience: `mac` is how the box says who it
     * is, and `stb_lang` and `timezone` are sent beside it because portals that are
     * missing them have been seen to answer with an empty list rather than an error.
     *
     * `noCache` on every call. A portal's answers are about *this* session — a token,
     * an account state, a channel page tied to both — and a cached one is a session
     * that has already ended being replayed as if it had not.
     */
    private fun open(url: String, token: String?): Reader {
        val builder = Request.Builder().url(url).get()
            .header("Cookie", "mac=$mac; stb_lang=en; timezone=Europe/London")
            .header("Cache-Control", "no-cache")
        if (userAgent != null) builder.header("User-Agent", userAgent)
        if (token != null) builder.header("Authorization", "Bearer $token")

        val response = client.newCall(builder.build()).execute()
        if (!response.isSuccessful) {
            response.close()
            throw HttpStatusException(response.code)
        }
        val body = response.body ?: run {
            response.close()
            throw HttpStatusException(response.code)
        }
        return InputStreamReader(body.byteStream(), Charsets.UTF_8).buffered(READ_BUFFER)
    }

    private companion object {
        const val READ_BUFFER = 16 * 1024
    }
}

/**
 * What a portal calls each of Castivio's four sections.
 *
 * Null for a kind the protocol has no type for, which there is none of today — it is
 * there so that adding a fifth kind to [MediaKind] is a compile-time question here
 * rather than a section that silently fetches channels.
 */
private val MediaKind.portalType: String?
    get() = when (this) {
        MediaKind.LIVE -> "itv"
        MediaKind.MOVIE -> "vod"
        MediaKind.SERIES -> "series"
        MediaKind.RADIO -> "radio"
    }

/**
 * An open portal session: where it answered, and the token it gave.
 *
 * The endpoint is carried rather than re-derived so that the search [StalkerHttpApi]
 * does on a handshake happens once per session instead of once per request.
 */
data class StalkerSession(val endpoint: String, val token: String)
