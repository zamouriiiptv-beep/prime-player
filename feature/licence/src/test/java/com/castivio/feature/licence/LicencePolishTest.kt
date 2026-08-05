package com.castivio.feature.licence

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.core.os.ConfigurationCompat
import androidx.test.core.app.ApplicationProvider
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.MotionLevel
import com.castivio.domain.entitlement.EntitlementState
import com.castivio.domain.entitlement.PricingDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * The four things the last polish pass changed, each of which a layout test
 * would go on passing without.
 *
 *  - An active licence is shown no prices.
 *  - The legal notice is reachable, and closes.
 *  - The condition resolves to the new one, at every motion level.
 *
 * Text-driven sizes are not asserted anywhere here — Robolectric does not lay
 * text out. Presence and absence are real; the fade's *timing* is asserted in
 * `LicenceMotionTest`, which needs no clock and no composition.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-land")
class LicencePolishTest {

    @get:Rule
    val compose = createComposeRule()

    private val res = ApplicationProvider.getApplicationContext<android.content.Context>().resources

    // -- Prices ------------------------------------------------------------

    /**
     * The state this pass was opened by: a paying customer being shown a price.
     *
     * `a lifetime licence is offered nothing further` in `LicenceLayoutTest`
     * covers the other half. This is the one that regressed, because an annual
     * licence used to be offered the lifetime upgrade here.
     */
    @Test
    fun `an active annual licence is shown no prices`() {
        show(EntitlementState.AnnualActive(expiresAtMs = EXPIRY_MS, daysRemaining = 200))

        for (offer in PricingDefaults.config.purchasable) {
            assertEquals(
                "an active licence is being shown the ${offer.plan} price",
                0,
                nodes(LicenceTags.plan(offer.plan.name.lowercase())),
            )
        }
        compose.onNodeWithText(res.getString(R.string.licence_status_active)).assertExists()
    }

    /** And a device still on trial is, because it has not bought anything yet. */
    @Test
    fun `a trial is still shown the prices`() {
        show(EntitlementState.TrialActive(expiresAtMs = 0, daysRemaining = 3))

        for (offer in PricingDefaults.config.purchasable) {
            assertTrue(
                "a trial is not being offered the ${offer.plan} plan",
                nodes(LicenceTags.plan(offer.plan.name.lowercase())) == 1,
            )
        }
    }

    /** And the countdown is a sentence on the line, not a fragment in the chip. */
    @Test
    fun `the trial countdown reads as a sentence`() {
        show(EntitlementState.TrialActive(expiresAtMs = 0, daysRemaining = 3))

        compose.onNodeWithText(
            res.getQuantityString(R.plurals.licence_trial_days, 3, 3),
        ).assertExists()
    }

    // -- The expiry date ----------------------------------------------------

    /**
     * A zero timestamp draws no date, and that guard is deliberate.
     *
     * Zero is the epoch, and "Valid until 1 Jan 1970" in front of a paying
     * customer is worse than a missing line. The guard was right; what was wrong
     * was the debug state board's own fixture, which used zero and so made the
     * one state with a date the one state on the board that could never show
     * it. Pinned here because a fixture is exactly the kind of thing that gets
     * quietly reset.
     */
    @Test
    fun `an epoch timestamp is not drawn as a date`() {
        show(EntitlementState.AnnualActive(expiresAtMs = 0, daysRemaining = 200))
        assertEquals("1 Jan 1970 is on screen", 0, nodes(LicenceTags.EXPIRY))
    }

    /**
     * An active annual licence says when it runs out; nothing else does.
     *
     * The date itself is not asserted — that is the platform's `DateFormat` and
     * testing it would be testing ICU. What is asserted is that the line exists,
     * that it carries the locale-formatted date the same call produces, and that
     * it is absent everywhere else.
     */
    @Test
    fun `an active annual licence shows when it expires`() {
        show(EntitlementState.AnnualActive(expiresAtMs = EXPIRY_MS, daysRemaining = 200))

        assertEquals("the expiry line is missing", 1, nodes(LicenceTags.EXPIRY))
        // The same locale the screen resolves, read the same way, so this
        // asserts the wiring rather than agreeing with a hard-coded en-US.
        val locale = ConfigurationCompat.getLocales(res.configuration).get(0)!!
        val expected = res.getString(
            R.string.licence_valid_until,
            formatExpiry(EXPIRY_MS, locale),
        )
        compose.onNodeWithText(expected).assertExists()
    }

