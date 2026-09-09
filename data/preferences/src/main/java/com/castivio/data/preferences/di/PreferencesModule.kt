package com.castivio.data.preferences.di

import com.castivio.data.preferences.SectionCatalogueStore
import com.castivio.domain.SectionCatalogue
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The one binding this module needs.
 *
 * Everything else in `:data:preferences` is a concrete class injected by its
 * constructor, which needs no module at all. [SectionCatalogue] is different because
 * it is a `:domain` contract with an implementation here — which is the point of it
 * being a contract: the loader that reads it is pure, and the day these marks move
 * from a preferences file into a column, this line is the only one that changes.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PreferencesModule {

    @Binds
    @Singleton
    abstract fun sectionCatalogue(store: SectionCatalogueStore): SectionCatalogue
}
