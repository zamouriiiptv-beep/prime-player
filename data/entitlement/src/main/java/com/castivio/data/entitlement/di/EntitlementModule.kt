package com.castivio.data.entitlement.di

import android.content.Context
import android.content.SharedPreferences
import com.castivio.core.common.AppDispatchers
import com.castivio.data.entitlement.AesGcmVault
import com.castivio.data.entitlement.BuildConfig
import com.castivio.data.entitlement.DefaultEntitlementRepository
import com.castivio.data.entitlement.LocalEntitlementSource
import com.castivio.data.entitlement.SealedClockStore
import com.castivio.data.entitlement.SealedEntitlementStore
import com.castivio.data.entitlement.SealedStore
import com.castivio.data.entitlement.VaultKeys
import com.castivio.domain.entitlement.EntitlementRepository
import com.castivio.domain.entitlement.EntitlementSource
import com.castivio.domain.entitlement.EntitlementStore
import com.castivio.domain.entitlement.Licensing
import com.castivio.domain.entitlement.PricingConfig
import com.castivio.domain.entitlement.PricingDefaults
import com.castivio.domain.identity.DeviceIdentity
import com.castivio.domain.time.ClockSignalSource
import com.castivio.domain.time.ClockStore
import com.castivio.domain.time.MonotonicClock
import com.castivio.domain.time.TrustedTime
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The licence, wired.
 *
 * The one binding that is absent is the important one. [EntitlementSource] — Castivio's
 * licence server, the only thing that can establish a purchase or withdraw one — has no
 * implementation in this build, so [DefaultEntitlementRepository] is given null and says
 * so plainly when asked to verify. Adding the server later is a single `@Provides` here
 * and nothing else: no domain type changes, no repository signature changes, and every
 * rule that governs what the server's answer means is already written and tested.
 */
@Module
@InstallIn(SingletonComponent::class)
object EntitlementModule {

    @Provides
    @Singleton
    @Sealed
    fun sealedPreferences(@ApplicationContext context: Context): SharedPreferences =
        context.getSharedPreferences(SealedStore.FILE, Context.MODE_PRIVATE)

    /**
     * The prices, the trial length and the grace, in one object nothing else may invent.
     *
     * Injected rather than read from [PricingDefaults] at the point of use so that a
     * future remote configuration is a change to this method — the policy already takes
     * the configuration as an argument and has never known a default.
     */
    @Provides
    @Singleton
    fun pricing(): PricingConfig = PricingDefaults.config

    @Provides
    @Singleton
    fun entitlementStore(
        @Sealed prefs: SharedPreferences,
        dispatchers: AppDispatchers,
    ): EntitlementStore = SealedEntitlementStore(sealed(prefs), dispatchers)

    @Provides
    @Singleton
    fun clockStore(@Sealed prefs: SharedPreferences): ClockStore = SealedClockStore(sealed(prefs))

    @Provides
    @Singleton
    fun trustedTime(signals: ClockSignalSource, store: ClockStore): TrustedTime =
        MonotonicClock(signals, store)

    /**
     * The one decision in this file that must never be got wrong.
     *
     * A debug build gets [Licensing.Development] and with it a local trial, so the app
     * can be installed on a phone and a television and used before the licence server
     * exists. A release build gets [Licensing.Production], which has **nowhere to put a
     * trial grantor** — the mistake is not guarded against, it is unrepresentable.
     *
     * `source` is null in both today. In production that means the build fails closed:
     * every device reads as
     * [com.castivio.domain.entitlement.EntitlementState.ServiceUnavailable] and the app
     * says so. That is why a release APK is not fit to publish until the server exists —
     * see `RELEASE_CHECKLIST.md`.
     */
    @Provides
    @Singleton
    fun licensing(config: PricingConfig): Licensing =
        if (BuildConfig.DEBUG) {
            Licensing.Development(trials = LocalEntitlementSource(config), source = null)
        } else {
            Licensing.Production(source = null)
        }

    @Provides
    @Singleton
    fun entitlementRepository(
        store: EntitlementStore,
        identity: DeviceIdentity,
        clock: TrustedTime,
        config: PricingConfig,
        licensing: Licensing,
    ): EntitlementRepository = DefaultEntitlementRepository(
        store = store,
        identity = identity,
        clock = clock,
        config = config,
        licensing = licensing,
    )

    /**
     * Built at the point of use rather than bound, so the sealing stays an
     * implementation detail of this module rather than something anywhere else in the
     * app could ask the graph for.
     *
     * Two of them exist — one for the record, one for the clock — reading the same file
     * with the same key. Each resolves that key once and holds it, so the cost is two
     * keystore lookups in the life of the process rather than two per read.
     */
    private fun sealed(prefs: SharedPreferences) =
        SealedStore(prefs, AesGcmVault { VaultKeys.sealingKey(prefs) })
}
