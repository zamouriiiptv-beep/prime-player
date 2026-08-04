package com.castivio.feature.licence

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
// DpRect carries its height as an extension property in this package rather than
// as a member, so without this the bounds have no size to read -- a small irony
// in a file whose subject is elements with no size.
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.core.design.theme.Sizing
import com.castivio.domain.entitlement.EntitlementState
import com.castivio.domain.entitlement.PricingDefaults
import com.castivio.domain.entitlement.ServiceFault
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every mandatory element of the licence screen is placed, at a real size, in
 * every entitlement state.
 *
 * ## Why this exists before the screen has ever run on a device
 *
 * Because the sibling screen's entire middle band — both identifiers, both copy
 * controls, both actions, the status line, the QR and its caption — measured
 * **zero** on a real phone and shipped. Every gate the project had stayed green:
 * it compiled, the strings resolved in 39 locales, and the HTML mockup measured
 * 96 of 96. A mockup measurement is a claim about the design and never a claim
 * about the implementation, and `weight(1f)` inside an infinite height
 * constraint divides nothing.
 *
 * Only Compose can be asked what Compose placed. That is what this asks.
 *
 * ## What it cannot claim
 *
 * **Anything that depends on the size of text.** Robolectric does not lay text
 * out: every `Text` measures 35dp tall whatever its declared style, and
 * `GraphicsMode.NATIVE` does not change it. That inflates the identity column by
 * about forty dp — more than any of these frames has to spare — so *fit* is not
 * assertable here and no text-driven element is held to a target. Those claims
 * live in `LicenceBudgetTest`, on the JVM, where the numbers are the device's.
 *
 * What is left is the claim worth having: Compose places all of it, and none of
 * it is zero.
 */
