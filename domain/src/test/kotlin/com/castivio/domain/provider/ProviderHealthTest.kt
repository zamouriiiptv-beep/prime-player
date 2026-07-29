package com.castivio.domain.provider

import com.castivio.core.common.AppError
import com.castivio.core.common.Outcome
import com.castivio.domain.ProviderSource
import com.castivio.domain.ProviderStatus
import com.castivio.domain.SourceKind
import com.castivio.domain.SyncState
import com.castivio.domain.entitlement.EntitlementState
import com.castivio.domain.entitlement.startDestination
import com.castivio.domain.entitlement.StartDestination
import com.castivio.domain.time.DAY_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the provider's subscription is doing, and the line it must never cross.
 *
 * The precedence is the substance of this file. Any one of these states is easy; what
 * decides whether the banner is useful is which one wins when three are true at once,
 * and the answer is always "the one stopping the user watching something right now".
 */
class ProviderHealthTest {

    private val t0 = 1_772_323_200_000L

    private fun status(
        usable: Boolean = true,
        expiresAtMs: Long? = null,
        activeConnections: Int = 0,
        maxConnections: Int = 0,
        statusLabel: String? = "Active",
    ) = Outcome.Success(
        ProviderStatus(
            usable = usable,
            expiresAtMs = expiresAtMs,
            activeConnections = activeConnections,
            maxConnections = maxConnections,
            statusLabel = statusLabel,
        ),
    )

    // ------------------------------------------------------------- the quiet cases

    @Test
    fun `a provider nobody has asked about says nothing`() {
        assertEquals(ProviderHealth.Unknown, ProviderHealth.of(null, t0))
        assertEquals(HealthSeverity.NONE, ProviderHealth.of(null, t0).severity)
    }

    @Test
    fun `a working provider with no expiry says nothing`() {
        assertEquals(ProviderHealth.Healthy, ProviderHealth.of(status(), t0))
    }

    @Test
    fun `an expiry far enough away says nothing`() {
        val health = ProviderHealth.of(status(expiresAtMs = t0 + 90 * DAY_MS), t0)

        assertEquals(ProviderHealth.Healthy, health)
        assertEquals(HealthSeverity.NONE, health.severity)
    }

    // ------------------------------------------------------------------ the notice

    @Test
    fun `an expiry inside the window is worth mentioning`() {
        val health = ProviderHealth.of(status(expiresAtMs = t0 + 2 * DAY_MS), t0)

        assertEquals(ProviderHealth.ExpiringSoon(t0 + 2 * DAY_MS, 2), health)
        assertEquals(HealthSeverity.NOTICE, health.severity)
        assertFalse(health.playbackLikelyFails)
    }

    @Test
    fun `the warning window is a parameter, not a constant nobody can move`() {
        val expires = t0 + 20 * DAY_MS

        assertEquals(ProviderHealth.Healthy, ProviderHealth.of(status(expiresAtMs = expires), t0))
        assertEquals(
            ProviderHealth.ExpiringSoon(expires, 20),
            ProviderHealth.of(status(expiresAtMs = expires), t0, warnWithinMs = 30 * DAY_MS),
        )
    }

    /** Rounded up, and rounded the same way the app licence rounds. */
    @Test
    fun `a subscription with thirty hours left has two days on it`() {
        val expires = t0 + 30 * 60 * 60 * 1000

        assertEquals(
            ProviderHealth.ExpiringSoon(expires, 2),
            ProviderHealth.of(status(expiresAtMs = expires), t0),
        )
    }

    // ----------------------------------------------------------------- the problems

    @Test
    fun `a stated expiry that has passed is expired`() {
        val health = ProviderHealth.of(status(expiresAtMs = t0 - 1), t0)

        assertEquals(ProviderHealth.Expired(t0 - 1, "Active"), health)
        assertEquals(HealthSeverity.PROBLEM, health.severity)
        assertTrue(health.playbackLikelyFails)
    }

