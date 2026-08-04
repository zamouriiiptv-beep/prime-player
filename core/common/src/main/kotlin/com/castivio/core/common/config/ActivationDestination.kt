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
     * Where a user goes when the app cannot help them.
     *
     * Two states offer this and only two: a licence that was withdrawn, and a
     * stored record that will not open. Both are questions about one specific
     * device that only Castivio can answer, and neither is fixed by buying
     * anything — which is why those states show this instead of the plans.
     *
     * A page rather than a `mailto:`. A television has no mail client, and
     * asking a remote to type an address into one it does not have is not an
     * escape hatch, it is a dead end with a label on it.
     *
     * Placeholder, on the same terms as [URL]: when the page exists, this line
     * changes and nothing else does.
     */
    const val SUPPORT_URL: String = "https://castivio.com/support"

    /**
     * The support page for *this* device.
     *
     * The address travels so the user does not have to read six pairs of hex off
     * a television and type them into a phone. Same reasoning as [portalUrl],
     * and the same limit: this is a link opened on the user's own device, never
     * a QR and never anything published.
     */
    fun supportUrl(macAddress: String? = null): String =
        if (macAddress.isNullOrBlank()) SUPPORT_URL else SUPPORT_URL + "?mac=" + macAddress.encoded()

    /**
     * The same address, without the scheme, for showing to a person.
     *
     * `https://` is noise on a screen somebody is reading off a television from
     * three metres away, and it is not what they would type. Derived rather than
     * written out a second time, so it cannot fall out of step with [URL].
     */
    val display: String = URL.removePrefix("https://").removePrefix("http://").trimEnd('/')

    /**
     * The address the app *opens*, which is not the address the QR *encodes*.
     *
     * ## The distinction, because it looks like a contradiction
     *
     * [URL] is the whole payload of the QR and always will be: a symbol is a
     * public object, photographed and pasted into support tickets, and
     * `ActivationQrTest` fails if a device identifier ever appears in one.
     *
     * This is the other direction. When the user presses a plan, the app hands
     * the portal a link on that user's own device — not published, not
     * photographable — and the portal needs to know which device it is binding a
     * licence to. Sending them to a bare page and asking them to retype a MAC
     * address they can see two centimetres away would be a worse product for no
     * security gain: `design/activation-spec.md` §4.1 states plainly that the
     * address is a **public identifier and not a secret**, and that possession
     * of it must never by itself grant control.
     *
     * Both come from the same constant, which is the rule that mattered: replace
     * [URL] and the QR, the printed address and this link all move together.
     *
     * The price is deliberately absent. The portal is the authority on what
     * something costs, and a client that posted an amount would be a client
     * somebody could edit to post a different one.
     *
     * @param plan the plan identifier, lower-case, or null to open the page with
     *   nothing chosen.
     * @param macAddress the device this licence would bind to, or null when it
     *   has not resolved yet — in which case the portal asks for it.
     */
    fun portalUrl(plan: String? = null, macAddress: String? = null): String {
        val query = buildList {
            if (!plan.isNullOrBlank()) add("plan=" + plan.encoded())
            if (!macAddress.isNullOrBlank()) add("mac=" + macAddress.encoded())
        }
        return if (query.isEmpty()) URL else URL + "?" + query.joinToString("&")
    }

    /**
     * Percent-encoding, by hand and for one reason.
     *
     * `java.net.URLEncoder` lives in the JDK, and this file is in a module that
     * must compile for every future platform — the invariant script rejects a
     * platform import here, and a JDK class is the same mistake wearing a
     * different name. The two values this ever sees are a lower-case plan
     * identifier and a MAC address, so the alphabet is small and known.
     */
    private fun String.encoded(): String = buildString {
        for (c in this@encoded) {
            when {
                c.isLetterOrDigit() && c.code < 128 -> append(c)
                c == '-' || c == '_' || c == '.' || c == '~' -> append(c)
                else -> append('%').append(c.code.toString(16).uppercase().padStart(2, '0'))
            }
        }
    }
}
