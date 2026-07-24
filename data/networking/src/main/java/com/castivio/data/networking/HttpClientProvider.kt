package com.castivio.data.networking

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * One HTTP client for the whole app. Providers are often slow rather than
 * broken, so timeouts are generous and configurable from Settings.
 */
object HttpClientProvider {
    fun create(timeoutSeconds: Long = 15, userAgent: String? = null): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .apply {
                if (userAgent != null) addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
                }
            }
            .build()
}