    /**
     * The month is a word, in every language Castivio ships.
     *
     * This is the assertion the first version did not make, and its absence is
     * why a numeric Arabic date reached a device. `DateFormat.MEDIUM` looks like
     * "a readable date" and is really "whatever this language puts in the medium
     * slot" — which is `dd/MM/y` in Arabic, `2027/02/19` in Japanese and
     * `19.02.2027` in German. A row of numbers separated by slashes is ambiguous
     * in exactly the way a date on a receipt must not be.
     *
     * So the claim is made directly: whatever the platform returns must contain
     * a letter. Not which letters, and not in what order — that is ICU's
     * business and testing it would be testing ICU.
     */
    @Test
    fun `the expiry month is named and not numbered in every language`() {
        for (tag in EVERY_LANGUAGE) {
            val formatted = formatExpiry(EXPIRY_MS, Locale.forLanguageTag(tag))
            assertTrue(
                "$tag formats the expiry as \"$formatted\", which has no month name in it",
                formatted.any { it.isLetter() },
            )
        }
    }

    /**
     * And no other state does, including the two that also have a date.
     *
     * Lifetime has no expiry at all; a trial has one and says it in days
     * instead, because two renderings of the same fact in different units is
     * how a user ends up counting on their fingers. Expired, missing, revoked
     * and unverified are conditions, and a date on any of them would be telling
     * somebody the day their licence stopped working.
     */
    @Test
    fun `no other state shows an expiry date`() {
        val ui = mutableStateOf(uiFor(EntitlementState.Unknown))
        compose.setContent { Harness(ui, MotionLevel.DISABLED) }

        for (state in states) {
            if (state is EntitlementState.AnnualActive) continue
            ui.value = uiFor(state)
            compose.waitForIdle()
            assertEquals(
                "$state is showing an expiry date",
                0,
                nodes(LicenceTags.EXPIRY),
            )
        }
    }

    /**
     * The date goes under the sentence, and the sentence keeps its reserved
     * height.
     *
     * What this does **not** assert, on purpose: that the status line stays at
     * the same y. It does not, and it should not — the field band centres its
     * content vertically, so a column that gains a line rises by half of it.
     * That is the band doing its job, it happens inside the one state that has
     * a date, and asserting the opposite would be pinning a bug in place.
     *
     * The claim the requirement actually makes is that no *other* state is
     * affected, and `no other state shows an expiry date` is what holds that:
     * eight of the nine compose no element here at all, so their columns are
     * arithmetically identical to what they were.
     */
    @Test
    fun `the expiry line sits under the status line without shrinking it`() {
        val ui = mutableStateOf(uiFor(EntitlementState.Lifetime))
        compose.setContent { Harness(ui, MotionLevel.DISABLED) }
        compose.waitForIdle()
        val undated = compose.onNodeWithTag(LicenceTags.STATUS).getUnclippedBoundsInRoot()

        compose.runOnIdle {
            ui.value = uiFor(EntitlementState.AnnualActive(EXPIRY_MS, daysRemaining = 200))
        }
        compose.waitForIdle()
        val status = compose.onNodeWithTag(LicenceTags.STATUS).getUnclippedBoundsInRoot()
        val expiry = compose.onNodeWithTag(LicenceTags.EXPIRY).getUnclippedBoundsInRoot()

        assertEquals(
            "the expiry line ate into the status line's reserved height",
            undated.height,
            status.height,
        )
        assertTrue(
            "the expiry line is not below the status line: it starts at " +
                "${expiry.top} and the status line ends at ${status.bottom}",
            expiry.top >= status.bottom,
        )
    }

    // -- The legal notice ---------------------------------------------------

