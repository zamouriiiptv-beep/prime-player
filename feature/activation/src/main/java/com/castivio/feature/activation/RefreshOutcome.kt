package com.castivio.feature.activation

import com.castivio.domain.portal.Reception

/**
 * What the refresh button is doing, as a closed set.
 *
 * Refresh is never a dead control: it says what it is doing while it does it and
 * what it found when it stops, and "nothing yet" is a sentence on a status line
 * rather than a dialogue somebody has to dismiss with a remote.
 */
internal sealed interface RefreshState {
    data object Idle : RefreshState
    data object Checking : RefreshState
    data object Found : RefreshState
    data object None : RefreshState
    data object Error : RefreshState
}

/**
 * How a press of Refresh ended, in the words the screen already has for it.
 *
 * ## Why four answers become three sentences
 *
 * The portal can fail four ways — unreachable, a different key held for this address, a
 * subscription sent that this device could not add, and nothing found because nothing was
 * sent — and the screen has three lines: "Subscription found", "Add your first M3U or
 * Xtream playlist", and "Couldn't refresh — try again". A fourth sentence would be a fourth
 * string in every one of 39 languages, and what the owner would do about the three kinds
 * of failure is the same thing: look at the website, where the subscription is shown as
 * still waiting.
 *
 * An exhaustive `when` over the sealed type, so a new kind of answer cannot be left to fall
 * into the wrong line without the build saying so.
 */
internal fun Reception.toRefreshState(): RefreshState = when (this) {
    is Reception.Added -> RefreshState.Found
    Reception.NothingSent -> RefreshState.None
    is Reception.Rejected,
    Reception.Unreachable,
    Reception.NotRecognised,
    -> RefreshState.Error
}
