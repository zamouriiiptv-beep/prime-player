package com.castivio.data.preferences

import android.content.Context
import android.content.SharedPreferences
import com.castivio.domain.ChannelQualities
import com.castivio.domain.StreamQuality
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What each channel has been measured at, kept in a preferences file.
 *
 * ## Why `SharedPreferences`
 *
 * One short string per channel ever watched, written a handful of times a session and read once.
 * A database table would need a migration for a figure that is cheap to measure again, and the
 * file is the same shape as [EngineMemoryStore]'s, which this is a sibling of.
 *
 * ## Never on the main thread
 *
 * The file is read on a background thread when the store is first needed, and the board sees an
 * empty map until it has been -- which draws no badges for the few milliseconds it takes, never a
 * wrong one. A measurement that arrives before the read finishes is kept and merged over it.
 * Writes are `apply()`, asynchronous, and one key at a time: the file is not rewritten for a
 * channel it already holds.
 *
 * ## Bounded
 *
 * Past [LIMIT] channels the file is cleared and begins again, as [EngineMemoryStore] does: an
 * eviction policy for entries that are each worth one more viewing is more machinery than the
 * problem deserves.
 */
@Singleton
class ChannelQualityStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : ChannelQualities {

    private val prefs: SharedPreferences by lazy { context.getSharedPreferences(FILE, Context.MODE_PRIVATE) }

    private val _known = MutableStateFlow<Map<String, StreamQuality>>(emptyMap())
    override val known: StateFlow<Map<String, StreamQuality>> = _known.asStateFlow()

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val stored = prefs.all.mapNotNull { (id, value) ->
                StreamQuality.named(value as? String)?.let { id to it }
            }.toMap()
            // Merged *under* what was measured meanwhile: the latest measurement wins.
            _known.update { measured -> stored + measured }
        }
    }

    override fun record(channelId: String, quality: StreamQuality) {
        if (_known.value[channelId] == quality) return
        val overflowed = _known.value.size >= LIMIT && channelId !in _known.value
        _known.update { current -> (if (overflowed) emptyMap() else current) + (channelId to quality) }
        CoroutineScope(Dispatchers.IO).launch {
            val edit = prefs.edit()
            if (overflowed) edit.clear()
            edit.putString(channelId, quality.name).apply()
        }
    }

    private companion object {
        const val FILE = "castivio_channel_quality"

        /** Ten thousand channels watched is more than a household has; past it the file starts over. */
        const val LIMIT = 10_000
    }
}
