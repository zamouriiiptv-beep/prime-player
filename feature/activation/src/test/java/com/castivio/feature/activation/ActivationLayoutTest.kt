package com.castivio.feature.activation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
// DpRect carries its height as an extension property in this package, not as a
// member, so without this import the bounds have no size to read -- a small
// irony in a file whose whole subject is elements with no size.
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.castivio.core.design.theme.CastivioTheme
import com.castivio.domain.activation.ActivationUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every mandatory element of the activation screen is on screen, at a real size.
 *
 * ## Why this exists
 *
 * The entire middle band of this screen — address, device key, both copy
 * controls, Add playlist, Refresh, the status line, the QR and its caption —
 * measured **zero** on a real phone, and shipped. The screen rendered as a header
 * sitting directly on the legal line.
 *
 * Every gate the project had stayed green while that was true:
 *
 * - It compiled. Nothing about the bug is a type error.
 * - The strings resolved in all 39 locales. The text was fine; it had no height.
 * - The HTML mockup measured 27/27 and 96/96 — **and could never have caught
 *   this**, because it measures a different layout engine. `flex: 1 1 auto`
 *   works in the mockup because the flex container has a definite height. The
 *   Compose equivalent, `weight(1f)`, divides *remaining* space, and the screen
 *   had been composed inside a vertically scrolling column, where the height
 *   constraint is infinite and there is no remaining space to divide.
 *
 * The lesson is narrow and worth keeping: **a mockup measurement is a claim about
 * the design, never a claim about the implementation.** Only Compose can be asked
 * what Compose placed.
 *
 * ## What this file had to fix about itself first
 *
 * The first version of this gate was wrong in two ways, and both are the same
 * mistake as the bug it was written to catch — measuring something adjacent to
 * the thing being claimed.
 *
 * 1. **It measured the wrong window.** It let Robolectric's default activity
 *    decide the height, and that activity keeps a 48dp navigation bar. Castivio
 *    calls `enableEdgeToEdge`, so the real screen has no such bar and the gate was
 *    testing a phone 48dp shorter than any Castivio ships to. The frame is now
 *    stated in [Frame] and applied with `requiredSize`, so the size under test is
 *    a decision in this file rather than a property of the harness.
 * 2. **It measured the wrong node.** `Add playlist` finds the label inside the
 *    button. A label keeps its line height while the control around it is crushed,
 *    so "placed, 1dp or more" passed a button too small to press. The fixed-size
 *    controls are asserted against [MIN_TARGET] through their own tags now.
 *
 * ## What it does not claim, and cannot
 *
 * **Anything that depends on the size of text.** Robolectric does not lay text
 * out: every `Text` here measures 35.0dp tall whatever its declared style — the
 * 32dp headline, the 20dp legal line and the 18dp overline all identical — and
 * `GraphicsMode.NATIVE` was tried, changes nothing, and is not left switched on
 * to imply otherwise. Widths are worse still; the title comes back 21dp wide.
 *
 * That inflates the identity column by about 40dp, which is more than the margin
 * the shortest frame has, so **fit is not assertable here** and the two action
 * buttons cannot be held to a touch target in this file — they are what a squeezed
 * column crushes first, and the squeeze would be the harness's. That claim lives
 * in `ActivationBudgetTest`, which computes the same column from the same
 * [Metrics] and the line heights `CastivioType` declares, on the JVM, where the
 * numbers are the device's.
 *
 * What is left here is the claim worth having and the one that failed on a real
 * phone: **Compose places every mandatory element, and none of them is zero.**
 * Appearance stays a device question — `design/activation-spec.md` §12.0 keeps
 * this as one gate among several.
 */