@RunWith(RobolectricTestRunner::class)
// Deliberately larger than every frame in [Frame] and deliberately equal to none
// of them. The qualifiers configure the resource table -- orientation, and
// `television` for the set the TV draws from -- and nothing else; the size under
// test comes from `requiredSize`, so no composition is ever clipped by the
// harness and nobody can mistake a qualifier for the device being tested.
@Config(qualifiers = "w1280dp-h800dp-land")
class LicenceLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `every mandatory element is placed on a landscape phone`() {
        compose.setContent { Screen(Frame.Phone) }
        compose.assertLicenceIsWhole()
    }

    /** The shortest phone Castivio ships to, where the band has least to spare. */
    @Test
    fun `every mandatory element is placed on the shortest phone`() {
        compose.setContent { Screen(Frame.ShortPhone) }
        compose.assertLicenceIsWhole()
    }

    @Config(qualifiers = "w1280dp-h800dp-land-television")
    @Test
    fun `every mandatory element is placed on a television`() {
        compose.setContent { Screen(Frame.Television) }
        compose.assertLicenceIsWhole(television = true)
    }

    /**
     * Every one of the nine entitlement states renders something whole.
     *
     * The states differ in what the middle of the column holds — plans, one
     * action, or nothing — so "the screen is whole" means something different in
     * each, and the helper is told which. What does not differ, and is asserted
     * everywhere, is that the band has height and the identity is in it.
     */
    @Test
    fun `every entitlement state renders a whole screen`() {
        // One composition, driven through the states. `setContent` may be called
        // once per rule, and calling it in a loop throws in a way that reads like
        // a layout failure and is not one.
        val ui = mutableStateOf(uiFor(EntitlementState.TrialActive(0, 3)))
        compose.setContent { Screen(Frame.Phone, ui) }

        for ((name, state) in everyState()) {
            ui.value = uiFor(state)
            compose.waitForIdle()
            val view = licenceView(state)

            val field = compose.onNodeWithTag(LicenceTags.FIELD).getUnclippedBoundsInRoot()
            assertTrue(
                "$name: the field band is ${field.height}, which is not a band",
                field.height > 150.dp,
            )

            compose.onNodeWithTag(LicenceTags.MAC_CAPSULE).assertHasSize("$name: the MAC capsule")
            compose.onNodeWithTag(LicenceTags.STATUS).assertExistsFor(name, "the status line")
            compose.onNodeWithTag(LicenceTags.QR).assertHasSize("$name: the QR")

            if (view.offersPlans) {
                for (offer in PricingDefaults.config.purchasable) {
                    compose.onNodeWithTag(LicenceTags.plan(offer.plan.name.lowercase()))
                        .assertHasSize("$name: the ${offer.plan} card")
                }
            } else if (view.action != null) {
                compose.onNodeWithTag(LicenceTags.ACTION).assertHasSize("$name: the ${view.action}")
            }
        }
    }

    /**
     * A state that offers no plans draws no plan cards.
     *
     * The positive claim above would pass a screen that always drew both cards.
     * Lifetime is the case that matters: there is nothing left to sell, and a
     * plan card there is asking for money twice.
     */
    @Test
    fun `a lifetime licence is offered nothing further`() {
        compose.setContent { Screen(Frame.Phone, mutableStateOf(uiFor(EntitlementState.Lifetime))) }
        for (offer in PricingDefaults.config.purchasable) {
            compose.onNodeWithTag(LicenceTags.plan(offer.plan.name.lowercase()))
                .assertDoesNotExistWithMessage("a lifetime licence is being offered ${offer.plan}")
        }
        compose.onNodeWithTag(LicenceTags.ACTION)
            .assertDoesNotExistWithMessage("a lifetime licence is being offered a recovery action")
    }

    /**
     * Exactly as many cards as there are purchasable plans.
     *
     * `PricingConfig.plans` is a list, so a third plan is expressible today. This
     * screen was designed for one or two; a third must fail loudly here rather
     * than silently overrun the band on a device.
     */
    @Test
    fun `the card count is the offered count`() {
        compose.setContent { Screen(Frame.Phone) }
        val offers = PricingDefaults.config.purchasable
        assertTrue(
            "the licence screen is drawn for one or two plans and the config offers " +
                "${offers.size}. Widen the design deliberately or narrow the config.",
            offers.size in 1..2,
        )
        for (offer in offers) {
            compose.onNodeWithTag(LicenceTags.plan(offer.plan.name.lowercase()))
                .assertHasSize("the ${offer.plan} card")
        }
    }

    /**
     * The status region holds its height with nothing in it.
     *
     * ## Why this is not the "nothing moves" test the sibling has
     *
     * That test toggles the line between quiet and speaking and requires the
     * column not to change height. It cannot be written here, and writing it
     * anyway would have produced a test that passes for the wrong reason: the
     * only state with no sentence is the one still loading, and that state also
     * draws skeletons in place of the cards, so the two compositions differ by
     * two dp for a reason that has nothing to do with the status line.
     *
     * So the claim is made directly instead — the empty region is at least its
     * reserved height — which is the property the "nothing moves" behaviour is
     * built on, and it is measurable exactly. An empty `Box` has no width, so its
     * bounds are a degenerate rectangle, but its *height* is real and is the
     * number in question.
     */
    @Test
    fun `the status region holds its reserved height with nothing to say`() {
        val loading = uiFor(EntitlementState.TrialExpired).copy(licence = null)
        compose.setContent { Screen(Frame.Phone, mutableStateOf(loading)) }

        val reserved = licenceMetricsFor(tv = false, available = Frame.Phone.height).statusHeight
        val actual = compose.onNodeWithTag(LicenceTags.STATUS).getUnclippedBoundsInRoot().height

        assertTrue(
            "the status region is $actual with nothing in it, and the frame reserves " +
                "$reserved. A line that appears rather than being reserved pushes the " +
                "plan cards up at the instant somebody presses one.",
            actual >= reserved,
        )
    }

    /**
     * The gate itself, checked against the bug it exists for.
     *
     * A regression test that has only ever passed is not evidence of anything —
     * that is exactly the position the HTML measurements were in for two screens.
     * So the original defect is reconstructed: the screen is given a frame with
     * an unbounded height, which is what made the sibling's band measure 0dp, and
     * the helper is **required to fail** in it.
     *
     * If someone makes this test pass, the helper has stopped detecting a missing
     * band and needs fixing before it is trusted again.
     */
    @Test
    fun `the gate fails when the screen is given an unbounded height`() {
        compose.setContent {
            CastivioTheme {
                Box(Modifier.requiredSize(Frame.Phone.width, Frame.Phone.height)) {
                    UnboundedHeight { LicenceScreenUnderTest(null) }
                }
            }
        }
        val failed = runCatching { compose.assertLicenceIsWhole() }.isFailure
        if (!failed) {
            fail(
                "The screen was composed with an infinite height constraint -- the exact " +
                    "arrangement that made the sibling's middle band measure 0dp on a real " +
                    "device -- and the gate reported it whole. The gate is broken.",
            )
        }
    }
}

/**
 * The three sizes the design was drawn at, in dp of usable screen.
 *
 * **Usable, meaning all of it.** `:app` is edge-to-edge and this screen is
 * immersive, so it is handed the whole display; these are not "screen minus the
 * system bars". Reading the size off Robolectric's default activity instead is
 * what had the sibling's gate testing a 312dp-tall phone against a design drawn
 * for 360.
 */
private enum class Frame(val width: Dp, val height: Dp) {
    Phone(873.dp, 393.dp),
    ShortPhone(800.dp, 360.dp),
    Television(960.dp, 540.dp),
}

@Composable
private fun Screen(frame: Frame, state: MutableState<LicenceUiState>? = null) {
    CastivioTheme {
        Box(Modifier.requiredSize(frame.width, frame.height)) {
            LicenceScreenUnderTest(state)
        }
    }
}

