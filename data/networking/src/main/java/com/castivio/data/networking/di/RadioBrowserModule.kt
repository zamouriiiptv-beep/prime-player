package com.castivio.data.networking.di

import android.os.SystemClock
import com.castivio.core.common.AppDispatchers
import com.castivio.data.networking.RadioBrowserApi
import com.castivio.data.networking.RadioBrowserDirectory
import com.castivio.data.networking.RadioBrowserDiscovery
import com.castivio.data.parsing.RadioBrowserServerPool
import com.castivio.domain.DirectoryCache
import com.castivio.domain.WorldRadioDirectory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.EventListener
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * The world radio directory, on the application's client and not on its own: the connection pool is shared, and the call counter of the
 * catalogue's imports -- which this is not part of -- is left alone by dropping the listener from the copy, as are the long timeouts a
 * subscription's panel needs. Nothing here is used by an import.
 */
@Module
@InstallIn(SingletonComponent::class)
object RadioBrowserModule {

    @Provides
    @Singleton
    fun worldRadioDirectory(
        client: OkHttpClient,
        cache: DirectoryCache,
        dispatchers: AppDispatchers,
    ): WorldRadioDirectory {
        val http = client.newBuilder()
            .eventListener(EventListener.NONE)
            .connectTimeout(CONNECT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_SECONDS, TimeUnit.SECONDS)
            .build()
        val monotonicMs: () -> Long = { SystemClock.elapsedRealtime() }
        val pool = RadioBrowserServerPool(clock = monotonicMs)
        val api = RadioBrowserApi(
            http = http,
            pool = pool,
            discovery = RadioBrowserDiscovery(http, dispatchers.io),
            io = dispatchers.io,
            clock = monotonicMs,
        )
        return RadioBrowserDirectory(api, cache)
    }

    private const val CONNECT_SECONDS = 6L
    private const val READ_SECONDS = 10L
    private const val CALL_SECONDS = 15L
}
