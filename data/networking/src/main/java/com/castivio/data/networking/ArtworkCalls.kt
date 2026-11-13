package com.castivio.data.networking

import okhttp3.Call
import okhttp3.Dispatcher
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * The image loader's way of making a call: the application's own client, and the whole-call limit that fits what is asked for.
 *
 * One client serves every picture the application draws, and one limit does not fit them. A channel's logo is some kilobytes
 * asked for by a list that is being drawn, and what has not come in six seconds is not coming. A poster or a backdrop is a few
 * hundred, over a phone's mobile data, while the connection is busy with a section's import; given a logo's six it was given up
 * on and its card stayed an empty frame. Neither limit is the client's, and the client cannot hold two.
 *
 * So the request says which it is. The card that asks for a poster writes [markerHeader] on it; this reads the word, gives the
 * call [markedLimitMs] if it is there and [limitMs] if it is not, and takes the word **off** before the call is made -- it is an
 * instruction to this class and never reaches a host. Everything else about the call is the client's: its connections, its name
 * cache, its `User-Agent`, its cache and its dispatcher.
 *
 * OkHttp's own way of giving one call a limit of its own: `Call.timeout()` is the call's, and is set before the call begins.
 */
class ArtworkCalls(
    private val client: OkHttpClient,
    private val markerHeader: String,
    private val markedLimitMs: Long,
    private val limitMs: Long,
) : Call.Factory {

    override fun newCall(request: Request): Call {
        val marked = request.header(markerHeader) != null
        val sent = if (marked) request.newBuilder().removeHeader(markerHeader).build() else request
        val call = client.newCall(sent)
        call.timeout().timeout(if (marked) markedLimitMs else limitMs, TimeUnit.MILLISECONDS)
        return call
    }

    companion object {

        /**
         * The application's client, derived for the pictures.
         *
         * Derivation keeps what a picture is right to share with everything else -- the connections, the name cache, the
         * `User-Agent`, the HTTP cache -- and changes three things:
         *
         * **A dispatcher of its own.** A client's dispatcher decides how many calls run at once, five to a host by default, and
         * queues the rest. Shared with the application's, a screen of posters from the provider's host stood in the same queue
         * as the import's calls to that host, and every poster stood behind the five that were running. The pictures' has
         * limits of its own, [maxRequestsPerHost] to a host and [maxRequests] in all, and the **same threads** -- a dispatcher
         * built on the shared one's executor -- so a poster waits for a place among posters and for nothing else, and no
         * second pool of threads is made for it.
         *
         * **A listener of its own,** [listenerFactory], so that what the pictures did is counted apart from what the provider's
         * calls did.
         *
         * **A limit on the whole call,** [callTimeoutMs], the logo's: [ArtworkCalls] gives a picture its longer one.
         */
        fun deriveClient(
            shared: OkHttpClient,
            listenerFactory: EventListener.Factory,
            maxRequests: Int,
            maxRequestsPerHost: Int,
            callTimeoutMs: Long,
        ): OkHttpClient = shared.newBuilder()
            .dispatcher(
                Dispatcher(shared.dispatcher.executorService).also {
                    it.maxRequests = maxRequests
                    it.maxRequestsPerHost = maxRequestsPerHost
                },
            )
            .eventListenerFactory(listenerFactory)
            .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
            .build()
    }
}
