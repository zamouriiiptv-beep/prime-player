package com.castivio.feature.player

import android.view.KeyEvent

/** Which way along the channel list a key moves: to the next channel, or to the one before. */
internal enum class ChannelStep(val by: Int) {
    Next(1),
    Previous(-1),
}

/**
 * What the player's screen is doing, as far as the channel keys are concerned.
 *
 * Four facts and nothing about focus: where the focus is, or whether there is any, is exactly what
 * a full-screen player cannot be asked.
 */
internal data class KeyScene(
    /** The screen is locked against presses. A lock that a key could walk round protects nothing. */
    val locked: Boolean,
    /** A sheet or the statistics panel is open, and has the keys it needs: a search box takes `+` and `-`. */
    val panelOpen: Boolean,
    /** The controls are drawn, and the arrows are what walks them. */
    val controlsShown: Boolean,
    /** The card that says the stream stopped is up, and its buttons are what the arrows walk. */
    val failureShown: Boolean = false,
)

/** Something is drawn that the arrows walk: the controls, a sheet or panel, or the failure card's buttons. */
private val KeyScene.walkedByArrows: Boolean get() = panelOpen || controlsShown || failureShown

/**
 * The step a remote's key means, or null when it is not a channel key right now.
 *
 * ## Three kinds of key
 *
 * **The remote's own channel keys** -- CH+ and CH-, `CHANNEL_UP` and `CHANNEL_DOWN` -- mean the channel
 * and nothing else, wherever the screen is, so they are channel keys whenever the screen is not locked.
 *
 * **The keys a remote has instead of them.** Page up and page down, which many remotes send for their
 * channel rocker; next and previous, which are what a skip pair sends; and plus and minus, the
 * channel keys of the remotes that label them that way. They mean the channel on a live picture and
 * nothing else on it -- but a sheet is open over the picture and a keyboard types its `+` and `-` into
 * the search box in it, so these are channel keys only when no sheet or panel is open.
 *
 * **The arrows.** Up is the next channel and down the one before, as CH+ and CH- are -- but the arrows
 * are also how a viewer walks the controls, a sheet, or the buttons of the card a stopped stream shows,
 * and that is not taken from them: they are channel keys only when nothing is drawn for them to walk,
 * which is the picture alone.
 *
 * Everything else is null, and goes to the focus as before: select, back, left and right, the media keys
 * that mean play, pause and seek, the volume, the numbers.
 */
internal fun channelStepOf(keyCode: Int, scene: KeyScene): ChannelStep? {
    if (scene.locked) return null
    return when (keyCode) {
        KeyEvent.KEYCODE_CHANNEL_UP -> ChannelStep.Next
        KeyEvent.KEYCODE_CHANNEL_DOWN -> ChannelStep.Previous

        KeyEvent.KEYCODE_PAGE_UP,
        KeyEvent.KEYCODE_MEDIA_NEXT,
        KeyEvent.KEYCODE_PLUS,
        KeyEvent.KEYCODE_NUMPAD_ADD,
        -> ChannelStep.Next.takeUnless { scene.panelOpen }

        KeyEvent.KEYCODE_PAGE_DOWN,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        KeyEvent.KEYCODE_MINUS,
        KeyEvent.KEYCODE_NUMPAD_SUBTRACT,
        -> ChannelStep.Previous.takeUnless { scene.panelOpen }

        KeyEvent.KEYCODE_DPAD_UP -> ChannelStep.Next.takeUnless { scene.walkedByArrows }
        KeyEvent.KEYCODE_DPAD_DOWN -> ChannelStep.Previous.takeUnless { scene.walkedByArrows }

        else -> null
    }
}
