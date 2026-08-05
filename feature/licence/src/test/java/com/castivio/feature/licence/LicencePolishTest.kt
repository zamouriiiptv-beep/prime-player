package com.castivio.feature.licence

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
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

/**
 * The four things the last polish pass changed, each of which a layout test
 * would go on passing without.
 *
 *  - An active licence is shown no prices.
 *  - The legal notice is reachable, and closes.
 *  - The condition fades rather than jumping, inside the 200–250ms the design
 *    calls for, and does not fade at all when motion is off.
 *  - A copy confirmation is **not** faded, because it answers a press.
 *
 * Text-driven sizes are not asserted anywhere here — Robolectric does not lay
 * text out. Presence, absence and timing are all real.
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
        show(EntitlementState.AnnualActive(expiresAtMs = 0, daysRemaining = 200))

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

    // -- The legal notice ---------------------------------------------------

    @Test
    fun `the full legal notice is one press from the footer and closes again`() {
        show(EntitlementState.Unknown)

        assertEquals("the notice is open before anything was pressed", 0, nodes(LicenceTags.NOTICE))

        compose.onNodeWithTag(LicenceTags.FOOTER).performClick()
        compose.waitForIdle()
        assertEquals("pressing the footer did not open the notice", 1, nodes(LicenceTags.NOTICE))
        compose.onNodeWithText(res.getString(R.string.licence_legal_full)).assertExists()

        compose.onNodeWithText(res.getString(R.string.licence_legal_close)).performClick()
        compose.waitForIdle()
        assertEquals("the notice would not close", 0, nodes(LicenceTags.NOTICE))
    }

    /**
     * The footer is never empty, in any state.
     *
     * A standing notice that disappears while the screen is loading is a notice
     * that is not standing.
     */
    @Test
    fun `the footer carries the standing notice in every state`() {
        val ui = mutableStateOf(uiFor(EntitlementState.Unknown))
        compose.setContent { Harness(ui, MotionLevel.DISABLED) }

        for (state in states) {
            ui.value = uiFor(state)
            compose.waitForIdle()
            compose.onNodeWithText(res.getString(R.string.licence_legal)).assertExists()
        }
    }

    // -- The fade -----------------------------------------------------------

    /**
     * A change of condition crosses over rather than cutting.
     *
     * The clock is driven by hand: with `autoAdvance` on, every frame of the
     * animation is skipped and the test would pass against an instant swap,
     * which is the thing it exists to tell apart.
     *
     * The order below is the whole trick and the first version of it was wrong.
     * The composition is settled *before* the clock is frozen; the state is then
     * written on the UI thread, and one frame is pumped to compose the new
     * target and start the animation. Freezing first and writing from the test
     * thread produced a tree that had never recomposed, and three tests that
     * failed saying the incoming chip was missing when nothing had asked for it
     * yet.
     */
    @Test
    fun `the condition fades when the licence changes`() {
        val ui = mutableStateOf(uiFor(EntitlementState.TrialActive(0, 3)))
        compose.setContent { Harness(ui, MotionLevel.FULL) }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false

        compose.runOnUiThread { ui.value = uiFor(EntitlementState.Lifetime) }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(FADE_MIDWAY)

        assertEquals(
            "the outgoing chip was cut rather than faded",
            1,
            textNodes(res.getString(R.string.licence_chip_trial)),
        )
        assertEquals(
            "the incoming chip is not composed during the fade, so it cannot be " +
                "pressed or focused until the animation ends",
            1,
            textNodes(res.getString(R.string.licence_chip_active)),
        )

        compose.mainClock.advanceTimeBy(FADE_OVER)
        assertEquals(
            "the outgoing chip is still on screen after the fade should have ended",
            0,
            textNodes(res.getString(R.string.licence_chip_trial)),
        )
    }

    /** And does not, when the user has said they do not want animation. */
    @Test
    fun `the condition is swapped instantly when motion is off`() {
        val ui = mutableStateOf(uiFor(EntitlementState.TrialActive(0, 3)))
        compose.setContent { Harness(ui, MotionLevel.DISABLED) }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false

        compose.runOnUiThread { ui.value = uiFor(EntitlementState.Lifetime) }
        compose.mainClock.advanceTimeByFrame()

        assertEquals(
            "motion is disabled and the old condition is still being drawn",
            0,
            textNodes(res.getString(R.string.licence_chip_trial)),
        )
        assertEquals(1, textNodes(res.getString(R.string.licence_chip_active)))
    }

    /**
     * A copy confirmation appears at once even with motion on.
     *
     * It answers something the user did a moment ago, and a fade between the
     * press and its acknowledgement is a delay dressed as a flourish. The
     * crossfade is keyed on the entitlement precisely so that this does not
     * animate.
     */
    @Test
    fun `a copy confirmation is not faded`() {
        val ui = mutableStateOf(uiFor(EntitlementState.Unknown))
        compose.setContent { Harness(ui, MotionLevel.FULL) }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false

        compose.runOnUiThread { ui.value = ui.value.copy(lastCopied = Copied.Address) }
        compose.mainClock.advanceTimeByFrame()

        assertEquals(
            "the copy confirmation was animated in rather than answering the press",
            1,
            textNodes(res.getString(R.string.licence_copied_mac)),
        )
        assertEquals(
            "the resting sentence is still on screen behind the confirmation",
            0,
            textNodes(res.getString(R.string.licence_status_none)),
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

/** The reference landscape phone, which is the frame the design is cut against. */
private val PHONE_WIDTH = 873.dp
private val PHONE_HEIGHT = 393.dp

/** Half of the 220ms fade: both halves of the crossover are on screen. */
private const val FADE_MIDWAY = 110L

/** Comfortably past the end of it. */
private const val FADE_OVER = 400L
