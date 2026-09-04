package com.castivio.feature.activation

/**
 * Handles for the elements a test has to find by something other than its text.
 *
 * These exist because of a real failure. The activation screen's whole middle
 * band — address, key, actions, status, QR — collapsed to zero height on a
 * device, and every gate the project had stayed green: the code compiled, the
 * strings resolved, the HTML mockup measured 27/27. Nothing was asserting that
 * the composition Compose actually places has the shape the design approved.
 *
 * A tag on the band itself is what makes "this band is 0dp tall" expressible.
 */
internal object ActivationTags {
    /** The middle band. The one that vanished. */
    const val FIELD = "activation.field"
    const val IDENTITY = "activation.identity"

    /**
     * The two glass pills.
     *
     * Tagged so the placement gate can require each to be present and measurable
     * -- a capsule is a declared height, so unlike the text inside it this is a
     * claim Robolectric can actually answer.
     */
    const val MAC_CAPSULE = "activation.macCapsule"
    const val KEY_CAPSULE = "activation.keyCapsule"
    const val CODE_ZONE = "activation.codeZone"
    const val QR = "activation.qr"

    /**
     * The two buttons, as a row.
     *
     * Tagged because the text finders reach the label inside a button, not the
     * button. A label is a line of type and stays its own height while the
     * control around it is crushed to 26dp — which is what a short band does
     * first, and what "Add playlist is placed" fails to notice.
     */
    const val ACTIONS = "activation.actions"

    /** Reserved height whether or not it has anything to say, so it is tagged. */
    const val STATUS = "activation.status"

    /**
     * The source choice: its two cards and its Back.
     *
     * Tagged for the same reason the capsules are. This step overflowed a
     * television's viewport and scrolled, which no existing gate could see --
     * the elements were all composed and all correct, and one of them was
     * simply off the bottom of the screen. "Inside the frame", "the same height
     * as each other" and "side by side rather than stacked" are three claims
     * about placement, and placement needs a handle.
     */
    const val SOURCE_XTREAM = "activation.sourceXtream"
    const val SOURCE_M3U = "activation.sourceM3u"

    /**
     * The lower pair, which are destinations rather than forms.
     *
     * Tagged on the same footing as the upper pair on purpose: the claim the gates
     * make is that all four are *one* card repeated, equal in width and height and
     * built from the same composable, and a claim about four things needs four
     * handles.
     */
    const val SOURCE_LOCAL = "activation.sourceLocal"
    const val SOURCE_USERS = "activation.sourceUsers"

    const val SOURCE_BACK = "activation.sourceBack"

    /**
     * The glass surface the grid and Back sit inside.
     *
     * Tagged because "the cards are in a container" is otherwise a claim only a
     * screenshot can settle, and because the two claims that matter about it are
     * relational: every card is *inside* it, and the terms sentence is *outside* it.
     * Both need its bounds.
     */
    const val SOURCE_CONTAINER = "activation.sourceContainer"

    /**
     * The media source step, which the third card opens.
     *
     * Tagged on the same footing as the source choice's four, and for the same
     * reason: the claim these gates make is that all four are *one* card repeated,
     * equal in width and height, and a claim about four things needs four handles.
     * The container and the heading are here because "the cards are inside the
     * glass" and "the header did not grow" are otherwise claims only a screenshot
     * can settle.
     */
    const val MEDIA_CONTAINER = "media.container"
    const val MEDIA_HEADING = "media.heading"
    const val MEDIA_VIDEO_LIBRARY = "media.videoLibrary"
    const val MEDIA_VIDEO_PICK = "media.videoPick"
    const val MEDIA_AUDIO_LIBRARY = "media.audioLibrary"
    const val MEDIA_MP3_PICK = "media.mp3Pick"

    /**
     * Back, which on this screen is centred rather than at the start.
     *
     * That is the whole of what the tag is for: "centred on the container" is two
     * numbers that have to agree — the gap to each inner edge — and neither is
     * reachable without the control's own bounds.
     */
    const val MEDIA_BACK = "media.back"

    /**
     * The two libraries and the two pickers.
     *
     * One set of handles for the two libraries and one for the two pickers, because
     * the video grid and the audio list are the same screen with a different box in
     * the middle -- and a gate that asserts "the container holds the content and the
     * footer, and the footer is inside it" is asking the same question of both.
     */
    const val LIBRARY_CONTAINER = "library.container"
    const val LIBRARY_HEADING = "library.heading"
    const val LIBRARY_GRID = "library.grid"
    const val LIBRARY_LIST = "library.list"
    const val LIBRARY_BACK = "library.back"

    const val PICKER_CONTAINER = "picker.container"
    const val PICKER_HEADING = "picker.heading"
    const val PICKER_PATH = "picker.path"
    const val PICKER_LIST = "picker.list"
    const val PICKER_BACK = "picker.back"

    /**
     * The lockup in the shared header, on every screen that wears one.
     *
     * Tagged because *which* corner it sits in is the question, and the answer changed:
     * it used to be told to mirror -- opposite the title, left in Arabic and right in
     * English -- and it now holds the same physical edge in every language, because the
     * brand is not language and a signature that reassembles itself per locale is two
     * signatures. Both claims look perfectly correct in whichever language they were
     * last read in, which is exactly why one of them has to be asserted.
     */
    const val HEADER_MARK = "chooser.mark"

    /**
     * One tile, and one row, repeated.
     *
     * Deliberately not unique per item: what is asserted of them is asserted of all of
     * them at once — every tile is 16:9 and every tile in a band is the same width,
     * every row is at least a touch target tall. Those are claims about the set, and a
     * tag per index would only let a test check the one it happened to name.
     *
     * One row tag rather than two, because the audio library's row and a picker's row
     * are the same composable. Two tags would be two ways to describe one thing, and
     * the day they disagree the disagreement is the bug.
     */
    const val BROWSE_TILE = "browse.tile"
    const val BROWSE_ROW = "browse.row"

    /** The saved-subscriptions step the fourth card opens. */
    const val SAVED_TITLE = "activation.savedTitle"
    const val SAVED_EMPTY = "activation.savedEmpty"
    const val SAVED_LIST = "activation.savedList"
    const val SAVED_ADD_XTREAM = "activation.savedAddXtream"
    const val SAVED_ADD_M3U = "activation.savedAddM3u"
    const val SAVED_BACK = "activation.savedBack"

    /**
     * The heading above them, tagged late and for a specific reason.
     *
     * It is the element the scroll clipped first: a user who had pushed the page
     * down by one gesture saw the two cards and no title. The claim that the
     * screen is three stacked things and all three are inside the viewport needs
     * a handle on the first of them, not only on the last two.
     *
     * On the heading `Column` rather than on either `Text`, because what is being
     * asserted is where the block sits, and a tag on the title alone would go
     * green with the subtitle hanging off the edge.
     */
    const val SOURCE_HEADING = "activation.sourceHeading"

    /**
     * The two bands that bracket the field, and the stage that holds all three.
     *
     * Tagged for diagnosis rather than for assertion. When the field band comes
     * out smaller than the arithmetic says it should, the question is which of
     * the three above it took the space, and that is not answerable from the
     * field's own measurement.
     */
    const val STAGE = "activation.stage"
    const val HEADER = "activation.header"
    const val FOOTER = "activation.footer"
}