/**
 * A parent that offers infinite height, which is the shape of the original bug.
 *
 * A vertically scrolling column, which is the real container the sibling screen
 * was wrongly given. Inside it the height constraint is infinite, `weight(1f)`
 * has no remaining space to claim, and the middle band measures 0dp.
 */
@Composable
private fun UnboundedHeight(content: @Composable () -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState())) { content() }
}

@Composable
private fun LicenceScreenUnderTest(state: MutableState<LicenceUiState>?) {
    LicenceScreen(
        state = state?.value ?: uiFor(EntitlementState.TrialExpired),
        onPlan = {},
        onRetry = {},
        onSupport = {},
        onCopied = {},
        onOpenLanguage = {},
    )
}

/** A device that has resolved its identity, in a stated entitlement. */
private fun uiFor(licence: EntitlementState) = LicenceUiState(
    licence = licence,
    address = "2F:19:EB:20:44:7C",
    deviceKey = "482731",
    qr = licenceQrBitmap(256),
    plans = PricingDefaults.config.purchasable,
)

/** All nine cases of the sealed type, named for a failure message. */
private fun everyState(): List<Pair<String, EntitlementState>> = listOf(
    "TrialActive" to EntitlementState.TrialActive(expiresAtMs = 0, daysRemaining = 3),
    "TrialExpired" to EntitlementState.TrialExpired,
    "AnnualActive" to EntitlementState.AnnualActive(expiresAtMs = 0, daysRemaining = 200),
    "AnnualExpired" to EntitlementState.AnnualExpired,
    "Lifetime" to EntitlementState.Lifetime,
    "Revoked" to EntitlementState.Revoked(revokedAtMs = 0),
    "Unknown" to EntitlementState.Unknown,
    "NOT_CONFIGURED" to EntitlementState.ServiceUnavailable(ServiceFault.NOT_CONFIGURED),
    "STORAGE_UNREADABLE" to EntitlementState.ServiceUnavailable(ServiceFault.STORAGE_UNREADABLE),
    "VerificationUnavailable" to EntitlementState.VerificationUnavailable(
        lastKnownPlan = com.castivio.domain.entitlement.Plan.ANNUAL,
        lastKnownExpiresAtMs = null,
        graceEndedAtMs = 0,
    ),
)

private fun SemanticsNodeInteraction.assertHasSize(what: String, min: Dp = 1.dp) {
    val bounds = getUnclippedBoundsInRoot()
    // Both dimensions in the message even though only height is asserted: a node
    // with zero *width* reports a degenerate rectangle whose height reads zero
    // too, and telling those apart from a CI log is the difference between one
    // round trip and four.
    assertTrue(
        "$what is ${bounds.width} x ${bounds.height}, below the ${min} floor",
        bounds.height >= min,
    )
}

private fun SemanticsNodeInteraction.assertExistsFor(state: String, what: String) {
    runCatching { assertExists() }.onFailure { fail("$state: $what is not on screen") }
}

private fun SemanticsNodeInteraction.assertDoesNotExistWithMessage(message: String) {
    if (runCatching { assertExists() }.isSuccess) fail(message)
}

/**
 * Everything the contract requires, present and at a real size.
 *
 * Collected rather than asserted one at a time, so a failure names every missing
 * element instead of the first — when a whole band goes, that is the difference
 * between "the QR is missing" and "the band is missing".
 */
private fun ComposeContentTestRule.assertLicenceIsWhole(television: Boolean = false) {
    val missing = mutableListOf<String>()

    fun placed(what: String, min: Dp = 1.dp, tag: String) {
        runCatching {
            val bounds = onNodeWithTag(tag).getUnclippedBoundsInRoot()
            if (bounds.height < min) {
                missing += "$what is ${bounds.width} x ${bounds.height}, below $min"
            }
        }.onFailure {
            missing += "$what — ${it.message?.lineSequence()?.firstOrNull().orEmpty()}"
        }
    }

    val target = Sizing.minTarget(television)

    placed("the field band", min = 150.dp, tag = LicenceTags.FIELD)
    placed("the identity column", tag = LicenceTags.IDENTITY)
    placed("the MAC capsule", min = target, tag = LicenceTags.MAC_CAPSULE)
    placed("the device key capsule", min = target, tag = LicenceTags.KEY_CAPSULE)
    placed("the plan row", tag = LicenceTags.PLANS)
    placed("the annual card", min = target, tag = LicenceTags.plan("annual"))
    placed("the lifetime card", min = target, tag = LicenceTags.plan("lifetime"))
    placed("the status line", tag = LicenceTags.STATUS)
    placed("the code zone", tag = LicenceTags.CODE_ZONE)
    placed("the QR", min = 100.dp, tag = LicenceTags.QR)
    placed("the header", tag = LicenceTags.HEADER)
    placed("the footer", tag = LicenceTags.FOOTER)

    if (missing.isNotEmpty()) {
        fail(
            "${missing.size} element(s) of the licence screen are missing or crushed:\n" +
                missing.joinToString("\n") { "  $it" },
        )
    }
}
