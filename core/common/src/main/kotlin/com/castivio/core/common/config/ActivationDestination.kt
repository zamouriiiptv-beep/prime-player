package com.castivio.core.common.config

/**
 * Where a user goes to attach a subscription to this device.
 *
 * ## One value, because two would drift
 *
 * The activation screen states this address twice — once as text a person can
 * type, once as a QR a phone can scan — and those two must never disagree. A
 * user who reads one and scans the other and lands in different places has been
 * given a broken instruction by a product that had the right answer in it.
 *
 * So it is a constant here, not a string resource. A URL is configuration, not
 * copy: it has no translation, and putting it in `strings.xml` would mean 39
 * files to change and 39 chances to change 38 of them. The screen composes it
 * into a localised sentence; the sentence is translated and the address is not.
 *
 * ## It is a placeholder, and it is meant to look like one
 *
 * The production address does not exist yet. [URL] is the only line that changes
 * when it does — nothing else in the app, in the QR encoder, or in any of the 37
 * languages holds a copy.
 *
 * ## What the QR may carry
 *
 * Exactly [URL] and nothing else. No MAC address, no device key, no identifier,
 * no query parameter derived from either. A QR is a public thing — it is
 * photographed, it appears in screenshots, it goes into support tickets — and a
 * device identifier inside one is a credential published to everyone who can see
 * the screen. The user types their own identifiers into the page after it opens,
 * which is a step, and the step is the point.
 *
 * `design/activation-spec.md` §5 holds the contract this implements.
 */
object ActivationDestination {

    /**
     * The address the QR encodes and the button opens.
     *
     * Placeholder. When Castivio's activation page exists, this line changes and
     * nothing else does.
     */
    const val URL: String = "https://castivio.com/activate"

    /**
     * The same address, without the scheme, for showing to a person.
     *
     * `https://` is noise on a screen somebody is reading off a television from
     * three metres away, and it is not what they would type. Derived rather than
     * written out a second time, so it cannot fall out of step with [URL].
     */
    val display: String = URL.removePrefix("https://").removePrefix("http://").trimEnd('/')
}
