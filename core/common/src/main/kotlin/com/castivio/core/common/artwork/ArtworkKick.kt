package com.castivio.core.common.artwork

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A word to every picture that gave up: the connection is free now, ask again.
 *
 * A picture that failed waits the seconds [PosterRetry] gives it, and when it has used them all it waits for this. It is said
 * by whatever knows the connection has been given back -- a section's load that has ended, which was holding it with a reply
 * of tens of megabytes -- and every card that is empty and was refused for a reason that can change takes it as leave to try
 * once more at once, instead of at the end of its own wait.
 *
 * A counter and not a flag: a flag that is already set cannot be said again, and the second load of a session is as good a
 * reason as the first. Nothing listens until a card has failed, so a wall whose pictures all arrived costs nothing.
 */
object ArtworkKick {

    private val kicks = MutableStateFlow(0)

    /** How many times it has been said; a card that failed reads it, and tries again when it is not what it was. */
    val count: StateFlow<Int> = kicks.asStateFlow()

    /** The connection is free: every empty card that can try again does so now. */
    fun kick() {
        kicks.update { it + 1 }
    }
}

/**
 * Says [ArtworkKick.kick] when a load that was running is over, and at no other time.
 *
 * A screen reads whether its section is loading each time that changes and hands it to [reading]. The word is said on the edge
 * from loading to not loading, whichever way the load ended -- done, or failed, both give the connection back -- and not for a
 * section that was never loading, which has nothing to ask again, nor again while it stays loaded.
 *
 * Held by the screen that reads it, one for each screen: a section's load is that screen's to end.
 */
class KickWhenLoadEnds(private val kick: () -> Unit = ArtworkKick::kick) {

    private var wasRunning = false

    /** The section's load is running ([running]) or it is not, as the screen sees it now. */
    fun reading(running: Boolean) {
        val ended = wasRunning && !running
        wasRunning = running
        if (ended) kick()
    }
}
