package com.castivio.data.preferences

import android.content.Context
import androidx.core.content.edit
import com.castivio.playback.api.EngineId
import com.castivio.playback.api.EngineMemory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which engine each source needed, between launches.
 *
 * ## Why this is `SharedPreferences` and not DataStore
 *
 * The same reason [LanguageStore] is, and it is a stronger reason here. The caller is the
 * opening path: a channel change reads this in the same breath as it builds the media
 * item, and the entire point of the read is to save the source a failed first attempt. An
 * asynchronous store would either be awaited — putting a disk round trip in front of the
 * picture, which is precisely the thing the player is not allowed to do — or read after
 * the open had already started on the wrong engine, which is the same as not reading it.
 *
 * It is one small map of strings, loaded once by the platform and served from memory
 * afterwards. That is the correct shape for a lookup on the critical path.
 *
 * ## Why it is bounded
 *
 * A user with a 100,000-channel bundle who watches all of it would otherwise accumulate a
 * key per channel forever. Only the awkward ones are stored — a source that opens on the
 * primary engine writes nothing, because the primary engine is what an unknown source
 * already gets — so the file holds the exceptions rather than the catalogue. When even
 * that grows past [LIMIT] it is cleared rather than pruned: an eviction policy for a
 * cache whose entries are individually worth two seconds is more machinery than the
 * problem deserves, and rediscovering a handful of them costs one slow open each.
 */
@Singleton
class EngineMemoryStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : EngineMemory {

    private val prefs by lazy { context.getSharedPreferences(FILE, Context.MODE_PRIVATE) }

    override fun preferred(sourceKey: String): EngineId? =
        when (prefs.getString(sourceKey, null)) {
            BACKUP -> EngineId.BACKUP
            else -> null
        }

    /**
     * Record what worked, and only when it is worth recording.
     *
     * The primary engine is the default for anything unknown, so storing "this one used
     * the primary" would fill the file with rows that change no decision. A source that
     * *was* on the backup and now opens on the primary — a provider that fixed their
     * encoder — has its row removed, which is how the memory stops being wrong rather
     * than staying wrong forever.
     */
    override fun remember(sourceKey: String, engine: EngineId) {
        if (sourceKey.isEmpty()) return
        when (engine) {
            EngineId.BACKUP -> {
                if (prefs.all.size >= LIMIT && !prefs.contains(sourceKey)) {
                    prefs.edit { clear() }
                }
                prefs.edit { putString(sourceKey, BACKUP) }
            }
            EngineId.PRIMARY -> if (prefs.contains(sourceKey)) prefs.edit { remove(sourceKey) }
        }
    }

    private companion object {
        const val FILE = "castivio.engines"
        const val BACKUP = "backup"
        const val LIMIT = 2_000
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class EngineMemoryModule {

    @dagger.Binds
    @Singleton
    abstract fun engineMemory(store: EngineMemoryStore): EngineMemory
}
