package com.castivio.domain.entitlement

import com.castivio.domain.ProviderSource
import com.castivio.domain.SourceKind
import com.castivio.domain.SyncState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two gates and every crossing between them.
 *
 * The crossings are the point. Each gate alone is obvious; what matters is that a
 * lapsed provider under a valid licence still opens Home, and that a lapsed licence
 * over a full catalogue does not.
 */
class StartGateTest {

    private val day = 24L * 60 * 60 * 1000
    private val t0 = 1_800_000_000_000L

    private fun source(
        lastImportAtMs: Long? = t0,
        itemCount: Int = 12_480,
    ) = ProviderSource(
        id = "src-1",
        kind = SourceKind.XTREAM,
        label = "Nova IPTV",
        url = "http://example.com:8080",
        sync = SyncState(lastImportAtMs = lastImportAtMs, itemCount = itemCount),
        createdAtMs = t0,
    )

    private val entitled = EntitlementState.TrialActive(expiresAtMs = t0 + 7 * day, daysRemaining = 7)

    // ----------------------------------------------------------- gate one first

    @Test
    fun `no entitlement sends the user to the licence screen`() {
        assertEquals(
            StartDestination.Licence(LicenceReason.NOT_ESTABLISHED),
            startDestination(EntitlementState.Unknown, source()),
        )
    }

    /**
     * The licence gate is answered before the catalogue is consulted, so a complete
     * library does not let an unlicensed device past.
     */
    @Test
    fun `an expired licence outranks a complete catalogue`() {
        assertEquals(
            StartDestination.Licence(LicenceReason.TRIAL_EXPIRED),
            startDestination(EntitlementState.TrialExpired, source()),
        )
        assertEquals(
            StartDestination.Licence(LicenceReason.SUBSCRIPTION_EXPIRED),
            startDestination(EntitlementState.AnnualExpired, source()),
        )
    }

    @Test
    fun `an unverifiable licence asks for verification rather than claiming expiry`() {
        val unverified = EntitlementState.VerificationUnavailable(
            lastKnownPlan = Plan.ANNUAL,
            lastKnownExpiresAtMs = t0 + 300 * day,
            graceEndedAtMs = t0 + 15 * day,
        )

        assertEquals(
            StartDestination.Licence(LicenceReason.VERIFICATION_REQUIRED),
            startDestination(unverified, source()),
        )
    }

    // ---------------------------------------------------------- then gate two

    @Test
    fun `entitled with no provider goes to activation`() {
        assertEquals(StartDestination.Activation, startDestination(entitled, source = null))
    }

    @Test
    fun `entitled with an import that never completed goes to activation`() {
        assertEquals(
            StartDestination.Activation,
            startDestination(entitled, source(lastImportAtMs = null)),
        )
    }

    /**
     * An import that committed nothing is not success. Landing on an empty app reads
     * as broken, and this is the one case where "the import worked" and "the user has
     * something" disagree.
     */
    @Test
    fun `entitled with a zero-item catalogue goes to activation`() {
        assertEquals(
            StartDestination.Activation,
            startDestination(entitled, source(itemCount = 0)),
        )
    }

    @Test
    fun `entitled with a complete catalogue opens home`() {
        assertEquals(StartDestination.Home, startDestination(entitled, source()))
    }

    @Test
    fun `every permissive licence opens home when a catalogue exists`() {
        val permissive = listOf(
            entitled,
            EntitlementState.AnnualActive(t0 + 365 * day, 365),
            EntitlementState.Lifetime,
        )

        for (state in permissive) {
            assertEquals("$state", StartDestination.Home, startDestination(state, source()))
        }
    }

    // ------------------------------------------------------------- the crossing

    /**
     * The decision that was argued over and settled: a provider whose subscription
     * lapsed has not lost its committed catalogue, so the app still opens Home. The
     * lapse changes what Home says, never where the app starts.
     *
     * `startDestination` cannot even express the alternative — it is not given the
     * provider's health — which is the strongest form the rule could take.
     */
    @Test
    fun `a lapsed provider under a valid licence still opens home`() {
        // A source imported long ago and never refreshed since: stale, reachable or
        // not, expired or not. None of that is an input here.
        val longStale = source(lastImportAtMs = t0 - 400 * day)

        assertEquals(StartDestination.Home, startDestination(entitled, longStale))
    }