    @Test
    fun `the legal page is one press from the footer and closes again`() {
        show(EntitlementState.Unknown)

        assertEquals("the legal page is open before anything was pressed", 0, nodes(LicenceTags.LEGAL))

        compose.onNodeWithTag(LicenceTags.FOOTER).performClick()
        compose.waitForIdle()
        assertEquals("pressing the link did not open the legal page", 1, nodes(LicenceTags.LEGAL))

        compose.onNodeWithTag(LicenceTags.LEGAL_CLOSE).performClick()
        compose.waitForIdle()
        assertEquals("the legal page would not close", 0, nodes(LicenceTags.LEGAL))
    }

    /**
     * All eight sections are on the page, headings and bodies both.
     *
     * Enumerated rather than spot-checked: a page that renders seven of eight is
     * a page missing a legal section, and the one it drops will be whichever one
     * nobody scrolled to.
     */
    @Test
    fun `every legal section is rendered`() {
        show(EntitlementState.Unknown)
        compose.onNodeWithTag(LicenceTags.FOOTER).performClick()
        compose.waitForIdle()

        for (id in LEGAL_STRINGS) {
            assertTrue(
                "the legal page is missing ${res.getResourceEntryName(id)}",
                textNodes(res.getString(id)) >= 1,
            )
        }
    }

    /**
     * Opening and closing it does not disturb the screen underneath.
     *
     * This is the claim that makes it an overlay rather than a second Activity:
     * the entitlement, the address, the QR and the plans are the same objects
     * afterwards, because nothing was ever torn down.
     */
    @Test
    fun `the entitlement survives a trip to the legal page`() {
        show(EntitlementState.TrialActive(expiresAtMs = 0, daysRemaining = 3))
        val countdown = res.getQuantityString(R.plurals.licence_trial_days, 3, 3)
        compose.onNodeWithText(countdown).assertExists()

        compose.onNodeWithTag(LicenceTags.FOOTER).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(LicenceTags.LEGAL_CLOSE).performClick()
        compose.waitForIdle()

        compose.onNodeWithText(countdown).assertExists()
        for (offer in PricingDefaults.config.purchasable) {
            assertEquals(
                "the ${offer.plan} card did not come back",
                1,
                nodes(LicenceTags.plan(offer.plan.name.lowercase())),
            )
        }
    }

    /**
     * The link is in the footer in every state.
     *
     * A door that is present only in some conditions is a door somebody will
     * report as missing.
     */
    @Test
    fun `the footer carries the legal link in every state`() {
        val ui = mutableStateOf(uiFor(EntitlementState.Unknown))
        compose.setContent { Harness(ui, MotionLevel.DISABLED) }

        for (state in states) {
            ui.value = uiFor(state)
            compose.waitForIdle()
            compose.onNodeWithText(res.getString(R.string.licence_legal_link)).assertExists()
        }
    }

    // -- The fade -----------------------------------------------------------

    /**
     * The condition ends up right, with motion on.
     *
     * The **timing** is not asserted here, and that is deliberate rather than a
     * gap. Driving Compose's test clock by hand needs the recomposition after a
     * state write to land before the clock is advanced; getting that ordering
     * wrong produces a test that fails saying the incoming content is missing
     * when nothing has asked for it yet, which is what two attempts did. There
     * is no device or emulator in this environment to iterate a frame-stepping
     * test against, so the timing moved to `LicenceMotionTest`, where it is a
     * number rather than a race.
     *
     * What stays here is the claim a number cannot make: whichever path the
     * change takes, the screen lands on the new condition and lets go of the old
     * one.
     */
    @Test
    fun `the condition resolves to the new one with motion on`() {
        assertConditionResolves(MotionLevel.FULL)
    }

    /** And with motion off, where there is no animation to resolve. */
    @Test
    fun `the condition resolves to the new one with motion off`() {
        assertConditionResolves(MotionLevel.DISABLED)
    }

