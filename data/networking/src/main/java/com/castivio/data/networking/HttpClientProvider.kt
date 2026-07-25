package com.castivio.data.networking

import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * One HTTP client for the whole app.
 *
 * Sharing it is not tidiness, it is latency: consecutive channels usually live on
 * the same host, so a warm connection pool means the TLS handshake and DNS lookup
 * for a zap are already paid. A client per request repays them every time, which
 * is tens to hundreds of milliseconds on the action users perform most.
 *
 * Timeouts are generous because providers are commonly slow rather than broken,
 * and a playlist that takes twelve seconds is still better than an error. The
 * read timeout applies between packets, not to the whole transfer, so a 100 MB
 * playlist over a weak connection does not trip it.
 */
object HttpClientProvider {

    fun create(
        cacheDirectory: File? = null,
        cacheBytes: Long = DEFAULT_CACHE_BYTES,
        connectTimeoutSeconds: Long = CONNECT_TIMEOUT_SECONDS,
        readTimeoutSeconds: Long = READ_TIMEOUT_SECONDS,
        userAgent: String? = null,
    ): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        // Kept alive long enough to cover a user browsing between channels, which
        // is when a saved handshake is actually worth something.
        .connectionPool(ConnectionPool(MAX_IDLE_CONNECTIONS, KEEP_ALIVE_MINUTES, TimeUnit.MINUTES))
        .apply {
            // The cache budget comes from the device's own capabilities: a Fire
            // Stick has a few gigabytes of storage in total, a Shield has a disk.
            if (cacheDirectory != null && cacheBytes > 0) {
                cache(Cache(File(cacheDirectory, CACHE_SUBDIRECTORY), cacheBytes))
            }
            if (userAgent != null) {
                addInterceptor { chain ->
                    // Applied to every request rather than per call: some providers
                    // reject the default OkHttp agent outright.
                    chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
                }
            }
        }
        .build()

    const val CACHE_SUBDIRECTORY = "http"

    /**
     * The default when no capability-derived budget is supplied. Playlists and
     * guides dominate it; images are cached separately with their own budget.
     */
    const val DEFAULT_CACHE_BYTES = 16L * 1024 * 1024

    const val CONNECT_TIMEOUT_SECONDS = 12L

    /** Between packets, not for the whole transfer. */
    const val READ_TIMEOUT_SECONDS = 20L

    private const val MAX_IDLE_CONNECTIONS = 8
    private const val KEEP_ALIVE_MINUTES = 5L
}
