package com.castivio.tv.di

import com.castivio.core.common.AppDispatchers
import com.castivio.core.common.DefaultDispatchers
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Dispatchers are injected rather than referenced directly so that every piece of
 * IO and parsing work can be driven from a test without a device — and so that
 * "which thread does this run on" is an answer the code states rather than one a
 * reader has to infer.
 */
@Module
@InstallIn(SingletonComponent::class)
object DispatchersModule {

    @Provides
    @Singleton
    fun dispatchers(): AppDispatchers = DefaultDispatchers
}
