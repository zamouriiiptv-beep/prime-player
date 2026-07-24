package com.castivio.core.platform

/**
 * What this remote can actually send.
 *
 * Fire TV remotes have no Guide key and no number pad; some boxes send media
 * keys others never emit. The focus engine and player read [RemoteAction], never
 * raw key codes, so supporting a new remote is one mapping — not a UI change.
 */
interface RemoteProfile {
    fun map(keyCode: Int): RemoteAction?

    /** When false, the UI must offer on-screen channel entry. */
    val hasNumericKeys: Boolean

    /** When false, Guide has to be reachable as a visible control. */
    val hasDedicatedGuideKey: Boolean

    val hasPlayPauseKey: Boolean
}

enum class RemoteAction {
    UP, DOWN, LEFT, RIGHT, SELECT, BACK, HOME,
    PLAY_PAUSE, FAST_FORWARD, REWIND, STOP,
    CHANNEL_UP, CHANNEL_DOWN,
    GUIDE, INFO, MENU,
    NUMBER_0, NUMBER_1, NUMBER_2, NUMBER_3, NUMBER_4,
    NUMBER_5, NUMBER_6, NUMBER_7, NUMBER_8, NUMBER_9,
}
