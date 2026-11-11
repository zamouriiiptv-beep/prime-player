package com.castivio.tv.di

import com.castivio.core.common.config.PortalConfig
import com.castivio.tv.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Where the application asks the Castivio portal for what its owner sent from the website.
 *
 * `:app` states it, and only `:app`, because it is the one module that knows which build it
 * is: a debug APK asks the test deployment the website is being built on, and a release APK
 * says nothing and gets the product's own address. See `PortalConfig`, and the block above
 * `android { }` in this module's build script, for how the two are chosen.
 */
@Module
@InstallIn(SingletonComponent::class)
object PortalConfigModule {

    @Provides
    @Singleton
    fun portalConfig(): PortalConfig = PortalConfig.of(BuildConfig.PORTAL_BASE_URL)
}
