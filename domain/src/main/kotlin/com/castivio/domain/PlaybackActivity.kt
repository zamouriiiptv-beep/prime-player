package com.castivio.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether a stream is being played or opened right now, for the work that must wait until it is not.
 *
 * Told by whoever holds the player -- the shell, from the player's own pulse -- and read by background work that has no
 * business competing with a picture for the connection or the processor: it asks [active] and waits. One value, held
 * here and nowhere else, so that what the work believes and what the viewer is looking at cannot disagree. It says
 * nothing of *what* is playing.
 */
class PlaybackActivity {

    private val _active = MutableStateFlow(false)

    /** True from the moment a stream is asked for until it has stopped. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    fun set(active: Boolean) {
        _active.value = active
    }
}