    /**
     * Panels lag. A line still flagged "Active" with yesterday's date is a panel that
     * has not run its expiry job yet, and the date is the more specific fact — the user
     * finds out either way the moment they press play, and finding out from a banner is
     * better.
     */
    @Test
    fun `a date in the past outranks a panel that still says active`() {
        assertEquals(
            ProviderHealth.Expired(t0 - DAY_MS, "Active"),
            ProviderHealth.of(status(usable = true, expiresAtMs = t0 - DAY_MS), t0),
        )
    }

    @Test
    fun `an unusable line with no date is a refusal, not an expiry`() {
        val health = ProviderHealth.of(status(usable = false, statusLabel = "Banned"), t0)

        assertEquals(ProviderHealth.Rejected("Banned"), health)
        assertEquals(HealthSeverity.PROBLEM, health.severity)
    }

    @Test
    fun `a refusal carries the provider's own word for it as a detail`() {
        val health = ProviderHealth.of(status(usable = false, statusLabel = "Disabled"), t0)

        assertEquals(ProviderHealth.Rejected("Disabled"), health)
        assertEquals("Disabled", health.providerResponse)
    }

    /**
     * The panel's word is a diagnostic line under Castivio's own sentence, never the
     * sentence itself — it arrives untranslated, in a language nobody chose, written by
     * a stranger. Every state that does not have one says so.
     */
    @Test
    fun `most states have nothing to quote`() {
        assertNull(ProviderHealth.Healthy.providerResponse)
        assertNull(ProviderHealth.Unknown.providerResponse)
        assertNull(ProviderHealth.of(status(expiresAtMs = t0 + 2 * DAY_MS), t0).providerResponse)
        assertNull(ProviderHealth.of(status(activeConnections = 2, maxConnections = 2), t0).providerResponse)
        assertNull(ProviderHealth.of(Outcome.Failure(AppError.TIMEOUT), t0).providerResponse)
    }

    /**
     * The rule that matters most in this file: **no decision is taken from the text.**
     *
     * A panel that spells it "Banned", "BANNED", "محظور" or leaves it blank is the same
     * situation, and a business rule that greps for a word breaks the first time a
     * provider spells it differently — silently, in the field, on someone's television.
     * The state comes from `usable`, `expiresAtMs` and the connection counts alone.
     */
    @Test
    fun `the provider's own words never change the decision`() {
        val words = listOf(null, "", "Banned", "BANNED", "Expired", "Active", "محظور", "已封禁", "42")

        // Refused, with no date: the same state every time, differing only in the detail.
        for (word in words) {
            assertEquals(word, ProviderHealth.Rejected(word), ProviderHealth.of(status(usable = false, statusLabel = word), t0))
        }

        // And a panel that says "Expired" while the line is usable and in date is still
        // healthy, because the word is not evidence of anything.
        assertEquals(
            ProviderHealth.Healthy,
            ProviderHealth.of(status(usable = true, expiresAtMs = t0 + 90 * DAY_MS, statusLabel = "Expired"), t0),
        )

        // The mirror image: a panel that says "Active" past its own date is expired.
        assertTrue(
            ProviderHealth.of(status(usable = true, expiresAtMs = t0 - 1, statusLabel = "Active"), t0)
                is ProviderHealth.Expired,
        )
    }

    @Test
    fun `every connection in use is its own state`() {
        val health = ProviderHealth.of(status(activeConnections = 2, maxConnections = 2), t0)

        assertEquals(ProviderHealth.ConnectionLimit(2, 2), health)
        assertTrue(health.playbackLikelyFails)
    }

    @Test
    fun `a plan with connections to spare says nothing about them`() {
        assertEquals(
            ProviderHealth.Healthy,
            ProviderHealth.of(status(activeConnections = 1, maxConnections = 3), t0),
        )
    }

    /** Unlimited plans report zero, which must not read as "no connections left". */
    @Test
    fun `an unstated connection limit is not a limit`() {
        assertEquals(
            ProviderHealth.Healthy,
            ProviderHealth.of(status(activeConnections = 4, maxConnections = 0), t0),
        )
    }

