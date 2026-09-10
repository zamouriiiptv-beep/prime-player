package com.castivio.data.preferences.di

import com.castivio.data.preferences.ProviderStatusStore
import com.castivio.data.preferences.SectionCatalogueStore
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
 * constructor, which needs no module at all. These two are different because they
 * are `:domain` contracts with implementations here — which is the point of them
 * being contracts: the code that reads them is pure, and the day these values move
 * from a preferences file into a column, these lines are the only ones that change.
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
}
