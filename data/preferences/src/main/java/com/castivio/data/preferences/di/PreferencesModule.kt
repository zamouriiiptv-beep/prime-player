package com.castivio.data.preferences.di

import com.castivio.data.preferences.ChannelLockStore
import com.castivio.data.preferences.ChannelQualityStore
import com.castivio.data.preferences.PinStore
import com.castivio.data.preferences.ProviderStatusStore
import com.castivio.data.preferences.SectionCatalogueStore
import com.castivio.domain.ChannelLocks
import com.castivio.domain.ChannelQualities
import com.castivio.domain.PinVault
import com.castivio.domain.ProviderStatusCatalogue
import com.castivio.domain.SectionCatalogue
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The bindings this module needs.
 *
 * Everything else in `:data:preferences` is a concrete class injected by its
 * constructor, which needs no module at all. These are different because they
 * are `:domain` contracts with implementations here — which is the point of them
 * being contracts: the code that reads them is pure, and the day these values move
 * from a preferences file into a column, these lines are the only ones that change.
 * The two newest are the channel lock's: which channels are locked, and the password.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PreferencesModule {

    @Binds
    @Singleton
    abstract fun sectionCatalogue(store: SectionCatalogueStore): SectionCatalogue

    @Binds
    @Singleton
    abstract fun providerStatusCatalogue(store: ProviderStatusStore): ProviderStatusCatalogue

    @Binds
    @Singleton
    abstract fun channelQualities(store: ChannelQualityStore): ChannelQualities

    @Binds
    @Singleton
    abstract fun channelLocks(store: ChannelLockStore): ChannelLocks

    @Binds
    @Singleton
    abstract fun pinVault(store: PinStore): PinVault
}