    // --------------------------------------------------------------- the precedence

    /**
     * The whole point of returning one state. All three of these are true at once, and
     * the user needs to be told the one that explains why the next thing they press
     * will not work.
     */
    @Test
    fun `what is happening now outranks what happens next week`() {
        val everything = status(
            expiresAtMs = t0 + DAY_MS,
            activeConnections = 3,
            maxConnections = 3,
        )

        assertEquals(ProviderHealth.ConnectionLimit(3, 3), ProviderHealth.of(everything, t0))
    }

    @Test
    fun `an expiry outranks a connection limit, because nothing will play either way`() {
        val expiredAndBusy = status(
            expiresAtMs = t0 - DAY_MS,
            activeConnections = 3,
            maxConnections = 3,
        )

        assertEquals(ProviderHealth.Expired(t0 - DAY_MS, "Active"), ProviderHealth.of(expiredAndBusy, t0))
    }

    @Test
    fun `a refusal outranks everything the panel said alongside it`() {
        val refused = status(
            usable = false,
            statusLabel = "Banned",
            activeConnections = 3,
            maxConnections = 3,
        )

        assertEquals(ProviderHealth.Rejected("Banned"), ProviderHealth.of(refused, t0))
    }

    // ------------------------------------------------------------- reaching the host

    /**
     * A network failure says nothing about the subscription. Reporting it as an expiry
     * would tell a user on hotel wifi that they need to pay their provider.
     */
    @Test
    fun `an unreachable provider is not an expired one`() {
        val health = ProviderHealth.of(Outcome.Failure(AppError.TIMEOUT), t0)

        assertEquals(ProviderHealth.Unreachable(AppError.TIMEOUT), health)
        assertEquals(HealthSeverity.NOTICE, health.severity)
    }

    @Test
    fun `every way of failing to reach a host reads the same`() {
        val networkErrors = listOf(
            AppError.NETWORK_UNAVAILABLE,
            AppError.TIMEOUT,
            AppError.SERVER_ERROR,
            AppError.NOT_FOUND,
            AppError.UNKNOWN,
        )

        for (error in networkErrors) {
            assertEquals("$error", ProviderHealth.Unreachable(error), ProviderHealth.of(Outcome.Failure(error), t0))
        }
    }

    /** Except a refusal, which is the provider answering rather than failing to. */
    @Test
    fun `rejected credentials are an answer, not an outage`() {
        assertEquals(
            ProviderHealth.Rejected(null),
            ProviderHealth.of(Outcome.Failure(AppError.UNAUTHORIZED), t0),
        )
    }

    // ------------------------------------------------------------------- the line

    /**
     * The decision that was argued over and settled, restated from the other side.
     *
     * `startDestination` does not take a [ProviderHealth] and cannot be given one, so a
     * lapsed provider is *inexpressible* as a reason to send the user somewhere other
     * than Home. This test cannot check a signature, so it checks the consequence: with
     * a valid app licence and a committed catalogue, every provider problem there is
     * still opens Home.
     */
    @Test
    fun `no provider problem changes where the app starts`() {
        val licensed = EntitlementState.Lifetime
        val catalogue = ProviderSource(
            id = "src-1",
            kind = SourceKind.XTREAM,
            label = "Nova IPTV",
            url = "http://example.com:8080",
            sync = SyncState(lastImportAtMs = t0 - 400 * DAY_MS, itemCount = 12_480),
            createdAtMs = t0,
        )

        val everyProblem = listOf(
            ProviderHealth.of(status(usable = false, statusLabel = null), t0),
            ProviderHealth.of(status(expiresAtMs = t0 - DAY_MS), t0),
            ProviderHealth.of(status(activeConnections = 2, maxConnections = 2), t0),
            ProviderHealth.of(Outcome.Failure(AppError.NETWORK_UNAVAILABLE), t0),
        )

        assertTrue(everyProblem.all { it.severity != HealthSeverity.NONE })
        assertEquals(StartDestination.Home, startDestination(licensed, catalogue))
    }
}