@RunWith(RobolectricTestRunner::class)
// Deliberately larger than every frame in [Frame], and deliberately not equal to
// any of them. The qualifiers configure the *resource table* -- orientation, and
// `television` for the set the TV draws from -- and nothing else. The size under
// test comes from `requiredSize`, so a window big enough to contain any frame
// means no composition is ever clipped by the harness, and nobody reading this
// can mistake a qualifier for the device being tested.
@Config(qualifiers = "w1280dp-h800dp-land")
class ActivationLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `every mandatory element is placed on a landscape phone`() {
        compose.setContent { Screen(Frame.Phone) }
        compose.assertActivationIsWhole()
    }

    @Config(qualifiers = "w1280dp-h800dp-land-television")
    @Test
    fun `every mandatory element is placed on a television`() {
        compose.setContent { Screen(Frame.Television) }
        compose.assertActivationIsWhole(television = true)
    }

    /** The shortest phone Castivio ships to, where the band has least to spare. */
    @Test
    fun `every mandatory element is placed on the shortest phone`() {
        compose.setContent { Screen(Frame.ShortPhone) }
        compose.assertActivationIsWhole()
    }

    /**
     * The band claims real height, not a few stray dp.
     *
     * Stated as its own assertion because it is the specific thing that broke: a
     * band between two hairlines that is 0dp tall is a band that is not there.
     */
    @Test
    fun `the field band takes the height the header and footer leave`() {
        compose.setContent { Screen(Frame.Phone) }
        val field = compose.onNodeWithTag(ActivationTags.FIELD).getUnclippedBoundsInRoot()
        assertTrue(
            "the field band is ${field.height}, which is not a band",
            field.height > 150.dp,
        )
    }

    /**
     * The status region's whole promise: nothing moves when it fills.
     *
     * §6.3 calls it a reserved height, and the only way to check a reservation is
     * to spend it — measure the column the region would push, with the region
     * quiet and then speaking, and require the two to be equal. Measuring the
     * region's own bounds cannot do it: empty, it has no width, and a degenerate
     * rectangle reports nothing useful about the space it is holding.
     */
    @Test
    fun `the status region reserves its height, so nothing moves when it fills`() {
        val identity = mutableStateOf(restingIdentity())
        compose.setContent { Screen(Frame.Phone, identity) }

        val quiet = compose.onNodeWithTag(ActivationTags.IDENTITY)
            .getUnclippedBoundsInRoot().height
        identity.value = identity.value.copy(refresh = RefreshState.None)
        compose.waitForIdle()
        val speaking = compose.onNodeWithTag(ActivationTags.IDENTITY)
            .getUnclippedBoundsInRoot().height

        assertEquals(
            "the identity column moved when the status line filled: $quiet then $speaking",
            quiet,
            speaking,
        )
    }

    /**
     * The gate itself, checked against the bug it exists for.
     *
     * A regression test that has only ever passed is not evidence of anything —
     * that is precisely the position the HTML measurements were in. So the
     * original defect is reconstructed here, through the real container, by asking
     * [ActivationSurface] for the frame the **forms** use. That is not a
     * hypothetical: it is the frame the address screen was wrongly given, and the
     * assertion helper is required to **fail** in it.
     *
     * If someone makes this test pass, the helper has stopped detecting a missing
     * band and needs fixing before it is trusted again.
     */
    @Test
    fun `the gate fails when the screen is given the scrolling form frame`() {
        compose.setContent {
            CastivioTheme {
                Stage(Frame.Phone) {
                    ActivationSurface(fixedViewport = false) { ActivationScreenUnderTest() }
                }
            }
        }
        val failed = runCatching { compose.assertActivationIsWhole() }.isFailure
        if (!failed) {
            fail(
                "The screen was composed in the scrolling form frame -- the exact " +
                    "arrangement that made the middle band measure 0dp on a real device " +
                    "-- and the gate reported it whole. The gate is broken.",
            )
        }
    }
}

/**
 * The three sizes the design was drawn at, in dp of usable screen.
 *
 * **Usable, meaning all of it.** `:app` calls `enableEdgeToEdge`, so activation
 * gets the whole display; these are not "screen minus the system bars". Reading
 * the size off Robolectric's default activity instead is what had this file
 * testing a 312dp-tall phone against a design drawn for 360.
 */
private enum class Frame(val width: Dp, val height: Dp) {
    /** The reference frame the mockup is measured at, and the reviewed device. */
    Phone(873.dp, 393.dp),

    /** The shortest Castivio ships to. The band has about 9dp to spare here. */
    ShortPhone(800.dp, 360.dp),

    Television(960.dp, 540.dp),
}

/**
 * The screen in the container the device actually builds around it.
 *
 * Composing `MacActivationScreen` into a bare `Box` is what the first version of
 * this file did, and it would have passed on the day the bug shipped: the screen
 * was never the broken part. [ActivationSurface] was, and the frame it hands out
 * is decided by [isFixedViewport], so both are in the path under test here rather
 * than assumed correct around it.
 */
@Composable
private fun Screen(frame: Frame, identity: MutableState<ActivationIdentityState>? = null) {
    CastivioTheme {
        Stage(frame) {
            ActivationSurface(
                fixedViewport = isFixedViewport(ActivationUiState(), ActivationStep.Mac),
            ) {
                ActivationScreenUnderTest(identity)
            }
        }
    }
}

