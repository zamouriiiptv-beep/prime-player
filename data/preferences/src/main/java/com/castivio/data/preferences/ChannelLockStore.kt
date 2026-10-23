package com.castivio.data.preferences

import android.content.Context
import android.content.SharedPreferences
import com.castivio.domain.ChannelLocks
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The locked channels, kept in a preferences file.
 *
 * ## Why `SharedPreferences`
 *
 * One short id for each channel somebody chose to lock, written when they lock or unlock one and
 * read once. A table in the catalogue's database would be a migration for a few strings, and it
 * would tie the lock to a database that is rebuilt from the provider -- where a preferences file
 * is the user's own and is left alone by a refresh. It is the sibling of [ChannelQualityStore] and
 * is kept the same way.
 *
 * ## One writer, in order
 *
 * The file is read first, on a background thread, and every lock and unlock after it is applied by
 * that same coroutine in the order the calls were made. A lock asked for in the first moments after
 * start-up is therefore applied to what was stored and not to an empty set, and a lock followed
 * quickly by an unlock of the same channel ends unlocked. Nothing here runs on the thread a press
 * is handled on: [lock] and [unlock] only queue.
 *
 * Until the first read has finished [locked] is null, which says "not known yet" rather than "none".
 */
@Singleton
class ChannelLockStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : ChannelLocks {

    private val prefs: SharedPreferences by lazy { context.getSharedPreferences(FILE, Context.MODE_PRIVATE) }

    private val _locked = MutableStateFlow<Set<String>?>(null)
    override val locked: StateFlow<Set<String>?> = _locked.asStateFlow()

    /** What to do to the set, in the order it was asked. Unbounded: a press never waits to queue. */
    private val changes = Channel<(Set<String>) -> Set<String>>(Channel.UNLIMITED)

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            var current: Set<String> = prefs.getStringSet(KEY, null).orEmpty().toSet()
            _locked.value = current
            for (change in changes) {
                val next = change(current)
                if (next == current) continue
                current = next
                _locked.value = next
                // Committed rather than applied: this is a background thread already, and a lock that
                // a process death could still take back is not one a household can rely on.
                prefs.edit().putStringSet(KEY, next).commit()
            }
        }
    }

    override fun lock(channelId: String) {
        changes.trySend { it + channelId }
    }

    override fun unlock(channelId: String) {
        changes.trySend { it - channelId }
    }

    private companion object {
        const val FILE = "castivio_channel_locks"
        const val KEY = "locked"
    }
}
