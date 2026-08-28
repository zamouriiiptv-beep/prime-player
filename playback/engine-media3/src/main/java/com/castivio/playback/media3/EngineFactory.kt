package com.castivio.playback.media3

import android.content.Context
import com.castivio.core.platform.DeviceCapabilities
import com.castivio.core.platform.MemoryClass
import com.castivio.playback.api.EngineFactory
import com.castivio.playback.api.EngineId
import com.castivio.playback.api.MediaKind
import com.castivio.playback.api.PlaybackEngine
import com.castivio.playback.api.PlaybackTuning
import com.castivio.playback.vlc.VlcPlaybackEngine
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Media3EngineFactory @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val capabilities: DeviceCapabilities,
) : EngineFactory {

    override fun create(id: EngineId, kind: MediaKind): PlaybackEngine = when (id) {
        EngineId.PRIMARY -> Media3Engine(
            context = context,
            profile = EngineProfile.PRIMARY,
            tuning = tuningFor(kind),
        )
        EngineId.BACKUP -> VlcPlaybackEngine(
            context = context,
            tuning = tuningFor(kind),
        )
    }

    private fun tuningFor(kind: MediaKind): PlaybackTuning = when (kind) {
        MediaKind.LIVE ->
            if (capabilities.memoryClass == MemoryClass.LOW) {
                PlaybackTuning.LIVE_LEAN
            } else {
                PlaybackTuning.LIVE_FAST
            }
        MediaKind.VOD, MediaKind.SERIES_EPISODE -> PlaybackTuning.VOD
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class PlaybackModule {

    @dagger.Binds
    @Singleton
    abstract fun engineFactory(real: Media3EngineFactory): EngineFactory
}