package com.castivio.feature.activation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
// DpRect carries its width and height as extension properties in this package,
// not as members. Without these two imports the bounds have no size to read --
// which is a small irony in a file whose whole subject is elements with no size.
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.castivio.core.design.theme.CastivioTheme
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every mandatory element of the activation screen is on screen, with a size.
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
 * ## Why placement and not presence
 *
 * `assertExists` would have passed throughout. Every one of those nodes was in
 * the semantics tree the whole time — composed, laid out, and zero pixels tall.
 * So this asserts bounds, and the band that vanished is asserted explicitly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w873dp-h393dp-land")
class ActivationLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `every mandatory element is placed on a landscape phone`() {
        compose.setContent { Screen() }
        compose.assertActivationIsWhole()
    }

    @Config(qualifiers = "w960dp-h540dp-television-land")
    @Test
    fun `every mandatory element is placed on a television`() {
        compose.setContent { Screen() }
        compose.assertActivationIsWhole()
    }

    /** The shortest phone Castivio ships to, where the band has least to spare. */
    @Config(qualifiers = "w800dp-h360dp-land")
    @Test
    fun `every mandatory element is placed on the shortest phone`() {
        compose.setContent { Screen() }
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
        compose.setContent { Screen() }
        val field = compose.onNodeWithTag(ActivationTags.FIELD).getUnclippedBoundsInRoot()
        assertTrue(
            "the field band is ${field.height}, which is not a band",
            field.height > 150.dp,
        )
    }

    /**
     * The gate itself, checked against the bug it exists for.
     *
     * A regression test that has only ever passed is not evidence of anything —
     * that is precisely the position the HTML measurements were in. So the
     * original defect is reconstructed here, by composing the same screen inside
     * the same kind of scrolling column it was in, and the assertion helper is
     * required to **fail**.
     *
     * If someone makes this test pass, the helper has stopped detecting a missing
     * band and needs fixing before it is trusted again.
     */
    @Test
    fun `the gate fails when the screen is put back in a scrolling column`() {
        compose.setContent {
            CastivioTheme {
                Box(Modifier.fillMaxSize()) {
                    ScrollingColumn { ActivationScreenUnderTest() }
                }
            }
        }
        val failed = runCatching { compose.assertActivationIsWhole() }.isFailure
        if (!failed) {
            fail(
                "The screen was composed inside a vertically scrolling column -- the " +
                    "exact arrangement that made the middle band measure 0dp on a real " +
                    "device -- and the gate reported it whole. The gate is broken.",
            )
        }
    }
}

/** The arrangement the screen was in when it broke, kept so the gate can be tested. */
@Composable
private fun ScrollingColumn(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) { content() }
}

@Composable
private fun Screen() {
    CastivioTheme {
        Box(Modifier.fillMaxSize()) { ActivationScreenUnderTest() }
    }
}

/**
 * The screen with a resolved identity, as a device shows it.
 *
 * Not the view model: this is a test about layout, and a state holder that reads
 * a keystore would make it a test about two things.
 */
@Composable
private fun ActivationScreenUnderTest() {
    MacActivationScreen(
        identity = ActivationIdentityState(
            address = "2F:19:EB:20:44:7C",
            deviceKey = "482731",
            qr = qrFixtureBitmap(256),
        ),
        onAddPlaylist = {},
        onRefresh = {},
        onCopied = {},
        onOpenLanguage = {},
    )
}

/**
 * Everything §14 of the approved contract requires, present and with a size.
 *
 * Collected rather than asserted one at a time, so a failure names every missing
 * element instead of the first one — when a whole band goes, that is the
 * difference between "the QR is missing" and "the band is missing".
 */
private fun ComposeContentTestRule.assertActivationIsWhole() {
    val missing = mutableListOf<String>()

    fun check(what: String, finder: () -> Unit) {
        runCatching(finder).onFailure {
            missing += "$what — ${it.message?.lineSequence()?.firstOrNull().orEmpty()}"
        }
    }

    fun byText(what: String, text: String) = check(what) {
        onNodeWithText(text, substring = true).assertIsDisplayed()
    }

    fun byDescription(what: String, description: String) = check(what) {
        onNodeWithContentDescription(description, substring = true).assertIsDisplayed()
    }

    fun byTag(what: String, tag: String, minHeight: Dp = 1.dp) =
        check(what) {
            val bounds = onNodeWithTag(tag).getUnclippedBoundsInRoot()
            if (bounds.height < minHeight || bounds.width < 1.dp) {
                error("placed at ${bounds.width} x ${bounds.height}")
            }
        }

    // The three bands, before anything inside them. A band with no height is the
    // failure this file exists for, and naming it first makes the report read
    // the way the screen broke.
    byTag("the field band", ActivationTags.FIELD, minHeight = 150.dp)
    byTag("the identity zone", ActivationTags.IDENTITY)
    byTag("the code zone", ActivationTags.CODE_ZONE)

    byText("the title", "Add your subscription")
    byText("the trial name", "Castivio trial")
    byText("the trial days", "7 days")
    byText("the language control", "Language")

    byText("the MAC label", "MAC ADDRESS")
    byDescription("the MAC address", "MAC address 2F 19 EB 20 44 7C")
    byDescription("the copy-MAC control", "Copy MAC address")

    byText("the device key label", "DEVICE KEY")
    byText("the device key", "482731")
    byDescription("the copy-key control", "Copy device key")

    byText("Add playlist", "Add playlist")
    byText("Refresh", "Refresh")
    byTag("the reserved status line", ActivationTags.STATUS)

    byTag("the QR fixture", ActivationTags.QR, minHeight = 100.dp)
    byText("the QR caption", "Scan to set up on your phone")

    byText("the legal line", "Castivio is only a player")

    if (missing.isNotEmpty()) {
        fail(
            "The approved activation composition is incomplete. " +
                "${missing.size} mandatory element(s) missing or not placed:\n  " +
                missing.joinToString("\n  "),
        )
    }
}
