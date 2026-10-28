package com.castivio.core.platform

import android.content.Context

/**
 * Whether the temporary development instruments are on: a debuggable build, and nothing else.
 *
 * Two instruments read it -- [LoadTrace], which times a section from the press on Home, and the opening
 * diagnostic in the player, which times a channel from the press to its first frame. Both are for finding out where
 * a wait goes, on a device, with no cable; neither is a feature. Off, every call into them returns before it takes a
 * reading, so a release build does what it did without them and draws nothing of theirs.
 *
 * Turned on once, by the shell, from the flag the platform already keeps -- the one [PerformanceLog.isVisibleIn]
 * reads for the lines debuggable builds print. Removing the instruments is deleting this file, [LoadTrace], the
 * strip that draws it and the opening panel, and the one-line calls that feed them.
 */
object DevDiagnostics {

    @Volatile
    var enabled: Boolean = false
        private set

    /** Reads the build's own flag. Idempotent: asked again, it says what it said. */
    fun enableIn(context: Context) {
        enable(PerformanceLog.isVisibleIn(context))
    }

    /** The switch itself, so a test can turn the instruments on and off without a build that is debuggable. */
    internal fun enable(on: Boolean) {
        enabled = on
    }
}
