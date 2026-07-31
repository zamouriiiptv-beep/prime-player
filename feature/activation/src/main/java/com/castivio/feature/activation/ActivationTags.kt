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
    const val CODE_ZONE = "activation.codeZone"
    const val QR = "activation.qr"

    /** Reserved height whether or not it has anything to say, so it is tagged. */
    const val STATUS = "activation.status"
}