/**
 * A window of a stated size.
 *
 * `requiredSize` rather than `size`: the point is to impose the frame on the
 * composition whatever the harness would otherwise have offered, which is the
 * whole correction being made here.
 */
@Composable
private fun Stage(frame: Frame, content: @Composable () -> Unit) {
    Box(Modifier.requiredSize(frame.width, frame.height)) { content() }
}

/**
 * The screen with a resolved identity, as a device shows it.
 *
 * Not the view model: this is a test about layout, and a state holder that reads
 * a keystore would make it a test about two things.
 */
@Composable
private fun ActivationScreenUnderTest(state: MutableState<ActivationIdentityState>? = null) {
    MacActivationScreen(
        identity = state?.value ?: restingIdentity(),
        onAddPlaylist = {},
        onRefresh = {},
        onCopied = {},
        onOpenLanguage = {},
    )
}

/** A device that has resolved its identity and has nothing to say yet. */
private fun restingIdentity() = ActivationIdentityState(
    address = "2F:19:EB:20:44:7C",
    deviceKey = "482731",
    qr = activationQrBitmap(256),
    // A trial with days on it, because the chip is only composed when there is
    // one -- and this file's job is to check that everything §14 requires is
    // placed, which means handing it a state where all of it exists.
    trialDaysRemaining = 7,
)

/**
 * Large enough to press, which is the only size a control is allowed to be.
 *
 * `Sizing.minTouchTarget` is 48dp and the television draws bigger, so one floor
 * serves every frame. It applies to the controls whose size is fixed by a
 * modifier -- the two copy boxes -- and deliberately not to anything a squeezed
 * column can crush, because in this harness the squeeze is the harness. See the
 * note on the actions row.
 */
private val MIN_TARGET = 48.dp

/**
 * Everything §14 of the approved contract requires, present and at a real size.
 *
 * Collected rather than asserted one at a time, so a failure names every missing
 * element instead of the first one — when a whole band goes, that is the
 * difference between "the QR is missing" and "the band is missing".
 */
