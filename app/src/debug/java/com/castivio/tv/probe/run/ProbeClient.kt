package com.castivio.tv.probe.run

import com.castivio.data.networking.HttpClientProvider
import okhttp3.EventListener
import okhttp3.OkHttpClient

/**
 * The probe's own HTTP client.
 *
 * It is made by the application's factory, [HttpClientProvider.create], so its timeouts (twelve seconds to
 * connect, twenty between packets), its name resolution and its User-Agent are the application's. And it differs
 * in the four ways that keep it apart from the application:
 *
 *  - **its own connection pool and its own dispatcher**: it shares no connection with anything else, and every run
 *    makes a new client, so every run starts with cold connections as a first load does;
 *  - **no cache**: a category answered from the disk is a measurement of the disk;
 *  - **no retry**, silent or otherwise: a connection that fails is a failure the report shows;
 *  - **its own event listener**, so nothing it does is counted in the strip's figures or the session's.
 *
 * The User-Agent is the application's constant, which is private to its networking module; the report prints it so
 * that a difference can be seen.
 */
object ProbeClient {

    const val USER_AGENT = "Castivio/1.0 (Android TV)"

    fun create(listener: EventListener.Factory): OkHttpClient = HttpClientProvider.create(
        cacheDirectory = null,
        userAgent = USER_AGENT,
        eventListenerFactory = listener,
    ).newBuilder()
        .retryOnConnectionFailure(false)
        .build()
}
