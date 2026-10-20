package com.castivio.feature.home

import com.castivio.domain.Recorded
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The provider term: four answers, one word each in the reader's language, and a date
 * only where one is owed.
 *
 * The panel's own word decides one thing -- whether it said "Expired" -- and is never
 * what the card shows. Panels send it in English whatever the reader reads, and an
 * Arabic screen saying "Expired" is the defect this mapping replaced.
 */
class ProviderTermTest {

    /** When the provider was asked; every answer below was received at this moment. */
    private val asked = 1_790_000_000_000L
    private val ahead = asked + 30 * DAY
    private val gone = asked - DAY

    @Test
    fun `a provider never asked says not checked, with no date`() {
        val term = providerTerm(null)

        assertEquals(R.string.home_account_unknown, term.word)
        assertNull(term.atMs)
    }

    @Test
    fun `an active subscription carries the date it runs out`() {
        val term = providerTerm(answer(usable = true, expiresAtMs = ahead, label = "Active"))

        assertEquals(R.string.home_account_active, term.word)
        assertEquals(ahead, term.atMs)
    }

    /** A playlist with no panel behind it, or a portal: usable, and no date stated. */
    @Test
    fun `an active subscription with no stated date says active`() {
        val term = providerTerm(answer(usable = true, expiresAtMs = null, label = null))

        assertEquals(R.string.home_account_active, term.word)
        assertNull(term.atMs)
    }

    /**
     * **No date, although the answer has one.** It is the day the subscription lapsed,
     * and the card's date slot reads "runs out on" -- the reasoning the licence card
     * beside it follows for an expired licence.
     */
    @Test
    fun `a subscription whose date had passed says expired and names no date`() {
        val term = providerTerm(answer(usable = false, expiresAtMs = gone, label = "Expired"))

        assertEquals(R.string.home_account_expired, term.word)
        assertNull(term.atMs)
    }

    @Test
    fun `a panel that says expired is read as expired, whatever its case, with no date given`() {
        for (word in listOf("Expired", "expired", "EXPIRED")) {
            val term = providerTerm(answer(usable = false, expiresAtMs = null, label = word))

            assertEquals(word, R.string.home_account_expired, term.word)
            assertNull(word, term.atMs)
        }
    }

    /**
     * A panel that still says "Active" over a date gone by. The validator refused it for
     * the date, and the card says why -- not "Active" in red, which was the screen
     * contradicting itself.
     */
    @Test
    fun `a date gone by is expired whatever the panel calls it`() {
        val term = providerTerm(answer(usable = false, expiresAtMs = gone, label = "Active"))

        assertEquals(R.string.home_account_expired, term.word)
    }

    /** The validator's own rule: a date has passed when it is before the asking, not on it. */
    @Test
    fun `a date on the moment of asking has not passed`() {
        val term = providerTerm(answer(usable = false, expiresAtMs = asked, label = "Banned"))

        assertEquals(R.string.home_account_inactive, term.word)
    }

    /** Banned, disabled: refused for a reason that renewing does not put right. */
    @Test
    fun `a subscription refused for another reason says inactive, with no date`() {
        val term = providerTerm(answer(usable = false, expiresAtMs = ahead, label = "Banned"))

        assertEquals(R.string.home_account_inactive, term.word)
        assertNull(term.atMs)
    }

    @Test
    fun `a refusal with neither a word nor a date says inactive`() {
        val term = providerTerm(answer(usable = false, expiresAtMs = null, label = null))

        assertEquals(R.string.home_account_inactive, term.word)
        assertNull(term.atMs)
    }

    /**
     * Only a usable subscription has a future to name. This is the assertion that fails
     * if a later edit hands a date back to an answer that has already said no.
     */
    @Test
    fun `only a usable answer carries a date`() {
        assertEquals(ahead, providerTerm(answer(usable = true, expiresAtMs = ahead, label = "Active")).atMs)

        val undated = listOf(
            null,
            answer(usable = false, expiresAtMs = gone, label = "Expired"),
            answer(usable = false, expiresAtMs = ahead, label = "Banned"),
            answer(usable = false, expiresAtMs = ahead, label = null),
        )
        for (status in undated) assertNull("$status", providerTerm(status).atMs)
    }

    /**
     * Only a subscription that ran out may be called expired. This is the assertion that
     * fails if another refusal is moved onto that branch because it was the nearest one.
     */
    @Test
    fun `nothing but a lapse is called expired`() {
        val ranOut = listOf(
            answer(usable = false, expiresAtMs = gone, label = null),
            answer(usable = false, expiresAtMs = null, label = "Expired"),
        )
        val everythingElse = listOf(
            null,
            answer(usable = true, expiresAtMs = ahead, label = "Active"),
            answer(usable = true, expiresAtMs = null, label = null),
            answer(usable = false, expiresAtMs = ahead, label = "Banned"),
            answer(usable = false, expiresAtMs = null, label = "Disabled"),
            answer(usable = false, expiresAtMs = null, label = null),
        )

        for (status in ranOut) assertEquals("$status", R.string.home_account_expired, providerTerm(status).word)
        for (status in everythingElse) assertNotEquals("$status", R.string.home_account_expired, providerTerm(status).word)
    }

    private fun answer(usable: Boolean, expiresAtMs: Long?, label: String?) =
        Recorded(usable = usable, expiresAtMs = expiresAtMs, label = label, atMs = asked)

    private companion object {
        const val DAY = 86_400_000L
    }
}
