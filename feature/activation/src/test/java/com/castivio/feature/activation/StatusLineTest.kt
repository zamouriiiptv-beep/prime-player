package com.castivio.feature.activation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the one reserved line says, and which claim wins when two are true.
 *
 * ## The defect this was written for
 *
 * A device review photographed the screen in French, mid-copy: the tick on the
 * device key capsule was lit, and the line underneath still read *"Aucun
 * abonnement pour l'instant"*. The only acknowledgement the user got was the grey
 * pill the operating system draws — in the system's language, not Castivio's,
 * which is why it looked like a localisation bug and was not one.
 *
 * The cause was ordering. The refresh outcomes were listed above the copy
 * confirmations, and `RefreshState.None` is not transient — press Refresh once
 * and it is the answer forever. From then on every copy was silent.
 *
 * The rule now: **when two things are true, say the one that is about to stop
 * being true.** A copy confirmation lasts 1.5 seconds; a subscription status
 * lasts until the subscription changes.
 */
class StatusLineTest {

    private fun status(state: ActivationIdentityState) = statusMessage(state)

    /** The case from the photograph: a standing refresh result must not mask a copy. */
    @Test
    fun `a copy confirmation is shown even after a refresh has answered`() {
        val afterRefresh = ActivationIdentityState(
            address = "2F:19:EB:20:44:7C",
            deviceKey = "482731",
            refresh = RefreshState.None,
        )
        assertEquals(
            Status(R.string.refresh_none, Tone.Missing),
            status(afterRefresh),
        )

        val thenCopiedKey = afterRefresh.copy(keyCopied = true, lastCopied = Copied.Key)
        assertEquals(
            "the refresh result masked the copy confirmation, which is the bug",
            Status(R.string.copied_key, Tone.Copied),
            status(thenCopiedKey),
        )
    }

    /** Both identifiers behave identically. Same mechanism, same tone. */
    @Test
    fun `the two identifiers confirm the same way`() {
        val base = ActivationIdentityState(address = "2F:19:EB:20:44:7C", deviceKey = "482731")
        val mac = status(base.copy(addressCopied = true, lastCopied = Copied.Address))
        val key = status(base.copy(keyCopied = true, lastCopied = Copied.Key))

        assertEquals(R.string.copied_mac, mac?.message)
        assertEquals(R.string.copied_key, key?.message)
        assertEquals("the two confirmations are drawn in different tones", mac?.tone, key?.tone)
        assertEquals(
            "a copy confirmation must be its own register, louder than a murmur -- " +
                "the platform draws a clipboard toast a moment later in the system " +
                "language, and Castivio's answer is the one to be believed",
            Tone.Copied,
            mac?.tone,
        )
    }

    /**
     * A failure still outranks the resting state, and stays red.
     *
     * The only thing that may take the line away from `danger` is a copy, and
     * only for as long as the copy confirmation lives.
     */
    @Test
    fun `a refresh failure keeps the danger tone`() {
        val failed = ActivationIdentityState(refresh = RefreshState.Error)
        assertEquals(Status(R.string.refresh_error, Tone.Broken), status(failed))
    }

    /**
     * At rest the screen is explicit about having nothing, in `warning`.
     *
     * `danger` would be telling somebody their new install is faulty on first
     * launch; muted, which it used to be, says nothing at all.
     */
    @Test
    fun `an untouched screen says there is no subscription yet, as a warning`() {
        assertEquals(
            Status(R.string.refresh_none, Tone.Missing),
            status(ActivationIdentityState()),
        )
    }

    /**
     * And it hands the line straight back.
     *
     * The confirmation is transient by design: 1.5 seconds, then whatever was
     * true before is true again. Clearing `lastCopied` is what the view model's
     * timer does, so this is the state it leaves behind — and the line must
     * return to the standing status rather than going blank.
     */
    @Test
    fun `when the confirmation expires the line returns to what it was saying`() {
        val afterRefresh = ActivationIdentityState(refresh = RefreshState.None)
        val during = afterRefresh.copy(addressCopied = true, lastCopied = Copied.Address)
        val after = during.copy(addressCopied = false, lastCopied = Copied.None)

        assertEquals(Status(R.string.copied_mac, Tone.Copied), status(during))
        assertEquals(status(afterRefresh), status(after))
    }

    /** Found is the one good outcome, and it is the one green tone on the screen. */
    @Test
    fun `a found subscription is the only success tone`() {
        assertEquals(
            Status(R.string.refresh_found, Tone.Good),
            status(ActivationIdentityState(refresh = RefreshState.Found)),
        )
    }
}
