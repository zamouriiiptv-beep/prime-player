package com.castivio.tv.di

import android.os.SystemClock
import com.castivio.core.common.AppDispatchers
import com.castivio.domain.SourceRepository
import com.castivio.domain.entitlement.EntitlementRepository
import com.castivio.domain.portal.AppOpening
import com.castivio.domain.portal.PullOnOpen
import com.castivio.domain.portal.SentSubscriptionReceiver
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/**
 * How the app opens, with the website asked what was sent while it was closed.
 *
 * In `:app` because it joins three things that live in three modules — the licence, the
 * subscriptions and the portal — and `:app` is the one place that sees all of them.
 */
@Module
@InstallIn(SingletonComponent::class)
object OpeningModule {

    /**
     * The scope is the process's, and that is the point: the ask outlives the screen that
     * started it, so a launch closed a second later still adds what it found and tells the
     * portal it arrived. A supervisor, so that one failed ask is not the end of the next.
     *
     * The clock is the one that only moves forward and does not stop in sleep, because the
     * only thing it measures is the gap between two asks.
     */
    @Provides
    @Singleton
    fun pullOnOpen(
        receiver: SentSubscriptionReceiver,
        dispatchers: AppDispatchers,
    ): PullOnOpen = PullOnOpen(
        receiver = receiver,
        scope = CoroutineScope(SupervisorJob() + dispatchers.io),
        elapsedMs = SystemClock::elapsedRealtime,
    )

    @Provides
    @Singleton
    fun appOpening(
        entitlement: EntitlementRepository,
        sources: SourceRepository,
        pull: PullOnOpen,
    ): AppOpening = AppOpening(entitlement, sources, pull)
}
