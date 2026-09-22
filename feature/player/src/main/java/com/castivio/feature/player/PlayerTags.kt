package com.castivio.feature.player

/**
 * Handles for the parts of the player a test has to find by something other than text.
 *
 * The player needs more of these than any screen before it, and for a reason worth
 * stating: it is the first screen whose correctness is mostly *placement over something
 * else*. Every other screen is a composition inside a container, and "is it inside the
 * container" is a question about two boxes. Here the container is a film, the controls are
 * drawn over it, and the claims are about a safe area that has no visual edge — nothing on
 * this screen looks wrong when a button is 6dp outside the overscan of a television that
 * the developer does not own.
 *
 * So the bands are tagged, the safe area is tagged, and the gates measure them.
 */
internal object PlayerTags {

    /** The whole screen, and the surface the video is drawn on. */
    const val ROOT = "player.root"
    const val VIDEO = "player.video"

    /**
     * The surface itself, inside [VIDEO].
     *
     * Two tags because they are two things. [VIDEO] is the picture *region*, which is the
     * whole screen and must stay that way — a player that insets its own picture is
     * showing a smaller film than the screen it was given. This is the drawing surface
     * within it, which is sized to the aspect and so is smaller whenever the source and
     * the screen disagree. The black is part of the region, not a margin around it.
     */
    const val SURFACE = "player.surface"

    /**
     * The inset box every control must stay inside.
     *
     * Tagged rather than recomputed in the test from a padding value, because the padding
     * is `Spacing.screen` on a handset and `Spacing.tvOverscan` on a television and a test
     * that hard-codes either is a test that only checks one of them.
     */
    const val SAFE = "player.safe"

    /** The three bands. Tagged so "they do not overlap" is expressible. */
    const val TOP = "player.top"
    const val CENTRE = "player.centre"
    const val BOTTOM = "player.bottom"

    const val TITLE = "player.title"
    const val BACK = "player.back"
    const val LOCK = "player.lock"
    const val CAST = "player.cast"

    const val PLAY = "player.play"
    const val PREVIOUS = "player.previous"
    const val NEXT = "player.next"
    const val REPLAY = "player.replay"
    const val FORWARD = "player.forward"

    const val TIMELINE = "player.timeline"
    const val THUMB = "player.timeline.thumb"
    const val POSITION = "player.position"
    const val DURATION = "player.duration"

    /** The caption layer. Named for what it draws, not for the button that lists tracks. */
    const val CAPTIONS = "player.captions"

    /**
     * The tools row and the controls in it that a gate names individually.
     *
     * "Back to live" is tagged because its placement is a fixed decision that was got
     * wrong once: it was an inline chip in the time row at 32dp, which measured as the
     * only control in the player below the touch floor. It belongs in this row, at this
     * row's size, and a test says so.
     */
    const val TOOLS = "player.tools"
    const val TO_LIVE = "player.toLive"
    const val SUBTITLES = "player.subtitles"
    const val AUDIO = "player.audio"
    const val SPEED = "player.speed"
    const val ASPECT = "player.aspect"
    const val GUIDE = "player.guide"
    const val CHANNELS = "player.channels"
    const val QUALITY = "player.quality"
    const val MORE = "player.more"
    const val FULLSCREEN = "player.fullscreen"

    /**
     * The programme strip.
     *
     * The most important tag in the file. The claim it exists for is that the strip is the
     * *same height* before the guide answers and after — which is two measurements of one
     * element across two states, and the reason EPG can be off the critical path without
     * the screen jumping when it lands.
     */
    const val EPG = "player.epg"

    const val OVERLAY = "player.overlay"
    const val ERROR_CARD = "player.errorCard"
    const val ERROR_RETRY = "player.errorRetry"

    /**
     * The backup-engine button, which is absent on two of the three error cards.
     *
     * Tagged so that absence is assertable. "Does not offer the backup engine for DRM" is
     * a claim about a control that is not there, and a test can only make it about a
     * control it can name.
     */
    const val ERROR_BACKUP = "player.errorBackup"

    /**
     * The evidence block and its Copy button.
     *
     * Tagged so a gate can assert the thing that matters about them: that they appear when
     * there is a diagnosis and that the copied text is the displayed text. A report whose
     * copy differs from what is on screen is worse than no copy button.
     */
    const val DIAGNOSIS = "player.diagnosis"
    const val DIAGNOSIS_COPY = "player.diagnosisCopy"

    const val SHEET = "player.sheet"
    const val SHEET_CLOSE = "player.sheetClose"

    /**
     * The subtitle search box and the key that runs it.
     *
     * Tagged because what the box *contains* is the fix for the defect that produced this
     * screen: it must open holding the name of what is playing, and a test that cannot find
     * the field cannot assert that. The button is named separately so "typing does not
     * search, pressing does" is a claim about two different controls.
     */
    const val SUBTITLE_QUERY = "player.subtitleQuery"
    const val SUBTITLE_SEARCH = "player.subtitleSearch"
    const val STATISTICS = "player.statistics"
    const val TOAST = "player.toast"

    /** The "+10 s" mark a jump raises over the picture, and the only proof a viewer gets. */
    const val JUMP_MARK = "player.jumpMark"
    const val LOCK_PILL = "player.lockPill"

    /** The small badge that says the backup engine is playing. Nothing else marks it. */
    const val ENGINE_BADGE = "player.engineBadge"
}
