package com.castivio.feature.home

import androidx.lifecycle.ViewModel
import com.castivio.domain.ChannelLocks
import com.castivio.domain.PinChange
import com.castivio.domain.PinVault
import com.castivio.domain.isLocked
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/**
 * The channel lock, for the screens that draw it: which channels are locked, and the password.
 *
 * A holder in front of two contracts and nothing more. It decides nothing -- what a press on a
 * locked channel does is `pressVerdictFor`, and what the dialog asks next is `PinPromptState` -- and
 * it is a view model only so a screen can hold one without knowing where the lock is kept. The board
 * and the shell each hold their own; both read the same stored lock, because the stores are
 * singletons.
 */
@HiltViewModel
class ChannelLockViewModel @Inject constructor(
    private val locks: ChannelLocks,
    private val pin: PinVault,
) : ViewModel() {

    /** The locked channels' ids, or null until they have been read. See [ChannelLocks.locked]. */
    val locked: StateFlow<Set<String>?> get() = locks.locked

    /** Whether [channelId] is locked, waiting for the stored set if it has not been read yet. */
    suspend fun isLocked(channelId: String): Boolean = locks.isLocked(channelId)

    fun lock(channelId: String) = locks.lock(channelId)

    fun unlock(channelId: String) = locks.unlock(channelId)

    /** Whether [entered] is the password. */
    suspend fun matches(entered: String): Boolean = pin.matches(entered)

    /** Replaces [current] with [new] if [current] is the password. */
    suspend fun change(current: String, new: String): PinChange = pin.change(current, new)
}
