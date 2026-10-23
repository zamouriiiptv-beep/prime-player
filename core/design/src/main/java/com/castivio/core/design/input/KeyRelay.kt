package com.castivio.core.design.input

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The remote's keys, offered to one screen before the focus system sees them.
 *
 * ## Why a screen would want that
 *
 * Compose delivers a key to whatever has the focus, and a full-screen player is a screen where
 * nothing may have it: the controls are hidden, the picture is not a control, and a key pressed
 * then has no destination. A channel key is not a thing to walk to -- it means the same wherever
 * the focus is, or is not -- so a screen that has such keys takes them here, from the activity,
 * ahead of the focus.
 *
 * ## What it does not do
 *
 * It never decides which keys are the screen's: [KeyTaker] answers for every key it is offered, and
 * says no to nearly all of them, which is what leaves navigation, selection and typing exactly as
 * they were. And it is one claim at a time -- the screen in front -- so two screens cannot both
 * answer the same key.
 *
 * Main thread only, like the activity's key dispatch that calls it and the composition that claims it.
 */
class KeyRelay {

    /**
     * The screen's answer for one key.
     *
     * @param keyCode the platform's key code, as `KeyEvent.getKeyCode()`.
     * @param down the key went down; false for the key coming up.
     * @param repeats how many times the platform has repeated this press: 0 for the press itself.
     * @return whether the screen took the key, which stops the focus system seeing it at all.
     */
    fun interface KeyTaker {
        fun take(keyCode: Int, down: Boolean, repeats: Int): Boolean
    }

    private var taker: KeyTaker? = null

    /**
     * [taker] is the screen that has the keys, until the returned release is called.
     *
     * The newest claim wins, and a release lets go of its own claim only: a screen that is replaced
     * by another and then disposed must not take the keys back from it, nor leave them with nobody.
     */
    fun claim(taker: KeyTaker): () -> Unit {
        this.taker = taker
        return { if (this.taker === taker) this.taker = null }
    }

    /** Whether the screen that has the keys took this one. False when no screen has them. */
    fun offer(keyCode: Int, down: Boolean, repeats: Int): Boolean =
        taker?.take(keyCode, down, repeats) == true
}

/**
 * The activity's relay, for the screens under it. Null where there is none -- a preview, a test --
 * which is a screen whose keys all go to the focus, as they always did.
 */
val LocalKeyRelay = staticCompositionLocalOf<KeyRelay?> { null }
