package com.castivio.feature.licence

/**
 * Handles for the elements a test has to find by something other than its text.
 *
 * The same reasoning as the sibling screen's, and it is not hypothetical there:
 * that screen's entire middle band collapsed to zero height on a device while
 * every gate stayed green — the code compiled, the strings resolved, the mockup
 * measured. Nothing was asserting that the composition Compose actually places
 * has the shape the design approved.
 *
 * A tag on the band is what makes "this band is 0dp tall" expressible.
 */
internal object LicenceTags {
    /** The middle band, and the thing whose height everything else depends on. */
    const val FIELD = "licence.field"

    /** The column inside it: capsules, plans, status. */
    const val IDENTITY = "licence.identity"

    const val MAC_CAPSULE = "licence.macCapsule"
    const val KEY_CAPSULE = "licence.keyCapsule"

    /**
     * The plan row, and each card in it by plan.
     *
     * The row is tagged as well as the cards because "two cards are present" and
     * "the row they are in has a height" are different claims, and a squeezed
     * band answers yes to the first while failing the second.
     */
    const val PLANS = "licence.plans"
    fun plan(id: String): String = "licence.plan.$id"

    /** The one action the blocked states offer instead of a purchase. */
    const val ACTION = "licence.action"

    /** Reserved height whether or not it has anything to say, so it is tagged. */
    const val STATUS = "licence.status"

    const val CODE_ZONE = "licence.codeZone"
    const val QR = "licence.qr"

    /** For diagnosis rather than assertion: which band took the space. */
    const val STAGE = "licence.stage"
    const val HEADER = "licence.header"

    /** The link in the footer, which is the only way to the legal page. */
    const val FOOTER = "licence.footer"

    /** The legal page, when it is open, and the button that closes it. */
    const val LEGAL = "licence.legal"
    const val LEGAL_CLOSE = "licence.legal.close"
}
