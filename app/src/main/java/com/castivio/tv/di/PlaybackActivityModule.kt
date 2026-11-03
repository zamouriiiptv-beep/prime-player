package com.castivio.tv.di

import com.castivio.domain.PlaybackActivity
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * The one flag that says whether a stream is playing or opening, shared by whoever tells it (the shell) and whoever waits
 * on it (background work that must not compete with a picture, such as the catch-up backfill).
 */
@Module
@InstallIn(SingletonComponent::class)
object PlaybackActivityModule {

    @Provides
    @Singleton
    fun playbackActivity(): PlaybackActivity = PlaybackActivity()
}
