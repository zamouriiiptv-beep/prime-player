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
     * Tagged on the same footing as the upper pair on purpose: they are the same card
     * composable at a different size, and the claims worth gating — that there are two
     * of them, that they are equal to each other, that they sit under the rule rather
     * than beside the leading pair — are all claims about where they were placed.
     */
    const val SOURCE_PORTAL = "activation.sourcePortal"
    const val SOURCE_USERS = "activation.sourceUsers"

    /**
     * The rule that separates the two ways in from the two that are not.
     *
     * Tagged rather than found by its words: what the gates ask of it is positional —
     * it is below both leading cards and above both of the others — and a finder that
     * matched its text would be asserting a translation.
     */
    const val SOURCE_OTHERS = "activation.sourceOthers"

    const val SOURCE_BACK = "activation.sourceBack"

    /**
     * The portal form's two fields.
     *
     * Tagged rather than found by their labels, for the reason the rename field is:
     * a text field merges its label, its value and its decoration into one node, and a
     * finder matching the label would be asserting a translation rather than a field.
     * What they gate is the claim the whole form rests on — two fields, and the second
     * is an address, with nothing between them asking for a MAC.
     */
    /**
     * The note shown when this device's identity is scoped to the app's data.
     *
     * Tagged because the claim is conditional: it must appear for an installation
     * identity and must not appear for a device one, and "a sentence is absent" is
     * only assertable against a handle.
     */
    const val MAC_INSTALLATION_NOTE = "activation.macInstallationNote"

    const val PORTAL_NAME = "activation.portalName"
    const val PORTAL_URL = "activation.portalUrl"

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

    /** The dark/light control, which every header in the flow carries. */
    const val HEADER_THEME = "chooser.theme"

    /** The time and the day beside it, the same pair Home's header carries. */
    const val HEADER_CLOCK = "chooser.clock"

    /** The saved-subscriptions step the chooser opens. */
    const val SAVED_TITLE = "activation.savedTitle"
    const val SAVED_EMPTY = "activation.savedEmpty"
    const val SAVED_LIST = "activation.savedList"
    const val SAVED_BACK = "activation.savedBack"

    /**
     * The kind badge, tagged for the one claim the row's middle group rests on.
     *
     * It sits directly after the only item that comes and goes between one subscription
     * and the next — the "in use" mark — in a group that centres itself, so if it starts
     * at the same place on a row that has that mark and a row that does not, the hidden
     * mark kept the space it measured and everything after it is aligned too. A mark
     * that collapsed would move this badge, which is why this is the thing that carries
     * the tag and not the name beside it.
     */
    const val SAVED_KIND = "activation.savedKind"

    /**
     * When the subscription runs out, on the rows that have an answer.
     *
     * Tagged rather than found by its words, because the assertion worth having is
     * about the rows that *do not* have one: a playlist with no subscription behind it
     * must draw no expiry at all, and "no node with this tag" is that claim where "no
     * text containing a date" would only be a claim about one translation.
     */
    const val SAVED_EXPIRES = "activation.savedExpires"
    const val SAVED_EDIT = "activation.savedEdit"
    const val SAVED_DELETE = "activation.savedDelete"
    const val SAVED_DELETE_DIALOG = "activation.savedDeleteDialog"
    const val SAVED_RENAME_DIALOG = "activation.savedRenameDialog"
    const val SAVED_RENAME_FIELD = "activation.savedRenameField"

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

    /** The sentence under that heading, as the media chooser has under its own. */
    const val SOURCE_SUBTITLE = "activation.sourceSubtitle"

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