    private fun assertConditionResolves(motion: MotionLevel) {
        val ui = mutableStateOf(uiFor(EntitlementState.TrialActive(0, 3)))
        compose.setContent { Harness(ui, motion) }
        compose.waitForIdle()

        compose.runOnIdle { ui.value = uiFor(EntitlementState.Lifetime) }
        compose.waitForIdle()

        assertEquals(
            "$motion: the old condition is still on screen",
            0,
            textNodes(res.getString(R.string.licence_chip_trial)),
        )
        assertEquals(
            "$motion: the new condition never arrived",
            1,
            textNodes(res.getString(R.string.licence_chip_active)),
        )
    }

    // -- Harness ------------------------------------------------------------

    private fun show(state: EntitlementState) {
        compose.setContent { Harness(mutableStateOf(uiFor(state)), MotionLevel.DISABLED) }
        compose.waitForIdle()
    }

    private fun nodes(tag: String): Int =
        compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size

    private fun textNodes(text: String): Int =
        compose.onAllNodes(hasText(text)).fetchSemanticsNodes().size

    private val states = listOf(
        EntitlementState.TrialActive(expiresAtMs = 0, daysRemaining = 3),
        EntitlementState.AnnualActive(expiresAtMs = 0, daysRemaining = 200),
        EntitlementState.Lifetime,
        EntitlementState.TrialExpired,
        EntitlementState.AnnualExpired,
        EntitlementState.Unknown,
        EntitlementState.Revoked(revokedAtMs = 0),
    )
}

/** One phone frame, one motion level, and the real screen inside them. */
@Composable
private fun Harness(state: MutableState<LicenceUiState>, motion: MotionLevel) {
    CastivioTheme(motionLevel = motion) {
        Box(Modifier.requiredSize(PHONE_WIDTH, PHONE_HEIGHT)) {
            LicenceScreen(
                state = state.value,
                onPlan = {},
                onRetry = {},
                onSupport = {},
                onCopied = {},
                onOpenLanguage = {},
            )
        }
    }
}

private fun uiFor(licence: EntitlementState) = LicenceUiState(
    licence = licence,
    address = "2F:19:EB:20:44:7C",
    deviceKey = "482731",
    qr = licenceQrBitmap(256),
    plans = PricingDefaults.config.purchasable,
)

/**
 * Every language Castivio ships, by tag.
 *
 * Written out rather than derived, because a unit test cannot enumerate the
 * resource directories it was compiled against. That is a real weakness and it
 * is stated rather than hidden: a thirty-ninth language could be added and this
 * list not updated. What limits the damage is that `check-invariants.sh` already
 * fails the build when the bundles disagree, so the gap is one of coverage here
 * and never of a missing translation there.
 */
private val EVERY_LANGUAGE = listOf(
    "ar", "bg", "bn", "cs", "da", "de", "el", "en", "es", "fa", "fi", "fil", "fr",
    "hi", "hr", "hu", "id", "it", "ja", "ko", "ms", "nb", "nl", "pl", "pt", "pt-PT",
    "ro", "ru", "sk", "sq", "sr", "sv", "th", "tr", "uk", "ur", "vi",
    "zh-Hans", "zh-Hant",
)

/** A fixed instant, so the formatted date is the same on every run. */
private const val EXPIRY_MS = 1_817_000_000_000L

/** Every string the legal page is required to draw. */
private val LEGAL_STRINGS = listOf(
    R.string.licence_legal_about_title, R.string.licence_legal_about_body,
    R.string.licence_legal_scope_title, R.string.licence_legal_scope_body,
    R.string.licence_legal_content_title, R.string.licence_legal_full,
    R.string.licence_legal_copyright_title, R.string.licence_legal_copyright_body,
    R.string.licence_legal_privacy_title, R.string.licence_legal_privacy_body,
    R.string.licence_legal_refund_title, R.string.licence_legal_refund_body,
    R.string.licence_legal_support_title, R.string.licence_legal_support_body,
    R.string.licence_legal_acceptance_title, R.string.licence_legal_acceptance_body,
)

/** The reference landscape phone, which is the frame the design is cut against. */
private val PHONE_WIDTH = 873.dp
private val PHONE_HEIGHT = 393.dp

