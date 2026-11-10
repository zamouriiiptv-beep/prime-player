package com.castivio.playback.media3

import android.content.Context
import android.util.Log
import com.castivio.core.platform.DeviceCapabilities
import com.castivio.core.platform.MemoryClass
import com.castivio.playback.api.EngineFactory
import com.castivio.playback.api.EngineId
import com.castivio.playback.api.EngineMemory
import com.castivio.playback.api.MediaKind
import com.castivio.playback.api.PlaybackEngine
import com.castivio.playback.api.PlaybackTuning
import com.castivio.playback.vlc.LibVlcWarmUp
import com.castivio.playback.vlc.VlcPlaybackEngine
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Media3EngineFactory @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val capabilities: DeviceCapabilities,
    /** Whether any channel has needed the backup: what decides whether LibVLC is worth getting ready. */
    private val memory: EngineMemory,
) : EngineFactory {

    /** The primary's warm-up has been begun: it is asked once in the process. */
    private val primaryWarming = AtomicBoolean(false)

    /** The backup's is under way, so a second call while it runs does not queue another. */
    private val backupWarming = AtomicBoolean(false)

    /**
     * One thread for the warm-ups, at the lowest priority and only when one is asked for: neither is ever what a
     * screen is waiting on, and neither should take the processor from one that is.
     */
    private val warmer: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { work ->
            Thread(work, WARM_THREAD).apply {
                priority = Thread.MIN_PRIORITY
                isDaemon = true
            }
        }
    }

    /**
     * The primary's: the platform's decoder list, asked once -- see [Media3Engine.warmDecoderList]. Cheap, and needed by
     * every viewer, so it is always done.
     *
     * The backup's: the process's LibVLC -- see [LibVlcWarmUp] -- built only when some channel is remembered as needing
     * it. Its native library is memory a viewer whose channels all play on the primary would carry for nothing, on a
     * stick with a gigabyte; such a viewer meets it, as before, the first time a channel falls over to it. Asked again
     * later -- a channel may have come to need it since -- it is looked at again, and built at most once.
     */
    override fun warmUp(id: EngineId) {
        when (id) {
            EngineId.PRIMARY -> {
                if (!primaryWarming.compareAndSet(false, true)) return
                runCatching { warmer.execute { Media3Engine.warmDecoderList() } }
            }
            EngineId.BACKUP -> {
                if (LibVlcWarmUp.ready || !backupWarming.compareAndSet(false, true)) return
                runCatching {
                    warmer.execute {
                        try {
                            if (memory.remembersAny(EngineId.BACKUP)) LibVlcWarmUp.build(context)
                        } catch (failure: Throwable) {
                            // An engine that needs LibVLC builds it itself, as it always did, and meets this there.
                            Log.w(TAG, "LibVLC could not be got ready ahead of a press", failure)
                        } finally {
                            backupWarming.set(false)
                        }
                    }
                }.onFailure { backupWarming.set(false) }
            }
        }
    }

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

    private companion object {
        const val TAG = "CastivioEngines"
        const val WARM_THREAD = "castivio-engine-warm-up"
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class PlaybackModule {

    @dagger.Binds
    @Singleton
    abstract fun engineFactory(real: Media3EngineFactory): EngineFactory
}