private fun ComposeContentTestRule.assertActivationIsWhole(television: Boolean = false) {
    val missing = mutableListOf<String>()

    fun check(what: String, finder: () -> Unit) {
        runCatching(finder).onFailure {
            missing += "$what — ${it.message?.lineSequence()?.firstOrNull().orEmpty()}"
        }
    }

    fun placed(what: String, min: Dp = 1.dp, node: () -> SemanticsNodeInteraction) = check(what) {
        val bounds = node().getUnclippedBoundsInRoot()
        // Both dimensions in the message even though only height is asserted: a
        // node with zero *width* reports a degenerate rectangle, whose height
        // reads zero too, and telling those two cases apart from a CI log is the
        // difference between one round trip and four.
        if (bounds.height < min) {
            error("placed ${bounds.width} x ${bounds.height}, wanted height >= $min")
        }
    }

    fun byText(what: String, text: String, min: Dp = 1.dp) =
        placed(what, min) { onNodeWithText(text, substring = true) }

    fun byDescription(what: String, description: String, min: Dp = 1.dp) =
        placed(what, min) { onNodeWithContentDescription(description, substring = true) }

    fun byTag(what: String, tag: String, min: Dp = 1.dp) =
        placed(what, min) { onNodeWithTag(tag) }

    // The sizes that decide everything else, printed whether or not anything
    // fails. Working out why an element measured zero without knowing what the
    // band and the columns measured is guesswork, and guesswork here costs a
    // seven-minute round trip each time.
    fun size(tag: String) = runCatching {
        onNodeWithTag(tag).getUnclippedBoundsInRoot().let { "${it.width} x ${it.height}" }
    }.getOrElse { "absent" }
    fun textSize(text: String) = runCatching {
        onNodeWithText(text, substring = true).getUnclippedBoundsInRoot()
            .let { "${it.width} x ${it.height}" }
    }.getOrElse { "absent" }
    println(
        "activation bands — stage ${size(ActivationTags.STAGE)} | " +
            "header ${size(ActivationTags.HEADER)} | " +
            "field ${size(ActivationTags.FIELD)} | " +
            "footer ${size(ActivationTags.FOOTER)}",
    )
    println(
        "activation inside — identity ${size(ActivationTags.IDENTITY)} | " +
            "code ${size(ActivationTags.CODE_ZONE)} | " +
            "mac ${size(ActivationTags.MAC_CAPSULE)} | " +
            "key ${size(ActivationTags.KEY_CAPSULE)} | " +
            "actions ${size(ActivationTags.ACTIONS)} | " +
            "status ${size(ActivationTags.STATUS)} | " +
            "qr ${size(ActivationTags.QR)}",
    )
    println(
        "activation text — title ${textSize("Add a playlist")} | " +
            "legal ${textSize("Castivio is a multimedia player")} | " +
            "caption ${textSize("Scan the QR code with your phone")}",
    )

    // The three bands first. A band with no height is the failure this file
    // exists for, and naming it first makes the report read the way the screen
    // broke.
    byTag("the field band", ActivationTags.FIELD, min = 150.dp)
    byTag("the identity zone", ActivationTags.IDENTITY, min = 100.dp)
    byTag("the code zone", ActivationTags.CODE_ZONE, min = 100.dp)

    // The two pills, at their declared height. This is one of the few sizes this
    // harness can be trusted on: a capsule is `Modifier.height(m.capsule)`, not a
    // line of text, so 56 here means 56 on a device. A pill that came back short
    // would mean the band squeezed it, which is the failure this file is for.
    // The frame's own pill height, not a constant: 52dp on a phone and 64 on a
    // television, because a 56dp D-pad target does not fit in a 52dp pill.
    val pill = metricsFor(tv = television, available = 0.dp).capsule
    byTag("the MAC capsule", ActivationTags.MAC_CAPSULE, min = pill)
    byTag("the device key capsule", ActivationTags.KEY_CAPSULE, min = pill)

    byText("the title", "Add a playlist")
    // One node, not two. The badge used to be a name and a trailing count and
    // is now one sentence with the numeral emphasised inside it, which is what
    // lets a language put the number anywhere it likes.
    byText("the trial badge", "7-day trial")

    // The chip and the two code values carry `clearAndSetSemantics`, which
    // replaces their text with one description -- deliberately, so a reader says
    // "MAC address 2F 19 EB" rather than spelling the punctuation. A text finder
    // cannot see them.
    // Presence only, and not [MIN_TARGET]: `clearAndSetSemantics` puts the
    // description on the chip's *label*, so this finder reaches a line of type
    // inside the control rather than the 48dp control around it. Asserting a
    // touch target against a text node would be the same category error as
    // asserting one against the Add playlist label.
    byDescription("the language control", "Language")

    byText("the MAC label", "MAC address")
    byDescription("the MAC address", "MAC address 2F 19 EB 20 44 7C")
    byDescription("the copy-MAC control", "Copy MAC address", min = MIN_TARGET)

    byText("the device key label", "Device key")
    byDescription("the device key", "482731")
    byDescription("the copy-key control", "Copy device key", min = MIN_TARGET)

    // The row, and then the labels inside it. The row is the assertion that
    // matters, because a label keeps its line height inside a button that has
    // been crushed to nothing -- but it is asserted at "not zero" and not at
    // [MIN_TARGET], and the difference is not a compromise.
    //
    // The buttons are the last children of the identity column, so they are what
    // a short band takes the space from. This harness gives every Text 35dp
    // instead of its declared 18 or 20, which spends about 40dp of a column whose
    // whole margin is nine -- so the row measures 41dp here and 48 on a device,
    // and a touch-target assertion in this file would be reporting the harness.
    // `ActivationBudgetTest` makes that claim on the real numbers.
    byTag("the actions row", ActivationTags.ACTIONS)
    byText("Add playlist", "Add playlist")
    byText("Refresh", "Refresh")

    // Existence only. The region is a reserved height that is empty at rest, so
    // its own bounds are degenerate; what it actually promises -- that nothing
    // moves when it fills -- is asserted in its own test, against the column it
    // would push.
    check("the reserved status line") { onNodeWithTag(ActivationTags.STATUS).assertExists() }

    byTag("the QR fixture", ActivationTags.QR, min = 100.dp)
    byText("the QR caption", "Scan the QR code with your phone")

    byText("the legal line", "Castivio is a multimedia player")

    if (missing.isNotEmpty()) {
        val report = "The approved activation composition is incomplete. " +
            "${missing.size} mandatory element(s) missing or not placed:\n  " +
            missing.joinToString("\n  ")
        // Printed as well as thrown: Gradle's console gives the exception type and
        // a line number and puts the message in an HTML report nobody on a CI
        // runner can open.
        println(report)
        fail(report)
    }
}
