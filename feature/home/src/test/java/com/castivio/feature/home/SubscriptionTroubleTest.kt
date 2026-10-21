package com.castivio.feature.home

import com.castivio.core.common.AppError
import com.castivio.domain.Recorded
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which trouble the footer's last line says, and the sentence each is said in.
 *
 * Getting one wrong sends a viewer to the wrong person: their provider about a router that
 * is off, or their router about a server that is down. So every cause is enumerated, and so
 * is the case the line was asked for -- a subscription paid for until next year, with a
 * server that does not answer.
 */
class SubscriptionTroubleTest {

    private val asked = 1_790_000_000_000L
    private val paid = Recorded(usable = true, expiresAtMs = asked + 100 * DAY, label = "Active", atMs = asked)

    @Test
    fun `nothing asked and nothing recorded says nothing`() {
        assertNull(subscriptionTrouble(contact = null, recorded = null))
    }

    @Test
    fun `an answered, usable subscription says nothing`() {
        assertNull(subscriptionTrouble(Contact.Answered, paid))
    }

    /**
     * **The case the line was asked for.** Paid for until a date to come, and the server
     * does not answer now: the trouble is the server's, and the date is not touched -- it
     * is the header card's, and the trouble is decided without it.
     */
    @Test
    fun `a paid subscription whose server does not answer is unreachable`() {
        for (error in listOf(AppError.TIMEOUT, AppError.NETWORK_UNAVAILABLE, AppError.SERVER_ERROR, AppError.NOT_FOUND, AppError.UNKNOWN)) {
            assertEquals("$error", SubscriptionTrouble.Unreachable, subscriptionTrouble(Contact.Failed(error, online = true), paid))
        }
    }

    /** The same failure with the device on no network is the viewer's network, not the provider. */
    @Test
    fun `a failure with the device off the network is offline`() {
        for (error in listOf(AppError.TIMEOUT, AppError.NETWORK_UNAVAILABLE)) {
            assertEquals("$error", SubscriptionTrouble.Offline, subscriptionTrouble(Contact.Failed(error, online = false), paid))
        }
    }

    /** A rejected login is the provider saying no, not the provider being away. */
    @Test
    fun `credentials the provider rejects are a refusal`() {
        assertEquals(SubscriptionTrouble.Refused, subscriptionTrouble(Contact.Failed(AppError.UNAUTHORIZED, online = true), paid))
    }

    @Test
    fun `an answer that the subscription ran out is expired`() {
        val ran = Recorded(usable = false, expiresAtMs = asked - DAY, label = "Expired", atMs = asked)

        assertEquals(SubscriptionTrouble.Expired, subscriptionTrouble(Contact.Answered, ran))
    }

    @Test
    fun `an answer that the subscription is refused for another reason is refused`() {
        val banned = Recorded(usable = false, expiresAtMs = asked + 100 * DAY, label = "Banned", atMs = asked)

        assertEquals(SubscriptionTrouble.Refused, subscriptionTrouble(Contact.Answered, banned))
    }

    /** What Home asked last is newer than what was recorded before it. */
    @Test
    fun `a failed asking speaks before an older answer`() {
        val ran = Recorded(usable = false, expiresAtMs = asked - DAY, label = "Expired", atMs = asked)

        assertEquals(SubscriptionTrouble.Unreachable, subscriptionTrouble(Contact.Failed(AppError.TIMEOUT, online = true), ran))
    }

    /**
     * Not asked in this visit -- a playlist with no account behind it, which has no server
     * to be in trouble with -- the recorded answer alone decides, and a usable one is fine.
     */
    @Test
    fun `without an asking the recorded answer decides`() {
        assertNull(subscriptionTrouble(contact = null, recorded = paid))
        assertEquals(
            SubscriptionTrouble.Expired,
            subscriptionTrouble(contact = null, recorded = Recorded(usable = false, expiresAtMs = null, label = "Expired", atMs = asked)),
        )
    }

    @Test
    fun `each trouble has its own sentence`() {
        assertEquals(R.string.home_trouble_unreachable, troubleSentence(SubscriptionTrouble.Unreachable))
        assertEquals(R.string.home_trouble_offline, troubleSentence(SubscriptionTrouble.Offline))
        assertEquals(R.string.home_trouble_expired, troubleSentence(SubscriptionTrouble.Expired))
        assertEquals(R.string.home_trouble_refused, troubleSentence(SubscriptionTrouble.Refused))
    }

    private companion object {
        const val DAY = 86_400_000L
    }
}
