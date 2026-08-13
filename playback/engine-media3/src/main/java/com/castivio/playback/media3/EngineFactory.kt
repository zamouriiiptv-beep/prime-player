package com.castivio.playback.media3

import android.content.Context
import com.castivio.core.platform.DeviceCapabilities
import com.castivio.core.platform.MemoryClass
import com.castivio.playback.api.EngineFactory
import com.castivio.playback.api.EngineId
import com.castivio.playback.api.MediaKind
import com.castivio.playback.api.PlaybackEngine
import com.castivio.playback.api.PlaybackTuning
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The real one.
 *
 * The only decision it makes is which tuning to hand over, and it makes it from what the
 * box can do rather than from what it is called. A 1 GB stick gets the lean profile
 * because a pre-warmed neighbour costs 20-40 MB it does not have; everything else gets
 * the fast one.
 */
@Singleton
class Media3EngineFactory @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val capabilities: DeviceCapabilities,
) : EngineFactory {

    override fun create(id: EngineId, kind: MediaKind): PlaybackEngine = Media3Engine(
        context = context,
        profile = when (id) {
            EngineId.PRIMARY -> EngineProfile.PRIMARY
            EngineId.BACKUP -> EngineProfile.BACKUP
        },
        tuning = tuningFor(kind),
    )

    private fun tuningFor(kind: MediaKind): PlaybackTuning = when (kind) {
        MediaKind.LIVE ->
            if (capabilities.memoryClass == MemoryClass.LOW) {
                PlaybackTuning.LIVE_LEAN
            } else {
                PlaybackTuning.LIVE_FAST
            }
        // On-demand can afford a deeper buffer, and seeking is better for it. The
        // half-second start that makes live feel instant would make a film stutter on
        // the first seek.
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
