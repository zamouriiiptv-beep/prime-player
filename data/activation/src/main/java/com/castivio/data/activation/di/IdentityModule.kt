package com.castivio.data.activation.di

import com.castivio.data.activation.AndroidClockSignals
import com.castivio.data.activation.AndroidDeviceIdentity
import com.castivio.data.activation.JvmSha256
import com.castivio.domain.identity.DeviceIdentity
import com.castivio.domain.identity.Sha256
import com.castivio.domain.time.ClockSignalSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Who this device is, and when it is.
 *
 * The two belong together: they are the pair of facts Castivio asserts to its licence
 * server, and the pair an entitlement is meaningless without. All three bindings are
 * singletons at their implementations — the identity because deriving it twice is
 * wasted work on a device that will answer the same thing forever, the clock signals
 * because reading the boot identifier twice on a ROM that hides it would produce two
 * different fallbacks and quietly invalidate an anchor.
 */
@Module
@InstallIn(SingletonComponent::class)
interface IdentityModule {

    @Binds
    fun sha256(implementation: JvmSha256): Sha256

    @Binds
    fun deviceIdentity(implementation: AndroidDeviceIdentity): DeviceIdentity

    @Binds
    fun clockSignals(implementation: AndroidClockSignals): ClockSignalSource
}