    @Test
    fun `licence state and provider state never share a destination`() {
        // Licence bad, catalogue good  → Licence
        assertEquals(
            StartDestination.Licence(LicenceReason.TRIAL_EXPIRED),
            startDestination(EntitlementState.TrialExpired, source()),
        )
        // Licence good, catalogue bad  → Activation
        assertEquals(
            StartDestination.Activation,
            startDestination(entitled, source(itemCount = 0)),
        )
        // Both bad                     → Licence, because gate one comes first
        assertEquals(
            StartDestination.Licence(LicenceReason.TRIAL_EXPIRED),
            startDestination(EntitlementState.TrialExpired, source(itemCount = 0)),
        )
    }

    // ------------------------------------------------ the six mappings, explicitly

    /**
     * The licence policy as a table, stated once so a future change has to argue with
     * a test rather than with a paragraph.
     *
     * The three permissive states reach the provider gate; the three denying states do
     * not, no matter what is on the device. There is no "browse anyway": an expired
     * app licence locks the app, and the only way past it is activation.
     */
    @Test
    fun `every entitlement state maps to exactly one gate outcome`() {
        val full = source()

        // Allowed → the provider gate decides.
        assertEquals(StartDestination.Home, startDestination(entitled, full))
        assertEquals(
            StartDestination.Home,
            startDestination(EntitlementState.AnnualActive(t0 + 365 * day, 365), full),
        )
        assertEquals(StartDestination.Home, startDestination(EntitlementState.Lifetime, full))

        // Denied → the licence screen, and nothing else.
        assertEquals(
            StartDestination.Licence(LicenceReason.TRIAL_EXPIRED),
            startDestination(EntitlementState.TrialExpired, full),
        )
        assertEquals(
            StartDestination.Licence(LicenceReason.SUBSCRIPTION_EXPIRED),
            startDestination(EntitlementState.AnnualExpired, full),
        )
        assertEquals(
            StartDestination.Licence(LicenceReason.REVOKED),
            startDestination(EntitlementState.Revoked(t0), full),
        )
    }

    /**
     * The same three permissive states with nothing imported yet reach the *provider*
     * gate rather than Home — proving gate one hands over rather than deciding.
     */
    @Test
    fun `a permitted licence with no catalogue reaches the provider gate`() {
        val permissive = listOf(
            entitled,
            EntitlementState.AnnualActive(t0 + 365 * day, 365),
            EntitlementState.Lifetime,
        )

        for (state in permissive) {
            assertEquals("$state", StartDestination.Activation, startDestination(state, source = null))
        }
    }

    /** A revoked entitlement is locked out even though it was bought outright. */
    @Test
    fun `revocation locks the app regardless of what was purchased`() {
        assertEquals(
            StartDestination.Licence(LicenceReason.REVOKED),
            startDestination(EntitlementState.Revoked(t0), source()),
        )
        assertEquals(
            StartDestination.Licence(LicenceReason.REVOKED),
            startDestination(EntitlementState.Revoked(null), source = null),
        )
    }

    // ------------------------------------------------------------ after unlocking

    /**
     * What happens the moment activation succeeds: the entitlement turns permissive and
     * the catalogue that was on the device the whole time opens straight away.
     *
     * The lock never touched the data, so there is nothing to import again — which is
     * the difference between unlocking an app and setting it up twice.
     */
    @Test
    fun `activating with a catalogue already on the device opens home without importing`() {
        val locked = EntitlementState.TrialExpired
        val unlocked = EntitlementState.AnnualActive(t0 + 365 * day, 365)
        val untouched = source()

        assertEquals(
            StartDestination.Licence(LicenceReason.TRIAL_EXPIRED),
            startDestination(locked, untouched),
        )
        assertEquals(StartDestination.Home, startDestination(unlocked, untouched))
    }

    @Test
    fun `activating without a catalogue continues to provider activation`() {
        val unlocked = EntitlementState.Lifetime

        assertEquals(StartDestination.Activation, startDestination(unlocked, source = null))
    }

    // ----------------------------------------------------------------- wording

    @Test
    fun `each denying state has its own sentence`() {
        assertEquals(LicenceReason.TRIAL_EXPIRED, licenceReason(EntitlementState.TrialExpired))
        assertEquals(LicenceReason.SUBSCRIPTION_EXPIRED, licenceReason(EntitlementState.AnnualExpired))
        assertEquals(LicenceReason.NOT_ESTABLISHED, licenceReason(EntitlementState.Unknown))
        assertEquals(
            LicenceReason.VERIFICATION_REQUIRED,
            licenceReason(
                EntitlementState.VerificationUnavailable(Plan.TRIAL, null, t0),
            ),
        )
        assertEquals(LicenceReason.REVOKED, licenceReason(EntitlementState.Revoked(t0)))
    }
}
