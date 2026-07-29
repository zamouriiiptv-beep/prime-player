package com.castivio.data.networking.di

import android.content.Context
import com.castivio.core.platform.DeviceCapabilities
import com.castivio.data.networking.HttpClientProvider
import com.castivio.data.networking.HttpProviderValidator
import com.castivio.data.networking.HttpStreamSource
import com.castivio.domain.ProviderValidator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

/**
 * One client, created lazily.
 *
 * The cache budget comes from [DeviceCapabilities], not from a constant: the same
 * APK runs on a Fire Stick with a few gigabytes of total storage and on a Shield
 * with a disk, and picking one number for both wastes space on one and starves the
 * other.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkingModule {

    @Provides
    @Singleton
    fun httpClient(
        @ApplicationContext context: Context,
        capabilities: DeviceCapabilities,
    ): OkHttpClient = HttpClientProvider.create(
        cacheDirectory = context.cacheDir,
        cacheBytes = capabilities.recommendedCacheBytes,
        userAgent = USER_AGENT,
    )

    @Provides
    @Singleton
    fun streamSource(client: OkHttpClient): HttpStreamSource = HttpStreamSource(client)

    /**
     * Asked before anything is imported, so that a wrong password, an expired
     * subscription, every connection in use and an unreachable host arrive as four
     * different answers instead of as "no channels".
     */
    @Provides
    @Singleton
    fun providerValidator(
        client: OkHttpClient,
        streams: HttpStreamSource,
    ): ProviderValidator = HttpProviderValidator(client, streams, USER_AGENT)

    /**
     * Providers do gate on the user agent, and some reject OkHttp's default
     * outright — so it is set explicitly and looks like a media player.
     */
    private const val USER_AGENT = "Castivio/1.0 (Android TV)"
}
