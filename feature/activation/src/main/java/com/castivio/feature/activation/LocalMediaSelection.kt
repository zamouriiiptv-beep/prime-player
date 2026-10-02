package com.castivio.feature.activation

/**
 * What the activation flow hands upward when a file on this device is chosen.
 *
 * ## Why it is still here when nothing in this module produces one
 *
 * The screens that did are gone — the media source and the four library and picker
 * screens behind it were removed with the local-video option. This type is what the
 * press they raised *carried*, and `:app` reads it: `PlayerHost` turns one of these into
 * a `PlayerRequest`, which is player code and is deliberately not touched by that
 * removal.
 *
 * So it stays, declared on its own rather than inside a host that no longer exists,
 * because deleting it would have meant editing the player's seam to delete it — and the
 * instruction that removed the feature drew that line explicitly. Three fields, and
 * every one of them already known at the moment of the press: nothing here needs a
 * lookup, which is what lets the player draw a title before it has a frame.
 *
 * Whoever revisits this: the honest end state is that this type and the seam in `:app`
 * that reads it go together, in one change that is allowed to touch both.
 */
data class LocalMediaSelection(
    val uri: String,
    val title: String,
    val isVideo: Boolean,
)
