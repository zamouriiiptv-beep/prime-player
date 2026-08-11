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
    const val SOURCE_BACK = "activation.sourceBack"

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
