package com.castivio.core.platform.di

import android.content.Context
import com.castivio.core.platform.AndroidDeviceCapabilities
import com.castivio.core.platform.AndroidPlatformServices
import com.castivio.core.platform.AndroidRemoteProfile
import com.castivio.core.platform.DeviceCapabilities
import com.castivio.core.platform.PlatformServices
import com.castivio.core.platform.RemoteProfile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the platform abstractions once, for the whole app.
 *
 * Supporting a new device family means providing different implementations
 * here — no feature module changes.
 */
@Module
@InstallIn(SingletonComponent::class)
object PlatformModule {

    @Provides
    @Singleton
    fun deviceCapabilities(@ApplicationContext context: Context): DeviceCapabilities =
        AndroidDeviceCapabilities(context)

    @Provides
    @Singleton
    fun remoteProfile(@ApplicationContext context: Context): RemoteProfile =
        AndroidRemoteProfile(context)

    @Provides
    @Singleton
    fun platformServices(@ApplicationContext context: Context): PlatformServices =
        AndroidPlatformServices(context)
}
