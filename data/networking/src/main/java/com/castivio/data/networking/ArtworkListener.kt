package com.castivio.data.networking

import com.castivio.core.common.artwork.ArtworkHost
import com.castivio.core.common.artwork.ArtworkMetrics
import com.castivio.core.common.artwork.ArtworkOutcome
import com.castivio.core.common.artwork.ArtworkTally
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException

/**
 * Tells [ArtworkTally] what the image loader's calls did, and does nothing else.
 *
 * Like [CallMetricsListener], an observer: an [EventListener] is handed events after the fact and has no way to change a
 * request, a response, a cache or a timeout. It is the image loader's client's own -- the pictures are not the provider's
 * calls, and counted among them they made the provider's figures say what the posters did.
 *
 * What it records is how each call **ended** -- a picture, a refusal with its status, a timeout, a road that failed, or a call
 * nobody wanted any more because its card had left the screen -- how long it took, how long it waited before it had a
 * connection to use (for a slot in the dispatcher, a name, a handshake), how large its body was, and which kind of host it
 * asked: the provider's, the public picture host the playlists name most, or another. Never an address: see [ArtworkHost].
 *
 * One listener per call, through [FACTORY], which is what makes the per-call fields safe.
 */
class ArtworkListener(private val tally: ArtworkTally = ArtworkMetrics) : EventListener() {

    private var beganAt = 0L
    private var connectedAt = 0L
    private var status: Int? = null
    private var bytes = 0L
    private var finished = false

    override fun callStart(call: Call) {
        beganAt = System.nanoTime()
        tally.started(hostKind(call.request().url))
    }

    /** The call has a connection to use: what it waited for before this -- a slot, a name, a handshake -- is over. */
    override fun connectionAcquired(call: Call, connection: Connection) {
        if (connectedAt == 0L) connectedAt = System.nanoTime()
    }

    override fun responseHeadersEnd(call: Call, response: Response) {
        // The last one wins: a redirect answers once for the hop and once for the reply that has the body.
        status = response.code
    }

    override fun responseBodyEnd(call: Call, byteCount: Long) {
        bytes = byteCount
    }

    override fun callEnd(call: Call) = finish(call, failure = null)

    override fun callFailed(call: Call, ioe: IOException) = finish(call, failure = ioe)

    private fun finish(call: Call, failure: IOException?) {
        if (finished) return
        finished = true
        val now = System.nanoTime()
        val took = if (beganAt > 0) now - beganAt else 0L
        val waited = if (beganAt > 0 && connectedAt >= beganAt) connectedAt - beganAt else NO_WAIT
        val answered = status
        val outcome = when {
            failure != null -> outcomeOf(call, failure)
            answered != null && answered >= HTTP_ERRORS -> ArtworkOutcome.HTTP
            else -> ArtworkOutcome.OK
        }
        tally.ended(
            outcome = outcome,
            httpStatus = if (outcome == ArtworkOutcome.HTTP) answered else null,
            bytes = bytes,
            callNanos = took,
            waitNanos = waited,
        )
    }

    companion object {
        /** One listener per call, which is what makes the per-call fields safe. */
        val FACTORY = EventListener.Factory { ArtworkListener() }

        private const val HTTP_ERRORS = 400
        private const val NO_WAIT = -1L

        /**
         * How a failed call failed.
         *
         * A call whose time ran out is an [InterruptedIOException] -- OkHttp's own call timeout and a socket timeout both are
         * -- and is asked **first**, because OkHttp's call timer ends the call by cancelling it: `isCanceled()` is true for a
         * call that timed out, and read before the exception it would file every timeout as a card that left the screen.
         *
         * A call that was cancelled and did not time out is not a failure: the card left the screen, and the loader says so
         * by cancelling. Anything else is the road: refused, reset, no route, no name.
         */
        internal fun outcomeOf(call: Call, failure: IOException): ArtworkOutcome = when {
            failure is InterruptedIOException -> ArtworkOutcome.TIMEOUT
            call.isCanceled() -> ArtworkOutcome.CANCELLED
            else -> ArtworkOutcome.NETWORK
        }

        /** The three words for a host: the public picture host by its name, the provider's by having been its API's. */
        internal fun hostKind(url: HttpUrl): ArtworkHost {
            val host = url.host
            return when {
                host == TMDB_HOST || host.endsWith(".$TMDB_HOST") -> ArtworkHost.TMDB
                CallMetrics.isApiHost(host) -> ArtworkHost.PROVIDER
                else -> ArtworkHost.OTHER
            }
        }

        private const val TMDB_HOST = "tmdb.org"
    }
}
